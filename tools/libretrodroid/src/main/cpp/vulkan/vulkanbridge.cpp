/*
 * Tirso addition to LibretroDroid (GPLv3, like LibretroDroid). See vulkanbridge.h.
 */
#include "vulkanbridge.h"

#include <android/hardware_buffer.h>
#include <media/NdkImageReader.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#ifdef TIRSO_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

#include "../log.h"

// ------------------------------------------------------------------ Vulkan functions
// Loaded from whichever driver is in use (the phone's, or a custom one through libadrenotools).
#define VK_GLOBAL_FUNCTIONS(X) \
    X(vkCreateInstance) \
    X(vkEnumerateInstanceExtensionProperties)
#define VK_INSTANCE_FUNCTIONS(X) \
    X(vkDestroyInstance) \
    X(vkEnumeratePhysicalDevices) \
    X(vkEnumerateDeviceExtensionProperties) \
    X(vkGetPhysicalDeviceProperties) \
    X(vkGetPhysicalDeviceMemoryProperties) \
    X(vkCreateAndroidSurfaceKHR) \
    X(vkDestroySurfaceKHR) \
    X(vkGetDeviceProcAddr)
#define VK_DEVICE_FUNCTIONS(X) \
    X(vkCreateImage) \
    X(vkDestroyImage) \
    X(vkCreateBuffer) \
    X(vkDestroyBuffer) \
    X(vkGetBufferMemoryRequirements) \
    X(vkAllocateMemory) \
    X(vkFreeMemory) \
    X(vkBindImageMemory) \
    X(vkBindBufferMemory) \
    X(vkMapMemory) \
    X(vkUnmapMemory) \
    X(vkInvalidateMappedMemoryRanges) \
    X(vkCreateCommandPool) \
    X(vkDestroyCommandPool) \
    X(vkAllocateCommandBuffers) \
    X(vkResetCommandBuffer) \
    X(vkBeginCommandBuffer) \
    X(vkEndCommandBuffer) \
    X(vkCmdPipelineBarrier) \
    X(vkCmdBlitImage) \
    X(vkCmdCopyImageToBuffer) \
    X(vkQueueSubmit) \
    X(vkQueueWaitIdle) \
    X(vkCreateFence) \
    X(vkDestroyFence) \
    X(vkWaitForFences) \
    X(vkResetFences)

namespace {
PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
#define VK_DECLARE(name) PFN_##name name = nullptr;
VK_GLOBAL_FUNCTIONS(VK_DECLARE)
VK_INSTANCE_FUNCTIONS(VK_DECLARE)
VK_DEVICE_FUNCTIONS(VK_DECLARE)
#undef VK_DECLARE
PFN_vkGetAndroidHardwareBufferPropertiesANDROID vkGetAndroidHardwareBufferPropertiesANDROID = nullptr;
}

namespace libretrodroid {

namespace {

const char* const WANTED_INSTANCE_EXTENSIONS[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
    VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
};

const char* const WANTED_DEVICE_EXTENSIONS[] = {
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
    VK_KHR_MAINTENANCE1_EXTENSION_NAME,
    VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
};

const char* VERTEX_SHADER =
    "#version 300 es\n"
    "layout(location = 0) in vec2 position;\n"
    "out vec2 uv;\n"
    "void main() {\n"
    // Row 0 of the Vulkan picture is its top; the GL framebuffer keeps the picture's bottom at y = 0.
    "  uv = vec2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);\n"
    "  gl_Position = vec4(position, 0.0, 1.0);\n"
    "}\n";

const char* FRAGMENT_SHADER_2D =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform sampler2D frame;\n"
    "in vec2 uv;\n"
    "out vec4 color;\n"
    "void main() { color = vec4(texture(frame, uv).rgb, 1.0); }\n";

const char* FRAGMENT_SHADER_EXTERNAL =
    "#version 300 es\n"
    "#extension GL_OES_EGL_image_external_essl3 : require\n"
    "precision mediump float;\n"
    "uniform samplerExternalOES frame;\n"
    "in vec2 uv;\n"
    "out vec4 color;\n"
    "void main() { color = vec4(texture(frame, uv).rgb, 1.0); }\n";

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Vulkan bridge: shader failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint buildProgram(const char* fragment) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) return 0;
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOGE("Vulkan bridge: program link failed");
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

bool hasExtension(const std::vector<VkExtensionProperties>& list, const char* name) {
    for (const auto& e : list) if (strcmp(e.extensionName, name) == 0) return true;
    return false;
}

const char* env(const char* name) {
    const char* value = getenv(name);
    return value != nullptr && value[0] != '\0' ? value : nullptr;
}

}  // namespace

