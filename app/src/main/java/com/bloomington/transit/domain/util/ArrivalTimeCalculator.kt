package com.bloomington.transit.domain.util

import com.bloomington.transit.data.model.GtfsStopTime
import com.bloomington.transit.data.model.StopTimeUpdate
import java.util.Calendar

object ArrivalTimeCalculator {

    fun formatEta(arrivalUnixSec: Long): String {
        val minutesAway = (arrivalUnixSec - System.currentTimeMillis() / 1000L) / 60L
        return when {
            minutesAway <= 0 -> "Due"
            minutesAway == 1L -> "1 min"
            minutesAway > 90 -> formatTime(arrivalUnixSec)
            else -> "$minutesAway min"
        }
    }

    // Returns absolute Unix seconds for a GTFS stop_time string (HH:MM:SS)
    // anchored to today's date.
    fun stopTimeToUnixSec(timeStr: String): Long {
        val parts = timeStr.split(":").map { it.toIntOrNull() ?: 0 }
        val h = parts.getOrElse(0) { 0 }
        val m = parts.getOrElse(1) { 0 }
        val s = parts.getOrElse(2) { 0 }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (cal.timeInMillis / 1000L) + h * 3600L + m * 60L + s
    }

    // Merge static schedule with realtime delay
    fun resolvedArrivalSec(staticTime: GtfsStopTime, stu: StopTimeUpdate?): Long {
        if (stu != null && stu.arrivalTime > 0) return stu.arrivalTime
        val base = stopTimeToUnixSec(staticTime.arrivalTime)
        val delay = stu?.arrivalDelaySec?.toLong() ?: 0L
        return base + delay
    }

    fun formatTime(unixSec: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = unixSec * 1000L }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val amPm = if (h < 12) "AM" else "PM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$h12:${m.toString().padStart(2, '0')} $amPm"
    }
}
