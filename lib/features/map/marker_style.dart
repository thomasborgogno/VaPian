import 'package:flutter/material.dart';

import '../../data/models/speed_camera.dart';

/// Blu: autovelox ufficiale verificato da OpenStreetMap.
/// Rosso: segnalazione utente approvata.
/// Arancione: segnalazione utente in attesa di verifica (o in fase di
/// modifica/inserimento).
/// (features.md 2.1)
const Color speedCameraUser = Color.fromARGB(255, 207, 3, 3);
const Color speedCameraOSM = Color(0xFF1565C0);
const Color speedCameraDraft = Color.fromARGB(255, 239, 127, 0);

/// Used for cameras shown only because "mostra solo velox ignorati" is
/// active in the map filter — grey signals "hidden from normal view".
const Color speedCameraGray = Color(0xFF9E9E9E);

Color markerColorFor(SpeedCamera camera) {
  if (camera.source == SpeedCameraSource.openstreetmap) {
    return speedCameraOSM;
  }
  return camera.status == SpeedCameraStatus.approved
      ? speedCameraUser
      : speedCameraDraft;
}
