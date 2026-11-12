package com.altarkits;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Talks to the AltarSMP plugin (by reflection, so AltarKits compiles without the AltarSMP jar).
 * It calls AltarSMP's own getXxxWeapon().createWeapon(), so the kits hand out the REAL legendaries.
 *
 * The list below is in the same order as the AltarSMP plugin's weapons. The kit GUI lists them in this
 * order and each one costs more coins than the one before it.
 */
public class AltarBridge {
    /** id = kit id (matches the AltarSMP command name), getter = method on the AltarSMP main class. */
    public record Legendary(String id, String name, String getter, String fallback) {}

    public static final List<Legendary> LEGENDARIES = List.of(
            new Legendary("bloodlust", "Bloodlust", "getBloodlustWeapon", "NETHERITE_SWORD"),
            new Legendary("boneblade", "Boneblade", "getBoneBladeWeapon", "NETHERITE_SWORD"),
            new Legendary("vulcanscrossbow", "Vulcan's Crossbow", "getVulcansCrossbowWeapon", "CROSSBOW"),
            new Legendary("hyperion", "Hyperion", "getHyperionWeapon", "IRON_SWORD"),
            new Legendary("wandofillusion", "Wand of Illusion", "getWandOfIllusionWeapon", "BLAZE_ROD"),
            new Legendary("frostscythe", "Frost Scythe", "getFrostScytheWeapon", "NETHERITE_HOE"),
            new Legendary("crazyslots", "Crazy Slots", "getCrazySlotsWeapon", "NETHERITE_SWORD"),
            new Legendary("nightpiercer", "Nightpiercer", "getNightpiercerWeapon", "NETHERITE_SWORD"),
            new Legendary("windweaver", "Windweaver", "getWindweaverWeapon", "NETHERITE_SWORD"),
            new Legendary("witherbone", "Witherbone", "getWitherboneWeapon", "NETHERITE_SWORD"),
            new Legendary("shadowblade", "Shadowblade", "getShadowBladeWeapon", "NETHERITE_SWORD"),
            new Legendary("pureblade", "Pureblade", "getPureBladeWeapon", "DIAMOND_SWORD"),
            new Legendary("earthgauntlet", "Earth Gauntlet", "getEarthGauntletWeapon", "NETHERITE_AXE"),
            new Legendary("paladinbattleaxe", "Paladin Battleaxe", "getPaladinBattleAxeWeapon", "NETHERITE_AXE"),
            new Legendary("cutlass", "Cutlass", "getCutlassWeapon", "IRON_SWORD"),
            new Legendary("palecrossbow", "Pale Crossbow", "getPaleCrossbowWeapon", "CROSSBOW"),
            new Legendary("eclipsesword", "Eclipse Sword", "getEclipseSwordWeapon", "NETHERITE_SWORD"),
            new Legendary("knightfall", "Knightfall", "getKnightfallWeapon", "NETHERITE_SWORD"),
            new Legendary("striker", "Striker", "getStrikerWeapon", "NETHERITE_SWORD"),
            new Legendary("nukelauncher", "Nuke Launcher", "getNukeLauncherWeapon", "CROSSBOW"),
            new Legendary("echo", "Echo", "getEchoWeapon", "NETHERITE_SWORD"),
            new Legendary("fireslash", "Fire Slash", "getFireSlashWeapon", "NETHERITE_SWORD")
    );

    private final KitsPlugin plugin;
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private boolean warned = false;

    public AltarBridge(KitsPlugin plugin) { this.plugin = plugin; }

    public static Legendary find(String id) {
        if (id == null) return null;
        for (Legendary l : LEGENDARIES) if (l.id().equalsIgnoreCase(id)) return l;
        return null;
    }

    private Plugin altar() {
        Plugin p = Bukkit.getPluginManager().getPlugin("AltarSMP");
        return (p != null && p.isEnabled()) ? p : null;
    }

    public boolean available() { return altar() != null; }

    public String randomId() {
        return LEGENDARIES.get(ThreadLocalRandom.current().nextInt(LEGENDARIES.size())).id();
    }

    public String nameOf(String id) {
        Legendary l = find(id);
        return l == null ? id : l.name();
    }

    /** A brand new copy of the real AltarSMP weapon, or null if AltarSMP is missing / the weapon can't be made. */
    public ItemStack create(String id) {
        Legendary l = find(id);
        Plugin p = altar();
        if (l == null || p == null) return null;
        try {
            Object weapon = p.getClass().getMethod(l.getter()).invoke(p);
            if (weapon == null) return null;
            Object item = weapon.getClass().getMethod("createWeapon").invoke(weapon);
            return item instanceof ItemStack is ? is : null;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Could not create the AltarSMP weapon '" + id + "': " + t);
            }
            return null;
        }
    }

    /** Cached copy used for the kit menu icons (so the menu doesn't rebuild weapons every second). */
    public ItemStack icon(String id) {
        if (id == null) return null;
        ItemStack cached = iconCache.get(id);
        if (cached == null) {
            cached = create(id);
            if (cached != null) iconCache.put(id, cached);
        }
        return cached == null ? null : cached.clone();
    }

    public void clearCache() { iconCache.clear(); }
}
