package games.luminor.streak;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class HttpServerManager {
    private final LuminorStreakPlugin plugin;
    private final StreakService streakService;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private HttpServer server;

    public HttpServerManager(LuminorStreakPlugin plugin, StreakService streakService) {
        this.plugin = plugin;
        this.streakService = streakService;
    }

    public void start() throws IOException {
        int port = plugin.getHttpPort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        plugin.getLogger().info("HTTP server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 3) {
            sendEmpty(exchange, 404);
            return;
        }

        String hash = parts[1];
        if (!hash.equals(plugin.getHttpHash())) {
            sendEmpty(exchange, 404);
            return;
        }

        String endpoint = parts[2];
        if (endpoint.equalsIgnoreCase("addfreeze")) {
            handleAddFreeze(exchange);
            return;
        }
        if (endpoint.equalsIgnoreCase("streaktop")) {
            handleStreakTop(exchange);
            return;
        }
        if (endpoint.equalsIgnoreCase("onlinetop")) {
            handleOnlineTop(exchange);
            return;
        }

        sendEmpty(exchange, 404);
    }

    private void handleAddFreeze(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String nick = params.get("nick");
        JsonObject response = new JsonObject();

        if (nick == null || nick.isBlank()) {
            response.addProperty("status", "error");
            response.addProperty("error", "nick_required");
            sendJson(exchange, 400, response);
            return;
        }

        FutureTask<JsonObject> task = new FutureTask<>(() -> {
            PlayerState state = streakService.findByLowerName(nick.toLowerCase());
            if (state == null) {
                JsonObject notFound = new JsonObject();
                notFound.addProperty("status", "ignored");
                notFound.addProperty("reason", "unknown_player");
                return notFound;
            }

            int max = plugin.getFreezeMax();
            boolean changed = false;
            if (state.streakFreezes < max) {
                state.streakFreezes += 1;
                changed = true;
            }

            streakService.saveState(state);

            PlayerState cached = streakService.getCached(state.uuid);
            if (cached != null) {
                cached.streakFreezes = state.streakFreezes;
                streakService.updateCache(cached);
            }

            JsonObject ok = new JsonObject();
            ok.addProperty("status", "ok");
            ok.addProperty("changed", changed);
            ok.addProperty("freezes", state.streakFreezes);
            return ok;
        });

        Bukkit.getScheduler().runTask(plugin, task);
        JsonObject result;
        try {
            result = task.get();
        } catch (Exception e) {
            response.addProperty("status", "error");
            response.addProperty("error", "internal");
            sendJson(exchange, 500, response);
            return;
        }

        sendJson(exchange, 200, result);
    }

    private void handleStreakTop(HttpExchange exchange) throws IOException {
        List<PlayerState> top = streakService.getTopStreak(10);
        JsonArray array = new JsonArray();
        for (PlayerState state : top) {
            JsonObject item = new JsonObject();
            item.addProperty("name", state.name == null ? "Unknown" : state.name);
            item.addProperty("streak", state.streakCount);
            array.add(item);
        }

        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.add("items", array);
        sendJson(exchange, 200, response);
    }

    private void handleOnlineTop(HttpExchange exchange) throws IOException {
        List<PlayerState> players = streakService.getAllPlayers();
        List<OnlineEntry> entries = new ArrayList<>();

        for (PlayerState state : players) {
            long ticks = plugin.getStatsReader().getPlayTimeTicks(state.uuid);
            entries.add(new OnlineEntry(state.name == null ? "Unknown" : state.name, ticks));
        }

        entries.sort(Comparator.comparingLong(a -> -a.ticks));
        JsonArray array = new JsonArray();
        int limit = Math.min(10, entries.size());
        for (int i = 0; i < limit; i++) {
            OnlineEntry entry = entries.get(i);
            double hours = entry.ticks / 20.0 / 3600.0;
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name);
            item.addProperty("hours", Math.round(hours * 100.0) / 100.0);
            item.addProperty("ticks", entry.ticks);
            array.add(item);
        }

        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.add("items", array);
        sendJson(exchange, 200, response);
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = decode(pair.substring(0, idx));
            String value = decode(pair.substring(idx + 1));
            params.put(key, value);
        }
        return params;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int code, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendEmpty(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    private static class OnlineEntry {
        final String name;
        final long ticks;

        OnlineEntry(String name, long ticks) {
            this.name = name;
            this.ticks = ticks;
        }
    }
}
