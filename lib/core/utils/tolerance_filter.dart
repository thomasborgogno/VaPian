/// 1:1 Dart port of the Android Auto native
/// `carapp/util/ToleranceFilter.kt` ("Filtro Tolleranza", features.md 2.2):
/// mutes the proximity audio alert while the driver is comfortably under the
/// limit, so it only speaks once they're close enough to actually be
/// speeding. Stateless — re-evaluated on every location tick, so crossing
/// back above the threshold naturally re-enables audio.
const double _toleranceRatio = 0.9;

bool shouldPlayAudio({
  required double speedKmh,
  required int? limitKmh,
  required bool toleranceFilterEnabled,
}) {
  if (!toleranceFilterEnabled || limitKmh == null) return true;
  return speedKmh > limitKmh * _toleranceRatio;
}
