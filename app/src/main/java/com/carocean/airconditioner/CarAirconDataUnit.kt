package com.carocean.airconditioner

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarAirconDataUnit() : Parcelable {
    var airSwitch: Boolean = false
    var airLeftTemp: Float = 0f
    var airRightTemp: Float = 0f
    var airWindSpeed: Int = 0
    var airWindMode: Int = 0
    var airCycle: Int = 0
    var airAc: Boolean = false
    var airFrontWind: Boolean = false
    var airRearWind: Boolean = false
    var airPtc: Boolean = false

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        airSwitch = parcel.readByte().toInt() != 0
        airLeftTemp = parcel.readFloat()
        airRightTemp = parcel.readFloat()
        airWindSpeed = parcel.readInt()
        airWindMode = parcel.readInt()
        airCycle = parcel.readInt()
        airAc = parcel.readByte().toInt() != 0
        airFrontWind = parcel.readByte().toInt() != 0
        airRearWind = parcel.readByte().toInt() != 0
        airPtc = parcel.readByte().toInt() != 0
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (airSwitch) 1 else 0)
        parcel.writeFloat(airLeftTemp)
        parcel.writeFloat(airRightTemp)
        parcel.writeInt(airWindSpeed)
        parcel.writeInt(airWindMode)
        parcel.writeInt(airCycle)
        parcel.writeByte(if (airAc) 1 else 0)
        parcel.writeByte(if (airFrontWind) 1 else 0)
        parcel.writeByte(if (airRearWind) 1 else 0)
        parcel.writeByte(if (airPtc) 1 else 0)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarAirconDataUnit(airSwitch=$airSwitch, airLeftTemp=$airLeftTemp, airRightTemp=$airRightTemp, airWindSpeed=$airWindSpeed, airWindMode=$airWindMode, airCycle=$airCycle, airAc=$airAc, airFrontWind=$airFrontWind, airRearWind=$airRearWind, airPtc=$airPtc)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarAirconDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarAirconDataUnit(parcel)
            override fun newArray(size: Int): Array<CarAirconDataUnit?> = arrayOfNulls(size)
        }
    }
}
