package com.tirso.tirsolador

import android.view.KeyEvent
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig

/**
 * On-screen controller layouts. The key codes follow the libretro RetroPad
 * mapping used by the N64 core (same mapping as the Lemuroid app):
 *   N64 A = RetroPad B, N64 B = RetroPad Y, Z = L2, C buttons = right stick.
 */
object Pads {
    /** Id of the menu button on the touch pad (not sent to the game). */
    const val MENU_ID = -1000

    val N64_LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Stick(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, contentDescription = "Control stick"),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L1, label = "L")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L2, label = "Z")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
            SecondaryDialConfig.Cross(
                8, 2, 2.0f, 0.1f,
                CrossConfig(GLRetroView.MOTION_SOURCE_DPAD),
                rotationProcessor = object : SecondaryDialConfig.RotationProcessor() {
                    override fun getRotation(rotation: Float): Float = rotation - 10f
                }
            ),
        )
    )

    val N64_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            dials = listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "A"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "B"),
            ),
            rotationInDegrees = 30f,
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "R")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "START")),
            SecondaryDialConfig.SingleButton(10, 1f, -0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L2, label = "Z")),
            SecondaryDialConfig.Cross(
                8, 2, 2.0f, 0.1f,
                CrossConfig(
                    GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                    shape = CrossConfig.Shape.CIRCLE,
                    useDiagonals = false,
                ),
                rotationProcessor = object : SecondaryDialConfig.RotationProcessor() {
                    override fun getRotation(rotation: Float): Float = rotation + 10f
                }
            ),
        )
    )

    private fun leftWithCross(shoulder: String, extra: List<SecondaryDialConfig>) = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Cross(CrossConfig(GLRetroView.MOTION_SOURCE_DPAD)),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L1, label = shoulder)),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_SELECT, label = "SELECT")),
            SecondaryDialConfig.SingleButton(8, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
        ) + extra
    )

    private fun rightFourButtons(a: String, x: String, y: String, b: String, extra: List<SecondaryDialConfig> = emptyList()) = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = a),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = x),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = y),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = b),
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "R")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "START")),
        ) + extra
    )

    /** Nintendo DS: D-pad, A B X Y in Nintendo positions, L, R, Start, Select. */
    val NDS_LEFT = leftWithCross("L", listOf(
        // L3: full screen (one DS screen) or both screens.
        SecondaryDialConfig.SingleButton(10, 1f, 0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_THUMBL, label = "⛶", contentDescription = "Full screen"))
    ))
    val NDS_RIGHT = rightFourButtons("A", "X", "Y", "B", listOf(
        // R3: swap the screens (the player changes the layout).
        SecondaryDialConfig.SingleButton(10, 1f, -0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_THUMBR, label = "⇄", contentDescription = "Swap screens"))
    ))

    /** PSP: D-pad + analog nub, ○ △ □ ✕ in PlayStation positions. */
    val PSP_LEFT = leftWithCross("L", listOf(
        SecondaryDialConfig.Stick(9, 2, 2.2f, 0.1f, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, contentDescription = "Analog stick",
            rotationProcessor = object : SecondaryDialConfig.RotationProcessor() {
                override fun getRotation(rotation: Float): Float = rotation - 10f
            })
    ))
    val PSP_RIGHT = rightFourButtons("○", "△", "□", "✕")

    private val tiltLeft = object : SecondaryDialConfig.RotationProcessor() {
        override fun getRotation(rotation: Float): Float = rotation - 10f
    }
    private val tiltRight = object : SecondaryDialConfig.RotationProcessor() {
        override fun getRotation(rotation: Float): Float = rotation + 10f
    }

    /**
     * GameCube (Dolphin): control stick, D-pad, L; A B X Y, C-stick, R, Z, Start.
     * Dolphin's mapping: RetroPad A=A, B=B, X=X, Y=Y, L2=L, R2=R, R1=Z.
     * Buttons sit where they are on a GameCube pad: A at the bottom (main thumb spot), B to its left,
     * X to its right, Y above.
     */
    val GC_LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Stick(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, contentDescription = "Control stick"),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L2, label = "L")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
            SecondaryDialConfig.Cross(8, 2, 2.0f, 0.1f, CrossConfig(GLRetroView.MOTION_SOURCE_DPAD), rotationProcessor = tiltLeft),
        )
    )
    val GC_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "X"),   // right
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "Y"),   // top
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),   // left
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),   // bottom
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R2, label = "R")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "START")),
            SecondaryDialConfig.SingleButton(10, 1f, -0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "Z")),
            SecondaryDialConfig.Cross(8, 2, 2.0f, 0.1f,
                CrossConfig(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, shape = CrossConfig.Shape.CIRCLE, useDiagonals = true),
                rotationProcessor = tiltRight),
        )
    )

    /** Wii Remote held sideways: D-pad, 1, 2, A, B, +, −, Home, shake. */
    val WII_SIDE_LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Cross(CrossConfig(GLRetroView.MOTION_SOURCE_DPAD)),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "A")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "B")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_SELECT, label = "−")),
            SecondaryDialConfig.SingleButton(8, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
        )
    )
    val WII_SIDE_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "2"),   // right
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "1"),   // left
            ),
            rotationInDegrees = 30f,
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R2, label = "SHAKE")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "+")),
            SecondaryDialConfig.SingleButton(8, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_THUMBR, label = "⌂", contentDescription = "Home")),
        )
    )

    /** Wii Remote + Nunchuk: Nunchuk stick, C, Z; A, B, pointer (right stick), +, −, 1, 2, shake. */
    val WII_NUNCHUK_LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Stick(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, contentDescription = "Nunchuk stick"),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "C")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "Z")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L1, label = "−")),
            SecondaryDialConfig.SingleButton(8, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
            SecondaryDialConfig.Cross(9, 2, 2.0f, 0.1f, CrossConfig(GLRetroView.MOTION_SOURCE_DPAD), rotationProcessor = tiltLeft),
        )
    )
    val WII_NUNCHUK_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),   // right
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),   // left
            ),
            rotationInDegrees = 30f,
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R2, label = "SHAKE")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "+")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "1")),
            SecondaryDialConfig.SingleButton(5, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_SELECT, label = "2")),
            SecondaryDialConfig.SingleButton(10, 1f, -0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_THUMBR, label = "⌂", contentDescription = "Home")),
            SecondaryDialConfig.Stick(8, 2, 2.2f, 0.1f, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, contentDescription = "Pointer",
                rotationProcessor = tiltRight),
        )
    )

    /** Wii Classic Controller: D-pad, both sticks, a b x y, L R ZL ZR, +, −, Home. */
    val WII_CLASSIC_LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Cross(CrossConfig(GLRetroView.MOTION_SOURCE_DPAD)),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L2, label = "L")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L1, label = "ZL")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_SELECT, label = "−")),
            SecondaryDialConfig.SingleButton(8, 1f, 0f, ButtonConfig(id = MENU_ID, label = "☰", contentDescription = "Menu")),
            SecondaryDialConfig.Stick(9, 2, 2.2f, 0.1f, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, contentDescription = "Left stick",
                rotationProcessor = tiltLeft),
        )
    )
    val WII_CLASSIC_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "a"),   // right
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "x"),   // top
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "y"),   // left
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "b"),   // bottom
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(2, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "+")),
            SecondaryDialConfig.SingleButton(3, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "ZR")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R2, label = "R")),
            SecondaryDialConfig.SingleButton(5, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_THUMBR, label = "⌂", contentDescription = "Home")),
            SecondaryDialConfig.Stick(8, 2, 2.2f, 0.1f, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, contentDescription = "Right stick",
                rotationProcessor = tiltRight),
        )
    )

    /** Game Boy Advance: D-pad, A, B, L, R, Start, Select. */
    val GBA_LEFT = leftWithCross("L", emptyList())
    val GBA_RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            dials = listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),
            ),
            rotationInDegrees = 30f,
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.DoubleButton(2, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "R")),
            SecondaryDialConfig.SingleButton(4, 1f, 0f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "START")),
        )
    )

    /** PlayStation: D-pad, ○ △ □ ✕ in PlayStation positions, L1 L2 R1 R2, Select, Start. */
    val PS1_LEFT = leftWithCross("L1", listOf(
        SecondaryDialConfig.SingleButton(10, 1f, 0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L2, label = "L2"))
    ))
    val PS1_RIGHT = rightFourButtons("○", "△", "□", "✕", listOf(
        SecondaryDialConfig.SingleButton(10, 1f, -0.1f, ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R2, label = "R2"))
    ))

    /** SNES, Mega Drive, MAME: D-pad, A B X Y, L, R, Start, Select. */
    val SNES_LEFT = leftWithCross("L", emptyList())
    val SNES_RIGHT = rightFourButtons("A", "X", "Y", "B")
    fun forName(name: String): Pair<RadialGamePadConfig, RadialGamePadConfig> = when (name) {
        "ps1" -> PS1_LEFT to PS1_RIGHT
        "nds" -> NDS_LEFT to NDS_RIGHT
        "psp" -> PSP_LEFT to PSP_RIGHT
        "gc" -> GC_LEFT to GC_RIGHT
        "gba" -> GBA_LEFT to GBA_RIGHT
        "snes" -> SNES_LEFT to SNES_RIGHT
        "wii_sideways" -> WII_SIDE_LEFT to WII_SIDE_RIGHT
        "wii_nunchuk" -> WII_NUNCHUK_LEFT to WII_NUNCHUK_RIGHT
        "wii_classic" -> WII_CLASSIC_LEFT to WII_CLASSIC_RIGHT
        else -> N64_LEFT to N64_RIGHT
    }
}
