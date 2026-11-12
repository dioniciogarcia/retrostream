// RetroStream for Windows: the game player.
// Runs a libretro engine (the same engines as the Android app), draws the picture with OpenGL, plays the sound,
// reads controllers and the keyboard, and shows RetroStream's in-game menu (Dear ImGui).
// Saves use the Android app's layout so backups are shared:  <saveroot>/saves/game.srm, <saveroot>/states/auto.state, slotN.state
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <SDL.h>
#include <SDL_opengl.h>
#include "imgui.h"
#include "imgui_impl_sdl2.h"
#include "imgui_impl_opengl3.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include "libretro.h"
#include <windows.h>
#include <algorithm>
#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <deque>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>
#include <vector>
#include <iostream>
#include <mutex>
#include <thread>

namespace fs = std::filesystem;

// ------------------------------------------------------------------ small helpers
static std::map<std::string, std::string> A;   // command line: --key value
static std::string arg(const char* k, const char* def = "") { auto it = A.find(k); return it == A.end() ? def : it->second; }
static fs::path P(const std::string& utf8) { return fs::u8path(utf8); }
static void logf(const char* fmt, ...) { va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap); fputc('\n', stderr); fflush(stderr); }
static bool readFile(const fs::path& p, std::vector<uint8_t>& out) {
  std::ifstream f(p, std::ios::binary); if (!f) return false;
  f.seekg(0, std::ios::end); auto n = (size_t)f.tellg(); f.seekg(0); out.resize(n);
  if (n) f.read((char*)out.data(), n); return (bool)f || n == 0;
}
static bool writeFile(const fs::path& p, const void* data, size_t n) {
  std::error_code ec; fs::create_directories(p.parent_path(), ec);
  fs::path tmp = p; tmp += ".tmp";
  { std::ofstream f(tmp, std::ios::binary); if (!f) return false; f.write((const char*)data, n); if (!f) return false; }
  fs::rename(tmp, p, ec); if (ec) { fs::remove(p, ec); fs::rename(tmp, p, ec); }
  return !ec;
}
static std::wstring wide(const std::string& s) { int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0); std::wstring w(n, 0); MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &w[0], n); if (!w.empty()) w.pop_back(); return w; }

// ------------------------------------------------------------------ texts (English, Spanish)
static bool ES = false;
static const char* tr(const char* en) {
  static const std::map<std::string, const char*> es = {
    {"Resume", "Continuar"}, {"Save game state", "Guardar estado"}, {"Load game state", "Cargar estado"}, {"Rewind 10 seconds", "Retroceder 10 segundos"},
    {"Fast forward", "Avance rápido"}, {"Take screenshot", "Captura de pantalla"}, {"Walkthrough (YouTube)", "Guía en video (YouTube)"},
    {"Screen filter", "Filtro de pantalla"}, {"Full screen", "Pantalla completa"}, {"Restart game", "Reiniciar juego"}, {"Exit game", "Salir del juego"},
    {"Back", "Volver"}, {"Slot", "Espacio"}, {"Empty", "Vacío"}, {"Saved", "Guardado"}, {"Loaded", "Cargado"}, {"Nothing saved in that slot", "No hay nada guardado ahí"},
    {"That save couldn't be loaded", "No se pudo cargar esa partida"}, {"Continuing where you left off", "Continuando donde lo dejaste"},
    {"Screenshot saved (Pictures > RetroStream)", "Captura guardada (Imágenes > RetroStream)"}, {"Rewound", "Retrocedido"},
    {"Nothing to rewind yet", "Aún no hay nada que retroceder"}, {"Normal speed", "Velocidad normal"}, {"Sharp pixels", "Píxeles nítidos"},
    {"Smooth", "Suave"}, {"on", "sí"}, {"off", "no"}, {"paused", "en pausa"}, {"Esc or the Guide button opens this menu", "Esc o el botón central del mando abren este menú"},
    {"Rewind isn't available for this console", "Retroceder no está disponible en esta consola"}, {"Restarted", "Reiniciado"}, {"Cheats", "Trucos"},
    {"Tick the cheats you want. Codes marked (M) or Master must be on for the others to work. Save first: cheats can break a game.",
     "Marca los trucos que quieras. Los códigos (M) o Master deben estar activos para que funcionen los demás. Guarda antes: los trucos pueden romper un juego."},
    {"Controls: arrows, Z X A S, Q W, Enter, Right Shift · Tab fast forward · Backspace rewind · F5 save · F7 load · F12 screenshot",
     "Controles: flechas, Z X A S, Q W, Enter, Mayús derecha · Tab avance rápido · Retroceso retroceder · F5 guardar · F7 cargar · F12 captura"},
  };
  if (!ES) return en; auto it = es.find(en); return it == es.end() ? en : it->second;
}

// ------------------------------------------------------------------ the engine
struct Core {
  HMODULE dll = nullptr;
  void (*init)(); void (*deinit)(); unsigned (*api_version)();
  void (*get_system_info)(retro_system_info*); void (*get_system_av_info)(retro_system_av_info*);
  void (*set_environment)(retro_environment_t); void (*set_video_refresh)(retro_video_refresh_t);
  void (*set_audio_sample)(retro_audio_sample_t); void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
  void (*set_input_poll)(retro_input_poll_t); void (*set_input_state)(retro_input_state_t);
  void (*set_controller_port_device)(unsigned, unsigned); void (*reset)(); void (*run)();
  size_t (*serialize_size)(); bool (*serialize)(void*, size_t); bool (*unserialize)(const void*, size_t);
  bool (*load_game)(const retro_game_info*); void (*unload_game)();
  void* (*get_memory_data)(unsigned); size_t (*get_memory_size)(unsigned);
  void (*cheat_reset)(); void (*cheat_set)(unsigned, bool, const char*);
} C;

struct Var { std::string value; std::vector<std::string> choices; };
static std::map<std::string, Var> vars;               // engine options
static std::map<std::string, std::string> wanted;      // options RetroStream sets (from the options file)
static bool varsChanged = true;
static std::string systemDir, saveDir;
static retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static retro_hw_render_callback hw = {};
static bool useHw = false;
static retro_system_av_info av = {};
static bool avChanged = false;
static unsigned rotation = 0;

// picture
static std::vector<uint32_t> rgba; static unsigned fw = 0, fh = 0; static bool fresh = false, hwFrame = false;
static GLuint tex = 0, fbo = 0, fboTex = 0, fboDepth = 0; static unsigned texW = 0, texH = 0, fboW = 0, fboH = 0;
static PFNGLGENFRAMEBUFFERSPROC pGenFramebuffers; static PFNGLBINDFRAMEBUFFERPROC pBindFramebuffer; static PFNGLFRAMEBUFFERTEXTURE2DPROC pFramebufferTexture2D;
static PFNGLGENRENDERBUFFERSPROC pGenRenderbuffers; static PFNGLBINDRENDERBUFFERPROC pBindRenderbuffer; static PFNGLRENDERBUFFERSTORAGEPROC pRenderbufferStorage;
static PFNGLFRAMEBUFFERRENDERBUFFERPROC pFramebufferRenderbuffer; static PFNGLCHECKFRAMEBUFFERSTATUSPROC pCheckFramebufferStatus;
static PFNGLDELETEFRAMEBUFFERSPROC pDeleteFramebuffers; static PFNGLDELETERENDERBUFFERSPROC pDeleteRenderbuffers;

// Engines that draw with the graphics card get their own OpenGL context (sharing textures with ours), so the menu
// drawing never disturbs their state; the picture is a shared texture.
static SDL_GLContext uiCtx = nullptr, coreCtx = nullptr;
static SDL_Window* window;
static void useCore() { if (coreCtx) SDL_GL_MakeCurrent(window, coreCtx); }
static void useUi() { if (coreCtx) SDL_GL_MakeCurrent(window, uiCtx); }

// sound
static SDL_AudioDeviceID audioDev = 0; static std::vector<int16_t> audioBuf; static int audioRate = 0;

// input
static std::vector<SDL_GameController*> pads;
static int16_t mouseX = 0, mouseY = 0; static bool mouseDown = false;
static float imgX = 0, imgY = 0, imgW = 1, imgH = 1;   // where the picture is on screen

