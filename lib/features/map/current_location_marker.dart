import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

/// Stand-in for `GoogleMap`'s native "my location" blue dot, which
/// `flutter_map` doesn't provide — a small blue circle with a white ring,
/// fed by a plain `location` package position stream (see `MapScreen`).
class CurrentLocationMarkerLayer extends StatelessWidget {
  const CurrentLocationMarkerLayer({super.key, required this.position});

  final LatLng position;

  static const double diameter = 18;

  @override
  Widget build(BuildContext context) {
    return MarkerLayer(
      markers: [
        Marker(
          point: position,
          rotate: true,
          width: diameter,
          height: diameter,
          child: Container(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.blue,
              border: Border.all(color: Colors.white, width: 2),
              boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 3)],
            ),
          ),
        ),
      ],
    );
  }
}
