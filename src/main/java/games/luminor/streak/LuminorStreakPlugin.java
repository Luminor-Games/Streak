package games.luminor.streak;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public class LuminorStreakPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Database database;
    private StreakService streakService;
    private HttpServerManager httpServer;
    private StatsReader statsReader;

    private int freezeMax;
    private String permUpdate;
    private String permReset;
    private String permReload;
    private String monitoringLink;
    private int httpPort;
    private String httpHash;
    private List<String> halfMessages;
    private List<String> fullMessages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        try {
            database = new Database(getDataFolder());
        } catch (SQLException e) {
            getLogger().severe("Failed to open SQLite database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        streakService = new StreakService(this, database);
        statsReader = new StatsReader(getPrimaryWorldFolder());

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("streak").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, streakService::tickMinute, 20L * 60L, 20L * 60L);

        httpServer = new HttpServerManager(this, streakService);
        try {
            httpServer.start();
        } catch (IOException e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
        }

        try {
            if (database.getMeta("day.key") == null) {
                database.setMeta("day.key", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            }
        } catch (SQLException e) {
            getLogger().warning("Failed to initialize day key: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (streakService != null) {
            streakService.saveAllOnline();
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().warning("Failed to close SQLite database: " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        streakService.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        streakService.onQuit(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            if (sender instanceof Player) {
                streakService.showHelp((Player) sender);
            } else {
                sender.sendMessage("Streak help is player-only");
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("STREAK: top is player-only");
                return true;
            }

            List<PlayerState> top = streakService.getTopStreak(10);
            sender.sendMessage(color("&6🔥 Топ 10 по стрику"));
            int pos = 1;
            for (PlayerState state : top) {
                String name = state.name == null ? "Unknown" : state.name;
                sender.sendMessage(color("&7" + pos + ". &f" + name + " &8— &e" + state.streakCount + " дн."));
                pos++;
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
            if (sender instanceof Player) {
                if (!sender.hasPermission(permUpdate)) {
                    sender.sendMessage(color("&cНет прав."));
                    return true;
                }
            }
            streakService.updateDay(sender);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("resetall")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage(color("&cКоманда только для консоли."));
                return true;
            }
            streakService.resetAll(sender);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (sender instanceof Player) {
                if (!sender.hasPermission(permReload)) {
                    sender.sendMessage(color("&cНет прав."));
                    return true;
                }
            }
            reloadConfig();
            reloadSettings();
            sender.sendMessage(color("&aOK конфиг перезагружен"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("STREAK: reset is player-only");
                return true;
            }

            Player player = (Player) sender;
            if (!player.hasPermission(permReset)) {
                player.sendMessage(color("&cНет прав."));
                return true;
            }

            if (args.length > 1) {
                String nick = args[1].toLowerCase();
                PlayerState state = streakService.findByLowerName(nick);
                if (state == null) {
                    player.sendMessage(color("&cERR unknown_player name=" + args[1]));
                    return true;
                }
                if (streakService.resetPlayer(state.uuid, state.name == null ? args[1] : state.name)) {
                    player.sendMessage(color("&aOK streak reset for " + args[1]));
                    getLogger().info("STREAK: reset name=" + args[1] + " uuid=" + state.uuid);
                } else {
                    player.sendMessage(color("&cERR reset failed"));
                }
            } else {
                if (streakService.resetPlayer(player.getUniqueId(), player.getName())) {
                    player.sendMessage(color("&aOK your streak reset"));
                    getLogger().info("STREAK: reset uuid=" + player.getUniqueId());
                } else {
                    player.sendMessage(color("&cERR reset failed"));
                }
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("STREAK: status is player-only");
            return true;
        }

        Player player = (Player) sender;
        PlayerState state = streakService.getCached(player.getUniqueId());
        if (state == null) {
            state = streakService.loadOrCreate(player);
        }
        streakService.showStatus(player, state);
        return true;
    }

    private void reloadSettings() {
        freezeMax = getConfig().getInt("options.freeze_max", 2);
        permUpdate = getConfig().getString("options.perm_update_streak", "luminor.admin.streakupdate");
        permReset = getConfig().getString("options.perm_reset_streak", "luminor.admin.streakreset");
        permReload = getConfig().getString("options.perm_reload_streak", "luminor.admin.streakreload");
        monitoringLink = getConfig().getString("options.monitoring_link", "");
        httpPort = getConfig().getInt("http.port", 8085);
        httpHash = getConfig().getString("http.hash", "");
        halfMessages = getConfig().getStringList("messages.half");
        fullMessages = getConfig().getStringList("messages.full");
    }

    private File getPrimaryWorldFolder() {
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().get(0).getWorldFolder();
        }
        return new File(getServer().getWorldContainer(), "world");
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public int getFreezeMax() {
        return freezeMax;
    }

    public String getPermUpdate() {
        return permUpdate;
    }

    public String getPermReset() {
        return permReset;
    }

    public String getMonitoringLink() {
        return monitoringLink;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public String getHttpHash() {
        return httpHash;
    }

    public List<String> getHalfMessages() {
        return halfMessages;
    }

    public List<String> getFullMessages() {
        return fullMessages;
    }

    public StatsReader getStatsReader() {
        return statsReader;
    }
}