// state of the session
static bool running = true, menuOpen = false, fastForward = false, ffToggle = false, rewinding = false, smooth = false, fullscreen = true;
static std::string title, consoleId, gameId;
static fs::path saveRoot, shotsQueue, picturesDir;
static std::string toastText; static Uint32 toastUntil = 0;
static void toast(const std::string& s) { toastText = s; toastUntil = SDL_GetTicks() + 2600; }
// Commands from the app (sent by the phone remote): one word per line on stdin, e.g. "pause", "save 2", "exit".
static std::mutex cmdLock; static std::deque<std::string> cmds;
static void readCommands() { std::string line; while (std::getline(std::cin, line)) { std::lock_guard<std::mutex> g(cmdLock); cmds.push_back(line); } }
static void tellState() { printf("{\"event\":\"state\",\"paused\":%s,\"ff\":%s,\"filter\":\"%s\"}\n", menuOpen ? "true" : "false", ffToggle ? "true" : "false", smooth ? "smooth" : "sharp"); fflush(stdout); }

// ------------------------------------------------------------------ libretro callbacks
static void RETRO_CALLCONV coreLog(enum retro_log_level level, const char* fmt, ...) {
  if (level < RETRO_LOG_WARN) return;
  va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap); fflush(stderr);
}
static uintptr_t RETRO_CALLCONV hwFramebuffer() { return fbo; }
static retro_proc_address_t RETRO_CALLCONV hwProc(const char* sym) { return (retro_proc_address_t)SDL_GL_GetProcAddress(sym); }
static bool RETRO_CALLCONV setRumble(unsigned port, enum retro_rumble_effect, uint16_t strength) {
  if (port < pads.size() && pads[port]) SDL_GameControllerRumble(pads[port], strength, strength, 200);
  return true;
}

static void addVar(const char* key, const char* def) {
  // "Description; a|b|c" -> choices a, b, c (the first is the default).
  Var v; const char* semi = strchr(def, ';'); std::string list = semi ? semi + 1 : def;
  while (!list.empty() && list[0] == ' ') list.erase(0, 1);
  size_t pos = 0; while (true) { size_t bar = list.find('|', pos); v.choices.push_back(list.substr(pos, bar == std::string::npos ? std::string::npos : bar - pos)); if (bar == std::string::npos) break; pos = bar + 1; }
  v.value = v.choices.empty() ? "" : v.choices[0];
  auto w = wanted.find(key); if (w != wanted.end()) v.value = w->second;
  vars[key] = v;
}

static bool RETRO_CALLCONV env(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((retro_log_callback*)data)->log = coreLog; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char**)data = systemDir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data = saveDir.c_str(); return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: pixfmt = *(retro_pixel_format*)data; return pixfmt <= RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: return false;   // the simple option list is enough
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
      for (auto* v = (const retro_variable*)data; v && v->key; v++) addVar(v->key, v->value);
      varsChanged = true; return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
      auto* v = (retro_variable*)data; auto it = vars.find(v->key);
      if (it != vars.end()) { v->value = it->second.value.c_str(); return true; }
      auto w = wanted.find(v->key); if (w != wanted.end()) { v->value = w->second.c_str(); return true; }
      v->value = nullptr; return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data = varsChanged; varsChanged = false; return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
      auto* h = (retro_hw_render_callback*)data;
      if (h->context_type != RETRO_HW_CONTEXT_OPENGL && h->context_type != RETRO_HW_CONTEXT_OPENGL_CORE) return false;
      h->get_current_framebuffer = hwFramebuffer; h->get_proc_address = hwProc;
      hw = *h; useHw = true; return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT: return true;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: *(unsigned*)data = RETRO_HW_CONTEXT_OPENGL_CORE; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY: av.geometry = *(retro_game_geometry*)data; return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: av = *(retro_system_av_info*)data; avChanged = true; return true;
    case RETRO_ENVIRONMENT_SET_ROTATION: rotation = *(unsigned*)data; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE: *(unsigned*)data = ES ? RETRO_LANGUAGE_SPANISH : RETRO_LANGUAGE_ENGLISH; return true;
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: ((retro_rumble_interface*)data)->set_rumble_state = setRumble; return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: toast(((retro_message*)data)->msg); return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: *(int*)data = 3; return true;
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO: case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS: return true;
    case RETRO_ENVIRONMENT_GET_USERNAME: *(const char**)data = "RetroStream"; return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING: *(bool*)data = fastForward; return true;
    default: return false;
  }
}

static inline uint32_t pack(unsigned r, unsigned g, unsigned b) { return r | (g << 8) | (b << 16) | 0xFF000000u; }
static int videoCalls = 0, dupCalls = 0;
static void RETRO_CALLCONV videoRefresh(const void* data, unsigned w, unsigned h, size_t pitch) {
  videoCalls++;
  if (videoCalls <= 4) logf("picture %d: %ux%u %s", videoCalls, w, h, !data ? "(same as before)" : data == RETRO_HW_FRAME_BUFFER_VALID ? "(graphics card)" : "(memory)");
  if (!data) { dupCalls++; return; }  // same picture as before
  fw = w; fh = h;
  if (data == RETRO_HW_FRAME_BUFFER_VALID) { hwFrame = true; fresh = true; return; }
  hwFrame = false; rgba.resize((size_t)w * h);
  for (unsigned y = 0; y < h; y++) {
    const uint8_t* row = (const uint8_t*)data + y * pitch; uint32_t* out = &rgba[(size_t)y * w];
    if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
      const uint32_t* s = (const uint32_t*)row;
      for (unsigned x = 0; x < w; x++) { uint32_t p = s[x]; out[x] = pack((p >> 16) & 255, (p >> 8) & 255, p & 255); }
    } else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
      const uint16_t* s = (const uint16_t*)row;
      for (unsigned x = 0; x < w; x++) { uint16_t p = s[x]; unsigned r = p >> 11, g = (p >> 5) & 63, b = p & 31; out[x] = pack((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)); }
    } else {
      const uint16_t* s = (const uint16_t*)row;
      for (unsigned x = 0; x < w; x++) { uint16_t p = s[x]; unsigned r = (p >> 10) & 31, g = (p >> 5) & 31, b = p & 31; out[x] = pack((r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2)); }
    }
  }
  fresh = true;
}
static void RETRO_CALLCONV audioSample(int16_t l, int16_t r) { audioBuf.push_back(l); audioBuf.push_back(r); }
static size_t RETRO_CALLCONV audioBatch(const int16_t* d, size_t frames) { audioBuf.insert(audioBuf.end(), d, d + frames * 2); return frames; }
static void RETRO_CALLCONV inputPoll() {}

