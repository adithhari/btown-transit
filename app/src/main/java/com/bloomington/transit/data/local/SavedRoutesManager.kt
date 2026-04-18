package com.bloomington.transit.data.local

import android.content.Context
import android.content.SharedPreferences
import com.bloomington.transit.domain.usecase.JourneyPlan
import com.bloomington.transit.domain.usecase.RouteLeg
import org.json.JSONArray
import org.json.JSONObject

data class SavedRoute(
    val id: String,
    val originName: String,
    val destName: String,
    val durationMin: Int,
    val transferCount: Int,
    val departureStr: String,
    val arrivalStr: String,
    val routeSummary: String   // e.g. "6 → 12"
)

class SavedRoutesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("saved_routes", Context.MODE_PRIVATE)

    fun save(originName: String, destName: String, journey: JourneyPlan): String {
        val id = "${System.currentTimeMillis()}"
        val routeNames = journey.legs.joinToString(" → ") { it.routeShortName }
        val obj = JSONObject().apply {
            put("id", id)
            put("originName", originName)
            put("destName", destName)
            put("durationMin", journey.totalDurationMin)
            put("transferCount", journey.transferCount)
            put("departureStr", journey.departureStr)
            put("arrivalStr", journey.arrivalStr)
            put("routeSummary", routeNames)
        }
        val all = loadRaw()
        all.put(obj)
        prefs.edit().putString("routes", all.toString()).apply()
        return id
    }

    fun load(): List<SavedRoute> {
        return (0 until loadRaw().length()).mapNotNull { i ->
            try {
                val o = loadRaw().getJSONObject(i)
                SavedRoute(
                    id = o.getString("id"),
                    originName = o.getString("originName"),
                    destName = o.getString("destName"),
                    durationMin = o.getInt("durationMin"),
                    transferCount = o.getInt("transferCount"),
                    departureStr = o.getString("departureStr"),
                    arrivalStr = o.getString("arrivalStr"),
                    routeSummary = o.getString("routeSummary")
                )
            } catch (_: Exception) { null }
        }.reversed()   // newest first
    }

    fun delete(id: String) {
        val all = loadRaw()
        val updated = JSONArray()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.getString("id") != id) updated.put(o)
        }
        prefs.edit().putString("routes", updated.toString()).apply()
    }

    fun isSaved(originName: String, destName: String, departureStr: String): Boolean {
        val all = loadRaw()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.getString("originName") == originName &&
                o.getString("destName") == destName &&
                o.getString("departureStr") == departureStr) return true
        }
        return false
    }

    private fun loadRaw(): JSONArray {
        val str = prefs.getString("routes", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }
}
