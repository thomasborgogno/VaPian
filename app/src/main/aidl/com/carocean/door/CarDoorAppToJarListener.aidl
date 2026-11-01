// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.door;

interface CarDoorAppToJarListener {
    void onDoorLeftFrontChanged(boolean open);   // 1
    void onDoorLeftRearChanged(boolean open);    // 2
    void onDoorRightFrontChanged(boolean open);  // 3
    void onDoorRightRearChanged(boolean open);   // 4
    void onDoorTrunkChanged(boolean open);       // 5
    void onDoorHoodChanged(boolean open);        // 6
    void onGearChanged(int gear);                // 7
}
