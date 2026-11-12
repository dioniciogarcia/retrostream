package com.tirso.tirsolador

import android.content.Context

/** What the home screen shows (Settings > Home screen). Shared by the TV and phone apps. */
internal object HomeLayout {
    private fun p(ctx: Context) = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    /** TV: "a" = Continue card + rows (new), "classic" = the earlier console screens. */
    @JvmStatic fun tvStyle(ctx: Context): String = p(ctx).getString("tv_style", "a") ?: "a"
    @JvmStatic fun tvStyle(ctx: Context, v: String) = p(ctx).edit().putString("tv_style", v).apply()
    /** Phone: consoles as a 3x3 grid with "See all" (true) or as one swipeable row (false, the earlier look). */
    @JvmStatic fun consoleGrid(ctx: Context) = p(ctx).getBoolean("console_grid", true)
    @JvmStatic fun consoleGrid(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("console_grid", on).apply()
    val SECTIONS = listOf("continue", "recent", "new", "consoles", "friends", "suggested")
    @JvmStatic fun shown(ctx: Context, section: String) = p(ctx).getBoolean("show_$section", section != "suggested")
    @JvmStatic fun shown(ctx: Context, section: String, on: Boolean) = p(ctx).edit().putBoolean("show_$section", on).apply()
}
