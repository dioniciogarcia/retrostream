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

    fun key(console: Console): String = when (console) {
        Console.GB, Console.GBC -> "gb"
        else -> console.name.lowercase()
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
    private val DESMUME = GameConfig.AltEngine("libdesmume_libretro_android.so", "DeSmuME", mapOf(
        "desmume_cpu_mode" to "jit", "desmume_num_cores" to "2", "desmume_frameskip" to "0",
        "desmume_internal_resolution" to "256x192", "desmume_screens_layout" to "hybrid/top",
        "desmume_hybrid_layout_ratio" to "2:1", "desmume_hybrid_showboth_screens" to "enabled",
        "desmume_pointer_type" to "touch", "desmume_pointer_mouse" to "enabled",
        "desmume_pointer_device_r" to "emulated", "desmume_pointer_colour" to "white",
    ))

    private val MGBA = mapOf(
        "mgba_use_bios" to "OFF", "mgba_skip_bios" to "ON", "mgba_idle_optimization" to "Remove Known",
        "mgba_interframe_blending" to "mix_smart", "mgba_color_correction" to "OFF", "mgba_frameskip" to "disabled",
    )

    fun forConsole(context: Context, console: Console, title: String): GameConfig {
        val base = GameConfig(title = title, core = "", pad = "snes", consoleName = console.title, shader = "sharp")
        return when (console) {
            Console.N64 -> base.copy(core = "libmupen64plus_next_gles3_libretro_android.so", pad = "n64", shader = "default",
                variables = n64(resolution(context)), screenMode = "overlay", aspect = 4f / 3f)
            Console.NDS -> base.copy(core = "libmelondsds_libretro_android.so", pad = "nds", variables = NDS,
                screenMode = "beside", aspect = 2f, altEngines = listOf(DESMUME))
            Console.GBA -> base.copy(core = "libmgba_libretro_android.so", pad = "gba", variables = MGBA, aspect = 3f / 2f)
            Console.GBC, Console.GB -> base.copy(core = "libmgba_libretro_android.so", pad = "gba", variables = MGBA, aspect = 10f / 9f)
            Console.SNES -> base.copy(core = "libsnes9x_libretro_android.so", pad = "snes", aspect = 4f / 3f)
            Console.NES -> base.copy(core = "libfceumm_libretro_android.so", pad = "gba", aspect = 4f / 3f)
            Console.MD -> base.copy(core = "libgenesis_plus_gx_libretro_android.so", pad = "snes", aspect = 4f / 3f)
            Console.MAME -> base.copy(core = "libmame2003_plus_libretro_android.so", pad = "snes", shader = "default",
                variables = mapOf("mame2003-plus_skip_disclaimer" to "enabled", "mame2003-plus_skip_warnings" to "enabled"))
        }
    }
}
