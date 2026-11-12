package com.altarkits;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The Altar Reroller: a gem item. Right-click it to turn it into a random AltarSMP legendary weapon.
 * Every player gets one free reroller the first time they join each day (tracked in daily.yml).
 */
public class Reroller {
    private final KitsPlugin plugin;
    private final NamespacedKey key;
    private final File file;
    private final YamlConfiguration daily;

    public Reroller(KitsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "reroller");
        this.file = new File(plugin.getDataFolder(), "daily.yml");
        this.daily = YamlConfiguration.loadConfiguration(file);
    }

    public ItemStack item(int amount) {
        Material m = Material.matchMaterial(plugin.getConfig().getString("reroller.material", "AMETHYST_SHARD"));
        if (m == null) m = Material.AMETHYST_SHARD;
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(KitManager.c("&d&lAltar Reroller"));
        meta.lore(List.of(
                KitManager.c("&7Right-click to roll a"),
                KitManager.c("&7random &6altar weapon&7!"),
                KitManager.c(""),
                KitManager.c("&eEveryone gets one free per day.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public boolean isReroller(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Puts rerollers in the inventory; anything that doesn't fit is dropped at the player's feet. */
    public void give(Player p, int amount) {
        for (ItemStack left : p.getInventory().addItem(item(amount)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private ZoneId zone() {
        try { return ZoneId.of(plugin.getConfig().getString("daily.timezone", ZoneId.systemDefault().getId())); }
        catch (Exception e) { return ZoneId.systemDefault(); }
    }

    /** Called on join: gives one reroller if the player hasn't received today's yet. */
    public void giveDaily(Player p) {
        String today = LocalDate.now(zone()).toString();
        String id = p.getUniqueId().toString();
        if (today.equals(daily.getString(id, ""))) return;
        daily.set(id, today);
        save();
        give(p, 1);
        p.sendMessage(KitManager.c("&d✦ &7Daily reward: &d+1 Altar Reroller&7! Right-click it for a random altar weapon."));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
    }

    /** Right-click: use up one reroller and hand out a random legendary. */
    public void use(Player p) {
        AltarBridge altar = plugin.altar();
        if (!altar.available()) {
            p.sendMessage(KitManager.c("&cThe AltarSMP plugin isn't running, so weapons can't be rolled right now."));
            return;
        }
        String id = altar.randomId();
        ItemStack weapon = altar.create(id);
        if (weapon == null) {
            p.sendMessage(KitManager.c("&cCouldn't roll a weapon. Tell an admin to check the console."));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        for (ItemStack left : p.getInventory().addItem(weapon).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.sendMessage(KitManager.c("&d✦ &7You rolled the &6&l" + altar.nameOf(id) + "&7!"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }

    public void save() {
        try { daily.save(file); } catch (IOException ex) { ex.printStackTrace(); }
    }
}
