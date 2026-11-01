// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.trajectory;

interface CarTrajectoryAppToJarListener {
    void onTrajectorySta(int v);    // 1
    void onTrajectoryValue(int v);  // 2
    void onTrajectoryMax(int v);    // 3
}
