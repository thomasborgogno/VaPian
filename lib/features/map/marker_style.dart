import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../data/models/speed_camera.dart';

/// Marker Verde: autovelox ufficiale verificato da OpenStreetMap.
/// Marker Blu: segnalazione utente approvata.
/// Marker Arancione: segnalazione utente in attesa di verifica.
/// (features.md 2.1)
double markerHueFor(SpeedCamera camera) {
  if (camera.source == SpeedCameraSource.openstreetmap) {
    return BitmapDescriptor.hueGreen;
  }
  return camera.status == SpeedCameraStatus.approved
      ? BitmapDescriptor.hueBlue
      : BitmapDescriptor.hueOrange;
}
