package com.khallybalak.child

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * يحسب وقت استخدام كل تطبيق منذ منتصف الليل من أحداث النظام
 * (أدق من queryAndAggregateUsageStats اللي بيلخبط حدود اليوم).
 */
class UsageTracker(ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private var cache: Map<String, Long> = emptyMap()
    private var cacheAt = 0L

    @Synchronized
    fun todayMillis(force: Boolean = false): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (!force && now - cacheAt < 15_000) return cache

        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val totals = HashMap<String, Long>()
        val openSince = HashMap<String, Long>()
        val events = usm.queryEvents(start, now) ?: return cache
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                RESUMED -> openSince[e.packageName] = e.timeStamp
                PAUSED -> openSince.remove(e.packageName)?.let { s ->
                    totals[e.packageName] = (totals[e.packageName] ?: 0L) + (e.timeStamp - s)
                }
            }
        }
        // التطبيق المفتوح حالياً
        openSince.forEach { (p, s) -> totals[p] = (totals[p] ?: 0L) + (now - s) }

        cache = totals; cacheAt = now
        return totals
    }

    fun todayMinutes(pkg: String): Long = (todayMillis()[pkg] ?: 0L) / 60_000

    private companion object {
        // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED = 1 ، MOVE_TO_BACKGROUND / ACTIVITY_PAUSED = 2
        const val RESUMED = 1
        const val PAUSED = 2
    }
}
