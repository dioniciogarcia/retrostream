package com.tirso.tirsolador

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Keeps this game's saves in its own private storage:
 *   saves/game.srm      in-game save (battery / controller pak)
 *   states/auto.state   where you left off (written when the app goes to the background)
 *   states/slotN.state  manual save states 1-3
 * The game file itself lives elsewhere and is never mixed with saves.
 */
class SaveManager(val root: File, val systemDir: File) {
    val savesDir = File(root, "saves").apply { mkdirs() }
    private val statesDir = File(root, "states").apply { mkdirs() }
    init {
        // Saves from RetroStream's earlier player (battery.sav) carry over.
        val legacy = File(root, "battery.sav")
        if (legacy.isFile && !File(savesDir, "game.srm").exists()) runCatching { legacy.copyTo(File(savesDir, "game.srm")) }
        systemDir.mkdirs()
    }
    private val sram = File(savesDir, "game.srm")
    private val auto = File(statesDir, "auto.state")

    fun readSram(): ByteArray? = sram.takeIf { it.exists() && it.length() > 0 }?.readBytes()
    fun writeSram(data: ByteArray) { if (data.isNotEmpty()) atomicWrite(sram, data) }

    fun readAutoState(): ByteArray? = auto.takeIf { it.exists() && it.length() > 0 }?.readBytes()
    fun writeAutoState(data: ByteArray) { if (data.isNotEmpty()) atomicWrite(auto, data) }
    fun deleteAutoState() { auto.delete() }

    private fun slot(n: Int) = File(statesDir, "slot$n.state")
    fun readSlot(n: Int): ByteArray? = slot(n).takeIf { it.exists() }?.readBytes()
    fun writeSlot(n: Int, data: ByteArray) {
        require(data.isNotEmpty()) { "empty state" }
        atomicWrite(slot(n), data)
    }
    fun slotInfo(n: Int): String? = slot(n).takeIf { it.exists() }?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.lastModified()))
    }

    /**
     * The save backup zip (also what TirsoDrive stores in its Games Save Data channel):
     *   tirso-saves.json   {"format":1,"package","title","console","created"} so any app can tell which game it is for
     *   tirso-saves.txt    marker kept for older Tirso apps
     *   saves/...          in-game saves, memory cards
     *   states/...         save states (the automatic "where you left off" one only when [withAutoState])
     */
    fun exportZip(out: OutputStream, meta: Map<String, String> = emptyMap(), withAutoState: Boolean = true) {
        ZipOutputStream(out).use { zip ->
            // Whole folders: some engines (Dolphin) keep memory cards and Wii saves in sub-folders.
            listOf(savesDir, statesDir).forEach { dir ->
                dir.walkTopDown()
                    .onEnter { it.name !in SKIP_FOLDERS }   // caches and logs aren't saves
                    .filter { it.isFile && !it.name.endsWith(".tmp") && (withAutoState || it != auto) }.forEach { f ->
                    zip.putNextEntry(ZipEntry("${dir.name}/" + f.relativeTo(dir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("tirso-saves.json"))
            val json = org.json.JSONObject()
            json.put("format", 1)
            meta.forEach { (k, v) -> json.put(k, v) }
            json.put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(Date()))
            zip.write(json.toString(1).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("tirso-saves.txt"))
            zip.write("Tirso game save backup\n".toByteArray())
            zip.closeEntry()
        }
    }

    /** Newest change to any save file (to skip automatic backups when nothing changed). */
    fun lastChange(): Long = listOf(savesDir, statesDir).flatMap { d ->
        d.walkTopDown().onEnter { it.name !in SKIP_FOLDERS }.filter { it.isFile && it != auto }.map { it.lastModified() }.toList()
    }.maxOrNull() ?: 0L

    /** The package a backup was made for ("" when the zip doesn't say). */
    fun backupPackage(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "tirso-saves.json") {
                    return runCatching { org.json.JSONObject(zip.readBytes().decodeToString()).optString("package") }.getOrDefault("")
                }
            }
        }
        return ""
    }

    /** Restores only files from a Tirso backup; anything else in the zip is ignored. */
    fun importZip(input: InputStream): Boolean {
        val pending = mutableMapOf<File, ByteArray>()
        var isTirso = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "tirso-saves.txt" -> isTirso = true
                    !e.isDirectory && Regex("^(saves|states)/[A-Za-z0-9._ ()\\[\\]-]+(/[A-Za-z0-9._ ()\\[\\]-]+)*$").matches(name)
                        && name.split('/').none { it == ".." || it == "." } -> {
                        val dir = if (name.startsWith("saves/")) savesDir else statesDir
                        val target = File(dir, name.substringAfter('/')).canonicalFile
                        if (target.path.startsWith(dir.canonicalPath + File.separator)) pending[target] = zip.readBytes()
                    }
                }
            }
        }
        if (!isTirso || pending.isEmpty()) return false
        pending.forEach { (f, data) -> atomicWrite(f, data) }
        return true
    }

    private companion object {
        val SKIP_FOLDERS = setOf("Cache", "ShaderCache", "Logs", "Dump", "ScreenShots", "Load", "ResourcePacks")
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
