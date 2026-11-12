package com.tirso.tirsolador

import android.content.Context

/**
 * Engine settings for each console: the same cores, options and controller layouts Tirso Compiler uses for
 * its TV games (N64: Mupen64Plus-Next, DS: melonDS DS / DeSmuME on 32-bit TVs, GBA: mGBA), plus the
 * other libretro cores for the consoles Tirso Compiler doesn't build games for.
 */
object Presets {
    const val OPTIONS = "player_options"

    @JvmStatic
    fun resolution(context: Context): String =
        context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).getString("resolution", "medium") ?: "medium"

    @JvmStatic
    fun setResolution(context: Context, value: String) {
        context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).edit().putString("resolution", value).apply()
    }

    /** Resolution chosen for one console (falls back to the general choice). */
    @JvmStatic
    fun resolution(context: Context, console: Console): String =
        context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).getString("resolution_" + console.name, null) ?: resolution(context)

    @JvmStatic
    fun setResolution(context: Context, console: Console, value: String) {
        context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).edit().putString("resolution_" + console.name, value).apply()
    }

    /** Resolution chosen for one game only (null = same as its console). */
    @JvmStatic
    fun gameResolution(context: Context, gameId: String): String? =
        context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).getString("resolution_g_$gameId", null)

    @JvmStatic
    fun setGameResolution(context: Context, gameId: String, value: String?) {
        val e = context.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE).edit()
        if (value == null) e.remove("resolution_g_$gameId") else e.putString("resolution_g_$gameId", value)
        e.apply()
    }

    fun key(console: Console): String = when (console) {
        Console.GB, Console.GBC -> "gb"
        else -> console.name.lowercase()
    }

    // PPSSPP (Tirso Compiler's PspAdapter): low / medium / high = 1x / 2x / 3x.
    private val PSP_SIZE = mapOf("low" to "480x272", "medium" to "960x544", "high" to "1440x816")
    private val PSP = mapOf("ppsspp_backend" to "opengl", "ppsspp_cpu_core" to "JIT", "ppsspp_frame_duplication" to "enabled",
        "ppsspp_auto_frameskip" to "disabled", "ppsspp_cheats" to "enabled")

    // Dolphin (Tirso Compiler's DolphinAdapter), widescreen hack on.
    private fun dolphin(wii: Boolean, res: String): Map<String, String> {
        val v = mutableMapOf(
            "dolphin_renderer" to "Hardware", "dolphin_cpu_core" to "4", "dolphin_main_cpu_thread" to "enabled",
            "dolphin_dsp_hle" to "enabled", "dolphin_fast_disc_speed" to "enabled", "dolphin_skip_gc_bios" to "enabled",
            "dolphin_shader_compilation_mode" to "0", "dolphin_aspect_ratio" to "3", "dolphin_enable_rumble" to "enabled",
            "dolphin_osd_enabled" to "disabled", "dolphin_ir_mode" to "1", "dolphin_cheats_enabled" to "enabled",
            "dolphin_cheats_import" to "enabled", "dolphin_widescreen_hack" to "enabled",
            "dolphin_efb_scale" to when (res) { "low" -> "1"; "high" -> "3"; else -> "2" },
        )
        if (wii) { v["dolphin_widescreen"] = "enabled"; v["dolphin_wiimote_default_device"] = "sideways"; v["dolphin_hotkey_sideways_toggle"] = "Disabled" }
        return v
    }

    // Real GLideN64 sizes (Mupen64Plus-Next options): low / medium / high.
    private val N64_SIZE = mapOf("low" to "320x240", "medium" to "640x480", "high" to "960x720")

    // Mupen64Plus-Next: Tirso Compiler's common options plus the "TV light" set (weak TV graphics chips).
    private fun n64(resolution: String) = mapOf(
        "mupen64plus-rdp-plugin" to "gliden64", "mupen64plus-cpucore" to "dynamic_recompiler",
        "mupen64plus-aspect" to "4:3", "mupen64plus-pak1" to "memory",
        "mupen64plus-EnableCopyDepthToRDRAM" to "Off", "mupen64plus-EnableCopyColorToRDRAM" to "Off",
        "mupen64plus-ThreadedRenderer" to "False", "mupen64plus-FrameDuping" to "True",
        "mupen64plus-MultiSampling" to "0", "mupen64plus-EnableShadersStorage" to "True",
        "mupen64plus-43screensize" to (N64_SIZE[resolution] ?: "640x480"),
    )

    // melonDS DS on 64-bit: Tirso Compiler's TV layout (big top screen left; small top + touch screen right).
    private val NDS = mapOf(
        "melonds_console_mode" to "ds", "melonds_sysfile_mode" to "builtin", "melonds_boot_mode" to "direct",
        "melonds_touch_mode" to "auto", "melonds_mic_input" to "microphone", "melonds_mic_input_active" to "always",
        "melonds_show_cursor" to "timeout", "melonds_render_mode" to "software", "melonds_threaded_renderer" to "enabled",
        "melonds_jit_enable" to "enabled", "melonds_number_of_screen_layouts" to "2", "melonds_hybrid_ratio" to "2",
        "melonds_hybrid_small_screen" to "both", "melonds_screen_gap" to "0",
        "melonds_screen_layout1" to "hybrid-top", "melonds_screen_layout2" to "hybrid-bottom",
    )

    // DeSmuME (32-bit TVs): the same layout and controls.
    private fun desmume(high: Boolean) = GameConfig.AltEngine("libdesmume_libretro_android.so", "DeSmuME", mapOf(
        "desmume_cpu_mode" to "jit", "desmume_num_cores" to "2", "desmume_frameskip" to "0",
        "desmume_internal_resolution" to (if (high) "512x384" else "256x192"), "desmume_screens_layout" to "hybrid/top",
        "desmume_hybrid_layout_ratio" to "2:1", "desmume_hybrid_showboth_screens" to "enabled",
        "desmume_pointer_type" to "touch", "desmume_pointer_mouse" to "enabled",
        "desmume_pointer_device_r" to "emulated", "desmume_pointer_colour" to "white",
    ))

    private val MGBA = mapOf(
        "mgba_use_bios" to "OFF", "mgba_skip_bios" to "ON", "mgba_idle_optimization" to "Remove Known",
        "mgba_interframe_blending" to "mix_smart", "mgba_color_correction" to "OFF", "mgba_frameskip" to "disabled",
    )

    fun forConsole(context: Context, console: Console, title: String, gameId: String? = null): GameConfig {
        // 2D cores (GB, GBC, GBA, NES, SNES, Mega Drive) draw at a fixed size, so "resolution" there is how the picture
        // is scaled up: low = plain, medium = crisp pixels (the old default), high = smooth upscale.
        val res = gameId?.let { gameResolution(context, it) } ?: resolution(context, console)
        val flat = when (res) { "low" -> "default"; "high" -> "smooth"; else -> "sharp" }
        val base = GameConfig(title = title, core = "", pad = "snes", consoleName = console.title, shader = flat)
        return when (console) {
            Console.N64 -> base.copy(core = "libmupen64plus_next_gles3_libretro_android.so", pad = "n64", shader = "default",
                variables = n64(res), screenMode = "overlay", aspect = 4f / 3f)
            Console.NDS -> base.copy(core = "libmelondsds_libretro_android.so", pad = "nds", variables = NDS,
                screenMode = "beside", aspect = 2f, altEngines = listOf(desmume(res == "high")))
            Console.GBA -> base.copy(core = "libmgba_libretro_android.so", pad = "gba", variables = MGBA, aspect = 3f / 2f)
            Console.GBC, Console.GB -> base.copy(core = "libmgba_libretro_android.so", pad = "gba", variables = MGBA, aspect = 10f / 9f)
            Console.SNES -> base.copy(core = "libsnes9x_libretro_android.so", pad = "snes", aspect = 4f / 3f)
            Console.NES -> base.copy(core = "libfceumm_libretro_android.so", pad = "gba", aspect = 4f / 3f)
            Console.MD -> base.copy(core = "libgenesis_plus_gx_libretro_android.so", pad = "snes", aspect = 4f / 3f)
            Console.MAME -> base.copy(core = "libmame2003_plus_libretro_android.so", pad = "snes", shader = "default",
                variables = mapOf("mame2003-plus_skip_disclaimer" to "enabled", "mame2003-plus_skip_warnings" to "enabled"))
            // Phone app: Tirso Compiler's PSP and GameCube/Wii settings, widescreen on.
            Console.PSP -> base.copy(core = "libppsspp_libretro_android.so", pad = "psp", shader = "default", aspect = 16f / 9f,
                systemZip = "ppsspp_system.zip", variables = PSP + mapOf("ppsspp_internal_resolution" to (PSP_SIZE[res] ?: "960x544")))
            // PS2: ARMSX2's libretro core, drawn with Vulkan. Your own BIOS goes in Settings > BIOS files.
            Console.PS2 -> base.copy(core = "libarmsx2_libretro_android.so", pad = "ps2", shader = "default", aspect = 16f / 9f,
                systemZip = "ps2_system.zip", graphics = "vulkan", variables = mapOf(
                    "armsx2_renderer" to "Vulkan", "armsx2_upscale" to when (res) { "low" -> "1x"; "high" -> "3x"; else -> "2x" },
                    "armsx2_aspect_ratio" to "16:9", "armsx2_widescreen_patches" to "enabled", "armsx2_no_interlacing_patches" to "enabled",
                    "armsx2_fast_boot" to "enabled", "armsx2_mtvu" to "enabled", "armsx2_bios" to "auto", "armsx2_cheats" to "enabled"))
            Console.GC, Console.WII -> base.copy(core = "libdolphin_libretro_android.so", shader = "default", aspect = 16f / 9f,
                pad = if (console == Console.WII) "wii_sideways" else "gc", systemZip = "dolphin_system.zip",
                controllerType = 1, preserveGl = true, graphics = "vulkan", variables = dolphin(console == Console.WII, res))
        }
    }
}
