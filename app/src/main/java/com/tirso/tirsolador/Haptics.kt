package com.tirso.tirsolador

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Phone vibration for games that use the N64 Rumble Pak. */
object Haptics {
    private var lastOn = false

    fun rumble(context: Context, weak: Float, strong: Float) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val strength = maxOf(weak, strong)
        if (strength <= 0.01f) {
            if (lastOn) vibrator.cancel()
            lastOn = false
            return
        }
        lastOn = true
        val amp = (strength * 255).toInt().coerceIn(1, 255)
        vibrator.vibrate(VibrationEffect.createOneShot(1000, amp))
    }
}
