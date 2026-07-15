package com.bortho.autovelox.carapp

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to (declared in `AndroidManifest.xml` with
 * the `androidx.car.app.CarAppService` intent-filter).
 */
class AutoveloxCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // TODO(Fase 7 hardening): restrict to the known Android Auto/Automotive
        // OS host signatures instead of accepting any host — see the OSM
        // ingestion hardening note in PLAN.md §4 for the same "MVP first,
        // hardened later" tradeoff.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session = AutoveloxSession()
}
