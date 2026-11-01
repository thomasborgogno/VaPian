// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.carsettings;

interface CarSettingAppToJarListener {
    void onEnergyRecovery(int value);               // 1
    void oncESC(int value);                         // 2
    void oneHAC(int value);                         // 3
    void onAirconCleanAuto(int value);              // 4
    void onAirconAiryAuto(int value);               // 5
    void onLongBatteryLife(int value);              // 6
    void onCarMomentarySpeed(float value);          // 7
    void onCarAverageSpeed(float value);            // 8
    void onCruisingRangeValue(float value);         // 9
    void onInstantaneousElectricValue(float value); // 10
}
