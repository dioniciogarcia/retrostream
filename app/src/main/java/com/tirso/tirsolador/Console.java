package com.tirso.tirsolador;

public enum Console {
    NES("NES", "Nintendo - Nintendo Entertainment System", "nes", 0xFFB83B52),
    SNES("SNES", "Nintendo - Super Nintendo Entertainment System", "snes", 0xFF8D66BE),
    MD("Sega Mega Drive", "Sega - Mega Drive - Genesis", "megadrive", 0xFF3069AC),
    GB("Game Boy", "Nintendo - Game Boy", "gb", 0xFF5A886D),
    GBC("Game Boy Color", "Nintendo - Game Boy Color", "gbc", 0xFFB47F44),
    GBA("Game Boy Advance", "Nintendo - Game Boy Advance", "gba", 0xFF7158AC),
    N64("Nintendo 64", "Nintendo - Nintendo 64", "n64", 0xFF347E99),
    MAME("MAME", "MAME", "mame", 0xFF3578A1),
    NDS("Nintendo DS", "Nintendo - Nintendo DS", "nds", 0xFFB34C65),
    // Phone app only (Tirso Compiler's phone engines).
    PSP("PSP", "Sony - PlayStation Portable", "psp", 0xFF4A4F5C),
    PS2("PlayStation 2", "Sony - PlayStation 2", "ps2", 0xFF1F3E8A),
    GC("GameCube", "Nintendo - GameCube", "gamecube", 0xFF5B3E9E),
    WII("Wii", "Nintendo - Wii", "wii", 0xFF2D8AB8);

    public final String title, thumbnailSystem, logo;
    public final int color;
    Console(String title, String thumbnailSystem, String logo, int color) {
        this.title = title; this.thumbnailSystem = thumbnailSystem; this.logo = logo; this.color = color;
    }
    /** Consoles only the phone app can play. */
    public boolean phoneOnly() { return this == PSP || this == PS2 || this == GC || this == WII; }
    /** Played in a fresh process of their own: N64 (closing it can crash), and on the phone PSP, PS2, GameCube and
     *  Wii, whose engines leave graphics state behind that breaks the next big engine started in the same process. */
    public static boolean ownProcess(Console c) { return c == N64 || (BuildConfig.PHONE && (c == PSP || c == PS2 || c == GC || c == WII)); }
    private static Console[] cached;
    /** The consoles this app plays (the TV app leaves out the phone-only ones). */
    public static Console[] all() {
        if (cached == null) {
            java.util.ArrayList<Console> list = new java.util.ArrayList<>();
            for (Console c : values()) if (BuildConfig.PHONE || !c.phoneOnly()) list.add(c);
            cached = list.toArray(new Console[0]);
        }
        return cached;
    }
}
