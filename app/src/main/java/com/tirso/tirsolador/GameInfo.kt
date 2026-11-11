package com.tirso.tirsolador

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * The in-game "Game information" window: box art, details, description and screenshots that
 * Tirso Compiler looked up and packed into assets/tirso/info/ when the app was built.
 */
object GameInfo {
    private const val DIR = "tirso/info"
    private val INK = Color.parseColor("#0F121A")
    private val CARD = Color.parseColor("#161A24")
    private val CHIP = Color.parseColor("#1C2130")
    private val LINE = Color.parseColor("#323A50")
    private val TEXT = Color.parseColor("#E9ECF5")
    private val BODY = Color.parseColor("#C9CEDC")
    private val MUTED = Color.parseColor("#8C93A8")
    private val CREAM = Color.parseColor("#EDE6D6")
    private val CREAM_INK = Color.parseColor("#1A1712")

    private val CONSOLES = mapOf(
        "n64" to "Nintendo 64", "nds" to "Nintendo DS", "gba" to "Game Boy Advance", "gb" to "Game Boy",
        "gbc" to "Game Boy Color", "snes" to "Super Nintendo", "nes" to "NES", "md" to "Sega Mega Drive", "mame" to "MAME",
    )
    private val MONTHS = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun show(activity: Activity, info: JSONObject, boxArt: Bitmap?, onClose: () -> Unit) {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1f), stroke)
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val scrim = FrameLayout(activity).apply { setBackgroundColor(Color.argb(235, 8, 10, 16)) }
        val card = LinearLayout(activity).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = rounded(CARD, 20f, LINE)
            setPadding(dp(20f), dp(20f), dp(20f), dp(16f))
        }
        val margin = if (landscape) dp(18f) else dp(12f)
        scrim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(margin, margin, margin, margin)
        })

        // Box art (left when sideways, top when upright)
        val box = boxArt
        if (box != null) {
            val art = ImageView(activity).apply {
                setImageBitmap(box); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Box art"
            }
            if (landscape) {
                card.addView(art, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.32f).apply { marginEnd = dp(20f) })
            } else {
                card.addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190f)).apply { bottomMargin = dp(14f) })
            }
        }

        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(right, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.68f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val scroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = true }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        right.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(text(info.optString("title", "Game"), 24f, TEXT, bold = true))
        val sub = listOfNotNull(
            CONSOLES[info.optString("console")],
            info.optString("developer").takeIf { it.isNotBlank() },
            info.optString("publisher").takeIf { it.isNotBlank() && it != info.optString("developer") },
        ).joinToString(" · ")
        if (sub.isNotBlank()) body.addView(text(sub, 13f, MUTED).apply { setPadding(0, dp(4f), 0, 0) })

        val chips = mutableListOf<String>()
        info.optString("genre").takeIf { it.isNotBlank() }?.let { chips += it }
        info.optInt("players", 0).takeIf { it > 0 }?.let { chips += if (it == 1) "1 player" else "1–$it players" }
        info.optInt("year", 0).takeIf { it > 0 }?.let { y ->
            val m = info.optInt("month", 0)
            chips += "Released " + (if (m in 1..12) "${MONTHS[m]} " else "") + y
        }
        info.optString("region").takeIf { it.isNotBlank() }?.let { chips += it }
        if (chips.isNotEmpty()) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in chips) {
                row.addView(text(c, 12f, TEXT).apply {
                    background = rounded(CHIP, 6f, Color.parseColor("#262C3D"))
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) })
            }
            body.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(row) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        }

        val description = info.optString("description")
        body.addView(text(if (description.isNotBlank()) description else tr("No description was found for this game."), 15f,
            if (description.isNotBlank()) BODY else MUTED).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })

        val shots = emptyList<Bitmap>()
        if (shots.isNotEmpty()) {
            body.addView(text(tr("SCREENSHOTS"), 11f, MUTED, bold = true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, bmp) in shots.withIndex()) {
                row.addView(ImageView(activity).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
                    background = rounded(INK, 8f, LINE); setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
                    contentDescription = "Screenshot"
                }, LinearLayout.LayoutParams(0, dp(if (landscape) 110f else 130f), 1f).apply { if (i > 0) marginStart = dp(10f) })
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }

        // Bottom bar: credits + close
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(text(info.optString("credits"), 11f, MUTED).apply { maxLines = 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12f) })
        val close = Button(activity).apply {
            text = tr("Back to game"); isAllCaps = false; setTextColor(CREAM_INK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(CREAM, 10f); setPadding(dp(18f), 0, dp(18f), 0); minHeight = dp(46f)
            setOnClickListener { dialog.dismiss() }
        }
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46f)))
        right.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })

        dialog.setContentView(scrim)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        // Controller: B or Back closes, up/down scrolls the text, A presses "Back to game".
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode in listOf(
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> { dialog.dismiss(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, dp(90f)); true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -dp(90f)); true }
                else -> false
            }
        }
        dialog.setOnDismissListener { onClose() }
        dialog.show()
        close.requestFocus()
    }
}
