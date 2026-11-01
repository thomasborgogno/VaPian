// Reconstructed from the head unit CAN bridge (com.kyhero.car.myhost) for read-only interoperability.
// Method ORDER defines the Binder transaction codes (1, 2, 3, ...): do not reorder.
package com.carocean.key;

interface CarKeyAppToJarListener {
    void onKeyValue(int keyCode, int keyState);  // 1
}
