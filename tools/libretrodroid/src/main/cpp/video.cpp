/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <cstdlib>
#include <string>
#include <cmath>
#include <utility>
#include <sstream>

#include "log.h"

#include "video.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es3/imagerendereres3.h"
#include "renderers/es2/imagerendereres2.h"

namespace libretrodroid {

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    LOGI("GL %s = %s\n", name, v);
}

GLuint loadShader(GLenum shaderType, const char* pSource) {
    GLuint shader = glCreateShader(shaderType);
    if (shader) {
        glShaderSource(shader, 1, &pSource, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    glGetShaderInfoLog(shader, infoLen, nullptr, buf);
                    LOGE("Could not compile shader %d:\n%s\n",
                         shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
    }
    return shader;
}

GLuint createProgram(const char* pVertexSource, const char* pFragmentSource) {
    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, pVertexSource);
    if (!vertexShader) {
        return 0;
    }

    GLuint pixelShader = loadShader(GL_FRAGMENT_SHADER, pFragmentSource);
    if (!pixelShader) {
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program) {
        glAttachShader(program, vertexShader);
        glAttachShader(program, pixelShader);
        glLinkProgram(program);
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(program, bufLength, nullptr, buf);
                    LOGE("Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(program);
            program = 0;
        }
    }
    return program;
}

void Video::updateProgram() {
    if (loadedShaderType.has_value() && loadedShaderType.value() == requestedShaderConfig) {
        return;
    }

    loadedShaderType = requestedShaderConfig;

    auto shaders = ShaderManager::getShader(requestedShaderConfig);

    shadersChain = {};

    std::for_each(shaders.passes.begin(), shaders.passes.end(), [&](const auto& item){
        auto shader = ShaderChainEntry { };

        shader.gProgram = createProgram(item.vertex.data(), item.fragment.data());
        if (!shader.gProgram) {
            LOGE("Could not create gl program.");
            throw std::runtime_error("Cannot create gl program");
        }

        shader.gvPositionHandle = glGetAttribLocation(shader.gProgram, "vPosition");

        shader.gvCoordinateHandle = glGetAttribLocation(shader.gProgram, "vCoordinate");

        shader.gTextureHandle = glGetUniformLocation(shader.gProgram, "texture");

        shader.gPreviousPassTextureHandle = glGetUniformLocation(shader.gProgram, "previousPass");

        shader.gTextureSizeHandle = glGetUniformLocation(shader.gProgram, "textureSize");

        shader.gScreenDensityHandle = glGetUniformLocation(shader.gProgram, "screenDensity");

        shadersChain.push_back(shader);
    });

    renderer->setShaders(shaders);
}

// --- Tirso Compiler patch -------------------------------------------------------------------
// Some cores (Dolphin) keep their own OpenGL state between frames and expect nobody else to
// change it (RetroArch calls this a "shared context"). LibretroDroid draws the frame with
// client-side vertex arrays, which silently draw nothing while the core's vertex array object or
// buffers are still bound, and its own state changes would then corrupt the core's cached state.
// When LIBRETRODROID_PRESERVE_GL=1 (set by the app before the game starts) the state touched here
// is saved, reset to defaults for LibretroDroid's own drawing, and restored afterwards.
namespace {
struct CoreGLStateGuard {
    bool active;
    GLint vao = 0, program = 0, arrayBuffer = 0, drawFb = 0, readFb = 0, activeTexture = 0;
    GLint tex[2] = {0, 0}, sampler[2] = {0, 0};
    GLint unpackBuffer = 0, packBuffer = 0, unpackAlignment = 4, unpackRowLength = 0;
    GLint viewport[4] = {0, 0, 0, 0}, scissorBox[4] = {0, 0, 0, 0};
    GLboolean blend = GL_FALSE, depthTest = GL_FALSE, scissorTest = GL_FALSE, cullFace = GL_FALSE,
              stencilTest = GL_FALSE, dither = GL_FALSE, polygonOffset = GL_FALSE, depthMask = GL_TRUE;
    GLboolean colorMask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
    GLfloat clearColor[4] = {0, 0, 0, 0};

    static bool enabled() {
        static const bool on = [] { const char* v = getenv("LIBRETRODROID_PRESERVE_GL"); return v && v[0] == '1'; }();
        return on;
    }

    CoreGLStateGuard() : active(enabled()) {
        if (!active) return;
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFb);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFb);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture);
        for (int i = 0; i < 2; ++i) {
            glActiveTexture(GL_TEXTURE0 + i);
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex[i]);
            glGetIntegerv(GL_SAMPLER_BINDING, &sampler[i]);
        }
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpackBuffer);
        glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &packBuffer);
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlignment);
        glGetIntegerv(GL_UNPACK_ROW_LENGTH, &unpackRowLength);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
        blend = glIsEnabled(GL_BLEND);
        depthTest = glIsEnabled(GL_DEPTH_TEST);
        scissorTest = glIsEnabled(GL_SCISSOR_TEST);
        cullFace = glIsEnabled(GL_CULL_FACE);
        stencilTest = glIsEnabled(GL_STENCIL_TEST);
        dither = glIsEnabled(GL_DITHER);
        polygonOffset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        glGetBooleanv(GL_DEPTH_WRITEMASK, &depthMask);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);

        // A clean state for LibretroDroid's own drawing.
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glBindSampler(0, 0);
        glBindSampler(1, 0);
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDepthMask(GL_TRUE);
    }

    static void set(GLenum cap, GLboolean on) { if (on) glEnable(cap); else glDisable(cap); }

    ~CoreGLStateGuard() {
        if (!active) return;
        glBindVertexArray(vao);
        glUseProgram(program);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFb);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFb);
        for (int i = 0; i < 2; ++i) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, tex[i]);
            glBindSampler(i, sampler[i]);
        }
        glActiveTexture(activeTexture);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlignment);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, unpackRowLength);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        set(GL_BLEND, blend);
        set(GL_DEPTH_TEST, depthTest);
        set(GL_SCISSOR_TEST, scissorTest);
        set(GL_CULL_FACE, cullFace);
        set(GL_STENCIL_TEST, stencilTest);
        set(GL_DITHER, dither);
        set(GL_POLYGON_OFFSET_FILL, polygonOffset);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glDepthMask(depthMask);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
    }
};
}  // namespace
// --- end of Tirso Compiler patch ------------------------------------------------------------

