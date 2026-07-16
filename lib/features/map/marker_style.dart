import 'package:flutter/material.dart';

import '../../data/models/speed_camera.dart';

/// Verde: autovelox ufficiale verificato da OpenStreetMap.
/// Blu: segnalazione utente approvata.
/// Arancione: segnalazione utente in attesa di verifica (o in fase di
/// modifica/inserimento).
/// (features.md 2.1)
const Color speedCameraGreen = Color(0xFF2E7D32);
const Color speedCameraBlue = Color(0xFF1565C0);
const Color speedCameraOrange = Color(0xFFEF6C00);

/// Used for cameras shown only because "mostra solo velox ignorati" is
/// active in the map filter — grey signals "hidden from normal view".
const Color speedCameraGray = Color(0xFF9E9E9E);

Color markerColorFor(SpeedCamera camera) {
  if (camera.source == SpeedCameraSource.openstreetmap) {
    return speedCameraBlue;
  }
  return camera.status == SpeedCameraStatus.approved
      ? speedCameraGreen
      : speedCameraOrange;
}
