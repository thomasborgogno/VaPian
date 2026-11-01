package com.bortho.autovelox.data.local

import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera

fun SpeedCameraEntity.toModel() = SpeedCamera(
    id = id,
    lat = lat,
    lng = lng,
    limit = speedLimit,
    direction = direction,
    status = CameraStatus.fromWire(status),
    source = CameraSource.fromWire(source),
    geohash = geohash,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
    osmNodeId = osmNodeId,
    osmNodeVersion = osmNodeVersion,
    directionReversed = directionReversed,
)

fun SpeedCamera.toEntity(region: String? = null, dirty: Boolean = false) = SpeedCameraEntity(
    id = id,
    lat = lat,
    lng = lng,
    speedLimit = limit,
    direction = direction,
    status = status.wire,
    source = source.wire,
    geohash = geohash,
    region = region,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
    osmNodeId = osmNodeId,
    osmNodeVersion = osmNodeVersion,
    directionReversed = directionReversed,
    dirty = dirty,
    deleted = false,
)
