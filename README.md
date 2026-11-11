# RetroStream

Aplicación nativa para Android TV: biblioteca de Telegram/TirsoDrive y emulación dentro del mismo APK. Android 8 o posterior; ARM de 32/64 bits y x86 de 32/64 bits.

## Versión 1.0.1

[Descargas y fuentes completas](https://github.com/Randy3m2/tirsolador/releases) · [Notas de la versión](docs/BUILD_1_0_1.md) · [Compilación](docs/NATIVE_BUILD.md)

- Bienvenida QR y sesión cifrada de Telegram, catálogos por consola y sincronización al entrar.
- Botón **Jugar**: prepara el archivo, inicia el núcleo y elimina la ROM temporal al salir; conserva partidas y estados.
- Logos PNG, favoritos, búsqueda, orden alfabético, portadas Libretro, temas claro/oscuro y consola a pantalla completa.
- Ajustes con tarjetas e interruptores para mostrar u ocultar consolas.
- Perfiles independientes de mandos y jugadores, D-pad separado de joystick, hasta cuatro jugadores donde el núcleo lo permite.
- Menú del juego con Atrás: información, FPS, estados, respaldar/restaurar partidas, reiniciar y salir.
- Respaldos ZIP verificados mediante el selector de archivos de Android. Las partidas permanecen en el televisor.

| Consola | Núcleo integrado | Grupo esperado |
|---|---|---|
| NES | FCEUmm | TD - ROMs - NES |
| SNES | Snes9x | TD - ROMs - SNES |
| Sega Mega Drive | Genesis Plus GX | TD - ROMs - Genesis |
| Game Boy | Gambatte | TD - ROMs - Game Boy |
| Game Boy Color | Gambatte | TD - ROMs - Game Boy Color |
| Game Boy Advance | mGBA | **DD-ROMS-GBA** |
| Nintendo 64 | Mupen64Plus-Next | TD - ROMs - N64 |
| MAME | MAME 2003-Plus | TD - ROMs - MAME |
| Nintendo DS | melonDS original | TD - ROMs - DS |

También se reconocen alias normalizados `TD`/`DD`, `GB`, `GBC`, `GBA`, `NDS`, `MegaDrive` y `Arcade`. Las coincidencias ambiguas se resuelven en la vinculación; no se mezclan catálogos de otras consolas. Solo se leen grupos accesibles por la cuenta autorizada.

## Instalación y compatibilidad

Instalar sobre 1.0.0 o 0.4.0 Preview conserva datos y sesión: se mantiene el identificador `com.tirso.tirsolador.preview` y su certificado. No desinstalar para actualizar.

Los núcleos se compilan desde revisiones fijadas en [config/cores.json](config/cores.json); las fuentes correspondientes y licencias se incluyen en Releases. No se necesitan APK de emuladores externos. MAME utiliza ROM sets compatibles con **2003-Plus**; algunos juegos requieren archivos padre o BIOS, importables desde Ajustes. NES FDS requiere `disksys.rom`. DS usa el arranque integrado de melonDS para ROMs compatibles.

Las pruebas automatizadas ejecutan diagnósticos propios de cada plataforma y Gridlee desde la distribución oficial de MAME. No equivalen a probar todos los juegos comerciales ni todos los televisores o mandos físicos. No se distribuyen ROMs comerciales, BIOS, credenciales ni sesiones.

## Desarrollo

`app/` contiene UI Java, TDLib, almacenamiento y frontends JNI. `tools/` prepara fuentes fijadas, compila núcleos y genera diagnósticos. CI comprueba ejecución real, imagen/audio/estados, interfaz, archivos, mandos y actualización sobre el APK anterior antes de publicar.

El respaldo automático a Google Drive/Telegram y el remapeo libre de cada botón no forman parte de esta versión; están disponibles los respaldos manuales y los perfiles de dispositivo/jugador.