// controller and keyboard -> RetroPad
static uint16_t padBits(unsigned port) {
  uint16_t b = 0;
  auto set = [&](unsigned id, bool on) { if (on) b |= (1u << id); };
  if (port < pads.size() && pads[port]) {
    SDL_GameController* g = pads[port];
    auto btn = [&](SDL_GameControllerButton x) { return SDL_GameControllerGetButton(g, x) != 0; };
    // Xbox layout -> RetroPad (SNES layout): bottom = B, right = A, left = Y, top = X.
    // "Swap A/B" in Settings > Controllers: by the letters printed on the buttons instead of their position.
    const bool sw = arg("swapab") == "1";
    set(RETRO_DEVICE_ID_JOYPAD_B, btn(sw ? SDL_CONTROLLER_BUTTON_B : SDL_CONTROLLER_BUTTON_A)); set(RETRO_DEVICE_ID_JOYPAD_A, btn(sw ? SDL_CONTROLLER_BUTTON_A : SDL_CONTROLLER_BUTTON_B));
    set(RETRO_DEVICE_ID_JOYPAD_Y, btn(sw ? SDL_CONTROLLER_BUTTON_Y : SDL_CONTROLLER_BUTTON_X)); set(RETRO_DEVICE_ID_JOYPAD_X, btn(sw ? SDL_CONTROLLER_BUTTON_X : SDL_CONTROLLER_BUTTON_Y));
    set(RETRO_DEVICE_ID_JOYPAD_SELECT, btn(SDL_CONTROLLER_BUTTON_BACK)); set(RETRO_DEVICE_ID_JOYPAD_START, btn(SDL_CONTROLLER_BUTTON_START));
    set(RETRO_DEVICE_ID_JOYPAD_UP, btn(SDL_CONTROLLER_BUTTON_DPAD_UP)); set(RETRO_DEVICE_ID_JOYPAD_DOWN, btn(SDL_CONTROLLER_BUTTON_DPAD_DOWN));
    set(RETRO_DEVICE_ID_JOYPAD_LEFT, btn(SDL_CONTROLLER_BUTTON_DPAD_LEFT)); set(RETRO_DEVICE_ID_JOYPAD_RIGHT, btn(SDL_CONTROLLER_BUTTON_DPAD_RIGHT));
    set(RETRO_DEVICE_ID_JOYPAD_L, btn(SDL_CONTROLLER_BUTTON_LEFTSHOULDER)); set(RETRO_DEVICE_ID_JOYPAD_R, btn(SDL_CONTROLLER_BUTTON_RIGHTSHOULDER));
    set(RETRO_DEVICE_ID_JOYPAD_L2, SDL_GameControllerGetAxis(g, SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 16000);
    set(RETRO_DEVICE_ID_JOYPAD_R2, SDL_GameControllerGetAxis(g, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 16000);
    set(RETRO_DEVICE_ID_JOYPAD_L3, btn(SDL_CONTROLLER_BUTTON_LEFTSTICK)); set(RETRO_DEVICE_ID_JOYPAD_R3, btn(SDL_CONTROLLER_BUTTON_RIGHTSTICK));
    // Consoles with no analog stick: the left stick also works as the d-pad.
    if (consoleId != "N64" && consoleId != "PSP" && consoleId != "PS2" && consoleId != "GC" && consoleId != "WII") {
      int ax = SDL_GameControllerGetAxis(g, SDL_CONTROLLER_AXIS_LEFTX), ay = SDL_GameControllerGetAxis(g, SDL_CONTROLLER_AXIS_LEFTY);
      set(RETRO_DEVICE_ID_JOYPAD_LEFT, ax < -16000); set(RETRO_DEVICE_ID_JOYPAD_RIGHT, ax > 16000);
      set(RETRO_DEVICE_ID_JOYPAD_UP, ay < -16000); set(RETRO_DEVICE_ID_JOYPAD_DOWN, ay > 16000);
    }
  }
  if (port == 0 && !menuOpen) {
    const Uint8* k = SDL_GetKeyboardState(nullptr);
    set(RETRO_DEVICE_ID_JOYPAD_UP, k[SDL_SCANCODE_UP]); set(RETRO_DEVICE_ID_JOYPAD_DOWN, k[SDL_SCANCODE_DOWN]);
    set(RETRO_DEVICE_ID_JOYPAD_LEFT, k[SDL_SCANCODE_LEFT]); set(RETRO_DEVICE_ID_JOYPAD_RIGHT, k[SDL_SCANCODE_RIGHT]);
    set(RETRO_DEVICE_ID_JOYPAD_B, k[SDL_SCANCODE_Z]); set(RETRO_DEVICE_ID_JOYPAD_A, k[SDL_SCANCODE_X]);
    set(RETRO_DEVICE_ID_JOYPAD_Y, k[SDL_SCANCODE_A]); set(RETRO_DEVICE_ID_JOYPAD_X, k[SDL_SCANCODE_S]);
    set(RETRO_DEVICE_ID_JOYPAD_L, k[SDL_SCANCODE_Q]); set(RETRO_DEVICE_ID_JOYPAD_R, k[SDL_SCANCODE_W]);
    set(RETRO_DEVICE_ID_JOYPAD_L2, k[SDL_SCANCODE_E]); set(RETRO_DEVICE_ID_JOYPAD_R2, k[SDL_SCANCODE_R]);
    set(RETRO_DEVICE_ID_JOYPAD_START, k[SDL_SCANCODE_RETURN]); set(RETRO_DEVICE_ID_JOYPAD_SELECT, k[SDL_SCANCODE_RSHIFT]);
  }
  return menuOpen ? 0 : b;
}
static int16_t RETRO_CALLCONV inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (menuOpen) return 0;
  switch (device) {
    case RETRO_DEVICE_JOYPAD: { uint16_t b = padBits(port); return id == RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)b : (int16_t)((b >> id) & 1); }
    case RETRO_DEVICE_ANALOG: {
      if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) return (padBits(port) >> id) & 1 ? 0x7fff : 0;
      int16_t v = 0;
      if (port < pads.size() && pads[port]) {
        SDL_GameControllerAxis ax = index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? (id == RETRO_DEVICE_ID_ANALOG_X ? SDL_CONTROLLER_AXIS_LEFTX : SDL_CONTROLLER_AXIS_LEFTY)
                                                                            : (id == RETRO_DEVICE_ID_ANALOG_X ? SDL_CONTROLLER_AXIS_RIGHTX : SDL_CONTROLLER_AXIS_RIGHTY);
        v = SDL_GameControllerGetAxis(pads[port], ax); if (v == -32768) v = -32767;
      }
      if (port == 0 && index == RETRO_DEVICE_INDEX_ANALOG_LEFT && v == 0) {
        // Keyboard: I J K L move the N64 stick.
        const Uint8* k = SDL_GetKeyboardState(nullptr);
        if (id == RETRO_DEVICE_ID_ANALOG_X) v = (k[SDL_SCANCODE_L] ? 32767 : 0) - (k[SDL_SCANCODE_J] ? 32767 : 0);
        else v = (k[SDL_SCANCODE_K] ? 32767 : 0) - (k[SDL_SCANCODE_I] ? 32767 : 0);
      }
      return v;
    }
    case RETRO_DEVICE_POINTER: case RETRO_DEVICE_LIGHTGUN: {
      if (port != 0) return 0;
      if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return mouseDown ? 1 : 0;
      if (id == RETRO_DEVICE_ID_POINTER_X) return mouseX;
      if (id == RETRO_DEVICE_ID_POINTER_Y) return mouseY;
      return 0;
    }
    default: return 0;
  }
}

// ------------------------------------------------------------------ loading the engine
template <typename T> static bool sym(T& f, const char* name) { f = (T)GetProcAddress(C.dll, name); if (!f) logf("missing %s", name); return f != nullptr; }
static bool loadCore(const std::string& path) {
  fs::path p = P(path);
  SetDllDirectoryW(p.parent_path().wstring().c_str());
  C.dll = LoadLibraryW(p.wstring().c_str());
  if (!C.dll) { logf("could not load engine %s (%lu)", path.c_str(), GetLastError()); return false; }
  bool ok = sym(C.init, "retro_init") & sym(C.deinit, "retro_deinit") & sym(C.api_version, "retro_api_version")
    & sym(C.get_system_info, "retro_get_system_info") & sym(C.get_system_av_info, "retro_get_system_av_info")
    & sym(C.set_environment, "retro_set_environment") & sym(C.set_video_refresh, "retro_set_video_refresh")
    & sym(C.set_audio_sample, "retro_set_audio_sample") & sym(C.set_audio_sample_batch, "retro_set_audio_sample_batch")
    & sym(C.set_input_poll, "retro_set_input_poll") & sym(C.set_input_state, "retro_set_input_state")
    & sym(C.set_controller_port_device, "retro_set_controller_port_device") & sym(C.reset, "retro_reset") & sym(C.run, "retro_run")
    & sym(C.serialize_size, "retro_serialize_size") & sym(C.serialize, "retro_serialize") & sym(C.unserialize, "retro_unserialize")
    & sym(C.load_game, "retro_load_game") & sym(C.unload_game, "retro_unload_game")
    & sym(C.get_memory_data, "retro_get_memory_data") & sym(C.get_memory_size, "retro_get_memory_size");
  C.cheat_reset = (void (*)())GetProcAddress(C.dll, "retro_cheat_reset"); C.cheat_set = (void (*)(unsigned, bool, const char*))GetProcAddress(C.dll, "retro_cheat_set");
  return ok;
}

