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

/** 自動化装置の検出記録。1チャンクにつき1件だけ残し、記録済みの場所は二度と通知しない。メインスレッド専用。 */
final class AutomationStore implements AutoCloseable {

    record Pending(long id, String content) {
    }

    private final Connection db;

    AutomationStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=3000");
            s.execute("CREATE TABLE IF NOT EXISTS detections (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,"
                    + " x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
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
    long insert(String world, int chunkX, int chunkZ, int x, int y, int z, String content) throws SQLException {
        try (PreparedStatement s = db.prepareStatement(
                "INSERT OR IGNORE INTO detections (world, chunk_x, chunk_z, x, y, z, detected_at, content)"
                        + " VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, world);
            s.setInt(2, chunkX);
            s.setInt(3, chunkZ);
            s.setInt(4, x);
            s.setInt(5, y);
            s.setInt(6, z);
            s.setLong(7, System.currentTimeMillis());
            s.setString(8, content);
            if (s.executeUpdate() == 0) {
                return -1L;
            }
            try (ResultSet r = s.getGeneratedKeys()) {
                return r.next() ? r.getLong(1) : -1L;
            }
        }
    }

    void markSent(long id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("UPDATE detections SET sent=1 WHERE id=?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
    }

    List<Pending> unsent(int limit) throws SQLException {
        List<Pending> result = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(
                "SELECT id, content FROM detections WHERE sent=0 ORDER BY id LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    result.add(new Pending(r.getLong(1), r.getString(2)));
                }
            }
        }
        return result;
    }

    int count(boolean onlyUnsent) throws SQLException {
        String sql = "SELECT COUNT(*) FROM detections" + (onlyUnsent ? " WHERE sent=0" : "");
        try (PreparedStatement s = db.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