void Video::renderFrame() {
    if (skipDuplicateFrames && !isDirty) return;
    isDirty = false;
    CoreGLStateGuard coreState;

    glDisable(GL_DEPTH_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (immersiveModeEnabled) {
        immersiveMode.renderBackground(
            videoLayout.getScreenWidth(),
            videoLayout.getScreenHeight(),
            videoLayout.getBackgroundVertices(),
            videoLayout.getRelativeForegroundBounds(),
            videoLayout.getFramebufferVertices().data(),
            renderer->getTexture()
        );
    }

    updateProgram();
    for (int i = 0; i < shadersChain.size(); ++i) {
        auto shader = shadersChain[i];
        auto passData = renderer->getPassData(i);
        auto isLastPass = i == shadersChain.size() - 1;

        glBindFramebuffer(GL_FRAMEBUFFER, passData.framebuffer.value_or(0));

        glViewport(
            0,
            0,
            passData.width.value_or(videoLayout.getScreenWidth()),
            passData.height.value_or(videoLayout.getScreenHeight())
        );

        glUseProgram(shader.gProgram);

        auto vertices = isLastPass ? videoLayout.getForegroundVertices() : videoLayout.getFramebufferVertices();
        glVertexAttribPointer(shader.gvPositionHandle, 2, GL_FLOAT, GL_FALSE, 0, vertices.data());
        glEnableVertexAttribArray(shader.gvPositionHandle);

        auto coordinates = videoLayout.getTextureCoordinates();
        glVertexAttribPointer(shader.gvCoordinateHandle, 2, GL_FLOAT, GL_FALSE, 0, coordinates.data());
        glEnableVertexAttribArray(shader.gvCoordinateHandle);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, renderer->getTexture());
        glUniform1i(shader.gTextureHandle, 0);

        if (shader.gPreviousPassTextureHandle != -1 && passData.texture.has_value()) {
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, passData.texture.value());
            glUniform1i(shader.gPreviousPassTextureHandle, 1);
        }

        glUniform2f(shader.gTextureSizeHandle, getTextureWidth(), getTextureHeight());

        glUniform1f(shader.gScreenDensityHandle, getScreenDensity());

        glDrawArrays(GL_TRIANGLES, 0, 6);

        glDisableVertexAttribArray(shader.gvPositionHandle);
        glDisableVertexAttribArray(shader.gvCoordinateHandle);

        if (shader.gPreviousPassTextureHandle != -1 && passData.texture.has_value()) {
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);

        glUseProgram(0);
    }
}

