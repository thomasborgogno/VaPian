// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.radar;

interface CarRadarAppToJarListener {
    void onRadarFrontLeftChanged(int v);        // 1
    void onRadarFrontLeftCenterChanged(int v);  // 2
    void onRadarFrontRightCenterChanged(int v); // 3
    void onRadarFrontRightChanged(int v);       // 4
    void onRadarRearLeftChanged(int v);         // 5
    void onRadarRearLeftCenterChanged(int v);   // 6
    void onRadarRearRightCenterChanged(int v);  // 7
    void onRadarRearRightChanged(int v);        // 8
    void onRadarFrontMax(int v);                // 9
    void onRadarRearMax(int v);                 // 10
}
