package com.khallybalak.child

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.khallybalak.shared.ChildDevice
import com.khallybalak.shared.TimeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.coroutines.resume

/**
 * قلب الحماية:
 *  • يراقب التطبيق المفتوح ويقفله حسب قواعد ولي الأمر (حظر / حد يومي / فترة مسموحة / وقت نوم / قفل كامل)
 *  • يقفل الإعدادات ومتجر Play وشاشات التثبيت والحذف
 *  • يحمي نفسه من التعطيل من الإعدادات
 *  • يلتقط الشاشة لايف عند طلب ولي الأمر (أندرويد 11+) مع إشعار ظاهر للابن
 */
class GuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var usage: UsageTracker
    private lateinit var admin: AdminController
    private var currentPkg = ""
    private var lastBlockAt = 0L
    private var lastContentCheck = 0L
    private var lastLockHandled = -1L
    private var liveJob: Job? = null
    private var imePackages: Set<String> = emptySet()
    private var wasPaired = false

    override fun onServiceConnected() {
        usage = UsageTracker(this)
        admin = AdminController(this)
        imePackages = getSystemService(InputMethodManager::class.java)
            .inputMethodList.map { it.packageName }.toSet()

        ChildState.start(this)
        scope.launch { ChildState.device.collect { onDeviceChanged(it) } }
        // إعادة الفحص دورياً (لو الحد اليومي خلص والتطبيق مفتوح)
        scope.launch { while (isActive) { delay(10_000); enforce(currentPkg) } }
        scope.launch { while (isActive) { ChildSync.uploadApps(this@GuardAccessibilityService, usage); delay(5 * 60_000) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg in imePackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPkg = pkg
                ChildState.currentApp = pkg
                enforce(pkg)
            }
            // التنقل داخل الإعدادات لا يغيّر النافذة دائماً، فنفحص المحتوى (بحد أقصى مرة كل ثانية)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isSettings(pkg)) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastContentCheck > 1000) { lastContentCheck = now; enforce(pkg) }
                }
            }
        }
    }

    private fun onDeviceChanged(d: ChildDevice) {
        val paired = d.id.isNotEmpty()
        if (wasPaired && !paired) admin.releaseAll()
        wasPaired = paired
        if (!paired) { stopLive(); return }

        admin.applyPolicy(d.policy)

        // قفل الشاشة عن بُعد (أول قراءة بعد التشغيل بنعتبرها قديمة)
        if (lastLockHandled < 0) lastLockHandled = d.lockNowRequest
        else if (d.lockNowRequest > lastLockHandled) {
            lastLockHandled = d.lockNowRequest
            admin.lockNow()
        }

        if (d.liveActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startLive() else stopLive()
        enforce(currentPkg)
    }

    // ───────────────────────── قرار القفل ─────────────────────────

    private fun enforce(pkg: String) {
        if (pkg.isEmpty()) return
        val reason = reasonToBlock(pkg) ?: return
        block(reason.first, goHome = !reason.second)
    }

    /** يرجع (السبب، هل هو قفل كامل للجهاز) */
    private fun reasonToBlock(pkg: String): Pair<String, Boolean>? =
        fullLockReason(pkg)?.let { it to true } ?: appBlockReason(pkg)?.let { it to false }

    private fun fullLockReason(pkg: String): String? {
        if (pkg == packageName || pkg in ALWAYS_ALLOWED) return null
        val d = ChildState.device.value
        if (d.id.isEmpty()) return null
        val p = d.policy
        if (p.lockDevice) return "الجهاز مقفول من ولي الأمر 🔒"
        if (p.bedtimeStart.isNotEmpty() && TimeWindow.isInside(p.bedtimeStart, p.bedtimeEnd, nowMinutes()))
            return "وقت النوم 🌙\nالجهاز هيفتح الساعة ${p.bedtimeEnd}"
        return null
    }

    private fun nowMinutes() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun appBlockReason(pkg: String): String? {
        if (pkg == packageName || pkg in ALWAYS_ALLOWED) return null
        val d = ChildState.device.value
        if (d.id.isEmpty()) return null
        val p = d.policy
        val nowMin = nowMinutes()

        if (isSettings(pkg) && !Perms.inGrace(this)) {
            if (p.blockSettings) return "الإعدادات مقفولة من ولي الأمر"
            if (settingsTouchOurApp()) return "غير مسموح بتعديل إعدادات الحماية"
        }
        if (p.blockInstallUninstall && pkg in INSTALLERS) return "تثبيت وحذف التطبيقات ممنوع"

        val rule = p.rules.find { it.pkg == pkg } ?: return null
        if (rule.blocked) return "التطبيق ده محظور من ولي الأمر"
        if (rule.windowStart.isNotEmpty() && !TimeWindow.isInside(rule.windowStart, rule.windowEnd, nowMin))
            return "التطبيق ده مسموح بس من ${rule.windowStart} لحد ${rule.windowEnd}"
        if (rule.dailyLimitMin > 0 && usage.todayMinutes(pkg) >= rule.dailyLimitMin)
            return "خلص وقتك النهارده ⏰\n(${rule.dailyLimitMin} دقيقة يومياً)"
        return null
    }

    private fun isSettings(pkg: String) =
        pkg == "com.android.settings" || pkg in EXTRA_SETTINGS || pkg.endsWith(".settings")

    /** هل شاشة الإعدادات الحالية تخص تطبيقنا (إمكانية الوصول / معلومات التطبيق / مسؤول الجهاز)؟ */
    private fun settingsTouchOurApp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val label = getString(R.string.app_name)
        return root.findAccessibilityNodeInfosByText(label).isNotEmpty()
    }

    /** في القفل الكامل لا نضغط Home حتى لا تتكرر الشاشة مع الـ Launcher */
    private fun block(reason: String, goHome: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 800) return
        lastBlockAt = now
        if (goHome) performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_REASON, reason)
        )
    }

    // ───────────────────────── الشاشة لايف ─────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startLive() {
        if (liveJob != null) return
        showLiveNotification(true)
        liveJob = scope.launch {
            while (isActive) {
                val started = SystemClock.elapsedRealtime()
                val stillWatching = captureScreen()?.let { ChildSync.pushFrame(it) } ?: true
                if (!stillWatching) break // ولي الأمر خرج من شاشة البث
                // ~إطار كل 1.2 ثانية — مناسب للاستضافة المشتركة
                delay((1200 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(400))
            }
            liveJob = null
            showLiveNotification(false)
        }
    }

    private fun stopLive() {
        liveJob?.cancel(); liveJob = null
        showLiveNotification(false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); buffer.close()
                    if (cont.isActive) cont.resume(soft)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
    }

    /** إشعار واضح للابن أثناء المشاهدة — شفافية */
    private fun showLiveNotification(show: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!show) { nm.cancel(LIVE_NOTIF_ID); return }
        nm.createNotificationChannel(
            NotificationChannel("live", "مشاهدة الشاشة", NotificationManager.IMPORTANCE_LOW)
        )
        nm.notify(
            LIVE_NOTIF_ID,
            NotificationCompat.Builder(this, "live")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ولي الأمر يشاهد الشاشة الآن")
                .setOngoing(true)
                .build()
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        ChildSync.alert(this, "⚠️ تم إيقاف خدمة الحماية على الجهاز")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopLive()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LIVE_NOTIF_ID = 7

        /** مسموح دائماً حتى في القفل الكامل: الاتصال والطوارئ */
        val ALWAYS_ALLOWED = setOf(
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.android.incallui",
        )

        /** المتاجر ومثبتات الحزم (التثبيت والحذف) */
        val INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
            "com.sec.android.app.samsungapps", "com.huawei.appmarket",
            "com.xiaomi.market", "com.heytap.market", "com.oppo.market",
        )

        val EXTRA_SETTINGS = setOf(
            "com.miui.securitycenter", "com.coloros.safecenter",
            "com.samsung.android.lool", "com.huawei.systemmanager",
        )
    }
}
