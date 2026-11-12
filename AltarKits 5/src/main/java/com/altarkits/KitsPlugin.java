package com.altarkits;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;
import java.util.*;

public class KitsPlugin extends JavaPlugin implements Listener, TabExecutor {
    private KitManager kitManager;
    private Cooldowns cooldowns;
    private Coins coins;
    private AltarBridge altar;
    private Reroller reroller;
    private KitGui gui;
    private NamespacedKey selectorKey, botKey;
    private final Map<UUID, Long> combat = new HashMap<>();

    /** Spawn point: set with /kitadmin setspawn, otherwise the first world's spawn. */
    public Location spawn() {
        Location l = getConfig().getLocation("spawn");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private int combatSeconds() { return getConfig().getInt("combat-seconds", 15); }

    private void tag(Player p) {
        boolean wasFree = !combat.containsKey(p.getUniqueId());
        combat.put(p.getUniqueId(), System.currentTimeMillis() + combatSeconds() * 1000L);
        if (wasFree) msg(p, "&c⚔ You are in combat! Don't log out for " + combatSeconds() + "s.");
    }

    public KitManager kitManager() { return kitManager; }
    public Cooldowns cooldowns() { return cooldowns; }
    public Coins coins() { return coins; }
    public AltarBridge altar() { return altar; }
    public Reroller reroller() { return reroller; }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        // config.yml defaults (written the first time, edit them freely)
        getConfig().addDefault("combat-seconds", 15);
        getConfig().addDefault("coins.per-kill", 10);          // coins for killing a player
        getConfig().addDefault("coins.kit-base-cost", 50);     // price of the first altar kit (only used when kits.yml is generated)
        getConfig().addDefault("coins.kit-cost-step", 25);     // each kit further down the list costs this much more
        getConfig().addDefault("reroller.material", "AMETHYST_SHARD");
        getConfig().addDefault("daily.timezone", ZoneId.systemDefault().getId());
        getConfig().options().copyDefaults(true);
        saveConfig();

        selectorKey = new NamespacedKey(this, "selector");
        botKey = new NamespacedKey(this, "kitbot");
        altar = new AltarBridge(this);
        coins = new Coins(this);
        reroller = new Reroller(this);
        kitManager = new KitManager(this);
        kitManager.load();
        cooldowns = new Cooldowns(this);
        gui = new KitGui(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        for (String c : List.of("kits", "kitadmin", "bot", "coins")) {
            getCommand(c).setExecutor(this);
            getCommand(c).setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, cooldowns::tick, 20L, 20L);       // 1 tick/sec, online players only
        Bukkit.getScheduler().runTaskTimer(this, () -> { cooldowns.save(); coins.save(); }, 1200L, 1200L);   // autosave
        Bukkit.getScheduler().runTaskTimer(this, () -> {                           // combat tag expiry
            long now = System.currentTimeMillis();
            combat.entrySet().removeIf(en -> {
                if (en.getValue() > now) return false;
                Player pl = Bukkit.getPlayer(en.getKey());
                if (pl != null) msg(pl, "&a✔ You are no longer in combat.");
                return true;
            });
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (cooldowns != null) cooldowns.save();
        if (coins != null) coins.save();
    }

    // ---------- selector sword ----------
    public boolean isSelector(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE);
    }

    private ItemStack selectorItem() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        m.displayName(KitManager.c("&6&lALTAR KITS"));
        m.lore(List.of(KitManager.c("&7Right-click to open the kits menu")));
        m.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public void giveSelector(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isSelector(it)) return;
        ItemStack s = selectorItem();
        ItemStack in8 = p.getInventory().getItem(8);
        if (in8 == null || in8.getType() == Material.AIR) p.getInventory().setItem(8, s);
        else p.getInventory().addItem(s);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        giveSelector(p);
        // Everyone ends up at spawn when they log in. Inventory is NOT touched, so gear is kept.
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && !p.isDead()) p.teleport(spawn()); }, 1L);
        // One free Altar Reroller the first time they join each day
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) reroller.giveDaily(p); }, 40L);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        e.setRespawnLocation(spawn()); // always respawn at spawn
        Bukkit.getScheduler().runTaskLater(this, () -> giveSelector(e.getPlayer()), 2L);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isSelector);
        combat.remove(e.getEntity().getUniqueId());
        // +coins for the killer (shown above their hotbar)
        Player killer = e.getEntity().getKiller();
        if (killer != null && !killer.equals(e.getEntity())) {
            int reward = getConfig().getInt("coins.per-kill", 10);
            if (reward > 0) {
                coins.add(killer.getUniqueId(), reward);
                killer.sendActionBar(KitManager.c("&6+" + reward + " coins"));
                killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            }
        }
    }
    @EventHandler public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d instanceof Player attacker && attacker != victim) { tag(victim); tag(attacker); }
    }
    /** Bots can never take damage. */
    @EventHandler public void onBotDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Mannequin m && m.getPersistentDataContainer().has(botKey, PersistentDataType.STRING))
            e.setCancelled(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long until = combat.remove(p.getUniqueId());
        // Left WHILE in combat -> counts as a death (gear drops). Left out of combat -> gear is kept.
        if (until != null && until > System.currentTimeMillis() && !p.isDead()) p.setHealth(0);
        cooldowns.save();
        coins.save();
    }
    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (isSelector(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }
    @EventHandler public void onInvClick(InventoryClickEvent e) {
        if (isSelector(e.getCurrentItem()) && e.getClickedInventory() != null
                && e.getClickedInventory().getType() != InventoryType.PLAYER) e.setCancelled(true);
    }
    @EventHandler public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (isSelector(e.getItem())) { e.setCancelled(true); gui.open(e.getPlayer()); return; }
        if (reroller.isReroller(e.getItem())) { e.setCancelled(true); reroller.use(e.getPlayer()); }
    }
    @EventHandler public void onBotClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getRightClicked() instanceof Mannequin m
                && m.getPersistentDataContainer().has(botKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
            gui.open(e.getPlayer());
        }
    }

    // ---------- commands ----------
    private void msg(CommandSender s, String m) { s.sendMessage(KitManager.c(m)); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "kits" -> {
                if (s instanceof Player p) gui.open(p); else msg(s, "&cPlayers only.");
            }
            case "coins" -> {
                if (a.length >= 1) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(a[0]);
                    msg(s, "&6" + a[0] + "'s coins: &e" + coins.get(op.getUniqueId()));
                } else if (s instanceof Player p) msg(p, "&6Your coins: &e" + coins.get(p.getUniqueId()));
                else msg(s, "&cPlayers only.");
            }
            case "bot" -> bot(s, a);
            case "kitadmin" -> admin(s, a);
        }
        return true;
    }

    private void bot(CommandSender s, String[] a) {
        if (!s.hasPermission("kit.admin")) { msg(s, "&cNo permission."); return; }
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) {
            for (Entity en : p.getNearbyEntities(6, 6, 6)) {
                if (en instanceof Mannequin m && m.getPersistentDataContainer().has(botKey, PersistentDataType.STRING)) {
                    m.remove(); msg(p, "&aRemoved the nearest bot."); return;
                }
            }
            msg(p, "&cNo bot within 6 blocks."); return;
        }
        if (a.length < 2 || !a[0].equalsIgnoreCase("spawn")) { msg(p, "&eUsage: /bot spawn <username> | /bot remove"); return; }
        String name = a[1];
        Location loc = p.getLocation();
        loc.setYaw(loc.getYaw() + 180f);
        loc.setPitch(0f);
        msg(p, "&7Looking up &f" + name + "&7...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PlayerProfile prof = Bukkit.createProfile(name);
            boolean ok;
            try { ok = prof.complete(true); } catch (Exception ex) { ok = false; }
            final boolean found = ok;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!found || prof.getId() == null) { msg(p, "&cCould not find a Minecraft account named " + name + "."); return; }
                ResolvableProfile rp = ResolvableProfile.resolvableProfile()
                        .uuid(prof.getId()).name(prof.getName()).addProperties(prof.getProperties()).build();
                loc.getWorld().spawn(loc, Mannequin.class, m -> {
                    m.setProfile(rp);
                    m.setImmovable(true);
                    m.setInvulnerable(true);
                    m.setPersistent(true);
                    m.setSilent(true);
                    m.customName(KitManager.c("&6&l◆ KITS ◆"));
                    m.setCustomNameVisible(true);
                    m.setDescription(Component.text().append(KitManager.c("&fPick a loadout and fight"))
                            .append(Component.newline()).append(KitManager.c("&7▸ Click to choose a kit")).build());
                    m.getPersistentDataContainer().set(botKey, PersistentDataType.STRING, "kits");
                    var eq = m.getEquipment();
                    eq.setArmorContents(new ItemStack[4]); // no armor
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                });
                msg(p, "&aSpawned kit bot with the skin of &f" + prof.getName() + "&a.");
            });
        });
    }

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("kit.admin")) { msg(s, "&cNo permission (kit.admin)."); return; }
        if (a.length == 0) {
            msg(s, "&6/kitadmin &elist &7| &ereload &7| &esaveinv <kit> &7| &eclearinv <kit> &7| &esetweapon <kit>");
            msg(s, "&6/kitadmin &esetcooldown <kit> <sec> &7| &esetperm <kit> <perm|none> &7| &esetname <kit> <name>");
            msg(s, "&6/kitadmin &eseticon <kit> &7| &esetslot <kit> <0-53> &7| &eresetcd <player> [kit]");
            msg(s, "&6/kitadmin &esetspawn &7| &esetcombat <seconds> &7| &esetcost <kit> <coins>");
            msg(s, "&6/kitadmin &ecoins <give|take|set> <player> <amount> &7| &egivereroller <player> [amount] &7| &eresetkits");
            return;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            msg(s, "&6Kits: &f" + String.join(", ", kitManager.kits.keySet())); return;
        }
        if (sub.equals("setspawn")) {
            if (!(s instanceof Player pl)) { msg(s, "&cPlayers only."); return; }
            getConfig().set("spawn", pl.getLocation()); saveConfig();
            msg(s, "&aSpawn set to your location. Deaths and logins send players here."); return;
        }
        if (sub.equals("setcombat")) {
            if (a.length < 2) { msg(s, "&eUsage: /kitadmin setcombat <seconds>"); return; }
            try { getConfig().set("combat-seconds", Math.max(0, Integer.parseInt(a[1]))); saveConfig(); msg(s, "&aCombat tag time set."); }
            catch (NumberFormatException ex) { msg(s, "&cNumber needed."); }
            return;
        }
        if (sub.equals("reload")) { kitManager.load(); msg(s, "&aReloaded kits.yml."); return; }
        if (sub.equals("resetkits")) {
            kitManager.regenerate();
            msg(s, "&aKits reset to the defaults (new costs + layout). Your old file was saved as kits.yml.bak."); return;
        }
        if (sub.equals("coins")) {
            if (a.length < 4) { msg(s, "&eUsage: /kitadmin coins <give|take|set> <player> <amount>"); return; }
            int amt;
            try { amt = Integer.parseInt(a[3]); } catch (NumberFormatException ex) { msg(s, "&cNumber needed."); return; }
            UUID target = Bukkit.getOfflinePlayer(a[2]).getUniqueId();
            switch (a[1].toLowerCase()) {
                case "give" -> coins.add(target, amt);
                case "take" -> coins.add(target, -amt);
                case "set" -> coins.set(target, amt);
                default -> { msg(s, "&eUsage: /kitadmin coins <give|take|set> <player> <amount>"); return; }
            }
            coins.save();
            msg(s, "&a" + a[2] + " now has &e" + coins.get(target) + " &acoins."); return;
        }
        if (sub.equals("givereroller")) {
            if (a.length < 2) { msg(s, "&eUsage: /kitadmin givereroller <player> [amount]"); return; }
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) { msg(s, "&cThat player isn't online."); return; }
            int amt = 1;
            if (a.length > 2) { try { amt = Math.max(1, Integer.parseInt(a[2])); } catch (NumberFormatException ex) { msg(s, "&cNumber needed."); return; } }
            reroller.give(target, amt);
            msg(s, "&aGave " + target.getName() + " " + amt + " Altar Reroller(s)."); return;
        }
        if (sub.equals("resetcd")) {
            if (a.length < 2) { msg(s, "&eUsage: /kitadmin resetcd <player> [kit]"); return; }
            OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
            cooldowns.clear(op.getUniqueId(), a.length > 2 ? a[2].toLowerCase() : null);
            msg(s, "&aCooldown(s) reset for " + a[1] + "."); return;
        }
        if (a.length < 2) { msg(s, "&cSpecify a kit id. &7(/kitadmin list)"); return; }
        Kit k = kitManager.get(a[1]);
        if (k == null) { msg(s, "&cUnknown kit. &7(/kitadmin list)"); return; }
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "saveinv" -> {
                if (p == null) { msg(s, "&cPlayers only."); return; }
                int n = kitManager.saveInventory(p, k);
                msg(s, "&aSaved " + n + " items from your inventory/armor as kit &f" + k.id + "&a. Players now get exactly this.");
            }
            case "clearinv" -> { k.sec.set("custom", null); kitManager.save(); msg(s, "&aKit &f" + k.id + " &anow uses the default loadout."); }
            case "setweapon" -> {
                if (p == null) { msg(s, "&cPlayers only."); return; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) { msg(s, "&cHold the weapon item first."); return; }
                ItemStack save = hand.clone(); save.setAmount(1);
                k.sec.set("weapon.item", save); kitManager.save();
                msg(s, "&aWeapon of kit &f" + k.id + " &aset to your held item (replaces the sword).");
            }
            case "setcost" -> {
                if (a.length < 3) { msg(s, "&eUsage: /kitadmin setcost <kit> <coins>"); return; }
                try { k.sec.set("cost", Math.max(0, Integer.parseInt(a[2]))); kitManager.save(); msg(s, "&aCost set."); }
                catch (NumberFormatException ex) { msg(s, "&cNumber needed."); }
            }
            case "setcooldown" -> {
                if (a.length < 3) { msg(s, "&eUsage: /kitadmin setcooldown <kit> <seconds>"); return; }
                try { k.sec.set("cooldown", Math.max(0, Integer.parseInt(a[2]))); kitManager.save(); msg(s, "&aCooldown set."); }
                catch (NumberFormatException ex) { msg(s, "&cNumber needed."); }
            }
            case "setperm" -> {
                if (a.length < 3) { msg(s, "&eUsage: /kitadmin setperm <kit> <permission|none>"); return; }
                k.sec.set("permission", a[2].equalsIgnoreCase("none") ? "" : a[2]); kitManager.save(); msg(s, "&aPermission set.");
            }
            case "setname" -> {
                if (a.length < 3) { msg(s, "&eUsage: /kitadmin setname <kit> <name with & colors>"); return; }
                k.sec.set("name", String.join(" ", Arrays.copyOfRange(a, 2, a.length))); kitManager.save(); msg(s, "&aName set.");
            }
            case "seticon" -> {
                if (p == null) { msg(s, "&cPlayers only."); return; }
                Material m = p.getInventory().getItemInMainHand().getType();
                if (m == Material.AIR) { msg(s, "&cHold an item."); return; }
                k.sec.set("icon", m.name()); kitManager.save(); msg(s, "&aIcon set.");
            }
            case "setslot" -> {
                if (a.length < 3) { msg(s, "&eUsage: /kitadmin setslot <kit> <0-53>"); return; }
                try { k.sec.set("slot", Integer.parseInt(a[2])); kitManager.save(); msg(s, "&aSlot set."); }
                catch (NumberFormatException ex) { msg(s, "&cNumber needed."); }
            }
            default -> msg(s, "&cUnknown subcommand.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("bot")) {
            if (a.length == 1) out.addAll(List.of("spawn", "remove"));
        } else if (cmd.getName().equalsIgnoreCase("kitadmin")) {
            if (a.length == 1) out.addAll(List.of("list", "reload", "saveinv", "clearinv", "setweapon", "setcooldown", "setcost", "setperm", "setname", "seticon", "setslot", "resetcd", "setspawn", "setcombat", "coins", "givereroller", "resetkits"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("coins")) out.addAll(List.of("give", "take", "set"));
            else if (a.length == 2 && !List.of("resetcd","setspawn","setcombat","givereroller","resetkits").contains(a[0].toLowerCase())) out.addAll(kitManager.kits.keySet());
            else if (a.length == 3 && a[0].equalsIgnoreCase("resetcd")) out.addAll(kitManager.kits.keySet());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
