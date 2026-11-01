// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
// The vendor interface also exposes ~25 set*() commands (A/C, drive settings, codes 3..26).
// They are intentionally NOT declared here: VaPian only reads signals, it never commands the vehicle.
package com.carocean.listener;

import com.carocean.listener.CarDataPluginToAppListener;
import com.carocean.airconditioner.CarAirconAppToJarListener;
import com.carocean.door.CarDoorAppToJarListener;
import com.carocean.key.CarKeyAppToJarListener;
import com.carocean.radar.CarRadarAppToJarListener;
import com.carocean.trajectory.CarTrajectoryAppToJarListener;
import com.carocean.carsettings.CarSettingAppToJarListener;

interface IMyAidlInterfaceListener {
    void registerListener(CarDataPluginToAppListener data, CarAirconAppToJarListener aircon, CarDoorAppToJarListener door,
                          CarKeyAppToJarListener key, CarRadarAppToJarListener radar,
                          CarTrajectoryAppToJarListener trajectory, CarSettingAppToJarListener setting);    // 1
    void unregisterListener(CarDataPluginToAppListener data, CarAirconAppToJarListener aircon, CarDoorAppToJarListener door,
                            CarKeyAppToJarListener key, CarRadarAppToJarListener radar,
                            CarTrajectoryAppToJarListener trajectory, CarSettingAppToJarListener setting);  // 2
}
