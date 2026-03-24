package games.luminor.streak;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StreakService {
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LuminorStreakPlugin plugin;
    private final Database database;
    private final Random random = new Random();
    private final Map<UUID, PlayerState> cache = new ConcurrentHashMap<>();

    private long updateTimeMills;

    public StreakService(LuminorStreakPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        updateTimeMills = calculateTime();
    }

    public void onJoin(Player player) {
        PlayerState state = loadOrCreate(player);
        ensureDailyRequirement(state);
        cache.put(player.getUniqueId(), state);
        try {
            database.savePlayer(state);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save player on join: " + e.getMessage());
        }
        sendJoinStatus(player, state);
    }

    public void onQuit(Player player) {
        PlayerState state = cache.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        try {
            database.savePlayer(state);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save player on quit: " + e.getMessage());
        }
    }

    public void saveAllOnline() {
        for (PlayerState state : cache.values()) {
            try {
                database.savePlayer(state);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save player state: " + e.getMessage());
            }
        }
        cache.clear();
    }

    public void tickMinute() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState state = cache.get(player.getUniqueId());
            if (state == null) {
                state = loadOrCreate(player);
                cache.put(player.getUniqueId(), state);
            }

            ensureDailyRequirement(state);

            state.dailyPlaySeconds += 60;

            if (!state.dailyHalfSent && state.dailyPlaySeconds >= (state.dailyRequiredSeconds / 2)) {
                state.dailyHalfSent = true;
                sendRandomMessage(player, plugin.getHalfMessages());
                player.sendMessage(color("&8/streak &7— статус"));
                player.sendMessage(color("&8/streak help &7— как это работает"));
            }

            if (!state.dailyFullSent && state.dailyPlaySeconds >= state.dailyRequiredSeconds) {
                state.dailyFullSent = true;
                state.dailyDone = true;
                if (!state.dailyCredited) {
                    state.streakCount += 1;
                    state.dailyCredited = true;
                }
                sendRandomMessage(player, plugin.getFullMessages());
                player.sendMessage(color("&8/streak &7— статус"));
                player.sendMessage(color("&8/streak help &7— как это работает"));
            }
        }

        if (updateTimeMills <= System.currentTimeMillis()) {
            updateDay(null);
        }
    }

    private void log(String msg, CommandSender sender) {
        if (sender == null) plugin.getLogger().info(msg);
        else sender.sendMessage(msg);
    }

    public long calculateTime() { // calculate update time in mills on Moscow
        ZonedDateTime zonedUpdateTime = ZonedDateTime.of(
                LocalDate.now().plusDays(1).atTime(0, 0),
                ZoneId.of("Europe/Moscow")
        );
        return zonedUpdateTime.toInstant().toEpochMilli();
    }

    public void showStatus(CommandSender sender, PlayerState state) {
        int needMin = (int) Math.ceil(state.dailyRequiredSeconds / 60.0);
        int doneMin = (int) Math.floor(state.dailyPlaySeconds / 60.0);

        sender.sendMessage(color("&6🔥 Ваш ежедневный огонёк"));
        sender.sendMessage(color("&7Стрик: &f" + state.streakCount + "&7 дн."));
        sender.sendMessage(color("&7Заморозки: &b❄ " + state.streakFreezes + "&7/&f" + plugin.getFreezeMax()));
        sender.sendMessage(color("&7Сегодня: &e" + doneMin + "&7/&e" + needMin + " мин."));
        if (state.dailyCredited) {
            sender.sendMessage(color("&aЗасчитано сегодня ✔ &8(огонёк уже начислен)"));
        } else {
            sender.sendMessage(color("&8Ещё не засчитано"));
        }
        sender.sendMessage(color("&8/streak help &7— как это работает"));
    }

    public void sendJoinStatus(Player player, PlayerState state) {
        int needMin = (int) Math.ceil(state.dailyRequiredSeconds / 60.0);
        int doneMin = (int) Math.floor(state.dailyPlaySeconds / 60.0);

        player.sendMessage(color("&6🔥 Твой огонёк"));
        player.sendMessage(color("&7Сегодня: &e" + doneMin + "&7/&e" + needMin + " мин. &8(можно суммарно)"));
        player.sendMessage(color("&7Стрик: &f" + state.streakCount + " &8| &b❄ Заморозки: &f" + state.streakFreezes + "&7/&f" + plugin.getFreezeMax()));
        if (state.dailyCredited) {
            player.sendMessage(color("&aЗасчитано сегодня ✔ &8(+1 уже начислен)"));
        } else {
            player.sendMessage(color("&8Ещё не засчитано — успеешь 🙂"));
        }
        player.sendMessage(color("&8/streak &7— статус"));
        player.sendMessage(color("&8/streak help &7— как это работает"));
    }

    public void showHelp(Player player) {
        player.sendMessage(color("&6🔥 Ежедневный огонёк — как это работает"));
        player.sendMessage(color("&7Каждый день нужно поиграть &e15–45 минут&7 (суммарно)."));
        player.sendMessage(color("&7Когда прогресс достигает &e100%&7 —"));
        player.sendMessage(color("&a•&7 огонёк &aсразу засчитывается&7 и стрик увеличивается на &a+1"));
        player.sendMessage(color("&7Раз в день система делает обновление дня:"));
        player.sendMessage(color("&c•&7 если огонёк &cне был засчитан&7 — тратится &b❄ заморозка&7 (макс. 2) или стрик сбрасывается"));
        player.sendMessage(color("&7После обновления выдаётся новое задание на следующий день."));
        player.sendMessage("");
        player.sendMessage(color("&7Заморозки можно получить за голосование:"));
        player.sendMessage(color("&b" + plugin.getMonitoringLink()));
        player.sendMessage("");
        player.sendMessage(color("&8Команды: &f/streak&7, &f/streak top"));
    }

    public boolean updateDay(CommandSender sender) {
        String today = LocalDate.now(ZoneId.systemDefault()).format(DAY_FORMAT);
        updateTimeMills = calculateTime();

        try {
            String dayKey = database.getMeta("day.key");
            if (today.equals(dayKey)) {
                log("STREAK: update skipped (already updated today) day=" + dayKey, sender);
                return false;
            }

            database.setMeta("day.key", today);
            log("STREAK: update fired day=" + today, sender);

            List<PlayerState> players = database.loadAllPlayers();
            for (PlayerState state : players) {
                if (!state.dailyCredited) {
                    if (state.streakFreezes > 0) {
                        state.streakFreezes -= 1;
                    } else {
                        state.streakCount = 0;
                    }
                }

                state.dailyPlaySeconds = 0;
                state.dailyDone = false;
                state.dailyCredited = false;
                state.dailyHalfSent = false;
                state.dailyFullSent = false;
                state.dailyWelcomedKey = null;
                state.dailyRequiredSeconds = randomRequirementSeconds();

                database.savePlayer(state);

                PlayerState cached = cache.get(state.uuid);
                if (cached != null) {
                    cached.streakCount = state.streakCount;
                    cached.streakFreezes = state.streakFreezes;
                    cached.dailyPlaySeconds = state.dailyPlaySeconds;
                    cached.dailyRequiredSeconds = state.dailyRequiredSeconds;
                    cached.dailyDone = state.dailyDone;
                    cached.dailyCredited = state.dailyCredited;
                    cached.dailyHalfSent = state.dailyHalfSent;
                    cached.dailyFullSent = state.dailyFullSent;
                    cached.dailyWelcomedKey = state.dailyWelcomedKey;
                }
            }

            log("STREAK: update finished", sender);
            return true;
        } catch (SQLException e) {
            log("STREAK: update failed: " + e.getMessage(), sender);
            return false;
        }
    }

    public void resetAll(CommandSender sender) {
        try {
            database.resetAll();
            sender.sendMessage("STREAK: resetall finished");
        } catch (SQLException e) {
            sender.sendMessage("STREAK: resetall failed: " + e.getMessage());
        }
    }

    public boolean resetPlayer(UUID uuid, String name) {
        PlayerState state;
        try {
            state = database.loadPlayer(uuid, name);
            state.streakCount = 0;
            state.streakFreezes = 0;
            state.dailyPlaySeconds = 0;
            state.dailyDone = false;
            state.dailyCredited = false;
            state.dailyHalfSent = false;
            state.dailyFullSent = false;
            state.dailyWelcomedKey = null;
            state.dailyRequiredSeconds = randomRequirementSeconds();
            database.savePlayer(state);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to reset player: " + e.getMessage());
            return false;
        }
    }

    public PlayerState getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public PlayerState loadOrCreate(Player player) {
        try {
            return database.loadPlayer(player.getUniqueId(), player.getName());
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load player: " + e.getMessage());
            return new PlayerState(player.getUniqueId());
        }
    }

    public List<PlayerState> getTopStreak(int limit) {
        try {
            return database.loadTopStreak(limit);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load streak top: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<PlayerState> getAllPlayers() {
        try {
            return database.loadAllPlayers();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load players: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public PlayerState findByLowerName(String lowerName) {
        try {
            return database.getPlayerByLowerName(lowerName);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to find player by name: " + e.getMessage());
            return null;
        }
    }

    public void updateCache(PlayerState state) {
        cache.put(state.uuid, state);
    }

    public void saveState(PlayerState state) {
        try {
            database.savePlayer(state);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save player state: " + e.getMessage());
        }
    }

    private void ensureDailyRequirement(PlayerState state) {
        if (state.dailyRequiredSeconds <= 0) {
            state.dailyRequiredSeconds = randomRequirementSeconds();
        }
    }

    public int randomRequirementSeconds() {
        int pick = random.nextInt(4);
        if (pick == 0) {
            return 900;
        } else if (pick == 1) {
            return 1200;
        } else if (pick == 2) {
            return 1800;
        }
        return 2700;
    }

    private void sendRandomMessage(Player player, List<String> messages) {
        if (messages.isEmpty()) {
            return;
        }
        int idx = random.nextInt(messages.size());
        player.sendMessage(color(messages.get(idx)));
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}
