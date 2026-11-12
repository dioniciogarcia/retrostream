package com.tirso.tirsolador

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Phone remote, phone side: finds RetroStream on TVs and PCs on the same Wi-Fi (see Remote.kt for the protocol),
 * remembers the paired ones and sends them commands. Answers always arrive on the main thread.
 */
object RemoteClient {
    class Device(val id: String, var name: String, val kind: String, var host: String, var port: Int, var token: String) {
        val paired get() = token.isNotEmpty()
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("host", host).put("port", port).put("token", token)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    // ------------------------------------------------------------------ devices remembered on this phone
    fun saved(ctx: Context): MutableList<Device> {
        val a = runCatching { JSONArray(ctx.getSharedPreferences("remote_devices", Context.MODE_PRIVATE).getString("list", "[]")!!) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Device(it.optString("id"), it.optString("name"), it.optString("kind", "tv"), it.optString("host"), it.optInt("port", Remote.PORT), it.optString("token")) }
        }.filter { it.id.isNotEmpty() }.toMutableList()
    }
    fun save(ctx: Context, list: List<Device>) {
        val a = JSONArray(); list.forEach { a.put(it.json()) }
        ctx.getSharedPreferences("remote_devices", Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }
    fun remember(ctx: Context, d: Device) { val l = saved(ctx); l.removeAll { it.id == d.id }; l.add(d); save(ctx, l) }
    fun forget(ctx: Context, id: String) { val l = saved(ctx); l.removeAll { it.id == id }; save(ctx, l) }
    fun phoneName(): String = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    // ------------------------------------------------------------------ finding devices on the Wi-Fi
    /** Asks the whole Wi-Fi who runs RetroStream; [done] gets every device that answered (saved ones get their new address). */
    fun discover(ctx: Context, done: (List<Device>) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val found = linkedMapOf<String, Device>()
            runCatching {
                DatagramSocket().use { u ->
                    u.broadcast = true; u.soTimeout = 400
                    val msg = "RSDISCOVER1".toByteArray()
                    val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                    wifiBroadcast(app)?.let { targets.add(it) }
                    val until = System.currentTimeMillis() + 1800
                    var sent = 0
                    while (System.currentTimeMillis() < until) {
                        if (sent < 3) { for (t in targets) runCatching { u.send(DatagramPacket(msg, msg.size, t, Remote.FIND_PORT)) }; sent++ }
                        val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size)
                        try { u.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                        val j = runCatching { JSONObject(String(p.data, 0, p.length).trim()) }.getOrNull() ?: continue
                        if (j.optInt("rs") != 1) continue
                        val id = j.optString("id"); if (id.isEmpty()) continue
                        found[id] = Device(id, j.optString("name", "RetroStream"), j.optString("kind", "tv"), p.address.hostAddress ?: continue, j.optInt("port", Remote.PORT), "")
                    }
                }
            }
            // Saved devices keep their pairing and learn their new address.
            val list = saved(app)
            for (d in list) found[d.id]?.let { f -> d.host = f.host; d.port = f.port; d.name = f.name }
            save(app, list)
            val out = list.toMutableList()
            for (f in found.values) if (out.none { it.id == f.id }) out.add(f)
            val seen = found.keys
            main.post { done(out.sortedWith(compareBy({ it.id !in seen }, { !it.paired }, { it.name }))) }
        }
    }
    @Suppress("DEPRECATION")
    private fun wifiBroadcast(ctx: Context): InetAddress? = runCatching {
        val w = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val d = w.dhcpInfo ?: return null
        if (d.ipAddress == 0) return null
        val b = (d.ipAddress and d.netmask) or d.netmask.inv()
        InetAddress.getByAddress(ByteArray(4) { k -> (b shr (k * 8) and 0xFF).toByte() })
    }.getOrNull()

    // ------------------------------------------------------------------ talking
    /** One request, one answer. Network problems come back as {"error":"offline"}. */
    fun call(host: String, port: Int, req: JSONObject, timeoutMs: Int = 6000, done: (JSONObject) -> Unit) {
        pool.execute {
            val res = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 2500); s.soTimeout = timeoutMs
                    s.getOutputStream().write((req.toString() + "\n").toByteArray(Charsets.UTF_8)); s.getOutputStream().flush()
                    val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() ?: error("no answer")
                    JSONObject(line)
                }
            }.getOrElse { JSONObject().put("error", "offline") }
            main.post { done(res) }
        }
    }
    fun call(d: Device, req: JSONObject, timeoutMs: Int = 6000, done: (JSONObject) -> Unit) = call(d.host, d.port, req.put("token", d.token), timeoutMs, done)

    /** A device's answer turned into words for the person (null when it worked). */
    fun problem(r: JSONObject): String? = when (r.optString("error")) {
        "" -> null
        "offline" -> T.t("No responde. Revisa que esté encendido, con RetroStream abierto y en la misma red Wi-Fi.")
        "unpaired" -> T.t("Este dispositivo olvidó el teléfono. Vincúlalo de nuevo.")
        "off" -> T.t("El control desde el teléfono está desactivado en ese dispositivo.")
        "tv_closed" -> T.t("Abre RetroStream en la TV y vuelve a intentarlo (Android no deja que el teléfono la abra sola).")
        "not found" -> T.t("Ese juego no está en la biblioteca de ese dispositivo. Revisa que use la misma cuenta de Telegram y sus canales.")
        "not playing" -> T.t("Ya no se está jugando nada ahí.")
        "empty slot" -> T.t("No hay nada guardado en ese espacio.")
        "no telegram" -> T.t("Ese dispositivo no está conectado a Telegram.")
        "after_exit" -> T.t("La copia se hará al salir del juego.")
        "not available" -> T.t("Esa opción no está disponible con el programa de esta consola.")
        "wrong code" -> T.t("Código incorrecto.")
        "expired" -> T.t("El código caducó. Pide uno nuevo.")
        "timeout" -> T.t("No respondió a tiempo.")
        else -> r.optString("error")
    }
}
