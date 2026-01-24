package games.luminor.streak;

import java.util.UUID;

public class PlayerState {
    public final UUID uuid;
    public String name;
    public String lowerName;

    public int streakCount;
    public int streakFreezes;

    public int dailyPlaySeconds;
    public int dailyRequiredSeconds;
    public boolean dailyDone;
    public boolean dailyCredited;
    public boolean dailyHalfSent;
    public boolean dailyFullSent;
    public String dailyWelcomedKey;

    public PlayerState(UUID uuid) {
        this.uuid = uuid;
    }
}