bool VulkanBridge::requested() {
    const char* value = getenv("LIBRETRODROID_VULKAN");
    return value != nullptr && strcmp(value, "1") == 0;
}

// ------------------------------------------------------------------ driver loading

bool VulkanBridge::loadLoader(bool allowCustom) {
    vulkanLibrary = nullptr;
    customDriver = false;

#ifdef TIRSO_ADRENOTOOLS
    const char* driverDir = env("LIBRETRODROID_VK_DRIVER_DIR");
    const char* driverLib = env("LIBRETRODROID_VK_DRIVER_LIB");
    const char* hookDir = env("LIBRETRODROID_VK_HOOK_DIR");
    const char* tmpDir = env("LIBRETRODROID_VK_TMP_DIR");
    if (allowCustom && driverDir && driverLib && hookDir && tmpDir) {
        std::string redirect = std::string(driverDir) + "redirect/";
        vulkanLibrary = adrenotools_open_libvulkan(
            RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT,
            tmpDir, hookDir, driverDir, driverLib, redirect.c_str(), nullptr);
        if (vulkanLibrary) {
            customDriver = true;
            LOGI("Vulkan bridge: custom GPU driver loaded (%s)", driverLib);
        } else {
            LOGE("Vulkan bridge: custom GPU driver %s could not be loaded; using the phone's driver", driverLib);
        }
    }
#endif

    if (!vulkanLibrary) vulkanLibrary = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vulkanLibrary) {
        LOGE("Vulkan bridge: no Vulkan library");
        return false;
    }
    vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr) dlsym(vulkanLibrary, "vkGetInstanceProcAddr");
    if (!vkGetInstanceProcAddr) {
        LOGE("Vulkan bridge: vkGetInstanceProcAddr missing");
        return false;
    }
#define VK_LOAD_GLOBAL(name) name = (PFN_##name) vkGetInstanceProcAddr(VK_NULL_HANDLE, #name);
    VK_GLOBAL_FUNCTIONS(VK_LOAD_GLOBAL)
#undef VK_LOAD_GLOBAL
    return vkCreateInstance != nullptr && vkEnumerateInstanceExtensionProperties != nullptr;
}

void VulkanBridge::unloadInstance() {
    if (surface != VK_NULL_HANDLE && vkDestroySurfaceKHR) vkDestroySurfaceKHR(instance, surface, nullptr);
    surface = VK_NULL_HANDLE;
    if (instance != VK_NULL_HANDLE && vkDestroyInstance) vkDestroyInstance(instance, nullptr);
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
}

// ------------------------------------------------------------------ device

