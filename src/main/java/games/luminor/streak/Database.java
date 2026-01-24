package games.luminor.streak;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Database {
    private final Connection connection;

    public Database(File dataFolder) throws SQLException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Failed to create data folder");
        }
        File dbFile = new File(dataFolder, "streak.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS meta (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT" +
                ")"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS players (" +
                "uuid TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "lower_name TEXT, " +
                "streak_count INTEGER, " +
                "streak_freezes INTEGER, " +
                "daily_play INTEGER, " +
                "daily_req INTEGER, " +
                "daily_done INTEGER, " +
                "daily_credited INTEGER, " +
                "daily_half INTEGER, " +
                "daily_full INTEGER, " +
                "daily_welcomed TEXT" +
                ")"
            );
        }
    }

    public String getMeta(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO meta(key, value) VALUES(?, ?) " +
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public PlayerState loadPlayer(UUID uuid, String name) throws SQLException {
        PlayerState state = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    state = readPlayer(rs);
                }
            }
        }

        if (state == null) {
            state = new PlayerState(uuid);
            state.name = name;
            state.lowerName = name.toLowerCase();
            state.streakCount = 0;
            state.streakFreezes = 0;
            state.dailyPlaySeconds = 0;
            state.dailyRequiredSeconds = 0;
            state.dailyDone = false;
            state.dailyCredited = false;
            state.dailyHalfSent = false;
            state.dailyFullSent = false;
            state.dailyWelcomedKey = null;
            savePlayer(state);
        } else {
            state.name = name;
            state.lowerName = name.toLowerCase();
            updateName(state);
        }

        return state;
    }

    private PlayerState readPlayer(ResultSet rs) throws SQLException {
        PlayerState state = new PlayerState(UUID.fromString(rs.getString("uuid")));
        state.name = rs.getString("name");
        state.lowerName = rs.getString("lower_name");
        state.streakCount = rs.getInt("streak_count");
        state.streakFreezes = rs.getInt("streak_freezes");
        state.dailyPlaySeconds = rs.getInt("daily_play");
        state.dailyRequiredSeconds = rs.getInt("daily_req");
        state.dailyDone = rs.getInt("daily_done") == 1;
        state.dailyCredited = rs.getInt("daily_credited") == 1;
        state.dailyHalfSent = rs.getInt("daily_half") == 1;
        state.dailyFullSent = rs.getInt("daily_full") == 1;
        state.dailyWelcomedKey = rs.getString("daily_welcomed");
        return state;
    }

    public void savePlayer(PlayerState state) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO players(uuid, name, lower_name, streak_count, streak_freezes, daily_play, daily_req, " +
            "daily_done, daily_credited, daily_half, daily_full, daily_welcomed) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(uuid) DO UPDATE SET " +
            "name=excluded.name, lower_name=excluded.lower_name, streak_count=excluded.streak_count, " +
            "streak_freezes=excluded.streak_freezes, daily_play=excluded.daily_play, daily_req=excluded.daily_req, " +
            "daily_done=excluded.daily_done, daily_credited=excluded.daily_credited, daily_half=excluded.daily_half, " +
            "daily_full=excluded.daily_full, daily_welcomed=excluded.daily_welcomed")) {
            ps.setString(1, state.uuid.toString());
            ps.setString(2, state.name);
            ps.setString(3, state.lowerName);
            ps.setInt(4, state.streakCount);
            ps.setInt(5, state.streakFreezes);
            ps.setInt(6, state.dailyPlaySeconds);
            ps.setInt(7, state.dailyRequiredSeconds);
            ps.setInt(8, state.dailyDone ? 1 : 0);
            ps.setInt(9, state.dailyCredited ? 1 : 0);
            ps.setInt(10, state.dailyHalfSent ? 1 : 0);
            ps.setInt(11, state.dailyFullSent ? 1 : 0);
            ps.setString(12, state.dailyWelcomedKey);
            ps.executeUpdate();
        }
    }

    private void updateName(PlayerState state) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE players SET name=?, lower_name=? WHERE uuid=?")) {
            ps.setString(1, state.name);
            ps.setString(2, state.lowerName);
            ps.setString(3, state.uuid.toString());
            ps.executeUpdate();
        }
    }

    public PlayerState getPlayerByLowerName(String lowerName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM players WHERE lower_name=?")) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readPlayer(rs);
                }
            }
        }
        return null;
    }

    public List<PlayerState> loadAllPlayers() throws SQLException {
        List<PlayerState> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM players")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readPlayer(rs));
                }
            }
        }
        return list;
    }

    public List<PlayerState> loadTopStreak(int limit) throws SQLException {
        List<PlayerState> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT uuid, name, lower_name, streak_count FROM players ORDER BY streak_count DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerState state = new PlayerState(UUID.fromString(rs.getString("uuid")));
                    state.name = rs.getString("name");
                    state.lowerName = rs.getString("lower_name");
                    state.streakCount = rs.getInt("streak_count");
                    list.add(state);
                }
            }
        }
        return list;
    }

    public void resetAll() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE players SET streak_count=0, streak_freezes=0, daily_play=0, daily_done=0, daily_credited=0, " +
            "daily_half=0, daily_full=0, daily_welcomed=NULL, daily_req=0")) {
            ps.executeUpdate();
        }
    }

    public void close() throws SQLException {
        connection.close();
    }
}
