package com.khallybalak.child

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** رفع بيانات جهاز الابن للسيرفر */
object ChildSync {
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun uploadApps(ctx: Context, usage: UsageTracker) = runCatching {
        if (!ChildApi.isPaired) return@runCatching
        val list = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val today = usage.todayMillis(force = true)
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .map { (pkg, label) -> Triple(pkg, label, ((today[pkg] ?: 0L) / 60_000).toInt()) }
                .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second })
                .map { (pkg, label, min) -> JSONObject().put("pkg", pkg).put("label", label).put("usageMin", min) }
        }
        ChildApi.api.call("apps", JSONObject().put("apps", JSONArray(list)))
    }

    fun alert(ctx: Context, msg: String) {
        ChildApi.init(ctx)
        if (!ChildApi.isPaired) return
        bg.launch { runCatching { ChildApi.api.call("alert", JSONObject().put("alert", msg)) } }
    }

    /**
     * يرفع إطار JPEG مضغوط (~30–60KB).
     * يرجع false لو ولي الأمر خرج من شاشة البث → نوقف التصوير.
     */
    suspend fun pushFrame(bmp: Bitmap): Boolean {
        val bytes = withContext(Dispatchers.Default) {
            val w = 540
            val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 45, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        }
        return runCatching { ChildApi.api.postBytes("frame", bytes, "image/jpeg").optBoolean("live", false) }
            .getOrDefault(true) // خطأ شبكة مؤقت: نكمل المحاولة
    }
}
