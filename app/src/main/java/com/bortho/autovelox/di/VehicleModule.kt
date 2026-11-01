package com.bortho.autovelox.di

import com.bortho.autovelox.vehicle.CanBridgeVehicleSignals
import com.bortho.autovelox.vehicle.VehicleSignals
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleModule {
    @Binds
    @Singleton
    abstract fun vehicleSignals(impl: CanBridgeVehicleSignals): VehicleSignals
}
