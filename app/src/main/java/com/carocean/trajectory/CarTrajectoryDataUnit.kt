package com.carocean.trajectory

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarTrajectoryDataUnit() : Parcelable {
    var traSta: Int = 0
    var traValue: Int = 0
    var traMax: Int = 0

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        traSta = parcel.readInt()
        traValue = parcel.readInt()
        traMax = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(traSta)
        parcel.writeInt(traValue)
        parcel.writeInt(traMax)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarTrajectoryDataUnit(traSta=$traSta, traValue=$traValue, traMax=$traMax)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarTrajectoryDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarTrajectoryDataUnit(parcel)
            override fun newArray(size: Int): Array<CarTrajectoryDataUnit?> = arrayOfNulls(size)
        }
    }
}
