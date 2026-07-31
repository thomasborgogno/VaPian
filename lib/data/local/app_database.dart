import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// Filename of the local SQLite store shared with the native Android Auto
/// side (`LocalCameraStore.kt`): both engines run in the same app process
/// (`AutoveloxCarAppService` has no `android:process` override) and open
/// this *same* on-disk file via their own platform APIs
/// (`getDatabasesPath()`/`Context.getDatabasePath()` both resolve under
/// `/data/data/<package>/databases/`), so a name match is all that's needed
/// to share data — no Method Channel involved. Keep this name, the table
/// names, and the column names below in sync with `LocalCameraStore.kt`
/// manually: there is no codegen or migration tool shared across the two
/// languages.
const String kSharedDatabaseName = 'autovelox_shared.db';
const int kSharedDatabaseVersion = 1;

/// Local mirror of `speed_cameras/{id}` — see `SpeedCamera`
/// (`lib/data/models/speed_camera.dart`) for field meanings.
class SpeedCamerasTable {
  const SpeedCamerasTable._();

  static const String name = 'speed_cameras';
  static const String id = 'id';
  static const String lat = 'lat';
  static const String lng = 'lng';
  static const String speedLimit = 'speed_limit';
  static const String direction = 'direction';
  static const String status = 'status';
  static const String source = 'source';
  static const String geohash = 'geohash';

  /// Italian region name (`italianRegions`) this camera belongs to, set
  /// when priming a region's dataset into the local store — lets a region
  /// download be pruned/refreshed independently of the others.
  static const String region = 'region';
  static const String createdBy = 'created_by';
  static const String createdAt = 'created_at';
  static const String updatedAt = 'updated_at';
  static const String osmNodeId = 'osm_node_id';
  static const String osmNodeVersion = 'osm_node_version';
  static const String directionReversed = 'direction_reversed';

  /// 1 while this row has local edits not yet confirmed written to
  /// Firestore (i.e. there's a matching `sync_queue` row still pending).
  static const String dirty = 'dirty';

  /// Tombstone: 1 once deleted locally (offline) but not yet confirmed
  /// deleted on Firestore — kept instead of a hard delete so the row
  /// disappears from reads immediately while [SyncService] still has a
  /// record to replay.
  static const String deleted = 'deleted';
}

/// Local mirror of `users/{uid}/ignored_cameras/{cameraId}` — see
/// `IgnoredCamera` (`lib/data/models/ignored_camera.dart`).
class IgnoredCamerasTable {
  const IgnoredCamerasTable._();

  static const String name = 'ignored_cameras';
  static const String uid = 'uid';
  static const String cameraId = 'camera_id';
  static const String ignoredAt = 'ignored_at';
  static const String dirty = 'dirty';
  static const String deleted = 'deleted';
}

/// Operations recorded locally (by either the Dart UI or, natively, the
/// Android Auto "Ignora"/"Segnala" buttons) and replayed against Firestore
/// once connectivity returns — see `SyncService`.
class SyncQueueTable {
  const SyncQueueTable._();

  static const String name = 'sync_queue';
  static const String rowId = 'id';
  static const String opType = 'op_type';

  /// JSON-encoded payload, shape depends on [opType] — see
  /// `SyncQueueOp` (`lib/data/local/sync_queue_op.dart`) for the encoder
  /// used by the Dart side.
  static const String payload = 'payload';
  static const String createdAt = 'created_at';
  static const String attempts = 'attempts';
}

/// Opens (or creates) the shared SQLite database described above.
///
/// A single process-wide instance is kept so every repository shares one
/// `Database` handle — `sqflite` already serializes writes per-connection,
/// and WAL mode ([_configure]) lets Dart reads proceed concurrently with an
/// in-flight write instead of blocking on it.
class AppDatabase {
  AppDatabase._(this.db);

  final Database db;

  static AppDatabase? _instance;

  static Future<AppDatabase> instance() async {
    final existing = _instance;
    if (existing != null) return existing;
    final opened = AppDatabase._(await _open());
    _instance = opened;
    return opened;
  }

