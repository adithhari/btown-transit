package com.bloomington.transit.presentation.planner

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.BuildConfig
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.SavedRoutesManager
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.domain.usecase.JourneyPlan
import com.bloomington.transit.domain.usecase.PlanTripUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class PlacePrediction(val description: String, val placeId: String)

enum class TimeMode { LEAVE_AT, ARRIVE_BY }

data class PlannerUiState(
    val journeys: List<JourneyPlan> = emptyList(),
    val isSearching: Boolean = false,
    val noResults: Boolean = false,
    val statusMsg: String = "",
    val timeMode: TimeMode = TimeMode.LEAVE_AT,
    /** Seconds since midnight. -1 means "now" (no explicit selection). */
    val timeSec: Long = -1L,
    val timeLabel: String = "Now"
)

class TripPlannerViewModel(app: Application) : AndroidViewModel(app) {

    private val planTrip = PlanTripUseCase()
    private val http = OkHttpClient()
    private val savedRoutesManager = SavedRoutesManager(app)

    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState

    private val _originPredictions = MutableStateFlow<List<PlacePrediction>>(emptyList())
    val originPredictions: StateFlow<List<PlacePrediction>> = _originPredictions

    private val _destPredictions = MutableStateFlow<List<PlacePrediction>>(emptyList())
    val destPredictions: StateFlow<List<PlacePrediction>> = _destPredictions

    private var originPlaceId = ""
    private var destPlaceId = ""
    private var originDescription = ""
    private var destDescription = ""

    private var originDebounce: Job? = null
    private var destDebounce: Job? = null

    fun fetchOriginPredictions(text: String) {
        originPlaceId = ""
        originDebounce?.cancel()
        if (text.length < 2) { _originPredictions.value = emptyList(); return }
        originDebounce = viewModelScope.launch {
            delay(300)
            _originPredictions.value = autocomplete(text)
        }
    }

    fun fetchDestPredictions(text: String) {
        destPlaceId = ""
        destDebounce?.cancel()
        if (text.length < 2) { _destPredictions.value = emptyList(); return }
        destDebounce = viewModelScope.launch {
            delay(300)
            _destPredictions.value = autocomplete(text)
        }
    }

    fun selectOrigin(prediction: PlacePrediction) {
        originPlaceId = prediction.placeId
        originDescription = prediction.description
        _originPredictions.value = emptyList()
    }

    fun selectDest(prediction: PlacePrediction) {
        destPlaceId = prediction.placeId
        destDescription = prediction.description
        _destPredictions.value = emptyList()
    }

    /** Returns the swapped descriptions so the Fragment can update the text fields. */
    fun swapOriginDest(): Pair<String, String> {
        val tmpId = originPlaceId; originPlaceId = destPlaceId; destPlaceId = tmpId
        val tmpDesc = originDescription; originDescription = destDescription; destDescription = tmpDesc
        _uiState.value = _uiState.value.copy(journeys = emptyList(), noResults = false, statusMsg = "")
        return Pair(originDescription, destDescription)
    }

    fun saveJourney(journey: JourneyPlan, originName: String, destName: String): Boolean {
        if (savedRoutesManager.isSaved(originName, destName, journey.departureStr)) return false
        savedRoutesManager.save(originName, destName, journey)
        return true
    }

    fun setTimeMode(mode: TimeMode) {
        _uiState.value = _uiState.value.copy(timeMode = mode)
    }

    fun setTime(hour: Int, minute: Int) {
        val sec = hour * 3600L + minute * 60L
        val amPm = if (hour < 12) "AM" else "PM"
        val h12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val label = "$h12:${minute.toString().padStart(2, '0')} $amPm"
        _uiState.value = _uiState.value.copy(timeSec = sec, timeLabel = label)
    }

    fun clearTime() {
        _uiState.value = _uiState.value.copy(timeSec = -1L, timeLabel = "Now")
    }

