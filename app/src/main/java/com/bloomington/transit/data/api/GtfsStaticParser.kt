package com.bloomington.transit.data.api

import android.content.Context
import android.util.Log
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipInputStream

class GtfsStaticParser(private val context: Context) {

    companion object {
        private const val TAG = "GtfsStaticParser"
        private const val GTFS_DIR = "gtfs_static"
    }

    suspend fun loadFromNetwork(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = NetworkModule.staticService.downloadGtfsZip(NetworkModule.GTFS_STATIC_URL)
            val bytes = body.bytes()
            extractZip(bytes)
            parseAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GTFS static", e)
            false
        }
    }

    suspend fun loadFromCache(): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, GTFS_DIR)
        if (!dir.exists() || dir.listFiles()?.isEmpty() == true) return@withContext false
        try {
            parseAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached GTFS", e)
            false
        }
    }

    private fun extractZip(bytes: ByteArray) {
        val dir = File(context.filesDir, GTFS_DIR).also { it.mkdirs() }
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(dir, entry.name)
                FileOutputStream(file).use { out -> zip.copyTo(out) }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun parseAll() {
        val dir = File(context.filesDir, GTFS_DIR)
        GtfsStaticCache.routes = parseRoutes(File(dir, "routes.txt"))
        GtfsStaticCache.stops = parseStops(File(dir, "stops.txt"))
        GtfsStaticCache.calendars = parseCalendars(File(dir, "calendar.txt"))
        GtfsStaticCache.shapes = parseShapes(File(dir, "shapes.txt"))
        val validRouteIds = GtfsStaticCache.routes.keys
        val allTrips = parseTrips(File(dir, "trips.txt"))
        // Filter trips to only those belonging to valid (non-excluded) routes
        val trips = allTrips.filter { (_, trip) -> trip.routeId in validRouteIds }
        GtfsStaticCache.trips = trips
        GtfsStaticCache.tripsByRoute = trips.values
            .groupBy { it.routeId }
            .mapValues { e -> e.value.map { it.tripId } }
        val validTripIds = trips.keys
        val allStopTimes = parseStopTimes(File(dir, "stop_times.txt"))
        // Filter stop times to only those belonging to valid trips
        val stopTimes = allStopTimes.filter { it.tripId in validTripIds }
        GtfsStaticCache.stopTimesByTrip = stopTimes.groupBy { it.tripId }
            .mapValues { e -> e.value.sortedBy { it.stopSequence } }
        GtfsStaticCache.stopTimesByStop = stopTimes.groupBy { it.stopId }
        GtfsStaticCache.markLoaded()
        Log.i(TAG, "GTFS loaded: ${GtfsStaticCache.routes.size} routes, ${GtfsStaticCache.stops.size} stops")
    }

    private fun parseRoutes(file: File): Map<String, GtfsRoute> {
        if (!file.exists()) return emptyMap()
        val result = mutableMapOf<String, GtfsRoute>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyMap()
            val idxId = headers.indexOf("route_id")
            val idxShort = headers.indexOf("route_short_name")
            val idxLong = headers.indexOf("route_long_name")
            val idxColor = headers.indexOf("route_color")
            val idxText = headers.indexOf("route_text_color")
            val excludedShortNames = setOf("12", "13", "14")
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                val id = cols.getOrElse(idxId) { "" }
                val shortName = cols.getOrElse(idxShort) { "" }
                if (id.isNotEmpty() && shortName !in excludedShortNames) {
                    result[id] = GtfsRoute(
                        routeId = id,
                        shortName = shortName,
                        longName = cols.getOrElse(idxLong) { "" },
                        color = cols.getOrElse(idxColor) { "1565C0" }.ifEmpty { "1565C0" },
                        textColor = cols.getOrElse(idxText) { "FFFFFF" }.ifEmpty { "FFFFFF" }
                    )
                }
            }
        }
        return result
    }

    private fun parseStops(file: File): Map<String, GtfsStop> {
        if (!file.exists()) return emptyMap()
        val result = mutableMapOf<String, GtfsStop>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyMap()
            val idxId = headers.indexOf("stop_id")
            val idxName = headers.indexOf("stop_name")
            val idxLat = headers.indexOf("stop_lat")
            val idxLon = headers.indexOf("stop_lon")
            val idxCode = headers.indexOf("stop_code")
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                val id = cols.getOrElse(idxId) { "" }
                val lat = cols.getOrElse(idxLat) { "0" }.toDoubleOrNull() ?: return@forEach
                val lon = cols.getOrElse(idxLon) { "0" }.toDoubleOrNull() ?: return@forEach
                if (id.isNotEmpty()) {
                    result[id] = GtfsStop(
                        stopId = id,
                        name = cols.getOrElse(idxName) { "" },
                        lat = lat,
                        lon = lon,
                        code = if (idxCode >= 0) cols.getOrElse(idxCode) { "" } else ""
                    )
                }
            }
        }
        return result
    }

    private fun parseTrips(file: File): Map<String, GtfsTrip> {
        if (!file.exists()) return emptyMap()
        val result = mutableMapOf<String, GtfsTrip>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyMap()
            val idxRoute = headers.indexOf("route_id")
            val idxService = headers.indexOf("service_id")
            val idxTrip = headers.indexOf("trip_id")
            val idxHead = headers.indexOf("trip_headsign")
            val idxShape = headers.indexOf("shape_id")
            val idxDir = headers.indexOf("direction_id")
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                val id = cols.getOrElse(idxTrip) { "" }
                if (id.isNotEmpty()) {
                    result[id] = GtfsTrip(
                        routeId = cols.getOrElse(idxRoute) { "" },
                        serviceId = cols.getOrElse(idxService) { "" },
                        tripId = id,
                        headsign = if (idxHead >= 0) cols.getOrElse(idxHead) { "" } else "",
                        shapeId = if (idxShape >= 0) cols.getOrElse(idxShape) { "" } else "",
                        directionId = if (idxDir >= 0) cols.getOrElse(idxDir) { "0" }.toIntOrNull() ?: 0 else 0
                    )
                }
            }
        }
        return result
    }

    private fun parseStopTimes(file: File): List<GtfsStopTime> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<GtfsStopTime>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyList()
            val idxTrip = headers.indexOf("trip_id")
            val idxArr = headers.indexOf("arrival_time")
            val idxDep = headers.indexOf("departure_time")
            val idxStop = headers.indexOf("stop_id")
            val idxSeq = headers.indexOf("stop_sequence")
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                result.add(
                    GtfsStopTime(
                        tripId = cols.getOrElse(idxTrip) { "" },
                        arrivalTime = cols.getOrElse(idxArr) { "" },
                        departureTime = cols.getOrElse(idxDep) { "" },
                        stopId = cols.getOrElse(idxStop) { "" },
                        stopSequence = cols.getOrElse(idxSeq) { "0" }.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return result
    }

    private fun parseShapes(file: File): Map<String, List<GtfsShape>> {
        if (!file.exists()) return emptyMap()
        val all = mutableListOf<GtfsShape>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyMap()
            val idxId = headers.indexOf("shape_id")
            val idxLat = headers.indexOf("shape_pt_lat")
            val idxLon = headers.indexOf("shape_pt_lon")
            val idxSeq = headers.indexOf("shape_pt_sequence")
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                val lat = cols.getOrElse(idxLat) { "0" }.toDoubleOrNull() ?: return@forEach
                val lon = cols.getOrElse(idxLon) { "0" }.toDoubleOrNull() ?: return@forEach
                all.add(
                    GtfsShape(
                        shapeId = cols.getOrElse(idxId) { "" },
                        lat = lat,
                        lon = lon,
                        sequence = cols.getOrElse(idxSeq) { "0" }.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return all.groupBy { it.shapeId }
            .mapValues { e -> e.value.sortedBy { it.sequence } }
    }

    private fun parseCalendars(file: File): Map<String, GtfsCalendar> {
        if (!file.exists()) return emptyMap()
        val result = mutableMapOf<String, GtfsCalendar>()
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.split(",")?.map { it.trim() } ?: return emptyMap()
            val idxSvc = headers.indexOf("service_id")
            fun h(name: String) = headers.indexOf(name)
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val cols = parseCsvLine(line)
                val svcId = cols.getOrElse(idxSvc) { "" }
                if (svcId.isNotEmpty()) {
                    result[svcId] = GtfsCalendar(
                        serviceId = svcId,
                        monday = cols.getOrElse(h("monday")) { "0" } == "1",
                        tuesday = cols.getOrElse(h("tuesday")) { "0" } == "1",
                        wednesday = cols.getOrElse(h("wednesday")) { "0" } == "1",
                        thursday = cols.getOrElse(h("thursday")) { "0" } == "1",
                        friday = cols.getOrElse(h("friday")) { "0" } == "1",
                        saturday = cols.getOrElse(h("saturday")) { "0" } == "1",
                        sunday = cols.getOrElse(h("sunday")) { "0" } == "1",
                        startDate = cols.getOrElse(h("start_date")) { "" },
                        endDate = cols.getOrElse(h("end_date")) { "" }
                    )
                }
            }
        }
        return result
    }

    // Simple CSV parser that handles quoted fields
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString().trim())
        return result
    }
}
