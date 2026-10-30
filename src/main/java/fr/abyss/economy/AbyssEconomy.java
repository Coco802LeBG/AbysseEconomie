package fr.abyss.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class AbyssEconomy extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String P = "§3[Abyss] §7";

    // ---------- modeles ----------

    static class Item {
        double base;
        double target;
        double stock;
    }

    static class Listing {
        UUID id;
        UUID seller;
        String sellerName;
        ItemStack item;
        double price;
        long expires;
    }

    private final Map<Material, Item> market = new LinkedHashMap<>();
    private final List<String> quickBuy = new ArrayList<>();
    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<Listing> listings = new ArrayList<>();
    private final Map<UUID, List<ItemStack>> returns = new HashMap<>();
    private final Random random = new Random();

    private double elasticity, sellRatio, driftPct, eventChance;
    private double inflScale, dailyGrowth, startBalance, tax;
    private long durationMs;
    private int maxListings;
    private long firstStart;
    private File dataFile;
    private YamlConfiguration data;

    // ---------- cycle de vie ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadSettings();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("balance", "pay", "baltop", "sell", "price", "ah", "shop")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        long cycle = 20L * 60L * getConfig().getLong("market.cycle-minutes", 10);
        new BukkitRunnable() {
            @Override
            public void run() {
                cycle();
            }
        }.runTaskTimer(this, cycle, cycle);
        new BukkitRunnable() {
            @Override
            public void run() {
                expireListings();
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);
        long save = 20L * 60L * 5L;
        new BukkitRunnable() {
            @Override
            public void run() {
                saveData();
            }
        }.runTaskTimer(this, save, save);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        elasticity = c.getDouble("market.elasticity", 0.6);
        sellRatio = c.getDouble("market.sell-ratio", 0.8);
        driftPct = c.getDouble("market.drift", 0.03);
        eventChance = c.getDouble("market.event-chance", 0.35);
        inflScale = Math.max(1, c.getDouble("inflation.money-scale", 2000000));
        dailyGrowth = c.getDouble("inflation.daily-growth", 0.01);
        startBalance = c.getDouble("player.start-balance", 1000);
        tax = c.getDouble("ah.tax", 0.05);
        durationMs = (long) (c.getDouble("ah.duration-hours", 48) * 3600000L);
        maxListings = c.getInt("ah.max-listings", 15);

        market.clear();
        ConfigurationSection sec = c.getConfigurationSection("items");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    getLogger().warning("Item inconnu dans la config: " + key);
                    continue;
                }
                Item it = new Item();
                it.base = sec.getDouble(key + ".base");
                it.target = Math.max(1, sec.getDouble(key + ".target"));
                it.stock = it.target;
                market.put(m, it);
            }
        }
        quickBuy.clear();
        quickBuy.addAll(c.getStringList("shop.quick-buy"));
    }

    private void loadData() {
        firstStart = data.getLong("first-start", 0);
        if (firstStart == 0) firstStart = System.currentTimeMillis();
        ConfigurationSection b = data.getConfigurationSection("balances");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                try {
                    balances.put(UUID.fromString(k), b.getDouble(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection n = data.getConfigurationSection("names");
        if (n != null) {
            for (String k : n.getKeys(false)) {
                try {
                    names.put(UUID.fromString(k), n.getString(k, "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (Map.Entry<Material, Item> e : market.entrySet()) {
            String path = "stock." + e.getKey().name();
            if (data.contains(path)) e.getValue().stock = data.getDouble(path);
        }
        ConfigurationSection ah = data.getConfigurationSection("ah");
        if (ah != null) {
            for (String k : ah.getKeys(false)) {
                try {
                    Listing l = new Listing();
                    l.id = UUID.fromString(k);
                    l.seller = UUID.fromString(ah.getString(k + ".seller", ""));
                    l.sellerName = ah.getString(k + ".name", "?");
                    l.price = ah.getDouble(k + ".price");
                    l.expires = ah.getLong(k + ".expires");
                    l.item = ah.getItemStack(k + ".item");
                    if (l.item != null) listings.add(l);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection r = data.getConfigurationSection("returns");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                try {
                    List<ItemStack> list = new ArrayList<>();
                    for (Object o : r.getList(k, new ArrayList<>())) {
                        if (o instanceof ItemStack is) list.add(is);
                    }
                    returns.put(UUID.fromString(k), list);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        data.set("first-start", firstStart);
        data.set("balances", null);
        data.set("names", null);
        data.set("ah", null);
        data.set("returns", null);
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            data.set("balances." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            data.set("names." + e.getKey(), e.getValue());
        }
        for (Map.Entry<Material, Item> e : market.entrySet()) {
            data.set("stock." + e.getKey().name(), e.getValue().stock);
        }
        for (Listing l : listings) {
            String b = "ah." + l.id;
            data.set(b + ".seller", l.seller.toString());
            data.set(b + ".name", l.sellerName);
            data.set(b + ".price", l.price);
            data.set(b + ".expires", l.expires);
            data.set(b + ".item", l.item);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : returns.entrySet()) {
            if (!e.getValue().isEmpty()) data.set("returns." + e.getKey(), e.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Impossible de sauvegarder data.yml: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        balances.putIfAbsent(p.getUniqueId(), startBalance);
        deliverReturns(p);
    }

    // ---------- moteur de marche (prix de /sell) ----------

    /** Inflation automatique: masse monetaire + temps. Aucun plafond. */
    private double inflation() {
        double total = 0;
        for (double d : balances.values()) total += d;
        double moneyPart = 1 + Math.sqrt(total / inflScale);
        double days = (System.currentTimeMillis() - firstStart) / 86400000.0;
        double timePart = Math.pow(1 + dailyGrowth, days);
        return moneyPart * timePart;
    }

    /** Moins il y a d'offre, plus c'est cher. */
    private double unitPrice(Item it, double supply, double infl) {
        double s = Math.max(supply, 1);
        return it.base * Math.pow(it.target / s, elasticity) * infl;
    }

    /** Offre totale = stock du marche + tout ce qui est en vente a l'AH. */
    private double supply(Material m, Item it) {
        return it.stock + listedAmount(m);
    }

    private int listedAmount(Material m) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Listing l : listings) {
            if (l.expires > now && l.item.getType() == m) n += l.item.getAmount();
        }
        return n;
    }

    /** Prix sans rien modifier (pour afficher le total dans /sell). */
    private double quoteSell(Material m, Item it, int n, double infl) {
        double s0 = supply(m, it);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += unitPrice(it, s0 + i, infl) * sellRatio;
        }
        return sum;
    }

    private double sellUnits(Material m, Item it, int n, double infl) {
        double v = quoteSell(m, it, n, infl);
        it.stock += n;
        return v;
    }

    /** Le marche vit tout seul: retour vers l'equilibre + evenements aleatoires. */
    private void cycle() {
        for (Item it : market.values()) {
            it.stock += (it.target - it.stock) * driftPct;
        }
        if (market.isEmpty() || random.nextDouble() >= eventChance) return;
        List<Map.Entry<Material, Item>> list = new ArrayList<>(market.entrySet());
        Map.Entry<Material, Item> e = list.get(random.nextInt(list.size()));
        boolean boom = random.nextBoolean();
        Item it = e.getValue();
        it.stock = Math.max(1, it.stock * (boom ? 0.5 : 1.8));
        String name = nice(e.getKey());
        if (boom) {
            Bukkit.broadcastMessage(P + "§6High demand for §e" + name + "§6: prices are skyrocketing!");
        } else {
            Bukkit.broadcastMessage(P + "§aOverproduction of §e" + name + "§a: prices are dropping!");
        }
    }

    // ---------- auction house ----------

    private void expireListings() {
        long now = System.currentTimeMillis();
        Iterator<Listing> it = listings.iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expires > now) continue;
            it.remove();
            returns.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) {
                s.sendMessage(P + "§cYour listing of §f" + nice(l.item.getType()) + " §cexpired.");
                deliverReturns(s);
            }
        }
    }

    private void deliverReturns(Player p) {
        List<ItemStack> list = returns.get(p.getUniqueId());
        if (list == null || list.isEmpty()) return;
        List<ItemStack> left = new ArrayList<>();
        int given = 0;
        for (ItemStack is : list) {
            Map<Integer, ItemStack> rest = p.getInventory().addItem(is.clone());
            if (rest.isEmpty()) given++;
            else left.addAll(rest.values());
        }
        if (left.isEmpty()) returns.remove(p.getUniqueId());
        else returns.put(p.getUniqueId(), left);
        if (given > 0) p.sendMessage(P + "§aYour unsold items were returned.");
        if (!left.isEmpty()) p.sendMessage(P + "§cInventory full: make room then use /ah to get the rest.");
    }

    private void giveItem(Player p, ItemStack is) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(is);
        Location loc = p.getLocation();
        for (ItemStack rest : left.values()) loc.getWorld().dropItemNaturally(loc, rest);
    }

    private void purchase(Player p, Listing l) {
        if (!listings.contains(l) || l.expires <= System.currentTimeMillis()) {
            p.sendMessage(P + "§cThis listing is no longer available.");
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            p.sendMessage(P + "§cThat's your own listing.");
            return;
        }
        if (bal(p.getUniqueId()) < l.price) {
            p.sendMessage(P + "§cYou need §e" + money(l.price) + "§c.");
            return;
        }
        listings.remove(l);
        add(p.getUniqueId(), -l.price);
        double net = l.price * (1 - tax);
        add(l.seller, net);
        Material m = l.item.getType();
        giveItem(p, l.item.clone());
        Item mk = market.get(m);
        if (mk != null) mk.stock = Math.max(1, mk.stock - l.item.getAmount());
        p.sendMessage(P + "You bought §f" + l.item.getAmount() + " " + nice(m) + " §7for §c" + money(l.price));
        Player s = Bukkit.getPlayer(l.seller);
        if (s != null) {
            s.sendMessage(P + "§a" + p.getName() + " bought your §f" + nice(m) + " §afor §f" + money(net) + " §7(" + (int) (tax * 100) + "% tax)");
        }
    }

    private Listing cheapest(Material m) {
        long now = System.currentTimeMillis();
        Listing best = null;
        for (Listing l : listings) {
            if (l.expires <= now || l.item.getType() != m) continue;
            if (best == null || l.price / l.item.getAmount() < best.price / best.item.getAmount()) best = l;
        }
        return best;
    }

    private int listingCount(Material m) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Listing l : listings) {
            if (l.expires > now && l.item.getType() == m) n++;
        }
        return n;
    }

    // ---------- utilitaires ----------

    private double bal(UUID id) {
        return balances.getOrDefault(id, 0.0);
    }

    private void add(UUID id, double amount) {
        balances.merge(id, amount, Double::sum);
    }

    private static String money(double d) {
        return d >= 100 ? String.format(Locale.US, "$%,.0f", d) : String.format(Locale.US, "$%,.2f", d);
    }

    private static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String dur(long ms) {
        long m = Math.max(0, ms) / 60000;
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private Component t(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private boolean sellable(ItemStack is) {
        if (is == null || is.getType().isAir() || !market.containsKey(is.getType())) return false;
        ItemMeta meta = is.getItemMeta();
        return meta == null || !(meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants());
    }

    /** Accepte 1500, 1.5k, 2m, 1b. */
    private Double parseAmount(String s) {
        try {
            s = s.toLowerCase(Locale.ROOT).replace(',', '.');
            double mult = 1;
            if (s.endsWith("k")) {
                mult = 1e3;
                s = s.substring(0, s.length() - 1);
            } else if (s.endsWith("m")) {
                mult = 1e6;
                s = s.substring(0, s.length() - 1);
            } else if (s.endsWith("b")) {
                mult = 1e9;
                s = s.substring(0, s.length() - 1);
            }
            double d = Double.parseDouble(s) * mult;
            if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return null;
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID findUuid(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private String trend(Item it) {
        double r = it.stock / it.target;
        if (r < 0.8) return "§c↑";
        if (r > 1.25) return "§a↓";
        return "§7→";
    }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        is.setItemMeta(meta);
        return is;
    }

    private void fillBottom(Inventory inv) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = inv.getSize() - 9; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private ItemStack display(Listing l, boolean mine) {
        ItemStack is = l.item.clone();
        ItemMeta meta = is.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add("§7Price: §a" + money(l.price));
        if (is.getAmount() > 1) lore.add("§7Per unit: §e" + money(l.price / is.getAmount()));
        lore.add("§7Seller: §f" + l.sellerName);
        lore.add("§7Time left: §f" + dur(l.expires - System.currentTimeMillis()));
        lore.add(mine ? "§cClick to remove" : "§eClick to buy");
        meta.setLore(lore);
        is.setItemMeta(meta);
        return is;
    }

    private void open(Player p, Gui g) {
        getServer().getScheduler().runTask(this, () -> p.openInventory(g.getInventory()));
    }

    // ---------- menus (GUI) ----------

    abstract class Gui implements InventoryHolder {
        protected Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }

        abstract void click(Player p, int slot, ClickType type);
    }

    /** /shop : "Quick Buy". Clique un item = annonces de l'AH les moins cheres. */
    class QuickBuyGui extends Gui {
        private final Material[] mats = new Material[45];

        QuickBuyGui() {
            inv = Bukkit.createInventory(this, 54, t("Quick Buy"));
            int i = 0;
            for (String entry : quickBuy) {
                if (i >= 45) break;
                String[] parts = entry.split(":");
                Material m = parts[0].equals("-") ? null : Material.matchMaterial(parts[0]);
                if (m == null) {
                    i++;
                    continue;
                }
                int amt = 1;
                if (parts.length > 1) {
                    try {
                        amt = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                amt = Math.max(1, Math.min(amt, m.getMaxStackSize()));
                ItemStack is = new ItemStack(m, amt);
                ItemMeta meta = is.getItemMeta();
                List<String> lore = new ArrayList<>();
                Listing best = cheapest(m);
                if (best != null) {
                    double unit = best.price / best.item.getAmount();
                    lore.add("§7Cheapest: §a" + money(unit * amt) + " §7for " + amt);
                    lore.add("§7Per unit: §e" + money(unit));
                    lore.add("§7Listings: §f" + listingCount(m));
                } else {
                    lore.add("§8No listings right now");
                }
                lore.add("");
                lore.add("§eClick to view");
                meta.setLore(lore);
                is.setItemMeta(meta);
                inv.setItem(i, is);
                mats[i] = m;
                i++;
            }
            fillBottom(inv);
            inv.setItem(47, icon(Material.HOPPER, "§aSell", "§7Click to open"));
            inv.setItem(48, icon(Material.ENDER_CHEST, "§eYour Listings", "§7Click to view"));
            inv.setItem(49, icon(Material.ANVIL, "§fAuction", "§7Click to view"));
        }

        @Override
        void click(Player p, int slot, ClickType type) {
            if (slot < 45 && mats[slot] != null) open(p, new AhGui(0, 0, mats[slot]));
            else if (slot == 47) open(p, new SellGui());
            else if (slot == 48) open(p, new MyGui(p));
            else if (slot == 49) open(p, new AhGui(0, 0, null));
        }
    }

    /** /ah : "Auction (Page N)", trie par prix croissant par defaut. */
    class AhGui extends Gui {
        final int page;
        final int sort;
        final Material filter;
        final int pages;
        final List<Listing> shown = new ArrayList<>();
        private final String[] sortNames = {"Lowest price", "Highest price", "Recently listed", "Ending soon"};

        AhGui(int pageWanted, int sort, Material filter) {
            this.sort = sort;
            this.filter = filter;
            long now = System.currentTimeMillis();
            List<Listing> all = new ArrayList<>();
            for (Listing l : listings) {
                if (l.expires > now && (filter == null || l.item.getType() == filter)) all.add(l);
            }
            Comparator<Listing> unit = Comparator.comparingDouble(l -> l.price / l.item.getAmount());
            switch (sort) {
                case 0 -> all.sort(unit);
                case 1 -> all.sort(unit.reversed());
                case 2 -> all.sort(Comparator.comparingLong((Listing l) -> l.expires).reversed());
                default -> all.sort(Comparator.comparingLong((Listing l) -> l.expires));
            }
            this.pages = Math.max(1, (all.size() + 44) / 45);
            this.page = Math.min(Math.max(pageWanted, 0), pages - 1);
            int from = page * 45;
            for (int i = from; i < Math.min(from + 45, all.size()); i++) shown.add(all.get(i));

            String title = "Auction (Page " + (page + 1) + ")" + (filter == null ? "" : " - " + nice(filter));
            inv = Bukkit.createInventory(this, 54, t(title));
            for (int i = 0; i < shown.size(); i++) inv.setItem(i, display(shown.get(i), false));
            fillBottom(inv);
            if (page > 0) inv.setItem(45, icon(Material.ARROW, "§ePrevious page"));
            if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, "§eNext page"));
            String[] lore = new String[5];
            for (int i = 0; i < 4; i++) lore[i] = (i == sort ? "§a▶ " : "§7  ") + sortNames[i];
            lore[4] = "§eClick to change";
            inv.setItem(47, icon(Material.HOPPER, "§6Sort", lore));
            inv.setItem(48, icon(Material.ENDER_CHEST, "§eYour Listings", "§7Click to view"));
            inv.setItem(49, icon(Material.ANVIL, "§fAuction", "§7Buy and sell items", "§eClick to refresh"));
            inv.setItem(51, icon(Material.COMPASS, "§bQuick Buy", "§7Click to open"));
        }

        @Override
        void click(Player p, int slot, ClickType type) {
            if (slot < 45) {
                if (slot >= shown.size()) return;
                Listing l = shown.get(slot);
                if (l.seller.equals(p.getUniqueId())) {
                    p.sendMessage(P + "§cThat's your listing. Remove it in §eYour Listings§c.");
                } else {
                    open(p, new ConfirmGui(l, this));
                }
                return;
            }
            switch (slot) {
                case 45 -> {
                    if (page > 0) open(p, new AhGui(page - 1, sort, filter));
                }
                case 47 -> open(p, new AhGui(0, (sort + 1) % 4, filter));
                case 48 -> open(p, new MyGui(p));
                case 49 -> open(p, new AhGui(page, sort, filter));
                case 51 -> open(p, new QuickBuyGui());
                case 53 -> {
                    if (page < pages - 1) open(p, new AhGui(page + 1, sort, filter));
                }
                default -> {
                }
            }
        }
    }

    class ConfirmGui extends Gui {
        private final Listing listing;
        private final AhGui back;

        ConfirmGui(Listing l, AhGui back) {
            this.listing = l;
            this.back = back;
            inv = Bukkit.createInventory(this, 27, t("Confirm Purchase"));
            inv.setItem(13, display(l, false));
            inv.setItem(11, icon(Material.LIME_STAINED_GLASS_PANE, "§aConfirm", "§7Pay §a" + money(l.price)));
            inv.setItem(15, icon(Material.RED_STAINED_GLASS_PANE, "§cCancel"));
        }

        @Override
        void click(Player p, int slot, ClickType type) {
            if (slot == 11) {
                purchase(p, listing);
                getServer().getScheduler().runTask(AbyssEconomy.this, () -> p.closeInventory());
            } else if (slot == 15) {
                open(p, new AhGui(back.page, back.sort, back.filter));
            }
        }
    }

    class MyGui extends Gui {
        private final List<Listing> shown = new ArrayList<>();

        MyGui(Player p) {
            for (Listing l : listings) {
                if (l.seller.equals(p.getUniqueId()) && shown.size() < 45) shown.add(l);
            }
            inv = Bukkit.createInventory(this, 54, t("Your Listings (" + shown.size() + "/" + maxListings + ")"));
            for (int i = 0; i < shown.size(); i++) inv.setItem(i, display(shown.get(i), true));
            fillBottom(inv);
            inv.setItem(49, icon(Material.ANVIL, "§fAuction", "§7Click to go back"));
        }

        @Override
        void click(Player p, int slot, ClickType type) {
            if (slot == 49) {
                open(p, new AhGui(0, 0, null));
            } else if (slot < shown.size()) {
                Listing l = shown.get(slot);
                if (listings.remove(l)) {
                    giveItem(p, l.item.clone());
                    p.sendMessage(P + "§aListing removed.");
                }
                open(p, new MyGui(p));
            }
        }
    }

    /** /baltop : "Most Money (Page N)" avec les tetes des joueurs. */
    class TopGui extends Gui {
        final int page;
        final int pages;

        TopGui(int pageWanted) {
            List<Map.Entry<UUID, Double>> top = new ArrayList<>(balances.entrySet());
            top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            this.pages = Math.max(1, (top.size() + 44) / 45);
            this.page = Math.min(Math.max(pageWanted, 0), pages - 1);
            inv = Bukkit.createInventory(this, 54, t("Most Money (Page " + (page + 1) + ")"));
            int from = page * 45;
            for (int i = from; i < Math.min(from + 45, top.size()); i++) {
                Map.Entry<UUID, Double> e = top.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.getKey()));
                sm.setDisplayName("§e#" + (i + 1) + " §f" + names.getOrDefault(e.getKey(), "?"));
                sm.setLore(List.of("§7Money: §a" + money(e.getValue())));
                head.setItemMeta(sm);
                inv.setItem(i - from, head);
            }
            fillBottom(inv);
            if (page > 0) inv.setItem(45, icon(Material.ARROW, "§ePrevious page"));
            if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, "§eNext page"));
            inv.setItem(49, icon(Material.EMERALD, "§aMost Money", "§7Click to refresh!"));
        }

        @Override
        void click(Player p, int slot, ClickType type) {
            if (slot == 49) open(p, new TopGui(page));
            else if (slot == 45 && page > 0) open(p, new TopGui(page - 1));
            else if (slot == 53 && page < pages - 1) open(p, new TopGui(page + 1));
        }
    }

    /** /sell : tu mets tes items dedans, le bouton vert vend tout. Le reste te revient a la fermeture. */
    class SellGui extends Gui {
        SellGui() {
            inv = Bukkit.createInventory(this, 54, t("Sell"));
            ItemStack blank = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
            for (int i = 45; i < 54; i++) inv.setItem(i, blank);
            inv.setItem(53, icon(Material.LIME_STAINED_GLASS_PANE, "§aConfirm", "§7Click to sell everything above"));
            refresh();
        }

        void refresh() {
            Map<Material, Integer> totals = new HashMap<>();
            int unsellable = 0;
            for (int i = 0; i < 45; i++) {
                ItemStack is = inv.getItem(i);
                if (is == null || is.getType().isAir()) continue;
                if (sellable(is)) totals.merge(is.getType(), is.getAmount(), Integer::sum);
                else unsellable++;
            }
            double infl = inflation();
            double sum = 0;
            for (Map.Entry<Material, Integer> e : totals.entrySet()) {
                sum += quoteSell(e.getKey(), market.get(e.getKey()), e.getValue(), infl);
            }
            inv.setItem(49, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§fSell",
                    "§7Total value: §a" + money(sum),
                    unsellable > 0 ? "§c" + unsellable + " stack(s) can't be sold" : "§7Put items in this menu"));
        }

        void confirm(Player p) {
            double infl = inflation();
            double total = 0;
            int count = 0;
            for (int i = 0; i < 45; i++) {
                ItemStack is = inv.getItem(i);
                if (!sellable(is)) continue;
                total += sellUnits(is.getType(), market.get(is.getType()), is.getAmount(), infl);
                count += is.getAmount();
                inv.setItem(i, null);
            }
            if (count == 0) {
                p.sendMessage(P + "§cNothing to sell.");
                return;
            }
            add(p.getUniqueId(), total);
            p.sendMessage(P + "You sold §f" + count + " items §7for §a" + money(total));
            getServer().getScheduler().runTask(AbyssEconomy.this, () -> p.closeInventory());
        }

        void handleClick(Player p, InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw >= 45 && raw < inv.getSize()) {
                e.setCancelled(true);
                if (raw == 53) confirm(p);
                return;
            }
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            getServer().getScheduler().runTask(AbyssEconomy.this, this::refresh);
        }

        @Override
        void click(Player p, int slot, ClickType type) {
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Gui gui)) return;
        if (!(e.getWhoClicked() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        if (gui instanceof SellGui sg) {
            sg.handleClick(p, e);
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        gui.click(p, e.getSlot(), e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Gui gui)) return;
        if (gui instanceof SellGui sg) {
            for (int raw : e.getRawSlots()) {
                if (raw >= 45 && raw < top.getSize()) {
                    e.setCancelled(true);
                    return;
                }
            }
            getServer().getScheduler().runTask(this, sg::refresh);
            return;
        }
        e.setCancelled(true);
    }

    /** Fermer /sell sans confirmer = tu recuperes tes items. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellGui)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        for (int i = 0; i < 45; i++) {
            ItemStack is = inv.getItem(i);
            if (is != null && !is.getType().isAir()) {
                giveItem(p, is);
                inv.setItem(i, null);
            }
        }
    }

    // ---------- commandes ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> {
                if (args.length > 0) {
                    UUID id = findUuid(args[0]);
                    if (id == null) {
                        sender.sendMessage(P + "§cPlayer not found.");
                        return true;
                    }
                    sender.sendMessage(P + args[0] + " has §a" + money(bal(id)));
                } else if (sender instanceof Player p) {
                    String msg = "§fYou have §a" + money(bal(p.getUniqueId()));
                    p.sendMessage(msg);
                    p.sendActionBar(t(msg));
                }
            }
            case "pay" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) {
                    p.sendMessage(P + "§c/pay <player> <amount>");
                    return true;
                }
                UUID to = findUuid(args[0]);
                Double amt = parseAmount(args[1]);
                if (to == null || to.equals(p.getUniqueId())) {
                    p.sendMessage(P + "§cInvalid player.");
                    return true;
                }
                if (amt == null) {
                    p.sendMessage(P + "§cInvalid amount.");
                    return true;
                }
                if (bal(p.getUniqueId()) < amt) {
                    p.sendMessage(P + "§cYou don't have enough money.");
                    return true;
                }
                add(p.getUniqueId(), -amt);
                add(to, amt);
                p.sendMessage(P + "You paid §a" + money(amt) + " §7to " + args[0] + ".");
                Player target = Bukkit.getPlayer(to);
                if (target != null) target.sendMessage(P + "You received §a" + money(amt) + " §7from " + p.getName() + ".");
            }
            case "baltop" -> {
                if (!(sender instanceof Player p)) return true;
                open(p, new TopGui(0));
            }
            case "price" -> {
                Material m = null;
                if (args.length > 0) m = Material.matchMaterial(args[0]);
                else if (sender instanceof Player p) m = p.getInventory().getItemInMainHand().getType();
                if (m == null || m.isAir()) {
                    sender.sendMessage(P + "§cUnknown item. Hold one or use /price <item>.");
                    return true;
                }
                sender.sendMessage("§3--- " + nice(m) + " ---");
                Item it = market.get(m);
                if (it != null) {
                    sender.sendMessage("§7Server buys at (/sell): §a" + money(unitPrice(it, supply(m, it), inflation()) * sellRatio)
                            + " §7each " + trend(it));
                }
                Listing best = cheapest(m);
                if (best != null) {
                    sender.sendMessage("§7Cheapest on /ah: §e" + money(best.price / best.item.getAmount()) + " §7each §8(" + listingCount(m) + " listings)");
                } else {
                    sender.sendMessage("§7No listings on /ah.");
                }
            }
            case "sell" -> {
                if (!(sender instanceof Player p)) return true;
                open(p, new SellGui());
            }
            case "shop" -> {
                if (!(sender instanceof Player p)) return true;
                open(p, new QuickBuyGui());
            }
            case "ah" -> {
                if (!(sender instanceof Player p)) return true;
                deliverReturns(p);
                if (args.length >= 1 && args[0].equalsIgnoreCase("sell")) {
                    if (args.length < 2) {
                        p.sendMessage(P + "§c/ah sell <price>  §7(ex: 1500, 2.5k, 1m)");
                        return true;
                    }
                    Double price = parseAmount(args[1]);
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (price == null) {
                        p.sendMessage(P + "§cInvalid price.");
                        return true;
                    }
                    if (hand.getType().isAir()) {
                        p.sendMessage(P + "§cHold the item you want to sell.");
                        return true;
                    }
                    int mine = 0;
                    for (Listing l : listings) {
                        if (l.seller.equals(p.getUniqueId())) mine++;
                    }
                    if (mine >= maxListings) {
                        p.sendMessage(P + "§cYou already have " + maxListings + " listings.");
                        return true;
                    }
                    Listing l = new Listing();
                    l.id = UUID.randomUUID();
                    l.seller = p.getUniqueId();
                    l.sellerName = p.getName();
                    l.item = hand.clone();
                    l.price = price;
                    l.expires = System.currentTimeMillis() + durationMs;
                    listings.add(l);
                    p.getInventory().setItemInMainHand(null);
                    p.sendMessage(P + "Listed §f" + l.item.getAmount() + " " + nice(l.item.getType()) + " §7for §a" + money(price)
                            + " §7(" + (int) (tax * 100) + "% tax on sale)");
                    Item mk = market.get(l.item.getType());
                    if (mk != null) {
                        double ref = unitPrice(mk, supply(l.item.getType(), mk), inflation()) * l.item.getAmount();
                        p.sendMessage(P + "§8Market reference price: " + money(ref));
                    }
                    return true;
                }
                Material f = null;
                if (args.length >= 1) {
                    f = Material.matchMaterial(args[0]);
                    if (f == null) {
                        p.sendMessage(P + "§cUnknown item.");
                        return true;
                    }
                }
                open(p, new AhGui(0, 0, f));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1 && name.equals("price")) {
            for (Material m : market.keySet()) out.add(m.name().toLowerCase(Locale.ROOT));
        } else if (args.length == 1 && name.equals("ah")) {
            out.add("sell");
            for (Listing l : listings) {
                String s = l.item.getType().name().toLowerCase(Locale.ROOT);
                if (!out.contains(s)) out.add(s);
            }
        } else if (args.length == 1 && (name.equals("pay") || name.equals("balance"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }
}