// ------------------------------------------------------------------ saves
static std::vector<uint8_t> lastSram;
static fs::path sramPath() { return saveRoot / "saves" / "game.srm"; }
static fs::path statePath(const std::string& name) { return saveRoot / "states" / (name + ".state"); }
static void loadSram() {
  void* mem = C.get_memory_data(RETRO_MEMORY_SAVE_RAM); size_t n = C.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (!mem || !n) return;
  std::vector<uint8_t> d; if (readFile(sramPath(), d) && !d.empty()) { memcpy(mem, d.data(), std::min(n, d.size())); }
  lastSram.assign((uint8_t*)mem, (uint8_t*)mem + n);
}
static void saveSram(bool force) {
  void* mem = C.get_memory_data(RETRO_MEMORY_SAVE_RAM); size_t n = C.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (!mem || !n) return;
  if (!force && lastSram.size() == n && memcmp(lastSram.data(), mem, n) == 0) return;
  if (writeFile(sramPath(), mem, n)) lastSram.assign((uint8_t*)mem, (uint8_t*)mem + n);
}
static bool snapshot(std::vector<uint8_t>& out) {
  size_t n = C.serialize_size(); if (!n) return false; out.resize(n); return C.serialize(out.data(), n);
}
static bool saveState(const std::string& name) { std::vector<uint8_t> s; return snapshot(s) && writeFile(statePath(name), s.data(), s.size()); }
static bool loadState(const std::string& name) { std::vector<uint8_t> s; return readFile(statePath(name), s) && !s.empty() && C.unserialize(s.data(), s.size()); }
static std::string slotInfo(int n) {
  std::error_code ec; auto p = statePath("slot" + std::to_string(n)); if (!fs::exists(p, ec)) return tr("Empty");
  auto ft = fs::last_write_time(p, ec); auto sys = std::chrono::time_point_cast<std::chrono::system_clock::duration>(ft - fs::file_time_type::clock::now() + std::chrono::system_clock::now());
  std::time_t tt = std::chrono::system_clock::to_time_t(sys); char buf[64]; std::strftime(buf, sizeof buf, "%Y-%m-%d %H:%M", std::localtime(&tt)); return buf;
}

// rewind: a state every half second, the last 30 seconds
static std::deque<std::vector<uint8_t>> rewindStates; static int rewindTick = 0;
static bool rewindable() { return consoleId == "NES" || consoleId == "SNES" || consoleId == "MD" || consoleId == "GB" || consoleId == "GBC" || consoleId == "GBA"; }
static void rewindBack(int steps) {
  if (rewindStates.empty()) { toast(tr("Nothing to rewind yet")); return; }
  std::vector<uint8_t> s;
  for (int i = 0; i < steps && !rewindStates.empty(); i++) { s = std::move(rewindStates.back()); rewindStates.pop_back(); }
  if (!s.empty()) C.unserialize(s.data(), s.size());
}

// ------------------------------------------------------------------ picture
static void ensureTexture(unsigned w, unsigned h) {
  if (!tex) glGenTextures(1, &tex);
  glBindTexture(GL_TEXTURE_2D, tex);
  if (w != texW || h != texH) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr); texW = w; texH = h; }
}
static void filterFor(GLuint t) { glBindTexture(GL_TEXTURE_2D, t); GLint f = smooth ? GL_LINEAR : GL_NEAREST; glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); }
/** The engine's drawing target. Created once and only resized afterwards: engines remember its id. */
static bool makeFbo(unsigned w, unsigned h) {
  w = std::max(w, fboW); h = std::max(h, fboH);
  if (!fbo) pGenFramebuffers(1, &fbo);
  if (fboTex) glDeleteTextures(1, &fboTex);
  glGenTextures(1, &fboTex); glBindTexture(GL_TEXTURE_2D, fboTex);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr); filterFor(fboTex);
  pBindFramebuffer(GL_FRAMEBUFFER, fbo);
  pFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTex, 0);
  if (hw.depth) {
    if (!fboDepth) pGenRenderbuffers(1, &fboDepth);
    pBindRenderbuffer(GL_RENDERBUFFER, fboDepth);
    pRenderbufferStorage(GL_RENDERBUFFER, hw.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, w, h);
    pFramebufferRenderbuffer(GL_FRAMEBUFFER, hw.stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepth);
  }
  bool ok = pCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
  pBindFramebuffer(GL_FRAMEBUFFER, 0); fboW = w; fboH = h;
  logf("framebuffer %u: %ux%u%s", fbo, w, h, ok ? "" : " (incomplete)");
  return ok;
}
static void noBlend(const ImDrawList*, const ImDrawCmd*) { glDisable(GL_BLEND); }

/** The last picture as RGBA rows, top row first. */
static bool grab(std::vector<uint32_t>& out, unsigned& w, unsigned& h) {
  w = fw; h = fh; if (!w || !h) return false;
  if (!hwFrame) { if (rgba.size() < (size_t)w * h) return false; out = rgba; return true; }
  out.resize((size_t)w * h);
  SDL_GLContext prev = SDL_GL_GetCurrentContext(); useCore();
  pBindFramebuffer(GL_FRAMEBUFFER, fbo); glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data()); pBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (coreCtx && prev) SDL_GL_MakeCurrent(window, prev);
  for (unsigned y = 0; y < h / 2; y++) std::swap_ranges(out.begin() + (size_t)y * w, out.begin() + (size_t)(y + 1) * w, out.begin() + (size_t)(h - 1 - y) * w);
  for (auto& p : out) p |= 0xFF000000u;
  return true;
}
static void pngWriter(void* ctx, void* data, int size) { auto* v = (std::vector<uint8_t>*)ctx; v->insert(v->end(), (uint8_t*)data, (uint8_t*)data + size); }
/** Phone remote: a small JPEG of the current picture (the "live screen" on the phone). */
static void jpgWriter(void* ctx, void* data, int size) { auto* v = (std::vector<uint8_t>*)ctx; v->insert(v->end(), (uint8_t*)data, (uint8_t*)data + size); }
static bool liveFrame(const std::string& file) {
  std::vector<uint32_t> px; unsigned w, h; if (!grab(px, w, h)) return false;
  unsigned ow = std::min(w, 480u), oh = std::max(1u, (unsigned)((uint64_t)h * ow / w));
  std::vector<uint8_t> rgb((size_t)ow * oh * 3);
  for (unsigned y = 0; y < oh; y++) for (unsigned x = 0; x < ow; x++) {
    uint32_t c = px[(size_t)(y * h / oh) * w + (x * w / ow)]; uint8_t* o = &rgb[((size_t)y * ow + x) * 3];
    o[0] = c & 0xFF; o[1] = (c >> 8) & 0xFF; o[2] = (c >> 16) & 0xFF;
  }
  std::vector<uint8_t> jpg; if (!stbi_write_jpg_to_func(jpgWriter, &jpg, ow, oh, 3, rgb.data(), 70)) return false;
  return writeFile(fs::u8path(file), jpg.data(), jpg.size());
}
static void screenshot() {
  std::vector<uint32_t> px; unsigned w, h; if (!grab(px, w, h)) return;
  time_t now = time(nullptr); char stamp[32]; strftime(stamp, sizeof stamp, "%Y%m%d-%H%M%S", localtime(&now));
  std::string safe; for (char c : title) safe += (strchr("<>:\"/\\|?*", c) ? '_' : c);
  std::vector<uint8_t> png; stbi_write_png_to_func(pngWriter, &png, w, h, 4, px.data(), w * 4);
  writeFile(picturesDir / P(safe + " " + stamp + ".png"), png.data(), png.size());
  // Also queued for the Telegram screenshots channel (sent by the app).
  std::vector<uint8_t> jpg; stbi_write_jpg_to_func(pngWriter, &jpg, w, h, 4, px.data(), 92);
  if (!shotsQueue.empty()) {
    writeFile(shotsQueue / (std::string(stamp) + ".jpg"), jpg.data(), jpg.size());
    std::string esc; for (char c : title) { if (c == '"' || c == '\\') esc += '\\'; esc += c; }
    std::string meta = "{\"id\":\"" + gameId + "\",\"title\":\"" + esc + "\",\"console\":\"" + consoleId + "\",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + ",\"time\":" + std::to_string((long long)now * 1000) + "}";
    writeFile(shotsQueue / (std::string(stamp) + ".json"), meta.data(), meta.size());
  }
  toast(tr("Screenshot saved (Pictures > RetroStream)"));
}

