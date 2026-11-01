package com.carocean.key

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarKeyDataUnit() : Parcelable {
    var keyCode: Int = 0
    var keyCodeSta: Int = 0

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        keyCode = parcel.readInt()
        keyCodeSta = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(keyCode)
        parcel.writeInt(keyCodeSta)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarKeyDataUnit(keyCode=$keyCode, keyCodeSta=$keyCodeSta)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarKeyDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarKeyDataUnit(parcel)
            override fun newArray(size: Int): Array<CarKeyDataUnit?> = arrayOfNulls(size)
        }
    }
}
