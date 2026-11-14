package com.khallybalak.parent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.khallybalak.shared.Api
import com.khallybalak.shared.ChildDevice
import com.khallybalak.shared.Policy
import com.khallybalak.shared.objects
import com.khallybalak.shared.toChildDevice
import com.khallybalak.shared.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class LiveFrame(val bitmap: Bitmap, val receivedAt: Long)

/** جلسة ولي الأمر (التوكن محفوظ على الموبايل) */
object Session {
    private lateinit var prefs: SharedPreferences
    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE) }
    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
}

object ParentRepo {
    private val api = Api { Session.token }
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** أي تعديل يعمل refresh فوري للشاشات بدل انتظار الدورة الجاية */
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun login(email: String, pass: String) = auth("login", email, pass)
    suspend fun register(email: String, pass: String) = auth("register", email, pass)

    private suspend fun auth(action: String, email: String, pass: String) {
        val j = api.call(action, JSONObject().put("email", email).put("password", pass))
        Session.token = j.getString("token")
    }

    fun logout() {
        bg.launch { runCatching { api.call("logout", JSONObject()) }; Session.token = null }
    }

    /** تحديث تلقائي كل 5 ثواني */
    fun childrenFlow(): Flow<List<ChildDevice>> = flow {
        while (true) {
            runCatching { api.call("children").optJSONArray("children").objects().map { it.toChildDevice() } }
                .getOrNull()?.let { emit(it) }
            withTimeoutOrNull(5_000) { refresh.first() }
        }
    }

    /** تحديث تلقائي كل 3 ثواني أو فوراً بعد أي تعديل */
    fun childFlow(id: String): Flow<ChildDevice?> = flow {
        while (true) {
            runCatching { api.call("child", query = mapOf("id" to id)).getJSONObject("child").toChildDevice() }
                .getOrNull()?.let { emit(it) }
            withTimeoutOrNull(3_000) { refresh.first() }
        }
    }

    suspend fun createPairCode(childName: String): String =
        api.call("create_code", JSONObject().put("name", childName)).getString("code")

    suspend fun updatePolicy(id: String, policy: Policy) {
        api.call("update_policy", JSONObject().put("policy", policy.toJson()), mapOf("id" to id))
        refresh.tryEmit(Unit)
    }

    suspend fun lockNow(id: String) { api.call("lock_now", JSONObject(), mapOf("id" to id)) }

    suspend fun clearAlert(id: String) {
        api.call("clear_alert", JSONObject(), mapOf("id" to id)); refresh.tryEmit(Unit)
    }

    suspend fun removeChild(id: String) { api.call("remove_child", JSONObject(), mapOf("id" to id)) }

    fun stopLive(id: String) {
        bg.launch { runCatching { api.call("stop_live", JSONObject(), mapOf("id" to id)) } }
    }

    /** سحب آخر إطار كل ~0.7 ثانية (السيرفر بيشغّل البث تلقائياً طول ما إحنا بنسحب) */
    fun liveFrames(id: String): Flow<LiveFrame> = flow {
        var since = 0L
        while (true) {
            val j = runCatching { api.call("live_frame", query = mapOf("id" to id, "since" to since)) }.getOrNull()
            val data = j?.optString("frame").orEmpty()
            if (j != null && data.isNotEmpty()) {
                since = j.optLong("ts")
                val bmp = withContext(Dispatchers.Default) {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) emit(LiveFrame(bmp, System.currentTimeMillis()))
            }
            delay(700)
        }
    }
}
