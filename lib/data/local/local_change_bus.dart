import 'dart:async';

/// Broadcast "something changed in the local database" signal.
///
/// `sqflite` has no native change-notification API, so every local write
/// (from a repository, or from [SyncService] pulling/pushing) calls
/// [notify] and every `watch*` stream re-queries on each tick instead of
/// diffing rows itself — simple and correct at this app's data volume
/// (a few thousand cameras at most).
class LocalChangeBus {
  LocalChangeBus._();

  static final LocalChangeBus instance = LocalChangeBus._();

  final _controller = StreamController<void>.broadcast();

  Stream<void> get onChange => _controller.stream;

  void notify() => _controller.add(null);
}
