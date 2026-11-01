// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.listener;

import com.carocean.airconditioner.CarAirconDataUnit;
import com.carocean.door.CarDoorDataUnit;
import com.carocean.key.CarKeyDataUnit;
import com.carocean.radar.CarRadarDataUnit;
import com.carocean.carsettings.CarSettingDataUnit;
import com.carocean.trajectory.CarTrajectoryDataUnit;

interface CarDataPluginToAppListener {
    void onAirconDataListener(in CarAirconDataUnit data);         // 1
    void onKeyDataListener(in CarKeyDataUnit data);               // 2
    void onDoorDataListener(in CarDoorDataUnit data);             // 3
    void onTrajectoryDataListener(in CarTrajectoryDataUnit data); // 4
    void onRadarDataListener(in CarRadarDataUnit data);           // 5
    void onSettingDataListener(in CarSettingDataUnit data);       // 6
}
