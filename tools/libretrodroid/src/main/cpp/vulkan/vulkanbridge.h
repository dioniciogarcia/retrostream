/*
 * Tirso addition to LibretroDroid (GPLv3, like LibretroDroid).
 *
 * Lets a libretro core that renders with Vulkan (RETRO_HW_CONTEXT_VULKAN) run inside
 * LibretroDroid's OpenGL ES player. The core draws each frame with Vulkan; the frame is
 * copied into an Android hardware buffer that Vulkan and OpenGL ES share, and LibretroDroid
 * then shows it exactly like a frame drawn by an OpenGL core.
 *
 * Turned on with the environment variable LIBRETRODROID_VULKAN=1.
 *
 * A custom Adreno driver (for example Mesa Turnip) is loaded through libadrenotools when
 * LIBRETRODROID_VK_DRIVER_DIR (folder, ending in "/"), LIBRETRODROID_VK_DRIVER_LIB (file name),
 * LIBRETRODROID_VK_HOOK_DIR (the app's native library folder, ending in "/") and
 * LIBRETRODROID_VK_TMP_DIR are set. If it can't be used, the phone's own driver is used.
 *
 * When a driver can't share pictures with OpenGL ES (no Android hardware buffer support),
 * frames are copied through memory instead (slower, but it works everywhere).
 */
#ifndef LIBRETRODROID_VULKANBRIDGE_H
#define LIBRETRODROID_VULKANBRIDGE_H

#define VK_USE_PLATFORM_ANDROID_KHR 1
#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES 1  // every Vulkan function is loaded from the chosen driver
#endif
#include <vulkan/vulkan.h>
#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <mutex>
#include <vector>
#include "libretro_vulkan.h"

struct AHardwareBuffer;
struct AImageReader;
struct ANativeWindow;

namespace libretrodroid {

class VulkanBridge {
public:
    static VulkanBridge& getInstance() {
        static VulkanBridge instance;
        return instance;
    }

    /** True when the player asked for Vulkan (LIBRETRODROID_VULKAN=1). */
    static bool requested();

    /** The core asked for a Vulkan context (SET_HW_RENDER with RETRO_HW_CONTEXT_VULKAN). */
    void setCoreWantsVulkan(bool value) { coreWantsVulkan = value; }
    bool isActive() const { return coreWantsVulkan; }

    void setNegotiationInterface(const retro_hw_render_context_negotiation_interface_vulkan* iface) {
        negotiation = iface;
    }

    /** Creates the Vulkan instance and (through the core) the device. Returns true once ready. */
    bool ensureDevice();
    bool isDeviceReady() const { return deviceReady; }

    /** The interface handed to the core by GET_HW_RENDER_INTERFACE. */
    retro_hw_render_interface_vulkan* getInterface() { return deviceReady ? &iface : nullptr; }

    /** Called on the GL thread after a (new) GL context was created. */
    void onGlContextCreated();

    /**
     * The core finished a frame (video refresh with RETRO_HW_FRAME_BUFFER_VALID).
     * Copies it into the shared buffer and draws it into [framebuffer] (GL thread).
     */
    bool presentInto(GLuint framebuffer, unsigned width, unsigned height);

    void destroy();

private:
    VulkanBridge() = default;

    // retro_hw_render_interface_vulkan callbacks
    static void cbSetImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                           const VkSemaphore* semaphores, uint32_t srcQueueFamily);
    static uint32_t cbGetSyncIndex(void* handle);
    static uint32_t cbGetSyncIndexMask(void* handle);
    static void cbSetCommandBuffers(void* handle, uint32_t num, const VkCommandBuffer* cmd);
    static void cbWaitSyncIndex(void* handle);
    static void cbLockQueue(void* handle);
    static void cbUnlockQueue(void* handle);
    static void cbSetSignalSemaphore(void* handle, VkSemaphore semaphore);

    bool loadLoader(bool allowCustom);
    void unloadInstance();
    bool createInstance();
    bool createDummySurface();
    bool ensureSharedImage(unsigned width, unsigned height);
    void releaseSharedImage();
    bool ensureGlResources();
    void releaseGlImage();
    bool copyFrame(unsigned width, unsigned height);
    bool ensureReadback(unsigned width, unsigned height);
    void releaseReadback();
    bool copyFrameToMemory(unsigned width, unsigned height);
    bool uploadReadback(unsigned width, unsigned height);
    void drawShared(GLuint framebuffer, unsigned width, unsigned height);

    bool coreWantsVulkan = false;
    bool deviceReady = false;
    bool deviceFailed = false;
    const retro_hw_render_context_negotiation_interface_vulkan* negotiation = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    AImageReader* dummyReader = nullptr;
    ANativeWindow* dummyWindow = nullptr;

    retro_hw_render_interface_vulkan iface {};
    std::recursive_mutex queueMutex;
    uint32_t frameIndex = 0;

    // The frame the core handed over with set_image.
    retro_vulkan_image currentImage {};
    bool hasImage = false;
    uint32_t imageQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> waitSemaphores;

    // Vulkan side of the shared buffer.
    AHardwareBuffer* sharedBuffer = nullptr;
    VkImage sharedImage = VK_NULL_HANDLE;
    VkDeviceMemory sharedMemory = VK_NULL_HANDLE;
    unsigned sharedWidth = 0;
    unsigned sharedHeight = 0;
    bool sharedFirstUse = true;
    bool foreignQueue = false;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties = nullptr;

    // OpenGL ES side of the shared buffer.
    EGLContext glContext = EGL_NO_CONTEXT;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    AHardwareBuffer* eglImageBuffer = nullptr;
    GLuint glTexture = 0;
    GLenum glTarget = GL_TEXTURE_2D;
    GLuint program2D = 0;
    GLuint programExternal = 0;
    GLuint vertexBuffer = 0;
    bool loggedFirstFrame = false;

    // Driver and picture-sharing mode.
    void* vulkanLibrary = nullptr;
    bool customDriver = false;
    bool shareWithHardwareBuffer = true;

    // Memory-copy fallback.
    VkBuffer readbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory readbackMemory = VK_NULL_HANDLE;
    void* readbackPointer = nullptr;
    bool readbackCoherent = true;
    unsigned readbackWidth = 0;
    unsigned readbackHeight = 0;
    unsigned uploadedWidth = 0;
    unsigned uploadedHeight = 0;
    bool readbackBgra = false;
};

}

#endif
