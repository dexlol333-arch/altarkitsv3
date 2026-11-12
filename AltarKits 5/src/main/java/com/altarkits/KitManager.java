package com.altarkits;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class KitManager {
    private final KitsPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;
    public final Map<String, Kit> kits = new LinkedHashMap<>();
    private int nextSlot = 0;
    // GUI slots used by kits (7 per row, 4 rows inside the border) = 28 kits
    private static final int[] SLOTS = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34, 37,38,39,40,41,42,43};

    public KitManager(KitsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kits.yml");
    }

    // ---------- text helpers ----------
    public static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String fmt(int s) {
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m " + String.format("%02d", sec) + "s";
        if (m > 0) return m + "m " + String.format("%02d", sec) + "s";
        return sec + "s";
    }

    // ---------- load / save ----------
    public void load() {
        if (!file.exists()) generateDefaults();
        cfg = YamlConfiguration.loadConfiguration(file);
        kits.clear();
        ConfigurationSection s = cfg.getConfigurationSection("kits");
        if (s == null) return;
        for (String id : s.getKeys(false)) kits.put(id, new Kit(id, s.getConfigurationSection(id)));
        plugin.altar().clearCache();
    }

    public void save() {
        try { cfg.save(file); } catch (IOException e) { e.printStackTrace(); }
    }

    /** Backs up kits.yml to kits.yml.bak and writes the default kits again (new costs, new layout). */
    public void regenerate() {
        try {
            if (file.exists())
                Files.copy(file.toPath(), new File(plugin.getDataFolder(), "kits.yml.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { e.printStackTrace(); }
        nextSlot = 0;
        generateDefaults();
        load();
    }

    public Kit get(String id) { return id == null ? null : kits.get(id.toLowerCase()); }

    // ---------- default kits ----------
    private ConfigurationSection add(YamlConfiguration y, String id, String name, String icon, String perm, int cd, int cost, String... desc) {
        ConfigurationSection s = y.createSection("kits." + id);
        s.set("name", name);
        s.set("icon", icon);
        s.set("slot", SLOTS[Math.min(nextSlot++, SLOTS.length - 1)]);
        s.set("permission", perm);
        s.set("cooldown", cd);
        s.set("cost", cost);
        s.set("description", Arrays.asList(desc));
        return s;
    }

    private void generateDefaults() {
        YamlConfiguration y = new YamlConfiguration();
        String stats = "&7Prot 3 &8| &7Sharp 4 &8| &7Unbreaking 3 &8| &7Mending 1";

        // FREE infinite kit (no permission, no cooldown, no cost)
        add(y, "free", "&a&lFREE KIT", "DIAMOND_SWORD", "", 0, 0,
                "&7Maxed diamond kit.", "&aFree forever &7- no cooldown!");

        // RANK KITS - permission based (LuckPerms), free to claim once you own the rank
        String[][] ranks = {
                {"vip", "&a&lVIP KIT", "kit.rank.vip", "&aVIP"},
                {"vipplus", "&b&lVIP+ KIT", "kit.rank.vipplus", "&bVIP+"},
                {"platinum", "&f&lPLATINUM KIT", "kit.rank.platinum", "&fPlatinum"},
                {"elite", "&6&lELITE KIT", "kit.rank.elite", "&6Elite"}};
        for (String[] r : ranks) {
            ConfigurationSection s = add(y, r[0], r[1], "NETHERITE_SWORD", r[2], 1800, 0,
                    "&7Exclusive " + r[3] + " &7rank kit.");
            s.set("armor", "NETHERITE");
            s.set("store-text", "&7Requires the " + r[3] + " &7rank. &eBuy it on our store!");
        }

        // RIB KIT - only players with the LuckPerms permission kit.rib can use it. Random altar weapon each time.
        ConfigurationSection rib = add(y, "rib", "&c&lRIB KIT", "BONE", "kit.rib", 1800, 0,
                "&7The exclusive &cRIB &7kit.", "&7Comes with a &6random altar weapon&7.", stats);
        rib.set("store-text", "&7Only for &cRIB &7members.");
        rib.set("weapon.altar", "random");
        rib.set("weapon.material", "NETHERITE_SWORD");

        // ALTAR KITS: one per AltarSMP legendary, each one costs MORE coins than the one before it
        int base = plugin.getConfig().getInt("coins.kit-base-cost", 50);
        int step = plugin.getConfig().getInt("coins.kit-cost-step", 25);
        int i = 0;
        for (AltarBridge.Legendary l : AltarBridge.LEGENDARIES) {
            ConfigurationSection s = add(y, l.id(), "&6&l" + l.name().toUpperCase() + " KIT", l.fallback(), "", 25 * 60,
                    base + step * i,
                    "&7Comes with the real &6" + l.name() + "&7.", stats);
            s.set("weapon.altar", l.id());
            s.set("weapon.material", l.fallback());   // only used if the AltarSMP plugin isn't installed
            s.set("weapon.name", "&6&l" + l.name());
            i++;
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException e) { e.printStackTrace(); }
    }

    // ---------- access / claim ----------
    public boolean hasAccess(Player p, Kit k) {
        return k.permission().isEmpty() || p.hasPermission(k.permission());
    }

    /** Does this player pay coins for this kit? (cost 0 or kit.bypass.cost = no) */
    public boolean pays(Player p, Kit k) {
        return k.cost() > 0 && !p.hasPermission("kit.bypass.cost");
    }

    public boolean claim(Player p, Kit k) {
        if (!hasAccess(p, k)) {
            p.sendMessage(c("&c✘ You don't own the " + k.name() + "&c. " + k.storeText()));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        int rem = plugin.cooldowns().get(p.getUniqueId(), k.id);
        if (rem > 0 && !p.hasPermission("kit.bypass.cooldown")) {
            p.sendMessage(c("&c✘ " + k.name() + " &cis on cooldown for &e" + fmt(rem) + "&c."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        boolean pay = pays(p, k);
        int have = plugin.coins().get(p.getUniqueId());
        if (pay && have < k.cost()) {
            p.sendMessage(c("&c✘ " + k.name() + " &ccosts &e" + k.cost() + " coins&c. You have &e" + have + "&c. Kill players to earn more!"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        give(p, k);
        if (pay) plugin.coins().take(p.getUniqueId(), k.cost());
        if (k.cooldown() > 0) plugin.cooldowns().set(p.getUniqueId(), k.id, k.cooldown());
        p.sendMessage(c("&a✔ You equipped the " + k.name() + "&a!" + (pay ? " &7(-" + k.cost() + " coins)" : "")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        return true;
    }

    // ---------- building the kit ----------
    private static Enchantment ench(String n) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(n.toLowerCase()));
    }

    private static ItemStack enchanted(Material m, String... list) {
        ItemStack it = new ItemStack(m);
        for (String e : list) {
            String[] sp = e.split(":");
            Enchantment en = ench(sp[0]);
            if (en != null) it.addUnsafeEnchantment(en, Integer.parseInt(sp[1]));
        }
        return it;
    }

    private static ItemStack pot(Material m, PotionType t, int amt) {
        ItemStack i = new ItemStack(m, amt);
        PotionMeta pm = (PotionMeta) i.getItemMeta();
        pm.setBasePotionType(t);
        i.setItemMeta(pm);
        return i;
    }

    private static ItemStack named(ItemStack it, String name) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        it.setItemMeta(meta);
        return it;
    }

    /**
     * The altar weapon for a kit (goes in the slot where the ender chest used to be).
     * Order: an admin-saved item (/kitadmin setweapon) > the real AltarSMP weapon > a vanilla stand-in.
     * Returns null when the kit has no weapon at all (free / rank kits).
     */
    public ItemStack weaponFor(Kit k) {
        ItemStack saved = k.sec.getItemStack("weapon.item");
        if (saved != null) return saved.clone();

        String altar = k.altarId();
        if (!altar.isEmpty()) {
            String id = altar.equals("random") ? plugin.altar().randomId() : altar;
            ItemStack real = plugin.altar().create(id);
            if (real != null) return real;
        }

        String matName = k.sec.getString("weapon.material", "");
        if (matName.isEmpty()) return null;
        Material m = Material.matchMaterial(matName);
        if (m == null) m = Material.DIAMOND_SWORD;
        ItemStack it;
        if (m == Material.CROSSBOW) {
            it = enchanted(m, "QUICK_CHARGE:3", "PIERCING:4", "UNBREAKING:3", "MENDING:1");
        } else if (m.name().endsWith("_AXE")) {
            it = enchanted(m, "SHARPNESS:4", "UNBREAKING:3", "MENDING:1", "EFFICIENCY:5");
        } else {
            it = enchanted(m, "SHARPNESS:4", "UNBREAKING:3", "MENDING:1");
        }
        String nm = k.sec.getString("weapon.name");
        return nm == null ? it : named(it, nm);
    }

    /** Every armor piece: Protection 3, Unbreaking 3, Mending 1. */
    private ItemStack armorPiece(String base, String piece) {
        Material m = Material.matchMaterial(base + "_" + piece);
        if (m == null) m = Material.matchMaterial("DIAMOND_" + piece);
        return enchanted(m, "PROTECTION:3", "UNBREAKING:3", "MENDING:1");
    }

    public void give(Player p, Kit k) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);

        ConfigurationSection custom = k.sec.getConfigurationSection("custom");
        if (custom != null && !custom.getKeys(false).isEmpty()) {
            // Admin-saved inventory (/kitadmin saveinv)
            for (String key : custom.getKeys(false)) {
                ItemStack it = custom.getItemStack(key);
                if (it != null) inv.setItem(Integer.parseInt(key), it.clone());
            }
            plugin.giveSelector(p);
            return;
        }

        // ---------- armor ----------
        String base = k.sec.getString("armor", "DIAMOND");
        String oSlot = k.sec.getString("armor-override.slot", "");
        String oMat = k.sec.getString("armor-override.material", "");
        String[] pieces = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        ItemStack[] armor = new ItemStack[4]; // boots, legs, chest, helmet
        for (int i = 0; i < 4; i++) {
            String piece = pieces[i];
            ItemStack it;
            if (piece.equals(oSlot) && Material.matchMaterial(oMat) != null) {
                it = armorPiece(oMat.replace("_" + piece, ""), piece);
            } else {
                it = armorPiece(base, piece);
            }
            armor[3 - i] = it;
        }
        inv.setArmorContents(armor);

        // ---------- off hand: green shield ----------
        ItemStack shield = enchanted(Material.SHIELD, "UNBREAKING:3", "MENDING:1");
        shield.setData(DataComponentTypes.BASE_COLOR, DyeColor.GREEN);
        inv.setItemInOffHand(shield);

        // ---------- hotbar (slots 0-8) ----------
        inv.setItem(0, enchanted(Material.DIAMOND_SWORD, "SHARPNESS:4", "UNBREAKING:3", "MENDING:1"));
        inv.setItem(1, enchanted(Material.DIAMOND_PICKAXE, "EFFICIENCY:5", "UNBREAKING:3", "MENDING:1"));
        inv.setItem(2, heal());
        inv.setItem(3, new ItemStack(Material.COBWEB, 64));
        inv.setItem(4, new ItemStack(Material.GOLDEN_APPLE, 64));
        inv.setItem(5, enchanted(Material.DIAMOND_AXE, "SHARPNESS:4", "EFFICIENCY:5", "UNBREAKING:3", "MENDING:1"));
        inv.setItem(6, heal());
        inv.setItem(7, new ItemStack(Material.WIND_CHARGE, 64));
        inv.setItem(8, new ItemStack(Material.WATER_BUCKET));

        // ---------- row 1 (slots 9-17) ----------
        inv.setItem(9, fire());
        inv.setItem(10, speed());
        inv.setItem(11, strength());
        inv.setItem(12, heal());
        inv.setItem(13, heal());
        inv.setItem(14, new ItemStack(Material.STONE, 64));
        inv.setItem(15, new ItemStack(Material.WATER_BUCKET));
        inv.setItem(16, pot(Material.TIPPED_ARROW, PotionType.SLOWNESS, 64));
        inv.setItem(17, new ItemStack(Material.EXPERIENCE_BOTTLE, 64));

        // ---------- row 2 (slots 18-26) ----------
        inv.setItem(18, fire());
        inv.setItem(19, speed());
        inv.setItem(20, strength());
        inv.setItem(21, heal());
        inv.setItem(22, heal());
        inv.setItem(23, heal());
        inv.setItem(24, new ItemStack(Material.WATER_BUCKET));
        inv.setItem(25, enchanted(Material.BOW, "POWER:5", "PUNCH:2", "FLAME:1", "UNBREAKING:3", "MENDING:1"));
        inv.setItem(26, new ItemStack(Material.EXPERIENCE_BOTTLE, 64));

        // ---------- row 3 (slots 27-35) ----------
        inv.setItem(27, fire());
        inv.setItem(28, speed());
        inv.setItem(29, strength());
        inv.setItem(30, heal());
        inv.setItem(31, heal());
        inv.setItem(32, heal());
        inv.setItem(33, plugin.reroller().item(1));          // replaces one of the water buckets
        ItemStack weapon = weaponFor(k);                      // replaces the ender chest
        inv.setItem(34, weapon != null ? weapon : new ItemStack(Material.ENDER_CHEST));
        inv.setItem(35, pot(Material.TIPPED_ARROW, PotionType.HARMING, 64));

        // The kit fills the whole inventory, so the selector sword only goes in if there is space.
        // Players can always reopen the menu with /kits or by clicking the kit bot.
        plugin.giveSelector(p);
    }

    private static ItemStack heal() { return pot(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 1); }
    private static ItemStack fire() { return pot(Material.POTION, PotionType.LONG_FIRE_RESISTANCE, 1); }
    private static ItemStack speed() { return pot(Material.POTION, PotionType.SWIFTNESS, 1); }
    private static ItemStack strength() { return pot(Material.POTION, PotionType.STRONG_STRENGTH, 1); }

    /** Admin: save the admin's current inventory as the kit's contents. */
    public int saveInventory(Player admin, Kit k) {
        k.sec.set("custom", null);
        int n = 0;
        PlayerInventory inv = admin.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR || plugin.isSelector(it)) continue;
            k.sec.set("custom." + i, it);
            n++;
        }
        save();
        return n;
    }
}