bool VulkanBridge::createInstance() {
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());

    std::vector<const char*> extensions;
    for (const char* name : WANTED_INSTANCE_EXTENSIONS) {
        if (hasExtension(available, name)) extensions.push_back(name);
        else LOGE("Vulkan bridge: instance extension missing: %s", name);
    }

    VkApplicationInfo app {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    if (negotiation && negotiation->get_application_info && negotiation->get_application_info()) {
        app = *negotiation->get_application_info();
    }
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    auto enumerateVersion = (PFN_vkEnumerateInstanceVersion)
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (enumerateVersion) enumerateVersion(&loaderVersion);
    if (app.apiVersion < VK_API_VERSION_1_1 && loaderVersion >= VK_API_VERSION_1_1) {
        app.apiVersion = VK_API_VERSION_1_1;
    }

    VkInstanceCreateInfo info {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = (uint32_t) extensions.size();
    info.ppEnabledExtensionNames = extensions.data();
    VkResult result = vkCreateInstance(&info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        LOGE("Vulkan bridge: vkCreateInstance failed (%d)", result);
        instance = VK_NULL_HANDLE;
        return false;
    }
#define VK_LOAD_INSTANCE(name) name = (PFN_##name) vkGetInstanceProcAddr(instance, #name);
    VK_INSTANCE_FUNCTIONS(VK_LOAD_INSTANCE)
#undef VK_LOAD_INSTANCE
    if (!vkEnumeratePhysicalDevices || !vkCreateAndroidSurfaceKHR || !vkGetDeviceProcAddr) {
        LOGE("Vulkan bridge: instance functions missing");
        return false;
    }
    return true;
}

bool VulkanBridge::createDummySurface() {
    // Dolphin asks the driver about a real surface (formats, size). It never presents to it:
    // its frames come back through set_image. A small image reader gives it a real window.
    if (!dummyReader) {
        if (AImageReader_new(64, 64, AIMAGE_FORMAT_RGBA_8888, 2, &dummyReader) != AMEDIA_OK) {
            LOGE("Vulkan bridge: could not create the helper surface");
            dummyReader = nullptr;
            return false;
        }
        AImageReader_getWindow(dummyReader, &dummyWindow);
    }
    VkAndroidSurfaceCreateInfoKHR info {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    info.window = dummyWindow;
    VkResult result = vkCreateAndroidSurfaceKHR(instance, &info, nullptr, &surface);
    if (result != VK_SUCCESS) {
        LOGE("Vulkan bridge: vkCreateAndroidSurfaceKHR failed (%d)", result);
        surface = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanBridge::ensureDevice() {
    if (deviceReady) return true;
    if (deviceFailed || negotiation == nullptr) {
        if (negotiation == nullptr) LOGE("Vulkan bridge: the core gave no Vulkan negotiation interface");
        return false;
    }
    deviceFailed = true;  // until everything below succeeds

    // A custom driver first (when one is set up), then the phone's own driver.
    bool ready = false;
    for (int attempt = 0; attempt < 2 && !ready; attempt++) {
        const bool allowCustom = attempt == 0;
        if (!allowCustom && !customDriver) break;  // the phone's driver was already tried
        if (!allowCustom) {
            unloadInstance();
            LOGI("Vulkan bridge: trying the phone's own Vulkan driver");
        }
        if (!loadLoader(allowCustom) || !createInstance() || !createDummySurface()) continue;
        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (count == 0) {
            LOGE("Vulkan bridge: this driver sees no GPU");
            continue;
        }
        std::vector<VkPhysicalDevice> gpus(count);
        vkEnumeratePhysicalDevices(instance, &count, gpus.data());
        gpu = gpus[0];
        ready = true;
    }
    if (!ready) return false;

    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, available.data());
    static std::vector<const char*> deviceExtensions;
    deviceExtensions.clear();
    for (const char* name : WANTED_DEVICE_EXTENSIONS) {
        if (hasExtension(available, name)) deviceExtensions.push_back(name);
        else LOGW("Vulkan bridge: device extension missing: %s", name);
    }
    foreignQueue = hasExtension(available, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    shareWithHardwareBuffer =
        hasExtension(available, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    if (!shareWithHardwareBuffer) {
        LOGW("Vulkan bridge: this driver can't share pictures directly; copying them through memory");
    }

    retro_vulkan_context context {};
    if (!negotiation->create_device ||
        !negotiation->create_device(&context, instance, gpu, surface, vkGetInstanceProcAddr,
                                    deviceExtensions.data(), (unsigned) deviceExtensions.size(),
                                    nullptr, 0, nullptr)) {
        LOGE("Vulkan bridge: the core could not create its Vulkan device");
        return false;
    }
    gpu = context.gpu;
    device = context.device;
    queue = context.queue;
    queueFamily = context.queue_family_index;

#define VK_LOAD_DEVICE(name) name = (PFN_##name) vkGetDeviceProcAddr(device, #name);
    VK_DEVICE_FUNCTIONS(VK_LOAD_DEVICE)
#undef VK_LOAD_DEVICE
    if (!vkQueueSubmit || !vkCmdBlitImage || !vkCmdCopyImageToBuffer) {
        LOGE("Vulkan bridge: device functions missing");
        return false;
    }
    if (shareWithHardwareBuffer) {
        vkGetAndroidHardwareBufferPropertiesANDROID = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
        if (!vkGetAndroidHardwareBufferPropertiesANDROID) shareWithHardwareBuffer = false;
    }

    VkCommandPoolCreateInfo poolInfo {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo allocInfo {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &allocInfo, &commandBuffer) != VK_SUCCESS) return false;
    VkFenceCreateInfo fenceInfo {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) return false;

    iface = {};
    iface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface.handle = this;
    iface.instance = instance;
    iface.gpu = gpu;
    iface.device = device;
    iface.get_device_proc_addr = vkGetDeviceProcAddr;
    iface.get_instance_proc_addr = vkGetInstanceProcAddr;
    iface.queue = queue;
    iface.queue_index = queueFamily;
    iface.set_image = cbSetImage;
    iface.get_sync_index = cbGetSyncIndex;
    iface.get_sync_index_mask = cbGetSyncIndexMask;
    iface.set_command_buffers = cbSetCommandBuffers;
    iface.wait_sync_index = cbWaitSyncIndex;
    iface.lock_queue = cbLockQueue;
    iface.unlock_queue = cbUnlockQueue;
    iface.set_signal_semaphore = cbSetSignalSemaphore;

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(gpu, &properties);
    LOGI("Vulkan bridge: ready on %s, %s driver (Vulkan %u.%u, driver version %u), pictures shared %s",
         properties.deviceName, customDriver ? "custom" : "phone's own",
         VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion),
         properties.driverVersion, shareWithHardwareBuffer ? "directly" : "through memory");

    deviceFailed = false;
    deviceReady = true;
    return true;
}

// ------------------------------------------------------------------ core callbacks

void VulkanBridge::cbSetImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                              const VkSemaphore* semaphores, uint32_t srcQueueFamily) {
    auto* self = static_cast<VulkanBridge*>(handle);
    if (image == nullptr) {
        self->hasImage = false;
        return;
    }
    self->currentImage = *image;
    self->hasImage = true;
    self->imageQueueFamily = srcQueueFamily;
    self->waitSemaphores.assign(semaphores, semaphores + numSemaphores);
}

uint32_t VulkanBridge::cbGetSyncIndex(void* handle) {
    return static_cast<VulkanBridge*>(handle)->frameIndex & 1u;
}

uint32_t VulkanBridge::cbGetSyncIndexMask(void*) {
    return 0x3;  // two frames in flight from the core's point of view
}

void VulkanBridge::cbSetCommandBuffers(void*, uint32_t, const VkCommandBuffer*) {
    // Dolphin submits its own work; nothing to do.
}

void VulkanBridge::cbWaitSyncIndex(void*) {
    // Each frame is fully copied (and waited for) before the core can start the next one.
}

void VulkanBridge::cbLockQueue(void* handle) {
    static_cast<VulkanBridge*>(handle)->queueMutex.lock();
}

void VulkanBridge::cbUnlockQueue(void* handle) {
    static_cast<VulkanBridge*>(handle)->queueMutex.unlock();
}

void VulkanBridge::cbSetSignalSemaphore(void*, VkSemaphore) {
}

// ------------------------------------------------------------------ shared picture (direct)

void VulkanBridge::releaseSharedImage() {
    releaseGlImage();
    if (device != VK_NULL_HANDLE) {
        if (sharedImage != VK_NULL_HANDLE) vkDestroyImage(device, sharedImage, nullptr);
        if (sharedMemory != VK_NULL_HANDLE) vkFreeMemory(device, sharedMemory, nullptr);
    }
    sharedImage = VK_NULL_HANDLE;
    sharedMemory = VK_NULL_HANDLE;
    if (sharedBuffer) AHardwareBuffer_release(sharedBuffer);
    sharedBuffer = nullptr;
    sharedWidth = sharedHeight = 0;
    sharedFirstUse = true;
}

bool VulkanBridge::ensureSharedImage(unsigned width, unsigned height) {
    if (sharedImage != VK_NULL_HANDLE && width == sharedWidth && height == sharedHeight) return true;
    releaseSharedImage();

    AHardwareBuffer_Desc desc {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &sharedBuffer) != 0) {
        LOGE("Vulkan bridge: could not allocate a %ux%u shared buffer", width, height);
        sharedBuffer = nullptr;
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props {VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &formatProps;
    if (vkGetAndroidHardwareBufferPropertiesANDROID(device, sharedBuffer, &props) != VK_SUCCESS) {
        LOGE("Vulkan bridge: buffer properties unavailable");
        releaseSharedImage();
        return false;
    }

    VkExternalMemoryImageCreateInfo external {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.pNext = &external;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device, &imageInfo, nullptr, &sharedImage) != VK_SUCCESS) {
        LOGE("Vulkan bridge: vkCreateImage for the shared buffer failed");
        releaseSharedImage();
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID import {VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.buffer = sharedBuffer;
    VkMemoryDedicatedAllocateInfo dedicated {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.pNext = &import;
    dedicated.image = sharedImage;
    VkMemoryAllocateInfo alloc {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.pNext = &dedicated;
    alloc.allocationSize = props.allocationSize;
    alloc.memoryTypeIndex = 0;
    for (uint32_t i = 0; i < 32; i++) {
        if (props.memoryTypeBits & (1u << i)) { alloc.memoryTypeIndex = i; break; }
    }
    if (vkAllocateMemory(device, &alloc, nullptr, &sharedMemory) != VK_SUCCESS ||
        vkBindImageMemory(device, sharedImage, sharedMemory, 0) != VK_SUCCESS) {
        LOGE("Vulkan bridge: importing the shared buffer failed");
        releaseSharedImage();
        return false;
    }

    sharedWidth = width;
    sharedHeight = height;
    sharedFirstUse = true;
    LOGI("Vulkan bridge: shared picture %ux%u", width, height);
    return true;
}

namespace {
VkImageMemoryBarrier sourceToTransfer(VkImage source, VkImageLayout layout) {
    VkImageMemoryBarrier b {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.oldLayout = layout;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = source;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}

VkImageMemoryBarrier sourceBack(VkImage source, VkImageLayout layout) {
    VkImageMemoryBarrier b {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.newLayout = layout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = source;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}
}  // namespace

bool VulkanBridge::copyFrame(unsigned width, unsigned height) {
    if (!hasImage) return false;
    VkImage source = currentImage.create_info.image;
    VkImageLayout sourceLayout = currentImage.image_layout;
    const uint32_t external = foreignQueue ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_EXTERNAL;

    vkResetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &begin);

    VkImageSubresourceRange range {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier before[2] {};
    before[0] = sourceToTransfer(source, sourceLayout);
    before[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    before[1].srcAccessMask = 0;
    before[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    before[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;  // the whole picture is replaced
    before[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    before[1].srcQueueFamilyIndex = sharedFirstUse ? VK_QUEUE_FAMILY_IGNORED : external;
    before[1].dstQueueFamilyIndex = sharedFirstUse ? VK_QUEUE_FAMILY_IGNORED : queueFamily;
    before[1].image = sharedImage;
    before[1].subresourceRange = range;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 2, before);

    VkImageBlit blit {};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[1] = {(int32_t) width, (int32_t) height, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[1] = {(int32_t) width, (int32_t) height, 1};
    vkCmdBlitImage(commandBuffer, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   sharedImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    VkImageMemoryBarrier after[2] {};
    after[0] = sourceBack(source, sourceLayout);
    // Hand the shared picture over to OpenGL ES (release to the "outside world" queue family).
    after[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    after[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    after[1].dstAccessMask = 0;
    after[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    after[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    after[1].srcQueueFamilyIndex = queueFamily;
    after[1].dstQueueFamilyIndex = external;
    after[1].image = sharedImage;
    after[1].subresourceRange = range;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         0, 0, nullptr, 0, nullptr, 2, after);
    vkEndCommandBuffer(commandBuffer);

    std::vector<VkPipelineStageFlags> waitStages(waitSemaphores.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkSubmitInfo submit {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = (uint32_t) waitSemaphores.size();
    submit.pWaitSemaphores = waitSemaphores.empty() ? nullptr : waitSemaphores.data();
    submit.pWaitDstStageMask = waitStages.empty() ? nullptr : waitStages.data();
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer;
    VkResult result;
    {
        std::lock_guard<std::recursive_mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, fence);
    }
    waitSemaphores.clear();
    if (result != VK_SUCCESS) {
        LOGE("Vulkan bridge: vkQueueSubmit failed (%d)", result);
        return false;
    }
    // Wait until the picture is really in the shared buffer before OpenGL ES reads it.
    result = vkWaitForFences(device, 1, &fence, VK_TRUE, 2000000000ull);
    vkResetFences(device, 1, &fence);
    if (result != VK_SUCCESS) {
        LOGE("Vulkan bridge: waiting for the frame failed (%d)", result);
        return false;
    }
    sharedFirstUse = false;
    frameIndex++;
    return true;
}

// ------------------------------------------------------------------ copy through memory (fallback)

void VulkanBridge::releaseReadback() {
    if (device != VK_NULL_HANDLE) {
        if (readbackPointer && readbackMemory != VK_NULL_HANDLE) vkUnmapMemory(device, readbackMemory);
        if (readbackBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, readbackBuffer, nullptr);
        if (readbackMemory != VK_NULL_HANDLE) vkFreeMemory(device, readbackMemory, nullptr);
    }
    readbackPointer = nullptr;
    readbackBuffer = VK_NULL_HANDLE;
    readbackMemory = VK_NULL_HANDLE;
    readbackWidth = readbackHeight = 0;
}

bool VulkanBridge::ensureReadback(unsigned width, unsigned height) {
    if (readbackBuffer != VK_NULL_HANDLE && width == readbackWidth && height == readbackHeight) return true;
    releaseReadback();
    VkBufferCreateInfo info {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = (VkDeviceSize) width * height * 4;
    info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &info, nullptr, &readbackBuffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req {};
    vkGetBufferMemoryRequirements(device, readbackBuffer, &req);
    VkPhysicalDeviceMemoryProperties mem {};
    vkGetPhysicalDeviceMemoryProperties(gpu, &mem);
    int chosen = -1;
    // Prefer cached memory (fast CPU reads), then any CPU-visible memory.
    const VkMemoryPropertyFlags wanted[] = {
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
    };
    for (VkMemoryPropertyFlags flags : wanted) {
        for (uint32_t i = 0; i < mem.memoryTypeCount && chosen < 0; i++) {
            if ((req.memoryTypeBits & (1u << i)) && (mem.memoryTypes[i].propertyFlags & flags) == flags) chosen = (int) i;
        }
        if (chosen >= 0) break;
    }
    if (chosen < 0) return false;
    readbackCoherent = (mem.memoryTypes[chosen].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    VkMemoryAllocateInfo alloc {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = (uint32_t) chosen;
    if (vkAllocateMemory(device, &alloc, nullptr, &readbackMemory) != VK_SUCCESS ||
        vkBindBufferMemory(device, readbackBuffer, readbackMemory, 0) != VK_SUCCESS ||
        vkMapMemory(device, readbackMemory, 0, VK_WHOLE_SIZE, 0, &readbackPointer) != VK_SUCCESS) {
        releaseReadback();
        return false;
    }
    readbackWidth = width;
    readbackHeight = height;
    LOGI("Vulkan bridge: copy buffer %ux%u", width, height);
    return true;
}

bool VulkanBridge::copyFrameToMemory(unsigned width, unsigned height) {
    if (!hasImage) return false;
    VkImage source = currentImage.create_info.image;
    VkImageLayout sourceLayout = currentImage.image_layout;
    const VkFormat format = currentImage.create_info.format;
    readbackBgra = format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB;

    vkResetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &begin);
    VkImageMemoryBarrier before = sourceToTransfer(source, sourceLayout);
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &before);
    VkBufferImageCopy region {};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {width, height, 1};
    vkCmdCopyImageToBuffer(commandBuffer, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, 1, &region);
    VkImageMemoryBarrier after = sourceBack(source, sourceLayout);
    VkBufferMemoryBarrier toHost {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = readbackBuffer;
    toHost.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, nullptr, 1, &toHost, 1, &after);
    vkEndCommandBuffer(commandBuffer);

    std::vector<VkPipelineStageFlags> waitStages(waitSemaphores.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkSubmitInfo submit {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = (uint32_t) waitSemaphores.size();
    submit.pWaitSemaphores = waitSemaphores.empty() ? nullptr : waitSemaphores.data();
    submit.pWaitDstStageMask = waitStages.empty() ? nullptr : waitStages.data();
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer;
    VkResult result;
    {
        std::lock_guard<std::recursive_mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, fence);
    }
    waitSemaphores.clear();
    if (result != VK_SUCCESS) return false;
    result = vkWaitForFences(device, 1, &fence, VK_TRUE, 2000000000ull);
    vkResetFences(device, 1, &fence);
    if (result != VK_SUCCESS) return false;
    if (!readbackCoherent) {
        VkMappedMemoryRange range {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        range.memory = readbackMemory;
        range.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(device, 1, &range);
    }
    frameIndex++;
    return true;
}

bool VulkanBridge::uploadReadback(unsigned width, unsigned height) {
    EGLContext current = eglGetCurrentContext();
    if (current == EGL_NO_CONTEXT) return false;
    if (current != glContext) {
        onGlContextCreated();
        glContext = current;
    }
    if (!vertexBuffer) {
        const GLfloat quad[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
        glGenBuffers(1, &vertexBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    glTarget = GL_TEXTURE_2D;
    if (!program2D) {
        program2D = buildProgram(FRAGMENT_SHADER_2D);
        if (!program2D) return false;
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    if (!glTexture) {
        glGenTextures(1, &glTexture);
        uploadedWidth = uploadedHeight = 0;
    }
    glBindTexture(GL_TEXTURE_2D, glTexture);
    if (uploadedWidth != width || uploadedHeight != height) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei) width, (GLsizei) height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        uploadedWidth = width;
        uploadedHeight = height;
    }
    // Blue and red are swapped in BGRA pictures: let the texture swap them back.
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, readbackBgra ? GL_BLUE : GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, readbackBgra ? GL_RED : GL_BLUE);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei) width, (GLsizei) height, GL_RGBA, GL_UNSIGNED_BYTE, readbackPointer);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

// ------------------------------------------------------------------ OpenGL ES side

void VulkanBridge::onGlContextCreated() {
    // A new GL context: everything made in the old one is gone with it.
    glContext = EGL_NO_CONTEXT;
    eglImage = EGL_NO_IMAGE_KHR;
    eglImageBuffer = nullptr;
    glTexture = 0;
    program2D = 0;
    programExternal = 0;
    vertexBuffer = 0;
    uploadedWidth = uploadedHeight = 0;
}

void VulkanBridge::releaseGlImage() {
    if (glTexture && eglGetCurrentContext() == glContext) glDeleteTextures(1, &glTexture);
    glTexture = 0;
    if (eglImage != EGL_NO_IMAGE_KHR) {
        auto destroyImage = (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
        if (destroyImage) destroyImage(eglGetCurrentDisplay(), eglImage);
    }
    eglImage = EGL_NO_IMAGE_KHR;
    eglImageBuffer = nullptr;
}

bool VulkanBridge::ensureGlResources() {
    EGLContext current = eglGetCurrentContext();
    if (current == EGL_NO_CONTEXT) return false;
    if (current != glContext) {
        onGlContextCreated();
        glContext = current;
    }
    if (!vertexBuffer) {
        const GLfloat quad[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
        glGenBuffers(1, &vertexBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    if (eglImageBuffer != sharedBuffer || eglImage == EGL_NO_IMAGE_KHR) {
        releaseGlImage();
        auto getClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)
            eglGetProcAddress("eglGetNativeClientBufferANDROID");
        auto createImage = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        auto targetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
            eglGetProcAddress("glEGLImageTargetTexture2DOES");
        if (!getClientBuffer || !createImage || !targetTexture) {
            LOGE("Vulkan bridge: EGL image functions unavailable");
            return false;
        }
        EGLClientBuffer clientBuffer = getClientBuffer(sharedBuffer);
        const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        eglImage = createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                               clientBuffer, attributes);
        if (eglImage == EGL_NO_IMAGE_KHR) {
            LOGE("Vulkan bridge: eglCreateImageKHR failed (0x%x)", eglGetError());
            return false;
        }
        eglImageBuffer = sharedBuffer;

        while (glGetError() != GL_NO_ERROR) {}
        glGenTextures(1, &glTexture);
        glTarget = GL_TEXTURE_2D;
        glBindTexture(GL_TEXTURE_2D, glTexture);
        targetTexture(GL_TEXTURE_2D, (GLeglImageOES) eglImage);
        if (glGetError() != GL_NO_ERROR) {
            // Some drivers only accept these buffers as "external" textures.
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(1, &glTexture);
            glGenTextures(1, &glTexture);
            glTarget = GL_TEXTURE_EXTERNAL_OES;
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, glTexture);
            targetTexture(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES) eglImage);
            if (glGetError() != GL_NO_ERROR) {
                LOGE("Vulkan bridge: OpenGL ES could not use the shared buffer");
                glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
                return false;
            }
        }
        glTexParameteri(glTarget, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(glTarget, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(glTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(glTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(glTarget, 0);
        LOGI("Vulkan bridge: OpenGL ES reads the shared picture as %s",
             glTarget == GL_TEXTURE_2D ? "a 2D texture" : "an external texture");
    }
    GLuint& program = glTarget == GL_TEXTURE_2D ? program2D : programExternal;
    if (!program) {
        program = buildProgram(glTarget == GL_TEXTURE_2D ? FRAGMENT_SHADER_2D : FRAGMENT_SHADER_EXTERNAL);
        if (!program) return false;
    }
    return true;
}

void VulkanBridge::drawShared(GLuint framebuffer, unsigned width, unsigned height) {
    GLint previousViewport[4];
    glGetIntegerv(GL_VIEWPORT, previousViewport);
    GLuint program = glTarget == GL_TEXTURE_2D ? program2D : programExternal;

    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glViewport(0, 0, (GLsizei) width, (GLsizei) height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(glTarget, glTexture);
    glUniform1i(glGetUniformLocation(program, "frame"), 0);
    glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(glTarget, 0);
    glUseProgram(0);
    glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
}

bool VulkanBridge::presentInto(GLuint framebuffer, unsigned width, unsigned height) {
    if (!deviceReady || !hasImage || width == 0 || height == 0) return false;
    if (shareWithHardwareBuffer) {
        if (ensureSharedImage(width, height) && copyFrame(width, height) && ensureGlResources()) {
            drawShared(framebuffer, width, height);
        } else {
            // Direct sharing didn't work on this driver: copy through memory from now on.
            LOGW("Vulkan bridge: direct sharing failed; copying pictures through memory instead");
            releaseSharedImage();
            shareWithHardwareBuffer = false;
            return false;
        }
    } else {
        if (!ensureReadback(width, height) || !copyFrameToMemory(width, height) ||
            !uploadReadback(width, height)) {
            return false;
        }
        drawShared(framebuffer, width, height);
    }
    if (!loggedFirstFrame) {
        loggedFirstFrame = true;
        LOGI("Vulkan bridge: first frame shown (%ux%u)", width, height);
    }
    return true;
}

void VulkanBridge::destroy() {
    if (device != VK_NULL_HANDLE) {
        {
            std::lock_guard<std::recursive_mutex> lock(queueMutex);
            if (vkQueueWaitIdle) vkQueueWaitIdle(queue);
        }
        releaseSharedImage();
        releaseReadback();
        if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
    }
    fence = VK_NULL_HANDLE;
    commandPool = VK_NULL_HANDLE;
    commandBuffer = VK_NULL_HANDLE;
    // The device belongs to the core's Vulkan backend; the process ends with the game.
    hasImage = false;
    deviceReady = false;
}

}  // namespace libretrodroid