float Video::getScreenDensity() {
    return std::min(videoLayout.getScreenWidth() / getTextureWidth(), videoLayout.getScreenHeight() / getTextureHeight());
}

float Video::getTextureWidth() {
    return renderer->lastFrameSize.first;
}

float Video::getTextureHeight() {
    return renderer->lastFrameSize.second;
}

void Video::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data != nullptr) {
        renderer->onNewFrame(data, width, height, pitch);
        isDirty = true;
    }
}

void Video::updateScreenSize(unsigned width, unsigned height) {
    videoLayout.updateScreenSize(width, height);
}

void Video::updateViewportSize(Rect viewportRect) {
    videoLayout.updateViewportSize(viewportRect);
}

void Video::updateViewportAlignment(unsigned int viewportAlignment) {
    videoLayout.updateViewportAlignment(viewportAlignment);
}

void Video::updateRendererSize(unsigned int width, unsigned int height) {
    LOGD("Updating renderer size: %d x %d", width, height);
    renderer->updateRenderedResolution(width, height);
}

void Video::updateRotation(float rotation) {
    videoLayout.updateRotation(rotation);
}

Video::Video(
    RenderingOptions renderingOptions,
    ShaderManager::Config shaderConfig,
    bool bottomLeftOrigin,
    float rotation,
    bool skipDuplicateFrames,
    bool immersiveModeEnabled,
    Rect viewportRect,
    ImmersiveMode::Config immersiveModeConfig,
    unsigned int viewportAlignment
) :
    requestedShaderConfig(std::move(shaderConfig)),
    skipDuplicateFrames(skipDuplicateFrames),
    immersiveModeEnabled(immersiveModeEnabled),
    immersiveMode(immersiveModeConfig),
    videoLayout(bottomLeftOrigin, rotation, viewportRect, viewportAlignment) {

    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);
    initializeGLESLogCallbackIfNeeded();

    LOGI("Initializing graphics");

    glViewport(0, 0, videoLayout.getScreenWidth(), videoLayout.getScreenHeight());

    glUseProgram(0);

    initializeRenderer(renderingOptions);
}

void Video::updateShaderType(ShaderManager::Config shaderConfig) {
    requestedShaderConfig = std::move(shaderConfig);
}

void Video::initializeRenderer(RenderingOptions renderingOptions) {
    auto shaders = ShaderManager::getShader(requestedShaderConfig);

    if (renderingOptions.hardwareAccelerated) {
        renderer = new FramebufferRenderer(
            renderingOptions.width,
            renderingOptions.height,
            renderingOptions.useDepth,
            renderingOptions.useStencil,
            std::move(shaders)
        );
    } else {
        if (renderingOptions.openglESVersion >= 3) {
            renderer = new ImageRendererES3();
        } else {
            renderer = new ImageRendererES2();
        }
    }

    renderer->setPixelFormat(renderingOptions.pixelFormat);
    updateProgram();
}

void Video::updateAspectRatio(float aspectRatio) {
    videoLayout.updateAspectRatio(aspectRatio);
}

} //namespace libretrodroid
