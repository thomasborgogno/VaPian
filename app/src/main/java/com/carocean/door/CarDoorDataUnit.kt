package com.carocean.door

import android.os.Parcel
import android.os.Parcelable

/** Wire layout reconstructed from the head unit bridge; field order = write order. Read-only data holder. */
class CarDoorDataUnit() : Parcelable {
    var doorLeftFront: Boolean = false
    var doorLeftRear: Boolean = false
    var doorRightFront: Boolean = false
    var doorRightRear: Boolean = false
    var doorTrunk: Boolean = false
    var doorHood: Boolean = false
    var gearValue: Int = 0

    constructor(parcel: Parcel) : this() {
        readFromParcel(parcel)
    }

    fun readFromParcel(parcel: Parcel) {
        doorLeftFront = parcel.readByte().toInt() != 0
        doorLeftRear = parcel.readByte().toInt() != 0
        doorRightFront = parcel.readByte().toInt() != 0
        doorRightRear = parcel.readByte().toInt() != 0
        doorTrunk = parcel.readByte().toInt() != 0
        doorHood = parcel.readByte().toInt() != 0
        gearValue = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (doorLeftFront) 1 else 0)
        parcel.writeByte(if (doorLeftRear) 1 else 0)
        parcel.writeByte(if (doorRightFront) 1 else 0)
        parcel.writeByte(if (doorRightRear) 1 else 0)
        parcel.writeByte(if (doorTrunk) 1 else 0)
        parcel.writeByte(if (doorHood) 1 else 0)
        parcel.writeInt(gearValue)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "CarDoorDataUnit(doorLeftFront=$doorLeftFront, doorLeftRear=$doorLeftRear, doorRightFront=$doorRightFront, doorRightRear=$doorRightRear, doorTrunk=$doorTrunk, doorHood=$doorHood, gearValue=$gearValue)"

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarDoorDataUnit> {
            override fun createFromParcel(parcel: Parcel) = CarDoorDataUnit(parcel)
            override fun newArray(size: Int): Array<CarDoorDataUnit?> = arrayOfNulls(size)
        }
    }
}
