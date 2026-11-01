package com.carocean.radar

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarRadarDataUnit() : Parcelable {
    var radarFrontLeft: Int = 0
    var radarFrontLeftCenter: Int = 0
    var radarFrontRightCenter: Int = 0
    var radarFrontRight: Int = 0
    var radarRearLeft: Int = 0
    var radarRearLeftCenter: Int = 0
    var radarRearRightCenter: Int = 0
    var radarRearRight: Int = 0
    var radarFrontMax: Int = 0
    var radarRearMax: Int = 0

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        radarFrontLeft = parcel.readInt()
        radarFrontLeftCenter = parcel.readInt()
        radarFrontRightCenter = parcel.readInt()
        radarFrontRight = parcel.readInt()
        radarRearLeft = parcel.readInt()
        radarRearLeftCenter = parcel.readInt()
        radarRearRightCenter = parcel.readInt()
        radarRearRight = parcel.readInt()
        radarFrontMax = parcel.readInt()
        radarRearMax = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(radarFrontLeft)
        parcel.writeInt(radarFrontLeftCenter)
        parcel.writeInt(radarFrontRightCenter)
        parcel.writeInt(radarFrontRight)
        parcel.writeInt(radarRearLeft)
        parcel.writeInt(radarRearLeftCenter)
        parcel.writeInt(radarRearRightCenter)
        parcel.writeInt(radarRearRight)
        parcel.writeInt(radarFrontMax)
        parcel.writeInt(radarRearMax)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarRadarDataUnit(radarFrontLeft=$radarFrontLeft, radarFrontLeftCenter=$radarFrontLeftCenter, radarFrontRightCenter=$radarFrontRightCenter, radarFrontRight=$radarFrontRight, radarRearLeft=$radarRearLeft, radarRearLeftCenter=$radarRearLeftCenter, radarRearRightCenter=$radarRearRightCenter, radarRearRight=$radarRearRight, radarFrontMax=$radarFrontMax, radarRearMax=$radarRearMax)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarRadarDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarRadarDataUnit(parcel)
            override fun newArray(size: Int): Array<CarRadarDataUnit?> = arrayOfNulls(size)
        }
    }
}
