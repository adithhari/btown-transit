package com.bloomington.transit.domain.usecase

import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.data.repository.TransitRepository

class GetVehiclePositionsUseCase(private val repository: TransitRepository) {
    suspend operator fun invoke(): List<VehiclePosition> = repository.getVehiclePositions()
}
