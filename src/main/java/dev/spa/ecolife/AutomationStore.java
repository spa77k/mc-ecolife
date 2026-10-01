package dev.spa.ecolife;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自動化装置の検出記録。1チャンクにつき1件だけ残し、記録済みの場所は二度と通知しない。メインスレッド専用。
 * SPSMCInsight が読み取り専用で開いて週次の出力に含めるため、列名を変えるときは両方を揃える。
 */
final class AutomationStore implements AutoCloseable {

    record Pending(long id, String content) {
    }

    /**
     * 1件の検出。owner・placer は分からなければ空文字、placedAt は設置時刻（UNIX秒、不明なら0）。
     * counts は AutomationWatch.Kind の順。
     */
    record Detection(String world, int chunkX, int chunkZ, int x, int y, int z,
                     String owner, String placer, long placedAt, String mapUrl, int[] counts, String content) {
    }

    private final Connection db;

    AutomationStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            // 書き込みは検出時だけでまれなため、他プラグインから読みやすい通常のジャーナルにする
            s.execute("PRAGMA busy_timeout=3000");
            s.execute("CREATE TABLE IF NOT EXISTS detections (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,"
                    + " x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                    + " owner TEXT NOT NULL, placer TEXT NOT NULL, placed_at INTEGER NOT NULL, map_url TEXT NOT NULL,"
                    + " transfer INTEGER NOT NULL, pickup INTEGER NOT NULL, piston INTEGER NOT NULL,"
                    + " dispense INTEGER NOT NULL, mob_death INTEGER NOT NULL,"
                    + " detected_at INTEGER NOT NULL, content TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0,"
                    + " UNIQUE(world, chunk_x, chunk_z))");
        }
    }

    static String key(String world, int chunkX, int chunkZ) {
        return world + "|" + chunkX + "|" + chunkZ;
    }

    Set<String> keys() throws SQLException {
        Set<String> keys = new HashSet<>();
        try (PreparedStatement s = db.prepareStatement("SELECT world, chunk_x, chunk_z FROM detections");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                keys.add(key(r.getString(1), r.getInt(2), r.getInt(3)));
            }
        }
        return keys;
    }

    /** 記録できたら採番したID、すでに記録済みなら -1。 */
    long insert(Detection d) throws SQLException {
        int[] c = d.counts();
        try (PreparedStatement s = prepare(
                "INSERT OR IGNORE INTO detections (world, chunk_x, chunk_z, x, y, z, owner, placer, placed_at,"
                        + " map_url, transfer, pickup, piston, dispense, mob_death, detected_at, content)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS,
                d.world(), d.chunkX(), d.chunkZ(), d.x(), d.y(), d.z(), d.owner(), d.placer(), d.placedAt(),
                d.mapUrl(), c[0], c[1], c[2], c[3], c[4], System.currentTimeMillis(), d.content())) {
            if (s.executeUpdate() == 0) {
                return -1L;
            }
            try (ResultSet r = s.getGeneratedKeys()) {
                return r.next() ? r.getLong(1) : -1L;
            }
        }
    }

    void markSent(long id) throws SQLException {
        try (PreparedStatement s = prepare("UPDATE detections SET sent=1 WHERE id=?", Statement.NO_GENERATED_KEYS, id)) {
            s.executeUpdate();
        }
    }

    List<Pending> unsent(int limit) throws SQLException {
        List<Pending> result = new ArrayList<>();
        try (PreparedStatement s = prepare("SELECT id, content FROM detections WHERE sent=0 ORDER BY id LIMIT ?",
                Statement.NO_GENERATED_KEYS, limit); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                result.add(new Pending(r.getLong(1), r.getString(2)));
            }
        }
        return result;
    }

    int count(boolean onlyUnsent) throws SQLException {
        String sql = "SELECT COUNT(*) FROM detections" + (onlyUnsent ? " WHERE sent=0" : "");
        try (PreparedStatement s = prepare(sql, Statement.NO_GENERATED_KEYS); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private PreparedStatement prepare(String sql, int generatedKeys, Object... args) throws SQLException {
        PreparedStatement s = db.prepareStatement(sql, generatedKeys);
        for (int i = 0; i < args.length; i++) {
            s.setObject(i + 1, args[i]);
        }
        return s;
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
