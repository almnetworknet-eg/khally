package com.khallybalak.shared

/** قاعدة لتطبيق واحد على جهاز الابن */
data class AppRule(
    val pkg: String = "",
    val blocked: Boolean = false,
    /** الحد اليومي بالدقائق، 0 = بلا حد */
    val dailyLimitMin: Int = 0,
    /** نافذة السماح "HH:mm" — فارغة = طوال اليوم */
    val windowStart: String = "",
    val windowEnd: String = "",
)

/** سياسة الحماية التي يحددها ولي الأمر */
data class Policy(
    val rules: List<AppRule> = emptyList(),
    val blockInstallUninstall: Boolean = true,
    val blockSettings: Boolean = true,
    val lockDevice: Boolean = false,
    val bedtimeStart: String = "",
    val bedtimeEnd: String = "",
)

data class InstalledApp(
    val pkg: String = "",
    val label: String = "",
    val usageMin: Int = 0,
)

/** بيانات جهاز الابن كما يرجعها السيرفر — id فارغ = الجهاز غير مربوط */
data class ChildDevice(
    val id: String = "",
    val name: String = "",
    val model: String = "",
    val lastSeen: Long = 0,
    val battery: Int = -1,
    val currentApp: String = "",
    val liveActive: Boolean = false,
    val lockNowRequest: Long = 0,
    val alert: String = "",
    val alertAt: Long = 0,
    val policy: Policy = Policy(),
    val apps: List<InstalledApp> = emptyList(),
    val version: Int = 0,
)

object TimeWindow {
    /** يحوّل "HH:mm" إلى دقائق منذ منتصف الليل */
    fun parse(t: String): Int? {
        val parts = t.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    /** هل الدقيقة الحالية داخل الفترة؟ (تدعم العبور بعد منتصف الليل مثل 22:00 → 07:00) */
    fun isInside(start: String, end: String, nowMinutes: Int): Boolean {
        val s = parse(start) ?: return false
        val e = parse(end) ?: return false
        return if (s <= e) nowMinutes in s until e else nowMinutes >= s || nowMinutes < e
    }

    fun isValidRange(start: String, end: String): Boolean =
        (start.isBlank() && end.isBlank()) || (parse(start) != null && parse(end) != null)
}