// ------------------------------------------------------------------ cheats (the libretro database list RetroStream downloaded)
struct Cheat { std::string name, code; bool on = false; };
static std::vector<Cheat> cheats;
static void loadCheats() {
  std::vector<uint8_t> d; if (arg("cheats").empty() || !readFile(P(arg("cheats")), d)) return;
  std::string s(d.begin(), d.end()); std::map<int, Cheat> byNum; size_t pos = 0;
  while (pos < s.size()) {
    size_t nl = s.find('\n', pos); std::string line = s.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos); pos = nl == std::string::npos ? s.size() : nl + 1;
    if (line.rfind("cheat", 0) != 0) continue;
    size_t us = line.find('_'), eq = line.find('='); if (us == std::string::npos || eq == std::string::npos) continue;
    int n = atoi(line.substr(5, us - 5).c_str()); std::string field = line.substr(us + 1, line.find_first_of(" =", us) - us - 1);
    std::string v = line.substr(eq + 1); while (!v.empty() && (v[0] == ' ' || v[0] == '"')) v.erase(0, 1); while (!v.empty() && (v.back() == ' ' || v.back() == '"' || v.back() == '\r')) v.pop_back();
    if (field == "desc") byNum[n].name = v; else if (field == "code") byNum[n].code = v;
  }
  std::vector<uint8_t> on; std::string onText; if (readFile(P(arg("cheatson")), on)) onText.assign(on.begin(), on.end());
  for (auto& [n, c] : byNum) if (!c.name.empty() && !c.code.empty()) { c.on = onText.find(c.code + "\n") != std::string::npos; cheats.push_back(c); }
}
static void applyCheats() {
  if (!C.cheat_reset || !C.cheat_set) return;
  C.cheat_reset(); unsigned i = 0;
  for (auto& c : cheats) if (c.on) C.cheat_set(i++, true, c.code.c_str());
  std::string text; for (auto& c : cheats) if (c.on) text += c.code + "\n";
  if (!arg("cheatson").empty()) writeFile(P(arg("cheatson")), text.data(), text.size());
}

// ------------------------------------------------------------------ the in-game menu
static std::string menuPage = "main";
static void openMenu() { menuOpen = true; menuPage = "main"; if (audioDev) { SDL_PauseAudioDevice(audioDev, 1); SDL_ClearQueuedAudio(audioDev); } tellState(); }
static void closeMenu() { menuOpen = false; if (audioDev) SDL_PauseAudioDevice(audioDev, 0); tellState(); }
static void walkthrough() {
  std::string q; for (unsigned char c : title + " walkthrough") { if (isalnum(c)) q += c; else { char b[4]; snprintf(b, 4, "%%%02X", c); q += b; } }
  SDL_OpenURL(("https://www.youtube.com/results?search_query=" + q).c_str());
}
static void drawMenu() {
  ImGuiIO& io = ImGui::GetIO();
  ImGui::GetBackgroundDrawList()->AddRectFilled(ImVec2(0, 0), io.DisplaySize, IM_COL32(5, 7, 12, 160));
  float w = std::min(620.f, io.DisplaySize.x * 0.9f);
  ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f), ImGuiCond_Always, ImVec2(0.5f, 0.5f));
  ImGui::SetNextWindowSize(ImVec2(w, 0));
  ImGui::Begin("menu", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoSavedSettings);
  ImGui::TextUnformatted(title.c_str());
  ImGui::TextDisabled("%s · %s", consoleId.c_str(), tr("paused"));
  ImGui::Separator();
  ImVec2 full(-FLT_MIN, 0);
  static bool focusFirst = true;
  auto item = [&](const std::string& label) { if (focusFirst) { ImGui::SetKeyboardFocusHere(); focusFirst = false; } return ImGui::Button(label.c_str(), full); };
  if (menuPage == "main") {
    if (item(tr("Resume"))) closeMenu();
    if (ImGui::Button(tr("Save game state"), full)) { menuPage = "save"; focusFirst = true; }
    if (ImGui::Button(tr("Load game state"), full)) { menuPage = "load"; focusFirst = true; }
    if (rewindable() && ImGui::Button(tr("Rewind 10 seconds"), full)) { rewindBack(20); toast(tr("Rewound")); closeMenu(); }
    if (ImGui::Button((std::string(tr("Fast forward")) + ": " + (ffToggle ? tr("on") : tr("off"))).c_str(), full)) { ffToggle = !ffToggle; closeMenu(); }
    if (ImGui::Button(tr("Take screenshot"), full)) { closeMenu(); screenshot(); }
    if (!cheats.empty()) { int n = 0; for (auto& c : cheats) n += c.on; if (ImGui::Button((std::string(tr("Cheats")) + (n ? " (" + std::to_string(n) + " " + tr("on") + ")" : "")).c_str(), full)) { menuPage = "cheats"; focusFirst = true; } }
    if (ImGui::Button(tr("Walkthrough (YouTube)"), full)) walkthrough();
    if (ImGui::Button((std::string(tr("Screen filter")) + ": " + (smooth ? tr("Smooth") : tr("Sharp pixels"))).c_str(), full)) { smooth = !smooth; filterFor(useHw ? fboTex : tex); tellState(); }
    if (ImGui::Button((std::string(tr("Full screen")) + ": " + (fullscreen ? tr("on") : tr("off")) + "   (F11)").c_str(), full)) { fullscreen = !fullscreen; SDL_SetWindowFullscreen(window, fullscreen ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0); }
    if (ImGui::Button(tr("Restart game"), full)) { C.reset(); toast(tr("Restarted")); closeMenu(); }
    if (ImGui::Button(tr("Exit game"), full)) running = false;
  } else if (menuPage == "cheats") {
    ImGui::TextWrapped("%s", tr("Tick the cheats you want. Codes marked (M) or Master must be on for the others to work. Save first: cheats can break a game."));
    ImGui::BeginChild("list", ImVec2(0, ImGui::GetIO().DisplaySize.y * 0.45f), ImGuiChildFlags_Borders);
    for (size_t i = 0; i < cheats.size(); i++) {
      ImGui::PushID((int)i);
      if (focusFirst) { ImGui::SetKeyboardFocusHere(); focusFirst = false; }
      if (ImGui::Checkbox(cheats[i].name.c_str(), &cheats[i].on)) applyCheats();
      ImGui::PopID();
    }
    ImGui::EndChild();
    if (ImGui::Button(tr("Back"), full)) { menuPage = "main"; focusFirst = true; }
  } else {
    bool save = menuPage == "save";
    for (int n = 1; n <= 3; n++) {
      std::string label = std::string(tr("Slot")) + " " + std::to_string(n) + "   ·   " + slotInfo(n);
      if (item(label)) {
        std::string name = "slot" + std::to_string(n);
        if (save) toast(saveState(name) ? tr("Saved") : "Error");
        else { std::error_code ec; if (!fs::exists(statePath(name), ec)) toast(tr("Nothing saved in that slot")); else toast(loadState(name) ? tr("Loaded") : tr("That save couldn't be loaded")); }
        closeMenu();
      }
    }
    if (ImGui::Button(tr("Back"), full)) { menuPage = "main"; focusFirst = true; }
  }
  ImGui::Separator();
  ImGui::TextWrapped("%s", tr("Esc or the Guide button opens this menu"));
  ImGui::TextWrapped("%s", tr("Controls: arrows, Z X A S, Q W, Enter, Right Shift · Tab fast forward · Backspace rewind · F5 save · F7 load · F12 screenshot"));
  ImGui::End();
  if (!menuOpen) focusFirst = true;
}

