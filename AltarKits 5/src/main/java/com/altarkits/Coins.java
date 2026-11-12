package com.altarkits;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player coin balances, saved in coins.yml (UUID -> balance). */
public class Coins {
    private final File file;
    private final Map<UUID, Integer> data = new HashMap<>();
    private boolean dirty = false;

    public Coins(KitsPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "coins.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String u : y.getKeys(false)) {
            try { data.put(UUID.fromString(u), y.getInt(u)); } catch (IllegalArgumentException ignored) {}
        }
    }

    public int get(UUID id) { return data.getOrDefault(id, 0); }

    public void set(UUID id, int amount) {
        data.put(id, Math.max(0, amount));
        dirty = true;
    }

    /** Adds coins (a negative amount subtracts, never below 0). */
    public void add(UUID id, int amount) { set(id, get(id) + amount); }

    /** Removes coins only if the player has enough. Returns true if it worked. */
    public boolean take(UUID id, int amount) {
        if (get(id) < amount) return false;
        set(id, get(id) - amount);
        return true;
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        data.forEach((u, v) -> { if (v > 0) y.set(u.toString(), v); });
        try { y.save(file); dirty = false; } catch (IOException ex) { ex.printStackTrace(); }
    }
}
