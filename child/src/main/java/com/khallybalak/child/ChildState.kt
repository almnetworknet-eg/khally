package com.khallybalak.child

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.khallybalak.shared.Api
import com.khallybalak.shared.ApiException
import com.khallybalak.shared.ChildDevice
import com.khallybalak.shared.ServerConfig
import com.khallybalak.shared.toChildDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** توكن الجهاز ومعرّفه محفوظين محلياً */
object ChildApi {
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("khally", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        ServerConfig.init(ctx)
    }

    val token: String? get() = prefs.getString("token", null)
    val isPaired get() = token != null
    val api = Api { token }

    fun save(token: String) = prefs.edit().putString("token", token).apply()
    fun clear() = prefs.edit().remove("token").apply()
}

/**
 * حالة الجهاز كما يحددها ولي الأمر.
 * Long-poll: السيرفر بيرد فوراً لما ولي الأمر يغيّر حاجة، وإلا بعد 20 ثانية.
 */
object ChildState {
    private val _device = MutableStateFlow(ChildDevice())
    val device: StateFlow<ChildDevice> = _device
    /** true بعد أول رد فعلي من السيرفر */
    val loaded = MutableStateFlow(false)

    @Volatile var currentApp = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(ctx: Context) {
        ChildApi.init(ctx)
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            var since = 0
            while (isActive) {
                if (!ChildApi.isPaired) { delay(3_000); continue }
                try {
                    val j = ChildApi.api.call(
                        "state",
                        query = mapOf("since" to since, "current_app" to currentApp, "battery" to battery(app)),
                        timeoutMs = 40_000,
                    )
                    val d = j.getJSONObject("child").toChildDevice()
                    since = d.version
                    _device.value = d
                    loaded.value = true
                } catch (e: ApiException) {
                    if (e.code == 401) { // ولي الأمر فك الربط
                        ChildApi.clear(); since = 0
                        _device.value = ChildDevice(); loaded.value = true
                    }
                    delay(5_000)
                } catch (e: Exception) {
                    delay(5_000) // مفيش نت — القواعد الأخيرة تفضل شغالة
                }
            }
        }
    }

    /** بعد الربط مباشرة: نلغي أي انتظار ونبدأ من جديد */
    fun restart(ctx: Context) { job?.cancel(); job = null; start(ctx) }

    private fun battery(ctx: Context): Int =
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

object Pairing {
    fun isPaired(ctx: Context): Boolean { ChildApi.init(ctx); return ChildApi.isPaired }

    suspend fun pair(ctx: Context, server: String, code: String) {
        ChildApi.init(ctx)
        ServerConfig.verifyAndSave(server)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar { it.uppercase() }
        val j = ChildApi.api.call("pair", JSONObject().put("code", code.trim().uppercase()).put("model", model))
        ChildApi.save(j.getString("token"))
        ChildState.restart(ctx)
    }

    fun unpairLocal(ctx: Context) { ChildApi.init(ctx); ChildApi.clear() }
}
