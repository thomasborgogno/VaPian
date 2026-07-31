import 'dart:async';

import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// Runs before every test file: `sqflite` needs a platform channel that
/// isn't available under `flutter test` (no real Android/iOS host), so
/// tests use the FFI-backed factory instead — same SQL engine, in-process.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  await testMain();
}