    fun search() {
        if (originPlaceId.isEmpty() || destPlaceId.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                noResults = true,
                statusMsg = "Please select origin and destination from the dropdown suggestions."
            )
            return
        }
        val currentState = _uiState.value
        viewModelScope.launch {
            _uiState.value = currentState.copy(isSearching = true, statusMsg = "Locating places…", journeys = emptyList(), noResults = false)

            if (!GtfsStaticCache.loaded.value) {
                _uiState.value = _uiState.value.copy(statusMsg = "Loading transit data, please wait…")
                GtfsStaticCache.loaded.first { it }
            }

            val originCoord = getPlaceCoords(originPlaceId)
            val destCoord = getPlaceCoords(destPlaceId)

            if (originCoord == null || destCoord == null) {
                _uiState.value = _uiState.value.copy(isSearching = false, noResults = true, statusMsg = "Could not resolve location.")
                return@launch
            }

            val originStops = nearestStops(originCoord.first, originCoord.second, 5)
            val destStops = nearestStops(destCoord.first, destCoord.second, 5)

            if (originStops.isEmpty() || destStops.isEmpty()) {
                _uiState.value = _uiState.value.copy(isSearching = false, noResults = true, statusMsg = "")
                return@launch
            }

            val mode = _uiState.value.timeMode
            val explicitSec = _uiState.value.timeSec
            val departAtSec = if (mode == TimeMode.LEAVE_AT && explicitSec >= 0) explicitSec else null
            val arriveByAtSec = if (mode == TimeMode.ARRIVE_BY && explicitSec >= 0) explicitSec else null

            // Try all combinations of nearby origin/dest stops; keep the best result
            data class SearchResult(val journeys: List<JourneyPlan>, val originStop: com.bloomington.transit.data.model.GtfsStop, val destStop: com.bloomington.transit.data.model.GtfsStop)
            val best = withContext(Dispatchers.Default) {
                var bestResult: SearchResult? = null
                outer@ for (oStop in originStops) {
                    for (dStop in destStops) {
                        if (oStop.stopId == dStop.stopId) continue
                        val journeys = planTrip(oStop.stopId, dStop.stopId, departAtSec, arriveByAtSec)
                        if (journeys.isNotEmpty()) {
                            val candidate = SearchResult(journeys, oStop, dStop)
                            if (bestResult == null ||
                                journeys.first().totalDurationMin < bestResult!!.journeys.first().totalDurationMin) {
                                bestResult = candidate
                            }
                            // Stop early if we already have a direct (no-transfer) route from the nearest stops
                            if (bestResult!!.journeys.first().transferCount == 0) break@outer
                        }
                    }
                }
                bestResult
            }

            val modeLabel = when {
                mode == TimeMode.ARRIVE_BY && explicitSec >= 0 -> "Arrive by ${_uiState.value.timeLabel}"
                mode == TimeMode.LEAVE_AT && explicitSec >= 0 -> "Leaving at ${_uiState.value.timeLabel}"
                else -> ""
            }
            _uiState.value = _uiState.value.copy(
                journeys = best?.journeys ?: emptyList(),
                isSearching = false,
                noResults = best == null,
                statusMsg = if (best != null)
                    "From: ${best.originStop.name}  →  To: ${best.destStop.name}" +
                    (if (modeLabel.isNotEmpty()) "  ($modeLabel)" else "")
                else if (mode == TimeMode.ARRIVE_BY && explicitSec >= 0)
                    "No routes arrive by ${_uiState.value.timeLabel}"
                else ""
            )
        }
    }

    private suspend fun autocomplete(text: String): List<PlacePrediction> {
        // 1. Always search GTFS stops locally — instant and always works
        val gtfsMatches = withContext(Dispatchers.Default) {
            GtfsStaticCache.stops.values
                .filter { it.name.contains(text, ignoreCase = true) }
                .sortedBy { it.name }
                .take(6)
                .map { stop ->
                    val label = if (stop.code.isNotEmpty()) "${stop.name} (Stop ${stop.code})"
                                else stop.name
                    PlacePrediction(label, "gtfs_stop:${stop.stopId}")
                }
        }

        // 2. Also query Places API for addresses/POIs — silently skip if unavailable
        val placesResults = withContext(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode(text, "UTF-8")
                val url = "https://maps.googleapis.com/maps/api/place/autocomplete/json" +
                        "?input=$q&location=39.1653,-86.5264&radius=20000&components=country:us" +
                        "&key=${BuildConfig.MAPS_API_KEY}"
                val body = http.newCall(Request.Builder().url(url).build()).execute()
                    .body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(body).optJSONArray("predictions") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    PlacePrediction(p.getString("description"), p.getString("place_id"))
                }
            } catch (_: Exception) { emptyList<PlacePrediction>() }
        }

        // GTFS stops first (most relevant for transit), then Places results
        return gtfsMatches + placesResults
    }

    private suspend fun getPlaceCoords(placeId: String): Pair<Double, Double>? {
        // Handle GTFS stop IDs directly — no network call needed
        if (placeId.startsWith("gtfs_stop:")) {
            val stopId = placeId.removePrefix("gtfs_stop:")
            val stop = GtfsStaticCache.stops[stopId] ?: return null
            return Pair(stop.lat, stop.lon)
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://maps.googleapis.com/maps/api/place/details/json" +
                        "?place_id=$placeId&fields=geometry&key=${BuildConfig.MAPS_API_KEY}"
                val body = http.newCall(Request.Builder().url(url).build()).execute()
                    .body?.string() ?: return@withContext null
                val loc = JSONObject(body).getJSONObject("result")
                    .getJSONObject("geometry").getJSONObject("location")
                Pair(loc.getDouble("lat"), loc.getDouble("lng"))
            } catch (_: Exception) { null }
        }
    }

    private fun nearestStops(lat: Double, lon: Double, count: Int): List<GtfsStop> {
        val dist = FloatArray(1)
        return GtfsStaticCache.stops.values
            .map { stop ->
                Location.distanceBetween(lat, lon, stop.lat, stop.lon, dist)
                Pair(stop, dist[0])
            }
            .sortedBy { it.second }
            .take(count)
            .map { it.first }
    }
}
