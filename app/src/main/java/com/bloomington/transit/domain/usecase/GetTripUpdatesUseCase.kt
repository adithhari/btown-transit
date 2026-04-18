package com.bloomington.transit.domain.usecase

import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.repository.TransitRepository

class GetTripUpdatesUseCase(private val repository: TransitRepository) {
    suspend operator fun invoke(): List<TripUpdate> = repository.getTripUpdates()
}
