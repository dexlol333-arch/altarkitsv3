package com.altarkits;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** A kit. Reads straight from kits.yml so admin edits apply instantly. */
public class Kit {
    public final String id;
    public final ConfigurationSection sec;

    public Kit(String id, ConfigurationSection sec) {
        this.id = id;
        this.sec = sec;
    }

    public String name() { return sec.getString("name", id); }
    public int slot() { return sec.getInt("slot", -1); }
    public int cooldown() { return sec.getInt("cooldown", 0); }
    /** Coins charged every time the kit is equipped (0 = free). */
    public int cost() { return Math.max(0, sec.getInt("cost", 0)); }
    public String permission() { return sec.getString("permission", ""); }
    public String storeText() { return sec.getString("store-text", "&7Buy this kit on our store!"); }
    public List<String> description() { return sec.getStringList("description"); }

    /**
     * Which AltarSMP legendary this kit gives: the "weapon.altar" value ("random" = a random one),
     * or the kit id itself when it matches an AltarSMP weapon. Empty = no altar weapon.
     */
    public String altarId() {
        String a = sec.getString("weapon.altar", "");
        if (!a.isEmpty()) return a.toLowerCase();
        return AltarBridge.find(id) != null ? id : "";
    }

    public Material icon() {
        Material m = Material.matchMaterial(sec.getString("icon", "DIAMOND_SWORD"));
        return m == null ? Material.IRON_INGOT : m;
    }
}
