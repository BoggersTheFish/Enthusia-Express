package io.enthusia.express.gui;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.util.ContainerScanner;
import io.enthusia.express.util.ItemCodec;
import io.enthusia.express.util.Text;
import io.enthusia.express.util.SoundFeedback;
import io.enthusia.express.util.SoundFeedback.Cue;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShippingService {
  public static final String TITLE_PREFIX = "Enthusia Express: Ship to ";
  public static final int PACKAGE_SLOT = 13;
  public static final int CONFIRM_SLOT = 15;
  public static final int CANCEL_SLOT = 11;
  private final io.enthusia.express.util.MainThread main;
  private final Map<UUID, Inventory> inventories = new HashMap<>();
  private final Set<UUID> pending = new HashSet<>();
  private final JavaPlugin plugin;
  private final MailRepository repository;
  private final CombatLogXHook combatHook;
  private final Map<UUID, UUID> targets = new HashMap<>();
  private final SoundFeedback sounds;
  private final NamespacedKey placeholderKey;

  public ShippingService(
      JavaPlugin plugin,
      MailRepository repository,
      CombatLogXHook combatHook,
      io.enthusia.express.util.MainThread main) {
    this(plugin, repository, combatHook, main, new SoundFeedback(plugin));
  }

  public ShippingService(
      JavaPlugin plugin,
      MailRepository repository,
      CombatLogXHook combatHook,
      io.enthusia.express.util.MainThread main,
      SoundFeedback sounds) {
    this.main = main;
    this.plugin = plugin;
    this.repository = repository;
    this.combatHook = combatHook;
    this.sounds = sounds;
    this.placeholderKey = new NamespacedKey("enthusiaexpress", "shipping-placeholder");
  }

  public void open(Player sender, OfflinePlayer target) {
    if (!sender.hasPermission("enthusiaexpress.use")
        || !sender.hasPermission("enthusiaexpress.packages.send")) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "no-permission"));
      return;
    }
    if (pending.contains(sender.getUniqueId())) {
      sender.sendMessage("\u00a7eYour shipment is still being saved.");
      return;
    }
    if (!combatHook.mayUseMail(sender)) {
      sender.sendMessage(
          Text.msg(
              plugin.getConfig(),
              combatHook.isAvailable() ? "combat-blocked" : "combatlogx-missing"));
      return;
    }
    Player online = target.getPlayer();
    if (online != null && online.isOnline()) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "target-online"));
      return;
    }
    sender.closeInventory();
    targets.put(sender.getUniqueId(), target.getUniqueId());
    Inventory inv = Bukkit.createInventory(null, 27, TITLE_PREFIX + target.getName());
    inv.setItem(CANCEL_SLOT, button(Material.BARRIER, "\u00a7cCancel"));
    inv.setItem(CONFIRM_SLOT, button(Material.LIME_CONCRETE, "\u00a7aConfirm shipment"));
    refreshPlaceholder(inv);
    inventories.put(sender.getUniqueId(), inv);
    sender.openInventory(inv);
  }

  public boolean owns(Player player, Inventory inventory) {
    return inventory != null && inventories.get(player.getUniqueId()) == inventory;
  }

  public void defer(Player player, Inventory inventory, boolean confirm) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!owns(player, inventory)
                  || player.getOpenInventory().getTopInventory() != inventory) return;
              if (confirm) confirm(player, inventory);
              else cancel(player);
            });
  }

  public void cancel(Player player) {
    player.closeInventory();
  }

  public void confirm(Player sender, Inventory inv) {
    if (!sender.hasPermission("enthusiaexpress.use")
        || !sender.hasPermission("enthusiaexpress.packages.send")) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "no-permission"));
      return;
    }
    if (pending.contains(sender.getUniqueId())) {
      sender.sendMessage("\u00a7eYour shipment is still being saved.");
      return;
    }
    if (!combatHook.mayUseMail(sender)) {
      sender.sendMessage(
          Text.msg(
              plugin.getConfig(),
              combatHook.isAvailable() ? "combat-blocked" : "combatlogx-missing"));
      sender.closeInventory();
      return;
    }
    if (!owns(sender, inv)) return;
    UUID targetId = targets.get(sender.getUniqueId());
    if (targetId == null) return;
    OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
    if (target.isOnline()) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "target-online"));
      sender.closeInventory();
      return;
    }
    ItemStack packageItem = inv.getItem(PACKAGE_SLOT);
    if (isPlaceholder(packageItem)) packageItem = null;
    if (!ContainerScanner.isAllowedShippingContainer(packageItem)) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "invalid-container"));
      return;
    }
    if (packageItem.getAmount() != 1) {
      sender.sendMessage("\u00a7cSend one container at a time.");
      return;
    }
    int count;
    int cost;
    try {
      count =
          ContainerScanner.countPackedItems(
              packageItem, plugin.getConfig().getInt("mail.max-recursive-container-depth", 8));
      cost = Math.multiplyExact(count, plugin.getConfig().getInt("mail.raw-gold-per-item", 1));
    } catch (IllegalArgumentException | ArithmeticException e) {
      sender.sendMessage("\u00a7cContainer nesting or shipment cost exceeds the configured limits.");
      return;
    }
    if (count <= 0) {
      sender.sendMessage(Text.msg(plugin.getConfig(), "empty-container"));
      return;
    }
    int have = countMaterial(sender, Material.RAW_GOLD);
    if (have < cost) {
      sender.sendMessage(
          Text.msg(
              plugin.getConfig(),
              "insufficient-gold",
              Map.of("cost", String.valueOf(cost), "have", String.valueOf(have))));
      return;
    }
    // Copy + encode on the primary thread before database work.
    ItemStack payloadItem = packageItem.clone();
    byte[] payload;
    try {
      payload = ItemCodec.encode(payloadItem);
    } catch (RuntimeException e) {
      sender.sendMessage("\u00a7cCould not encode that container.");
      return;
    }
    inv.setItem(PACKAGE_SLOT, null);
    removeMaterial(sender, Material.RAW_GOLD, cost);
    sender.closeInventory();
    targets.remove(sender.getUniqueId());
    String targetName = Optional.ofNullable(target.getName()).orElse(targetId.toString());
    pending.add(sender.getUniqueId());
    main.complete(
        repository.insertMailLimited(
            sender.getUniqueId(),
            sender.getName(),
            targetId,
            targetName,
            io.enthusia.express.mail.MailType.PACKAGE,
            payload,
            count,
            plugin
                .getConfig()
                .getBoolean("mail.limits.one-outstanding-package-per-recipient", false)),
        (result, error) -> {
          pending.remove(sender.getUniqueId());
          if (error != null) {
            // Compensate on the server thread; use the current session after a reconnect.
            refundPlayer(sender, payloadItem, cost);
            sender.sendMessage("\u00a7cShipment failed; your package and fee were refunded.");
            plugin.getLogger().severe("Package insert failed: " + error.getMessage());
          } else if (result.isEmpty()) {
            refundPlayer(sender, payloadItem, cost);
            sender.sendMessage(Text.msg(plugin.getConfig(), "outstanding-package"));
          } else {
            sender.sendMessage(
                Text.msg(
                    plugin.getConfig(),
                    "package-sent",
                    Map.of(
                        "target",
                        targetName,
                        "cost",
                        String.valueOf(cost),
                        "items",
                        String.valueOf(count))));
            sounds.play(sender, Cue.PACKAGE_SEND);
          }
        });
  }

  public void returnPackageOnClose(Player player, Inventory inv) {
    if (!owns(player, inv)) return;
    ItemStack stack = inv.getItem(PACKAGE_SLOT);
    if (stack != null && !isPlaceholder(stack) && !stack.getType().isAir()) {
      inv.setItem(PACKAGE_SLOT, null);
      Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
      overflow
          .values()
          .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }
    targets.remove(player.getUniqueId());
    inventories.remove(player.getUniqueId());
  }

  public void deferPlaceholderRefresh(Player player, Inventory inventory) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (owns(player, inventory)) refreshPlaceholder(inventory);
            });
  }

  public void refreshPlaceholder(Inventory inventory) {
    ItemStack current = inventory.getItem(PACKAGE_SLOT);
    if (current == null || current.getType().isAir()) inventory.setItem(PACKAGE_SLOT, placeholder());
  }

  public boolean isPlaceholder(ItemStack item) {
    if (item == null || item.getType() != Material.GRAY_STAINED_GLASS_PANE) return false;
    ItemMeta meta = item.getItemMeta();
    return meta != null
        && meta.getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE);
  }

  public void shutdown() {
    for (Player player : Bukkit.getOnlinePlayers())
      if (inventories.containsKey(player.getUniqueId())) player.closeInventory();
  }

  private static void refundPlayer(Player sender, ItemStack payloadItem, int cost) {
    Player refundTarget =
        Optional.ofNullable(Bukkit.getPlayer(sender.getUniqueId())).orElse(sender);
    give(refundTarget, payloadItem);
    for (int remaining = cost; remaining > 0; remaining -= Math.min(64, remaining))
      give(refundTarget, new ItemStack(Material.RAW_GOLD, Math.min(64, remaining)));
    if (!refundTarget.isOnline()) refundTarget.saveData();
  }

  private static void give(Player player, ItemStack item) {
    player
        .getInventory()
        .addItem(item)
        .values()
        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
  }

  private static ItemStack button(Material material, String name) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    meta.setDisplayName(name);
    stack.setItemMeta(meta);
    return stack;
  }

  private ItemStack placeholder() {
    ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = stack.getItemMeta();
    meta.setDisplayName("\u00a77Place package here");
    meta.setLore(List.of("\u00a77Accepted: Shulker Boxes and Bundles"));
    meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
    stack.setItemMeta(meta);
    return stack;
  }

  private static int countMaterial(Player player, Material material) {
    int count = 0;
    for (ItemStack item : player.getInventory().getStorageContents()) {
      if (item != null && item.getType() == material) count += item.getAmount();
    }
    return count;
  }

  private static void removeMaterial(Player player, Material material, int amount) {
    int remaining = amount;
    ItemStack[] contents = player.getInventory().getStorageContents();
    for (int i = 0; i < contents.length && remaining > 0; i++) {
      ItemStack item = contents[i];
      if (item == null || item.getType() != material) continue;
      int take = Math.min(item.getAmount(), remaining);
      item.setAmount(item.getAmount() - take);
      if (item.getAmount() <= 0) contents[i] = null;
      remaining -= take;
    }
    player.getInventory().setStorageContents(contents);
  }
}
