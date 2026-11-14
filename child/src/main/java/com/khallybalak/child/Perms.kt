package com.khallybalak.child

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class Perm(val title: String, val desc: String, val granted: Boolean, val intent: Intent)

object Perms {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("khally", Context.MODE_PRIVATE)

    fun status(ctx: Context): List<Perm> = buildList {
        val pkg = ctx.packageName
        add(
            Perm(
                "الوصول لبيانات الاستخدام", "لحساب وقت استخدام كل تطبيق",
                hasUsageAccess(ctx), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
        )
        add(
            Perm(
                "مسؤول الجهاز", "لقفل الشاشة عن بُعد وحماية التطبيق من الحذف",
                AdminController(ctx).isAdmin,
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لحماية الطفل بواسطة ولي الأمر"),
            )
        )
        if (Build.VERSION.SDK_INT >= 33) add(
            Perm(
                "الإشعارات", "لإظهار إشعار أثناء مشاهدة ولي الأمر للشاشة",
                NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            )
        )
        add(
            Perm(
                "إيقاف توفير البطارية", "حتى لا يوقف النظام الحماية في الخلفية",
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg),
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            )
        )
        add(
            Perm(
                "خدمة الحماية (إمكانية الوصول)",
                "فعّل \"خلي بالك\" من قائمة إمكانية الوصول. لو ظهرت رسالة (إعداد مقيّد): افتح معلومات التطبيق ← ⋮ ← السماح بالإعدادات المقيدة.",
                isAccessibilityOn(ctx), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
        )
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isAccessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, GuardAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * مهلة دقيقتين لفتح الإعدادات أثناء الإعداد فقط.
     * بعد اكتمال كل الصلاحيات لا توجد مهلة، فلا يمكن للابن استغلالها.
     */
    fun startGrace(ctx: Context) {
        if (status(ctx).all { it.granted }) return
        prefs(ctx).edit().putLong("graceUntil", System.currentTimeMillis() + 120_000).apply()
    }

    fun inGrace(ctx: Context) = System.currentTimeMillis() < prefs(ctx).getLong("graceUntil", 0)
}