  static Future<Database> _open() async {
    final path = p.join(await getDatabasesPath(), kSharedDatabaseName);
    return openDatabase(
      path,
      version: kSharedDatabaseVersion,
      onConfigure: _configure,
      onCreate: (db, version) => createSchema(db),
    );
  }

  static Future<void> _configure(Database db) async {
    // WAL: readers don't block behind the native Kotlin side's writer (or
    // vice versa) since both open this same file from the same process.
    await db.execute('PRAGMA journal_mode=WAL');
    await db.execute('PRAGMA foreign_keys=ON');
  }

  /// Test-only: an isolated in-memory database with the same schema,
  /// avoiding the shared on-disk file entirely. Requires
  /// `sqflite_common_ffi`'s `databaseFactory` to be set by the caller (see
  /// `test/local/sqlite_test_setup.dart`).
  static Future<AppDatabase> openInMemoryForTest() async {
    final db = await openDatabase(
      inMemoryDatabasePath,
      version: kSharedDatabaseVersion,
      onCreate: (db, version) => createSchema(db),
    );
    return AppDatabase._(db);
  }

  static Future<void> createSchema(Database db) async {
    await db.execute('''
      CREATE TABLE ${SpeedCamerasTable.name} (
        ${SpeedCamerasTable.id} TEXT PRIMARY KEY,
        ${SpeedCamerasTable.lat} REAL NOT NULL,
        ${SpeedCamerasTable.lng} REAL NOT NULL,
        ${SpeedCamerasTable.speedLimit} INTEGER,
        ${SpeedCamerasTable.direction} INTEGER,
        ${SpeedCamerasTable.status} TEXT NOT NULL,
        ${SpeedCamerasTable.source} TEXT NOT NULL,
        ${SpeedCamerasTable.geohash} TEXT NOT NULL,
        ${SpeedCamerasTable.region} TEXT,
        ${SpeedCamerasTable.createdBy} TEXT,
        ${SpeedCamerasTable.createdAt} INTEGER,
        ${SpeedCamerasTable.updatedAt} INTEGER,
        ${SpeedCamerasTable.osmNodeId} INTEGER,
        ${SpeedCamerasTable.osmNodeVersion} INTEGER,
        ${SpeedCamerasTable.directionReversed} INTEGER NOT NULL DEFAULT 0,
        ${SpeedCamerasTable.dirty} INTEGER NOT NULL DEFAULT 0,
        ${SpeedCamerasTable.deleted} INTEGER NOT NULL DEFAULT 0
      )
    ''');
    await db.execute(
      'CREATE INDEX idx_speed_cameras_geohash ON ${SpeedCamerasTable.name}(${SpeedCamerasTable.geohash})',
    );
    await db.execute(
      'CREATE INDEX idx_speed_cameras_region ON ${SpeedCamerasTable.name}(${SpeedCamerasTable.region})',
    );
    await db.execute(
      'CREATE INDEX idx_speed_cameras_own_pending ON ${SpeedCamerasTable.name}'
      '(${SpeedCamerasTable.createdBy}, ${SpeedCamerasTable.status}, ${SpeedCamerasTable.source})',
    );

    await db.execute('''
      CREATE TABLE ${IgnoredCamerasTable.name} (
        ${IgnoredCamerasTable.uid} TEXT NOT NULL,
        ${IgnoredCamerasTable.cameraId} TEXT NOT NULL,
        ${IgnoredCamerasTable.ignoredAt} INTEGER NOT NULL,
        ${IgnoredCamerasTable.dirty} INTEGER NOT NULL DEFAULT 0,
        ${IgnoredCamerasTable.deleted} INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (${IgnoredCamerasTable.uid}, ${IgnoredCamerasTable.cameraId})
      )
    ''');

    await db.execute('''
      CREATE TABLE ${SyncQueueTable.name} (
        ${SyncQueueTable.rowId} INTEGER PRIMARY KEY AUTOINCREMENT,
        ${SyncQueueTable.opType} TEXT NOT NULL,
        ${SyncQueueTable.payload} TEXT NOT NULL,
        ${SyncQueueTable.createdAt} INTEGER NOT NULL,
        ${SyncQueueTable.attempts} INTEGER NOT NULL DEFAULT 0
      )
    ''');
  }
}
