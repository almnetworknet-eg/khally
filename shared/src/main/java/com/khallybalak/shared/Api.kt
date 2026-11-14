package com.khallybalak.shared

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** عنوان السيرفر — المستخدم بيكتبه مرة واحدة في التطبيق (مثل: mydomain.com) */
object ServerConfig {
    private var prefs: SharedPreferences? = null

    /** السيرفر الافتراضي — مكتوب جاهز في التطبيقين */
    const val DEFAULT_ADDRESS = "almeng.net/app/khally"

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("server", Context.MODE_PRIVATE)
    }

    val baseUrl: String get() = prefs?.getString("url", "").orEmpty()
    val displayAddress: String get() = prefs?.getString("display", null) ?: DEFAULT_ADDRESS
    val isSet get() = baseUrl.isNotEmpty()

    /** "mydomain.com" ← https://mydomain.com/khally/api/index.php */
    fun normalize(input: String): String {
        var s = input.trim().removeSuffix("/")
        s = s.removePrefix("https://").removePrefix("http://")
        if (s.isEmpty()) return ""
        s = when {
            s.endsWith("index.php") -> s
            s.endsWith("/api") -> "$s/index.php"
            s.contains("/") -> "$s/api/index.php"          // مسار مخصص مثل mydomain.com/parent
            else -> "$s/khally/api/index.php"
        }
        return "https://$s"
    }

    /** يتأكد إن السيرفر متثبت قبل الحفظ */
    suspend fun verifyAndSave(input: String) {
        val url = normalize(input)
        if (url.isEmpty()) throw ApiException(0, "اكتب عنوان السيرفر")
        val j = try {
            Api(baseUrlOverride = url) { null }.call("ping")
        } catch (e: ApiException) {
            if (e.code == 503) throw ApiException(503, "السيرفر لسه ماتثبتش — افتح khally/install.php")
            throw ApiException(e.code, "العنوان ده مش سيرفر خلي بالك")
        } catch (e: Exception) {
            throw ApiException(0, "تعذر الوصول للسيرفر — تأكد من العنوان والنت")
        }
        if (j.optString("app") != "khally") throw ApiException(0, "العنوان ده مش سيرفر خلي بالك")
        if (!j.optBoolean("installed", true)) throw ApiException(503, "السيرفر لسه ماتثبتش — افتح khally/install.php")
        prefs?.edit()?.putString("url", url)?.putString("display", input.trim())?.apply()
    }
}

class ApiException(val code: Int, message: String) : Exception(message)

/** عميل HTTP خفيف بدون مكتبات خارجية */
class Api(private val baseUrlOverride: String? = null, private val token: () -> String?) {

    suspend fun call(
        action: String,
        body: JSONObject? = null,
        query: Map<String, Any> = emptyMap(),
        timeoutMs: Int = 15_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = open(action, query, timeoutMs)
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        read(conn)
    }

    suspend fun postBytes(action: String, bytes: ByteArray, contentType: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = open(action, emptyMap(), 15_000)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(bytes) }
            read(conn)
        }

    private fun open(action: String, query: Map<String, Any>, timeoutMs: Int): HttpURLConnection {
        val qs = (mapOf<String, Any>("action" to action) + query).entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value.toString())}"
        }
        val base = baseUrlOverride ?: ServerConfig.baseUrl
        if (base.isEmpty()) throw ApiException(0, "اكتب عنوان السيرفر أولاً")
        return (URL("$base?$qs").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            token()?.let { setRequestProperty("X-Token", it) }
        }
    }

    private fun read(conn: HttpURLConnection): JSONObject = try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) throw ApiException(code, json.optString("error", "خطأ في السيرفر ($code)"))
        json
    } finally {
        conn.disconnect()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

// ───────── تحويل JSON ⇄ الموديلات ─────────

fun AppRule.toJson(): JSONObject = JSONObject()
    .put("pkg", pkg).put("blocked", blocked).put("dailyLimitMin", dailyLimitMin)
    .put("windowStart", windowStart).put("windowEnd", windowEnd)

fun Policy.toJson(): JSONObject = JSONObject()
    .put("rules", JSONArray(rules.map { it.toJson() }))
    .put("blockInstallUninstall", blockInstallUninstall)
    .put("blockSettings", blockSettings)
    .put("lockDevice", lockDevice)
    .put("bedtimeStart", bedtimeStart)
    .put("bedtimeEnd", bedtimeEnd)

fun JSONObject.toAppRule() = AppRule(
    pkg = optString("pkg"), blocked = optBoolean("blocked"),
    dailyLimitMin = optInt("dailyLimitMin"),
    windowStart = optString("windowStart"), windowEnd = optString("windowEnd"),
)

fun JSONObject?.toPolicy(): Policy {
    if (this == null) return Policy()
    val d = Policy()
    return Policy(
        rules = optJSONArray("rules").objects().map { it.toAppRule() },
        blockInstallUninstall = optBoolean("blockInstallUninstall", d.blockInstallUninstall),
        blockSettings = optBoolean("blockSettings", d.blockSettings),
        lockDevice = optBoolean("lockDevice", d.lockDevice),
        bedtimeStart = optString("bedtimeStart"),
        bedtimeEnd = optString("bedtimeEnd"),
    )
}

fun JSONObject.toChildDevice() = ChildDevice(
    id = optString("id"),
    name = optString("name"),
    model = optString("model"),
    lastSeen = optLong("lastSeen"),
    battery = optInt("battery", -1),
    currentApp = optString("currentApp"),
    liveActive = optBoolean("liveActive"),
    lockNowRequest = optLong("lockNowRequest"),
    alert = optString("alert"),
    alertAt = optLong("alertAt"),
    policy = optJSONObject("policy").toPolicy(),
    apps = optJSONArray("apps").objects().map {
        InstalledApp(it.optString("pkg"), it.optString("label"), it.optInt("usageMin"))
    },
    version = optInt("version"),
)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
