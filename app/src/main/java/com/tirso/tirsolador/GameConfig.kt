package com.tirso.tirsolador

/**
 * Everything the player needs to know about one game session. Tirso Compiler writes this into each game's
 * assets; RetroStream builds it from the console of the game being played (see [Presets]).
 */
data class GameConfig(
    val title: String,
    val core: String,
    val pad: String,
    val consoleName: String = "",
    val orientation: String = "landscape",
    val shader: String = "default",
    val autoResume: Boolean = true,
    val rumble: Boolean = true,
    val variables: Map<String, String> = emptyMap(),
    val buildId: String = "retrostream",
    /** "overlay": controls over the game; "beside": game between the controls; "above": game on top. */
    val screenMode: String = "overlay",
    /** Width / height of the game picture, used by "beside" and "above". */
    val aspect: Float = 4f / 3f,
    /** Engine to use instead when the main engine isn't installed for this device's processor type
     *  (DeSmuME on 32-bit TV devices, like Tirso Compiler's TV games). */
    val altEngines: List<AltEngine> = emptyList(),
    val rotateLayouts: Map<String, Layout> = emptyMap(),
    val controllerType: Int = -1,
    val preserveGl: Boolean = false,
    val gpuDriver: String? = null,
    val gpuDriverSha1: String = "",
    val gpuDriverName: String = "",
    val graphics: String = "opengl",
    val rom: String? = null,
    val dataFile: String? = null,
    val romSize: Long = -1,
    val romSha1: String = "",
    val systemZip: String? = null,
    val biosFile: String? = null,
    val cueSheet: String? = null,
) {
    data class AltEngine(val core: String, val label: String, val variables: Map<String, String>)
    data class Layout(val screenMode: String, val aspect: Float, val variables: Map<String, String>)
}
