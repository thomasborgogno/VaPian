package com.carocean.carsettings

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarSettingDataUnit() : Parcelable {
    var energyRecoveryValue: Int = 0
    var cESCValue: Int = 0
    var eHACValue: Int = 0
    var airconCleanAutoValue: Int = 0
    var airconAiryAutoValue: Int = 0
    var longBatteryValue: Int = 0
    var carMomentarySpeed: Float = 0f
    var carAverageSpeed: Float = 0f
    var cruisingRangeValue: Float = 0f
    var instantaneousElectricValue: Float = 0f

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        energyRecoveryValue = parcel.readInt()
        cESCValue = parcel.readInt()
        eHACValue = parcel.readInt()
        airconCleanAutoValue = parcel.readInt()
        airconAiryAutoValue = parcel.readInt()
        longBatteryValue = parcel.readInt()
        carMomentarySpeed = parcel.readFloat()
        carAverageSpeed = parcel.readFloat()
        cruisingRangeValue = parcel.readFloat()
        instantaneousElectricValue = parcel.readFloat()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(energyRecoveryValue)
        parcel.writeInt(cESCValue)
        parcel.writeInt(eHACValue)
        parcel.writeInt(airconCleanAutoValue)
        parcel.writeInt(airconAiryAutoValue)
        parcel.writeInt(longBatteryValue)
        parcel.writeFloat(carMomentarySpeed)
        parcel.writeFloat(carAverageSpeed)
        parcel.writeFloat(cruisingRangeValue)
        parcel.writeFloat(instantaneousElectricValue)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarSettingDataUnit(energyRecoveryValue=$energyRecoveryValue, cESCValue=$cESCValue, eHACValue=$eHACValue, airconCleanAutoValue=$airconCleanAutoValue, airconAiryAutoValue=$airconAiryAutoValue, longBatteryValue=$longBatteryValue, carMomentarySpeed=$carMomentarySpeed, carAverageSpeed=$carAverageSpeed, cruisingRangeValue=$cruisingRangeValue, instantaneousElectricValue=$instantaneousElectricValue)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarSettingDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarSettingDataUnit(parcel)
            override fun newArray(size: Int): Array<CarSettingDataUnit?> = arrayOfNulls(size)
        }
    }
}