// ------------------------------------------------------------------ options file: key=value lines
static void readOptions(const std::string& file) {
  std::vector<uint8_t> d; if (file.empty() || !readFile(P(file), d)) return;
  std::string s(d.begin(), d.end()); size_t pos = 0;
  while (pos < s.size()) {
    size_t nl = s.find('\n', pos); std::string line = s.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos); pos = nl == std::string::npos ? s.size() : nl + 1;
    if (!line.empty() && line.back() == '\r') line.pop_back();
    size_t eq = line.find('='); if (eq != std::string::npos) wanted[line.substr(0, eq)] = line.substr(eq + 1);
  }
}

// ------------------------------------------------------------------ main
int main(int argc, char* argv[]) {
  for (int i = 1; i + 1 < argc; i += 2) if (strncmp(argv[i], "--", 2) == 0) A[argv[i] + 2] = argv[i + 1];
  ES = arg("lang") == "es"; title = arg("title", "RetroStream"); consoleId = arg("console"); gameId = arg("gameid");
  saveRoot = P(arg("saveroot")); shotsQueue = P(arg("shots")); picturesDir = P(arg("pictures"));
  systemDir = arg("system"); saveDir = (saveRoot / "saves").u8string();
  smooth = arg("filter") == "smooth"; fullscreen = arg("fullscreen", "1") == "1";
  std::error_code ec; fs::create_directories(saveRoot / "saves", ec); fs::create_directories(saveRoot / "states", ec); fs::create_directories(P(systemDir), ec);
  readOptions(arg("options"));

  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMECONTROLLER | SDL_INIT_TIMER) != 0) { logf("SDL: %s", SDL_GetError()); return 2; }
  SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");

  if (!loadCore(arg("core"))) { printf("{\"event\":\"error\",\"reason\":\"engine\"}\n"); fflush(stdout); return 3; }
  C.set_environment(env); C.set_video_refresh(videoRefresh); C.set_audio_sample(audioSample); C.set_audio_sample_batch(audioBatch);
  C.set_input_poll(inputPoll); C.set_input_state(inputState);
  C.init();

  retro_system_info info = {}; C.get_system_info(&info);
  std::string romPath = arg("rom"); std::vector<uint8_t> romData;
  retro_game_info game = {}; game.path = romPath.c_str();
  if (!info.need_fullpath) { if (!readFile(P(romPath), romData)) { logf("could not read the game file"); printf("{\"event\":\"error\",\"reason\":\"file\"}\n"); fflush(stdout); return 4; } game.data = romData.data(); game.size = romData.size(); }
  logf("engine loaded: %s %s, need_fullpath=%d", info.library_name ? info.library_name : "?", info.library_version ? info.library_version : "", (int)info.need_fullpath);
  if (!C.load_game(&game)) { logf("the engine could not open the game"); printf("{\"event\":\"error\",\"reason\":\"game\"}\n"); fflush(stdout); C.deinit(); return 5; }
  C.get_system_av_info(&av);
  for (unsigned p = 0; p < 4; p++) C.set_controller_port_device(p, RETRO_DEVICE_JOYPAD);
  if (consoleId == "N64") C.set_controller_port_device(0, RETRO_DEVICE_ANALOG);

  // Window and OpenGL context (core profile for engines that draw with the graphics card).
  // OpenGL: the newest version the graphics card offers (a "3.3" request on AMD gives exactly 3.3, which is too old for the
  // PS2 and GameCube/Wii engines' shaders). Engines asking for plain OpenGL get a compatibility context.
  bool compat = useHw && hw.context_type == RETRO_HW_CONTEXT_OPENGL;
  SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1); SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24); SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, compat ? SDL_GL_CONTEXT_PROFILE_COMPATIBILITY : SDL_GL_CONTEXT_PROFILE_CORE);
  window = SDL_CreateWindow(title.c_str(), SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, 1280, 960,
    SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI | (fullscreen ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0));
  SDL_GLContext gl = nullptr;
  static const int versions[][2] = { {4, 6}, {4, 5}, {4, 3}, {4, 1}, {4, 0}, {3, 3} };
  for (auto& v : versions) {
    if (!window) break;
    if (useHw && (unsigned)v[0] * 10 + v[1] < hw.version_major * 10 + hw.version_minor) break;
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, v[0]); SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, v[1]);
    gl = SDL_GL_CreateContext(window);
    if (gl) { logf("graphics: hw=%d type=%d asked %u.%u, got OpenGL %d.%d%s", (int)useHw, useHw ? (int)hw.context_type : -1, hw.version_major, hw.version_minor, v[0], v[1], compat ? " compatibility" : " core"); break; }
  }
  uiCtx = gl;
  if (!gl) { logf("OpenGL: %s", SDL_GetError()); printf("{\"event\":\"error\",\"reason\":\"graphics\"}\n"); fflush(stdout); return 6; }
  SDL_GL_MakeCurrent(window, gl); SDL_GL_SetSwapInterval(0);
  pGenFramebuffers = (PFNGLGENFRAMEBUFFERSPROC)SDL_GL_GetProcAddress("glGenFramebuffers"); pBindFramebuffer = (PFNGLBINDFRAMEBUFFERPROC)SDL_GL_GetProcAddress("glBindFramebuffer");
  pFramebufferTexture2D = (PFNGLFRAMEBUFFERTEXTURE2DPROC)SDL_GL_GetProcAddress("glFramebufferTexture2D"); pGenRenderbuffers = (PFNGLGENRENDERBUFFERSPROC)SDL_GL_GetProcAddress("glGenRenderbuffers");
  pBindRenderbuffer = (PFNGLBINDRENDERBUFFERPROC)SDL_GL_GetProcAddress("glBindRenderbuffer"); pRenderbufferStorage = (PFNGLRENDERBUFFERSTORAGEPROC)SDL_GL_GetProcAddress("glRenderbufferStorage");
  pFramebufferRenderbuffer = (PFNGLFRAMEBUFFERRENDERBUFFERPROC)SDL_GL_GetProcAddress("glFramebufferRenderbuffer"); pCheckFramebufferStatus = (PFNGLCHECKFRAMEBUFFERSTATUSPROC)SDL_GL_GetProcAddress("glCheckFramebufferStatus");
  pDeleteFramebuffers = (PFNGLDELETEFRAMEBUFFERSPROC)SDL_GL_GetProcAddress("glDeleteFramebuffers"); pDeleteRenderbuffers = (PFNGLDELETERENDERBUFFERSPROC)SDL_GL_GetProcAddress("glDeleteRenderbuffers");

  // In-game menu look.
  IMGUI_CHECKVERSION(); ImGui::CreateContext();
  ImGuiIO& io = ImGui::GetIO(); io.IniFilename = nullptr; io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard | ImGuiConfigFlags_NavEnableGamepad;
  int dh = 1080; { SDL_DisplayMode dm; if (SDL_GetDesktopDisplayMode(0, &dm) == 0) dh = dm.h; }
  float px = std::max(18.f, dh / 1080.f * 26.f);
  ImFontConfig cfg; static const ImWchar ranges[] = { 0x0020, 0x00FF, 0x2010, 0x2030, 0 };
  if (!io.Fonts->AddFontFromFileTTF("C:\\Windows\\Fonts\\segoeui.ttf", px, &cfg, ranges)) io.Fonts->AddFontDefault();
  ImGuiStyle& st = ImGui::GetStyle(); ImGui::StyleColorsDark();
  st.WindowRounding = 18; st.FrameRounding = 10; st.WindowPadding = ImVec2(22, 20); st.FramePadding = ImVec2(14, 10); st.ItemSpacing = ImVec2(8, 6);
  st.Colors[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.086f, 0.13f, 0.97f); st.Colors[ImGuiCol_Button] = ImVec4(0.10f, 0.12f, 0.18f, 1);
  st.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.184f, 0.427f, 0.965f, 1); st.Colors[ImGuiCol_ButtonActive] = ImVec4(0.15f, 0.36f, 0.85f, 1);
  st.Colors[ImGuiCol_NavHighlight] = ImVec4(1, 1, 1, 1); st.Colors[ImGuiCol_Border] = ImVec4(0.16f, 0.19f, 0.27f, 1);
  ImGui_ImplSDL2_InitForOpenGL(window, gl);
  ImGui_ImplOpenGL3_Init(compat ? "#version 130" : "#version 330");

  logf("game loaded: %ux%u max %ux%u, %.2f fps, %.0f Hz", av.geometry.base_width, av.geometry.base_height, av.geometry.max_width, av.geometry.max_height, av.timing.fps, av.timing.sample_rate);
  if (useHw) {
    SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
    coreCtx = SDL_GL_CreateContext(window);
    if (!coreCtx) { logf("engine context: %s", SDL_GetError()); printf("{\"event\":\"error\",\"reason\":\"graphics\"}\n"); fflush(stdout); return 6; }
    SDL_GL_MakeCurrent(window, coreCtx);
    if (!makeFbo(std::max(av.geometry.max_width, 1u), std::max(av.geometry.max_height, 1u))) logf("framebuffer not complete");
    logf("engine context: %s · %s", (const char*)glGetString(GL_VERSION), (const char*)glGetString(GL_RENDERER));
    // Some engines (Dolphin) take whatever framebuffer is bound when they start as "the screen": make it ours.
    pBindFramebuffer(GL_FRAMEBUFFER, fbo);
    if (hw.context_reset) hw.context_reset();
    logf("engine context ready (error %u)", (unsigned)glGetError());
  }

  // Sound at the engine's own rate; the game's speed follows the sound.
  audioRate = (int)(av.timing.sample_rate + 0.5); if (audioRate <= 0) audioRate = 44100;
  SDL_AudioSpec want = {}, have = {}; want.freq = audioRate; want.format = AUDIO_S16SYS; want.channels = 2; want.samples = 1024;
  audioDev = SDL_OpenAudioDevice(nullptr, 0, &want, &have, 0);
  if (audioDev) SDL_PauseAudioDevice(audioDev, 0);
  double fps = av.timing.fps > 1 ? av.timing.fps : 60.0;

  loadSram(); loadCheats();
  bool firstFrame = true;
  printf("{\"event\":\"started\"}\n"); fflush(stdout);
  std::thread(readCommands).detach();
  Uint64 freq = SDL_GetPerformanceFrequency(), next = SDL_GetPerformanceCounter(), playStart = SDL_GetTicks64(), playedMs = 0, lastTick = SDL_GetTicks64();
  int frameNo = 0; bool guideWas = false, comboMenuWas = false, comboShotWas = false, comboFfWas = false;

  while (running) {
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
      ImGui_ImplSDL2_ProcessEvent(&e);
      if (e.type == SDL_QUIT) running = false;
      else if (e.type == SDL_CONTROLLERDEVICEADDED) {
        // The same controller can be announced twice: open it once, or games see two players pressing the same buttons.
        SDL_JoystickID id = SDL_JoystickGetDeviceInstanceID(e.cdevice.which); bool known = false;
        for (auto* p : pads) if (p && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(p)) == id) known = true;
        if (!known) { SDL_GameController* g = SDL_GameControllerOpen(e.cdevice.which); if (g) { bool placed = false; for (auto& p : pads) if (!p) { p = g; placed = true; break; } if (!placed) pads.push_back(g); logf("controller %d: %s", (int)pads.size(), SDL_GameControllerName(g)); } }
      }
      else if (e.type == SDL_CONTROLLERDEVICEREMOVED) { for (auto& p : pads) if (p && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(p)) == e.cdevice.which) { SDL_GameControllerClose(p); p = nullptr; } }
      else if (e.type == SDL_KEYDOWN && !e.key.repeat) {
        switch (e.key.keysym.sym) {
          case SDLK_ESCAPE: case SDLK_F1: if (menuOpen) { if (menuPage != "main") menuPage = "main"; else closeMenu(); } else openMenu(); break;
          case SDLK_F11: fullscreen = !fullscreen; SDL_SetWindowFullscreen(window, fullscreen ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0); break;
          case SDLK_F12: if (!menuOpen) screenshot(); break;
          case SDLK_F5: if (!menuOpen) toast(saveState("slot1") ? std::string(tr("Saved")) + " · " + tr("Slot") + " 1" : "Error"); break;
          case SDLK_F7: if (!menuOpen) toast(loadState("slot1") ? std::string(tr("Loaded")) + " · " + tr("Slot") + " 1" : tr("Nothing saved in that slot")); break;
          default: break;
        }
      } else if (e.type == SDL_CONTROLLERBUTTONDOWN && menuOpen && e.cbutton.button == SDL_CONTROLLER_BUTTON_B) { if (menuPage != "main") menuPage = "main"; else closeMenu(); }
      else if (e.type == SDL_MOUSEMOTION || e.type == SDL_MOUSEBUTTONDOWN || e.type == SDL_MOUSEBUTTONUP) {
        int mx, my; Uint32 b = SDL_GetMouseState(&mx, &my); mouseDown = (b & SDL_BUTTON_LMASK) && !menuOpen;
        float nx = (mx - imgX) / imgW, ny = (my - imgY) / imgH;
        mouseX = (int16_t)std::max(-32767.f, std::min(32767.f, nx * 65534.f - 32767.f)); mouseY = (int16_t)std::max(-32767.f, std::min(32767.f, ny * 65534.f - 32767.f));
      }
    }
    // Phone remote commands (through the app).
    for (;;) {
      std::string c; { std::lock_guard<std::mutex> g(cmdLock); if (cmds.empty()) break; c = cmds.front(); cmds.pop_front(); }
      while (!c.empty() && (c.back() == '\r' || c.back() == ' ')) c.pop_back();
      std::string word = c.substr(0, c.find(' ')); int slot = 1; if (c.size() > word.size() + 1) slot = std::max(1, std::min(3, atoi(c.c_str() + word.size() + 1)));
      if (word == "pause") { if (!menuOpen) openMenu(); }
      else if (word == "resume") { if (menuOpen) closeMenu(); }
      else if (word == "exit") running = false;
      else if (word == "save") { bool ok = saveState("slot" + std::to_string(slot)); toast(ok ? std::string(tr("Saved")) + " · " + tr("Slot") + " " + std::to_string(slot) : "Error"); printf("{\"event\":\"done\",\"what\":\"save\",\"ok\":%s}\n", ok ? "true" : "false"); fflush(stdout); }
      else if (word == "load") { std::error_code ec; bool ok = fs::exists(statePath("slot" + std::to_string(slot)), ec) && loadState("slot" + std::to_string(slot)); toast(ok ? std::string(tr("Loaded")) + " · " + tr("Slot") + " " + std::to_string(slot) : tr("Nothing saved in that slot")); printf("{\"event\":\"done\",\"what\":\"load\",\"ok\":%s}\n", ok ? "true" : "false"); fflush(stdout); }
      else if (word == "shot") screenshot();
      else if (word == "ff") { ffToggle = !ffToggle; toast(ffToggle ? std::string(tr("Fast forward")) + " 3x" : tr("Normal speed")); tellState(); }
      else if (word == "reset") { C.reset(); toast(tr("Restarted")); }
      else if (word == "frame") { bool ok = c.size() > 6 && liveFrame(c.substr(6)); printf("{\"event\":\"done\",\"what\":\"frame\",\"ok\":%s}\n", ok ? "true" : "false"); fflush(stdout); }
      else if (word == "filter") { smooth = c.find("smooth") != std::string::npos; filterFor(useHw ? fboTex : tex); tellState(); }
      else if (word == "flush") { saveSram(true); printf("{\"event\":\"done\",\"what\":\"flush\",\"ok\":true}\n"); fflush(stdout); }
    }
    // Controller shortcuts: Guide or Select+Start opens the menu; Select+R1 screenshot; Select+R2 fast forward; hold Select+L1 to rewind.
    bool guide = false, comboMenu = false, comboShot = false, comboFf = false, comboRewind = false;
    for (auto* g : pads) if (g) {
      bool sel = SDL_GameControllerGetButton(g, SDL_CONTROLLER_BUTTON_BACK);
      guide |= SDL_GameControllerGetButton(g, SDL_CONTROLLER_BUTTON_GUIDE) != 0;
      comboMenu |= sel && SDL_GameControllerGetButton(g, SDL_CONTROLLER_BUTTON_START);
      comboShot |= sel && SDL_GameControllerGetButton(g, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
      comboFf |= sel && SDL_GameControllerGetAxis(g, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 16000;
      comboRewind |= sel && SDL_GameControllerGetButton(g, SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
    }
    if ((guide && !guideWas) || (comboMenu && !comboMenuWas)) { if (menuOpen) closeMenu(); else openMenu(); }
    if (comboShot && !comboShotWas && !menuOpen) screenshot();
    if (comboFf && !comboFfWas && !menuOpen) { ffToggle = !ffToggle; toast(ffToggle ? std::string(tr("Fast forward")) + " 3x" : tr("Normal speed")); tellState(); }
    guideWas = guide; comboMenuWas = comboMenu; comboShotWas = comboShot; comboFfWas = comboFf;
    const Uint8* k = SDL_GetKeyboardState(nullptr);
    fastForward = !menuOpen && (ffToggle || k[SDL_SCANCODE_TAB]);
    bool wantRewind = !menuOpen && rewindable() && (k[SDL_SCANCODE_BACKSPACE] || comboRewind);

    // Run the game.
    Uint64 now64 = SDL_GetTicks64(); if (!menuOpen) playedMs += now64 - lastTick; lastTick = now64;
    int runs = menuOpen ? 0 : fastForward ? 3 : 1;
    for (int r = 0; r < runs; r++) {
      if (wantRewind) { if (frameNo % 6 == 0) rewindBack(1); }
      if (varsChanged) { /* engine reads them in retro_run */ }
      if (useHw) pBindFramebuffer(GL_FRAMEBUFFER, fbo);
      C.run(); frameNo++;
      if (useHw && (frameNo <= 3 || frameNo % 600 == 0)) {
        GLenum err = glGetError(); unsigned char px[4] = {0, 0, 0, 0};
        pBindFramebuffer(GL_FRAMEBUFFER, fbo); glReadPixels(fw / 2, fh / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
        logf("frame %d: %ux%u, %d pictures (%d repeats), GL error %u, centre pixel %u,%u,%u", frameNo, fw, fh, videoCalls, dupCalls, (unsigned)err, px[0], px[1], px[2]);
      }
      if (useHw) pBindFramebuffer(GL_FRAMEBUFFER, 0);
      if (firstFrame) {
        firstFrame = false;
        if (arg("fresh") != "1") { std::error_code ec2; if (fs::exists(statePath("auto"), ec2) && loadState("auto")) toast(tr("Continuing where you left off")); }
        if (std::any_of(cheats.begin(), cheats.end(), [](const Cheat& c) { return c.on; })) applyCheats();
      }
      if (rewindable() && !wantRewind && ++rewindTick >= 30) { rewindTick = 0; std::vector<uint8_t> s; if (snapshot(s)) { rewindStates.push_back(std::move(s)); if (rewindStates.size() > 60) rewindStates.pop_front(); } }
      if (frameNo % 600 == 0) saveSram(false);
      // Sound: drop it while fast-forwarding, otherwise queue it.
      if (audioDev && !audioBuf.empty() && !fastForward) SDL_QueueAudio(audioDev, audioBuf.data(), (Uint32)(audioBuf.size() * sizeof(int16_t)));
      audioBuf.clear();
      if (avChanged) {
        avChanged = false;
        logf("new size: %ux%u max %ux%u, %.2f fps", av.geometry.base_width, av.geometry.base_height, av.geometry.max_width, av.geometry.max_height, av.timing.fps);
        if (useHw && (av.geometry.max_width > fboW || av.geometry.max_height > fboH)) { makeFbo(av.geometry.max_width, av.geometry.max_height); }
        fps = av.timing.fps > 1 ? av.timing.fps : fps;
      }
    }

    // Draw: the picture fitted to the window, then the menu and messages (in our own context).
    if (coreCtx) { glFinish(); useUi(); }
    int ww, wh; SDL_GL_GetDrawableSize(window, &ww, &wh);
    ImGui_ImplOpenGL3_NewFrame(); ImGui_ImplSDL2_NewFrame(); ImGui::NewFrame();
    if (fresh && !hwFrame && fw && fh) { ensureTexture(fw, fh); filterFor(tex); glPixelStorei(GL_UNPACK_ALIGNMENT, 4); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fw, fh, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data()); }
    fresh = false;
    if (fw && fh) {
      float aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio : (float)fw / fh;
      if (rotation & 1) aspect = 1.f / aspect;
      ImVec2 ds = ImGui::GetIO().DisplaySize;
      float w = ds.x, h = w / aspect; if (h > ds.y) { h = ds.y; w = h * aspect; }
      imgX = (ds.x - w) / 2; imgY = (ds.y - h) / 2; imgW = w; imgH = h;
      ImDrawList* dl = ImGui::GetBackgroundDrawList();
      dl->AddCallback(noBlend, nullptr);
      if (hwFrame && fboTex) {
        // Most engines draw upside down (OpenGL's origin is the bottom-left corner); the ones that don't say so.
        float u = (float)fw / fboW, v = (float)fh / fboH;
        if (hw.bottom_left_origin) dl->AddImage((ImTextureID)(intptr_t)fboTex, ImVec2(imgX, imgY), ImVec2(imgX + w, imgY + h), ImVec2(0, v), ImVec2(u, 0));
        else dl->AddImage((ImTextureID)(intptr_t)fboTex, ImVec2(imgX, imgY), ImVec2(imgX + w, imgY + h), ImVec2(0, 0), ImVec2(u, v));
      }
      else if (tex) dl->AddImage((ImTextureID)(intptr_t)tex, ImVec2(imgX, imgY), ImVec2(imgX + w, imgY + h), ImVec2(0, 0), ImVec2((float)fw / texW, (float)fh / texH));
      dl->AddCallback(ImDrawCallback_ResetRenderState, nullptr);
    }
    if (menuOpen) drawMenu();
    if (!toastText.empty() && SDL_GetTicks() < toastUntil) {
      ImGui::SetNextWindowPos(ImVec2(24, ImGui::GetIO().DisplaySize.y - 24), ImGuiCond_Always, ImVec2(0, 1));
      ImGui::SetNextWindowBgAlpha(0.85f);
      ImGui::Begin("toast", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoSavedSettings);
      ImGui::TextUnformatted(toastText.c_str()); ImGui::End();
    }
    if (fastForward) { ImGui::GetForegroundDrawList()->AddText(ImVec2(24, 20), IM_COL32(255, 255, 255, 220), ">> 3x"); }
    ImGui::Render();
    glViewport(0, 0, ww, wh); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    SDL_GL_SwapWindow(window);
    useCore();

    // Keep the game's own speed: follow the sound (or the clock when there is none).
    if (menuOpen) { SDL_Delay(16); next = SDL_GetPerformanceCounter(); continue; }
    if (fastForward) { next = SDL_GetPerformanceCounter(); continue; }
    Uint32 frameBytes = (Uint32)(audioRate / fps * 4);
    if (audioDev && SDL_GetQueuedAudioSize(audioDev) > 0) {
      while (SDL_GetQueuedAudioSize(audioDev) > frameBytes * 3 && running) SDL_Delay(1);
      next = SDL_GetPerformanceCounter();
    } else {
      next += (Uint64)(freq / fps); Uint64 cur = SDL_GetPerformanceCounter();
      if (next > cur) SDL_Delay((Uint32)((next - cur) * 1000 / freq)); else next = cur;
    }
  }

  // Leaving: keep the in-game save and "where you left off", then report the time played.
  saveSram(true);
  saveState("auto");
  if (audioDev) SDL_CloseAudioDevice(audioDev);
  useCore();
  if (useHw && hw.context_destroy) hw.context_destroy();
  C.unload_game(); C.deinit();
  printf("{\"event\":\"exit\",\"played\":%llu}\n", (unsigned long long)playedMs); fflush(stdout);
  if (coreCtx) { SDL_GL_DeleteContext(coreCtx); coreCtx = nullptr; SDL_GL_MakeCurrent(window, uiCtx); }
  ImGui_ImplOpenGL3_Shutdown(); ImGui_ImplSDL2_Shutdown(); ImGui::DestroyContext();
  SDL_GL_DeleteContext(gl); SDL_DestroyWindow(window); SDL_Quit();
  (void)playStart;
  return 0;
}
