package com.khallybalak.child

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.khallybalak.shared.Policy

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        ChildSync.alert(context, "⚠️ محاولة لإلغاء صلاحية مسؤول الجهاز")
        return "إلغاء هذه الصلاحية يوقف حماية ولي الأمر وسيتم إبلاغه."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        ChildSync.alert(context, "⚠️ تم إلغاء صلاحية مسؤول الجهاز")
    }
}

/**
 * - كمسؤول جهاز (Device Admin): قفل الشاشة عن بُعد.
 * - كمالك جهاز (Device Owner عبر adb): منع التثبيت/الحذف على مستوى النظام،
 *   منع إعادة ضبط المصنع والوضع الآمن وتغيير الوقت، ومنع حذف تطبيق الحماية.
 */
class AdminController(private val ctx: Context) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(ctx, AdminReceiver::class.java)

    val isAdmin get() = dpm.isAdminActive(admin)
    val isOwner get() = dpm.isDeviceOwnerApp(ctx.packageName)

    fun lockNow() {
        if (isAdmin) runCatching { dpm.lockNow() }
    }

    fun applyPolicy(p: Policy) {
        if (!isOwner) return
        runCatching {
            restrict(UserManager.DISALLOW_INSTALL_APPS, p.blockInstallUninstall)
            restrict(UserManager.DISALLOW_UNINSTALL_APPS, p.blockInstallUninstall)
            restrict(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true)
            restrict(UserManager.DISALLOW_FACTORY_RESET, true)
            restrict(UserManager.DISALLOW_SAFE_BOOT, true)
            restrict(UserManager.DISALLOW_CONFIG_DATE_TIME, true) // منع التحايل بتغيير الساعة
            dpm.setUninstallBlocked(admin, ctx.packageName, true)
        }
    }

    /** عند فك الربط: إزالة كل القيود */
    fun releaseAll() {
        if (!isOwner) return
        runCatching {
            listOf(
                UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_CONFIG_DATE_TIME,
            ).forEach { dpm.clearUserRestriction(admin, it) }
            dpm.setUninstallBlocked(admin, ctx.packageName, false)
        }
    }

    private fun restrict(key: String, on: Boolean) {
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }
}
