package games.luminor.streak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.util.UUID;

public class StatsReader {
    private final File statsFolder;

    public StatsReader(File worldFolder) {
        this.statsFolder = new File(worldFolder, "stats");
    }

    public long getPlayTimeTicks(UUID uuid) {
        File statsFile = new File(statsFolder, uuid.toString() + ".json");
        if (!statsFile.exists()) {
            return 0L;
        }

        try (FileReader reader = new FileReader(statsFile)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                return 0L;
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonObject stats = root.getAsJsonObject("stats");
            if (stats == null) {
                return 0L;
            }
            JsonObject custom = stats.getAsJsonObject("minecraft:custom");
            if (custom == null) {
                return 0L;
            }
            JsonElement play = custom.get("minecraft:play_one_minute");
            if (play == null || !play.isJsonPrimitive()) {
                return 0L;
            }
            return play.getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
