package dev.spa.ecolife.invite;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Main-thread confined durable ledger. SENDING is deliberately never automatically retried. */
public final class InviteStore implements AutoCloseable {
    private final Connection db;

    public record Person(UUID id, String name, boolean eligible) {}

    public record Link(
            UUID newcomer,
            UUID inviter,
            String state,
            boolean overrideIp,
            double inviterAmount,
            double newcomerAmount,
            String inviterPay,
            String newcomerPay) {}

    public record Ranking(String name, long count) {}

    public InviteStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        exec("PRAGMA journal_mode=WAL");
        exec("PRAGMA synchronous=FULL");
        exec("PRAGMA busy_timeout=3000");
        exec("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        exec(
                "CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL,"
                    + " eligible INTEGER NOT NULL)");
        exec("CREATE INDEX IF NOT EXISTS player_name ON players(name COLLATE NOCASE)");
        exec(
                "CREATE TABLE IF NOT EXISTS ips (uuid TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY"
                    + " KEY(uuid,hash))");
        exec(
                "CREATE TABLE IF NOT EXISTS invites (newcomer TEXT PRIMARY KEY, inviter TEXT NOT"
                    + " NULL, state TEXT NOT NULL, override_ip INTEGER NOT NULL, inviter_amount"
                    + " REAL NOT NULL, newcomer_amount REAL NOT NULL, inviter_pay TEXT NOT NULL"
                    + " DEFAULT 'PENDING', newcomer_pay TEXT NOT NULL DEFAULT 'PENDING', created_at"
                    + " INTEGER NOT NULL, completed_at INTEGER)");
        exec("CREATE INDEX IF NOT EXISTS invited_by ON invites(inviter,state)");
        exec(
                "CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER"
                    + " NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, newcomer TEXT NOT"
                    + " NULL)");
    }

    public String metadata(String key, String initial) throws SQLException {
        update("INSERT OR IGNORE INTO metadata VALUES (?,?)", key, initial);
        try (PreparedStatement s = query("SELECT value FROM metadata WHERE key=?", key);
                ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Missing metadata");
            return r.getString(1);
        }
    }

    public void person(UUID id, String name, boolean eligible, String hash) throws SQLException {
        update(
                "INSERT INTO players VALUES (?,?,?) ON CONFLICT(uuid) DO UPDATE SET"
                    + " name=excluded.name",
                id,
                name,
                eligible);
        if (hash != null) update("INSERT OR IGNORE INTO ips VALUES (?,?)", id, hash);
    }

    public Person person(UUID id) throws SQLException {
        try (PreparedStatement s = query("SELECT * FROM players WHERE uuid=?", id);
                ResultSet r = s.executeQuery()) {
            return r.next() ? person(r) : null;
        }
    }

    public Person find(String name) throws SQLException {
        try (PreparedStatement s =
                        query("SELECT * FROM players WHERE name=? COLLATE NOCASE LIMIT 2", name);
                ResultSet r = s.executeQuery()) {
            if (!r.next()) return null;
            Person p = person(r);
            return r.next() ? null : p; // ambiguous historical names fail closed
        }
    }

    private Person person(ResultSet r) throws SQLException {
        return new Person(
                UUID.fromString(r.getString("uuid")),
                r.getString("name"),
                r.getBoolean("eligible"));
    }

    public boolean knownIp(UUID id) throws SQLException {
        try (PreparedStatement s = query("SELECT 1 FROM ips WHERE uuid=? LIMIT 1", id);
                ResultSet r = s.executeQuery()) {
            return r.next();
        }
    }

    public boolean sameIp(UUID a, UUID b) throws SQLException {
        try (PreparedStatement s =
                        query(
                                "SELECT 1 FROM ips a JOIN ips b ON a.hash=b.hash WHERE a.uuid=? AND"
                                    + " b.uuid=? LIMIT 1",
                                a,
                                b);
                ResultSet r = s.executeQuery()) {
            return r.next();
        }
    }

    public Link link(UUID id) throws SQLException {
        try (PreparedStatement s = query("SELECT * FROM invites WHERE newcomer=?", id);
                ResultSet r = s.executeQuery()) {
            return r.next() ? link(r) : null;
        }
    }

    private Link link(ResultSet r) throws SQLException {
        return new Link(
                UUID.fromString(r.getString("newcomer")),
                UUID.fromString(r.getString("inviter")),
                r.getString("state"),
                r.getBoolean("override_ip"),
                r.getDouble("inviter_amount"),
                r.getDouble("newcomer_amount"),
                r.getString("inviter_pay"),
                r.getString("newcomer_pay"));
    }

    public List<Link> invited(UUID id, int page) throws SQLException {
        List<Link> result = new ArrayList<>();
        try (PreparedStatement s =
                        query(
                                "SELECT * FROM invites WHERE inviter=? ORDER BY created_at"
                                    + " DESC,newcomer LIMIT 45 OFFSET ?",
                                id,
                                (long) page * 45);
                ResultSet r = s.executeQuery()) {
            while (r.next()) result.add(link(r));
        }
        return result;
    }

    public List<Ranking> top(int page) throws SQLException {
        List<Ranking> result = new ArrayList<>();
        try (PreparedStatement s =
                        query(
                                "SELECT p.name,COUNT(*) n FROM invites i JOIN players p ON"
                                    + " p.uuid=i.inviter WHERE state='COMPLETE' GROUP BY i.inviter"
                                    + " ORDER BY n DESC,p.name,i.inviter LIMIT 45 OFFSET ?",
                                (long) page * 45);
                ResultSet r = s.executeQuery()) {
            while (r.next()) result.add(new Ranking(r.getString(1), r.getLong(2)));
        }
        return result;
    }

    public void bind(
            UUID newcomer, UUID inviter, boolean override, double a, double b, String actor)
            throws SQLException {
        transaction(
                () -> {
                    update(
                            "INSERT INTO"
                                + " invites(newcomer,inviter,state,override_ip,inviter_amount,newcomer_amount,created_at)"
                                + " VALUES (?,?,'WAITING',?,?,?,?) ON CONFLICT(newcomer) DO UPDATE"
                                + " SET inviter=excluded.inviter,state='WAITING',override_ip=excluded.override_ip,inviter_amount=excluded.inviter_amount,newcomer_amount=excluded.newcomer_amount,created_at=excluded.created_at"
                                + " WHERE invites.state='CANCELLED'",
                            newcomer,
                            inviter,
                            override,
                            a,
                            b,
                            System.currentTimeMillis());
                    audit(actor, override ? "BIND_OVERRIDE_IP" : "BIND", newcomer);
                });
    }

    public void state(UUID id, String state) throws SQLException {
        update("UPDATE invites SET state=? WHERE newcomer=?", state, id);
    }

    public void override(UUID id, String actor) throws SQLException {
        transaction(
                () -> {
                    update(
                            "UPDATE invites SET override_ip=1,state='WAITING' WHERE newcomer=? AND"
                                + " state IN ('WAITING','BLOCKED')",
                            id);
                    audit(actor, "OVERRIDE_IP", id);
                });
    }

    public boolean cancel(UUID id, String actor) throws SQLException {
        final boolean[] changed = {false};
        transaction(
                () -> {
                    changed[0] =
                            update(
                                            "UPDATE invites SET state='CANCELLED' WHERE newcomer=?"
                                                + " AND state IN ('WAITING','BLOCKED') AND"
                                                + " inviter_pay='PENDING' AND"
                                                + " newcomer_pay='PENDING'",
                                            id)
                                    == 1;
                    if (changed[0]) audit(actor, "CANCEL", id);
                });
        return changed[0];
    }

    public void payment(UUID id, boolean inviter, String state) throws SQLException {
        update(
                "UPDATE invites SET "
                        + (inviter ? "inviter_pay" : "newcomer_pay")
                        + "=? WHERE newcomer=?",
                state,
                id);
    }

    public void resolve(UUID id, boolean inviter, boolean paid, String actor) throws SQLException {
        transaction(
                () -> {
                    String col = inviter ? "inviter_pay" : "newcomer_pay";
                    if (update(
                                    "UPDATE invites SET "
                                            + col
                                            + "=? WHERE newcomer=? AND "
                                            + col
                                            + "='SENDING'",
                                    paid ? "PAID" : "PENDING",
                                    id)
                            != 1) throw new SQLException("Payment is not uncertain");
                    audit(actor, "RESOLVE_" + col + "_" + (paid ? "PAID" : "UNPAID"), id);
                });
    }

    public boolean complete(UUID id) throws SQLException {
        return update(
                        "UPDATE invites SET state='COMPLETE',completed_at=? WHERE newcomer=? AND"
                            + " state='WAITING' AND inviter_pay='PAID' AND newcomer_pay='PAID'",
                        System.currentTimeMillis(),
                        id)
                == 1;
    }

    private void audit(String actor, String action, UUID id) throws SQLException {
        update(
                "INSERT INTO audit(at,actor,action,newcomer) VALUES (?,?,?,?)",
                System.currentTimeMillis(),
                actor,
                action,
                id);
    }

    private void transaction(SqlAction action) throws SQLException {
        db.setAutoCommit(false);
        try {
            action.run();
            db.commit();
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute(sql);
        }
    }

    private PreparedStatement query(String sql, Object... args) throws SQLException {
        PreparedStatement s = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++)
            s.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
        return s;
    }

    private int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement s = query(sql, args)) {
            return s.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
