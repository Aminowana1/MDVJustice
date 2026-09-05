package xyz.mdvcraft.justice.database;

import xyz.mdvcraft.justice.chat.model.MuteRecord;
import xyz.mdvcraft.justice.chat.model.SlowRecord;
import xyz.mdvcraft.justice.prison.model.PlayerSnapshot;
import xyz.mdvcraft.justice.prison.model.Sentence;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class JusticeRepository implements AutoCloseable {
    private final Connection connection;

    public JusticeRepository(File file) throws SQLException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mutes (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        expires_at INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        staff TEXT NOT NULL
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS manual_slows (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        delay_ms INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS auto_slows (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        delay_ms INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS justice_settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sentences (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        required_points INTEGER NOT NULL,
                        current_points INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        staff TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        completed_at INTEGER NOT NULL DEFAULT 0,
                        inventory_storage TEXT NOT NULL,
                        inventory_armor TEXT NOT NULL,
                        inventory_offhand TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        exp REAL NOT NULL,
                        total_exp INTEGER NOT NULL,
                        food INTEGER NOT NULL,
                        saturation REAL NOT NULL,
                        game_mode TEXT NOT NULL
                    )
                    """);
        }
    }

    public synchronized Map<UUID, MuteRecord> loadMutes() throws SQLException {
        Map<UUID, MuteRecord> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM mutes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                result.put(uuid, new MuteRecord(
                        uuid,
                        rs.getString("player_name"),
                        rs.getLong("expires_at"),
                        rs.getString("reason"),
                        rs.getString("staff")
                ));
            }
        }
        return result;
    }

    public synchronized Map<UUID, SlowRecord> loadSlows(String table) throws SQLException {
        if (!table.equals("manual_slows") && !table.equals("auto_slows")) {
            throw new IllegalArgumentException("Tabla invalida");
        }

        Map<UUID, SlowRecord> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                result.put(uuid, new SlowRecord(
                        uuid,
                        rs.getString("player_name"),
                        rs.getLong("delay_ms"),
                        rs.getLong("expires_at")
                ));
            }
        }
        return result;
    }

    public synchronized void saveMute(MuteRecord record) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO mutes(uuid, player_name, expires_at, reason, staff)
                VALUES(?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name=excluded.player_name,
                    expires_at=excluded.expires_at,
                    reason=excluded.reason,
                    staff=excluded.staff
                """)) {
            ps.setString(1, record.uuid().toString());
            ps.setString(2, record.playerName());
            ps.setLong(3, record.expiresAt());
            ps.setString(4, record.reason());
            ps.setString(5, record.staff());
            ps.executeUpdate();
        }
    }

    public synchronized void deleteMute(UUID uuid) throws SQLException {
        deleteByUuid("mutes", uuid);
    }

    public synchronized void saveSlow(String table, SlowRecord record) throws SQLException {
        if (!table.equals("manual_slows") && !table.equals("auto_slows")) {
            throw new IllegalArgumentException("Tabla invalida");
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO %s(uuid, player_name, delay_ms, expires_at)
                VALUES(?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name=excluded.player_name,
                    delay_ms=excluded.delay_ms,
                    expires_at=excluded.expires_at
                """.formatted(table))) {
            ps.setString(1, record.uuid().toString());
            ps.setString(2, record.playerName());
            ps.setLong(3, record.delayMillis());
            ps.setLong(4, record.expiresAt());
            ps.executeUpdate();
        }
    }

    public synchronized void deleteSlow(String table, UUID uuid) throws SQLException {
        if (!table.equals("manual_slows") && !table.equals("auto_slows")) {
            throw new IllegalArgumentException("Tabla invalida");
        }
        deleteByUuid(table, uuid);
    }

    private void deleteByUuid(String table, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table + " WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized long loadGlobalSlow() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM justice_settings WHERE key='global_slow_ms'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                try {
                    return Long.parseLong(rs.getString(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    public synchronized void saveGlobalSlow(long millis) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO justice_settings(key,value) VALUES('global_slow_ms',?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            ps.setString(1, Long.toString(Math.max(0L, millis)));
            ps.executeUpdate();
        }
    }

    public synchronized Map<UUID, Sentence> loadOpenSentences() throws SQLException {
        Map<UUID, Sentence> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sentences WHERE status IN ('ACTIVE','RELEASING')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Sentence sentence = readSentence(rs);
                result.put(sentence.uuid(), sentence);
            }
        }
        return result;
    }

    public synchronized void createSentence(Sentence sentence) throws SQLException {
        PlayerSnapshot s = sentence.snapshot();

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sentences(
                    uuid, player_name, status, required_points, current_points,
                    reason, staff, created_at, completed_at,
                    inventory_storage, inventory_armor, inventory_offhand,
                    level, exp, total_exp, food, saturation, game_mode
                ) VALUES(?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name=excluded.player_name,
                    status=excluded.status,
                    required_points=excluded.required_points,
                    current_points=excluded.current_points,
                    reason=excluded.reason,
                    staff=excluded.staff,
                    created_at=excluded.created_at,
                    completed_at=0,
                    inventory_storage=excluded.inventory_storage,
                    inventory_armor=excluded.inventory_armor,
                    inventory_offhand=excluded.inventory_offhand,
                    level=excluded.level,
                    exp=excluded.exp,
                    total_exp=excluded.total_exp,
                    food=excluded.food,
                    saturation=excluded.saturation,
                    game_mode=excluded.game_mode
                """)) {
            int i = 1;
            ps.setString(i++, sentence.uuid().toString());
            ps.setString(i++, sentence.playerName());
            ps.setString(i++, sentence.status());
            ps.setInt(i++, sentence.requiredPoints());
            ps.setInt(i++, sentence.currentPoints());
            ps.setString(i++, sentence.reason());
            ps.setString(i++, sentence.staff());
            ps.setLong(i++, sentence.createdAt());
            ps.setString(i++, s.storage());
            ps.setString(i++, s.armor());
            ps.setString(i++, s.offhand());
            ps.setInt(i++, s.level());
            ps.setFloat(i++, s.exp());
            ps.setInt(i++, s.totalExp());
            ps.setInt(i++, s.food());
            ps.setFloat(i++, s.saturation());
            ps.setString(i, s.gameMode());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSentenceProgress(UUID uuid, int points) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sentences SET current_points=? WHERE uuid=?")) {
            ps.setInt(1, points);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSentenceStatus(UUID uuid, String status, boolean complete) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sentences SET status=?, completed_at=? WHERE uuid=?")) {
            ps.setString(1, status);
            ps.setLong(2, complete ? System.currentTimeMillis() : 0L);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    private Sentence readSentence(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        PlayerSnapshot snapshot = new PlayerSnapshot(
                rs.getString("inventory_storage"),
                rs.getString("inventory_armor"),
                rs.getString("inventory_offhand"),
                rs.getInt("level"),
                rs.getFloat("exp"),
                rs.getInt("total_exp"),
                rs.getInt("food"),
                rs.getFloat("saturation"),
                rs.getString("game_mode")
        );

        return new Sentence(
                uuid,
                rs.getString("player_name"),
                rs.getString("status"),
                rs.getInt("required_points"),
                rs.getInt("current_points"),
                rs.getString("reason"),
                rs.getString("staff"),
                rs.getLong("created_at"),
                snapshot
        );
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
