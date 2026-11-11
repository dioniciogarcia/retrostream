package com.tirso.tirsolador

/** The player's texts are written in English (as in Tirso Compiler's player); Spanish is looked up here. */
private val ES = mapOf(
    "Microphone allowed. Close and reopen the game to use it." to "Micrófono permitido. Cierra y abre el juego para usarlo.",
    "Microphone not allowed. You can allow it in the phone's Settings > Apps." to "Micrófono no permitido. Puedes permitirlo en Ajustes > Aplicaciones.",
    "Microphone on" to "Micrófono activado", "Microphone off" to "Micrófono desactivado",
    "Microphone: allow" to "Micrófono: permitir", "Microphone: on" to "Micrófono: activado", "Microphone: off" to "Micrófono: desactivado",
    "The game couldn't be prepared." to "No se pudo preparar el juego.",
    "Continuing where you left off" to "Continuando donde lo dejaste",
    "The game engine couldn't be loaded on this phone." to "No se pudo cargar el motor del juego en este dispositivo.",
    "The game file couldn't be opened. It may be damaged or unsupported." to "No se pudo abrir el juego. Puede estar dañado o no ser compatible.",
    "This phone's graphics chip isn't supported (OpenGL ES 3 is required)." to "Los gráficos de este dispositivo no son compatibles (se requiere OpenGL ES 3).",
    "That save couldn't be loaded." to "No se pudo cargar esa partida.", "That save couldn't be loaded" to "No se pudo cargar esa partida",
    "Connect a Bluetooth controller to play. Back opens the menu." to "Conecta un mando para jugar. Atrás abre el menú.",
    "Full screen (R3 switches screen)" to "Pantalla completa (R3 cambia de pantalla)", "Both screens" to "Ambas pantallas",
    "Touch screen" to "Pantalla táctil", "Top screen" to "Pantalla superior",
    "Resume" to "Continuar", "Game information" to "Información del juego", "Cheats" to "Trucos", "Backing up your save to Telegram…" to "Guardando tu partida en Telegram…", "Save backed up to Telegram" to "Partida guardada en Telegram", "Save backup failed" to "Falló la copia de la partida", "Looking for cheats…" to "Buscando trucos…", "No cheats were found for this game (or there is no internet connection)." to "No se encontraron trucos para este juego (o no hay conexión a internet).",
    "Save game state" to "Guardar estado", "Load game state" to "Cargar estado",
    "Show both screens (L3)" to "Mostrar ambas pantallas (L3)", "Full screen (L3)" to "Pantalla completa (L3)",
    "Switch screen (R3)" to "Cambiar pantalla (R3)", "Swap screens (R3)" to "Intercambiar pantallas (R3)",
    "Controller help" to "Ayuda del mando", "Hide touch controls" to "Ocultar controles táctiles", "Show touch controls" to "Mostrar controles táctiles",
    "Touch controls hidden. Press Back (or swipe back) for the menu to show them again." to "Controles táctiles ocultos. Pulsa Atrás para volver a mostrarlos desde el menú.",
    "Screen filter" to "Filtro de pantalla", "Graphics" to "Gráficos",
    "Restart game" to "Reiniciar juego",
    "Back up saves" to "Copia de partidas en Telegram", "Restore saves" to "Restaurar partidas desde Telegram",
    "Connect TirsoDrive on the home screen to back up to Telegram" to "Conecta TirsoDrive en la pantalla principal para hacer copias en Telegram",
    "Connect TirsoDrive on the home screen to restore from Telegram" to "Conecta TirsoDrive en la pantalla principal para restaurar desde Telegram",
    "Backing up to Telegram…" to "Guardando copia en Telegram…", "Looking in Telegram…" to "Buscando en Telegram…",
    "Couldn't read the backup channel" to "No se pudo leer el canal de copias",
    "No backup of this game in Telegram yet" to "Aún no hay copia de este juego en Telegram",
    "Saves will be backed up to Telegram when you leave the game" to "Las partidas se copiarán a Telegram al salir del juego",
    "To restore from Telegram, leave the game and use Settings > Save backups" to "Para restaurar desde Telegram, sal del juego y usa Ajustes > Copias de partidas",
    "Latest" to "Última", "Restore saves from Telegram" to "Restaurar partidas desde Telegram",
    "Restore this backup?" to "¿Restaurar esta copia?", "Restoring…" to "Restaurando…",

    "Restart the game?" to "¿Reiniciar el juego?", "Progress since your last in-game save will be lost." to "Se perderá el progreso desde tu último guardado en el juego.",
    "Hide speed (fps)" to "Ocultar velocidad (fps)", "Show speed (fps)" to "Mostrar velocidad (fps)",
    "Save problem report" to "Guardar informe de problemas", "Legal notices" to "Avisos legales", "Exit game" to "Salir del juego",
    "Save to slot" to "Guardar en ranura", "Load from slot" to "Cargar de ranura", "Slot" to "Ranura", "empty" to "vacía",
    "Saved to slot" to "Guardado en ranura", "Loaded slot" to "Ranura cargada", "Saving failed" to "No se pudo guardar", "is empty" to "está vacía",
    "Restart" to "Reiniciar", "Cancel" to "Cancelar", "Close" to "Cerrar", "OK" to "Aceptar", "Controller" to "Mando",
    "The game closed unexpectedly last time" to "El juego se cerró inesperadamente la última vez", "Copy report" to "Copiar informe",
    "Report copied. Paste it to Claude." to "Informe copiado. Pégalo en el chat con Claude.",
    "Menu: Back on the remote, or Select + Start together.\n" to "Menú: Atrás en el control, o Select + Start juntos.\n",
    "Full screen / both screens: L3 (press the left stick).\n" to "Pantalla completa / ambas: L3 (pulsa el stick izquierdo).\n",
    "Swap screens: R3 (press the right stick).\n" to "Intercambiar pantallas: R3 (pulsa el stick derecho).\n",
    "Touch screen: move the pencil with the right stick, touch with R2.\n" to "Pantalla táctil: mueve el lápiz con el stick derecho, toca con R2.\n",
    "Problem report saved in Downloads. Send it to Claude." to "Informe guardado en Descargas. Envíaselo a Claude.",
    "The report couldn't be saved." to "No se pudo guardar el informe.",
    "Legal notices are missing from this build." to "Faltan los avisos legales en esta versión.",
    "This changes how GameCube and Wii pictures are drawn. " to "", "Close game" to "Cerrar juego",
    "Restore saves?" to "¿Restaurar partidas?", "Restore" to "Restaurar", "Saves backed up" to "Partidas respaldadas", "Backup failed" to "Falló el respaldo",
    "Saves restored. Reopen the game to use them." to "Partidas restauradas. Vuelve a abrir el juego para usarlas.",
    "That file isn't a save backup for this game" to "Ese archivo no es un respaldo de este juego",
    "Original" to "Original", "Plain scaling, a little soft." to "Escalado simple, algo suave.",
    "Sharp pixels" to "Píxeles nítidos", "Crisp, evenly sized pixels." to "Píxeles nítidos y uniformes.",
    "Smooth upscale" to "Suavizado", "Smooths jagged edges: pixel art looks closer to a high-resolution picture." to "Suaviza los bordes: el pixel art se ve más cercano a alta resolución.",
    "Smooth upscale, strong" to "Suavizado fuerte", "Stronger smoothing (uses more battery)." to "Suavizado más fuerte (usa más batería).",
    "Handheld LCD" to "LCD portátil", "Shows the pixel grid like the original handheld screen." to "Muestra la cuadrícula de píxeles como la pantalla original.",
    "CRT TV" to "TV CRT", "Scanlines like an old TV." to "Líneas de barrido como una TV antigua.",
    "No description was found for this game." to "No se encontró descripción de este juego.", "Back to game" to "Volver al juego", "SCREENSHOTS" to "CAPTURAS",
    "Problem report" to "Informe de problemas", "Show" to "Mostrar",
)

fun tr(text: String): String {
    if (T.english()) return text
    ES[text]?.let { return it }
    val i = text.indexOf(": ")
    if (i > 0) { val head = ES[text.substring(0, i)]; if (head != null) return head + ": " + tr(text.substring(i + 2)) }
    return text
}
