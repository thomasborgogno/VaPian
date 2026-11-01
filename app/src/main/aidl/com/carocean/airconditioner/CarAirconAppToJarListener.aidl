// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.airconditioner;

interface CarAirconAppToJarListener {
    void onAirSwitchChanged(boolean on);        // 1
    void onAirLeftTempeChanged(float temp);     // 2
    void onAirRightTempeChanged(float temp);    // 3
    void onAirWindSpeedChanged(int speed);      // 4
    void onAirWindModeChanged(int mode);        // 5
    void onAirCycleChanged(int cycle);          // 6
    void onAirAcChanged(boolean on);            // 7
    void onAirFrontDefrostChanged(boolean on);  // 8
    void onAirRearDefrostChanged(boolean on);   // 9
    void onAirPTCChanged(boolean on);           // 10
}
