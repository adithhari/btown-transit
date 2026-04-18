package com.bloomington.transit.presentation.favorites

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FavoriteStopInfo(
    val stop: GtfsStop,
    val nickname: String,
    val nextArrivals: List<ScheduleEntry>
)

data class FavoritesUiState(
    val favorites: List<FavoriteStopInfo> = emptyList(),
    val allStops: List<GtfsStop> = emptyList(),
    val isLoading: Boolean = true
)

class FavoritesViewModel(private val context: Context) : ViewModel() {

    private val prefs = PreferencesManager(context)
    private val repository = TransitRepositoryImpl()
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(prefs.favoriteStopIds, prefs.favoriteNicknames) { ids, nicknames ->
                ids to nicknames
            }.collect { (ids, nicknames) ->
                refreshWithIds(ids, nicknames)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val ids       = prefs.favoriteStopIds.first()
                val nicknames = prefs.favoriteNicknames.first()
                refreshWithIds(ids, nicknames)
            }
        }
    }

    private suspend fun refreshWithIds(ids: Set<String>, nicknames: Map<String, String>) {
        try {
            val updates = try { getTripUpdates() } catch (_: Exception) { emptyList() }
            val infos = ids.mapNotNull { stopId ->
                val stop = GtfsStaticCache.stops[stopId] ?: return@mapNotNull null
                val arrivals = getSchedule(stopId, updates).filter { !it.isPast }.take(3)
                FavoriteStopInfo(
                    stop      = stop,
                    nickname  = nicknames[stopId] ?: "",
                    nextArrivals = arrivals
                )
            }.sortedBy { (it.nickname.ifEmpty { it.stop.name }).lowercase() }

            _uiState.value = _uiState.value.copy(
                favorites = infos,
                allStops  = GtfsStaticCache.stops.values.sortedBy { it.name },
                isLoading = false
            )
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun addFavorite(stopId: String) {
        viewModelScope.launch { prefs.addFavoriteStop(stopId) }
    }

    fun removeFavorite(stopId: String) {
        viewModelScope.launch { prefs.removeFavoriteStop(stopId) }
    }

    fun setNickname(stopId: String, nickname: String) {
        viewModelScope.launch { prefs.setFavoriteNickname(stopId, nickname) }
    }
}
