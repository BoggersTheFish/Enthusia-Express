package io.enthusia.express.gui;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.mail.*;
import io.enthusia.express.util.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class MailboxService {
  public static final String TITLE_PREFIX = "Enthusia Express Mailbox";
  private final JavaPlugin plugin;
  private final MailRepository repository;
  private final CombatLogXHook combatHook;
  private final MainThread main;
  private final SoundFeedback sounds;
  private final Map<UUID, Session> sessions = new HashMap<>();
  private final Set<UUID> claiming = new HashSet<>();
  private boolean stopping;

  private static final class Session {
    final Inventory inventory;
    final MailType type;
    final int page;
    final Map<Integer, MailRecord> records = new HashMap<>();
    boolean loaded;

    Session(Inventory inventory, MailType type, int page) {
      this.inventory = inventory;
      this.type = type;
      this.page = page;
    }
  }

  public MailboxService(
      JavaPlugin plugin, MailRepository repository, CombatLogXHook combatHook, MainThread main) {
    this(plugin, repository, combatHook, main, new SoundFeedback(plugin));
  }

  public MailboxService(
      JavaPlugin plugin,
      MailRepository repository,
      CombatLogXHook combatHook,
      MainThread main,
      SoundFeedback sounds) {
    this.plugin = plugin;
    this.repository = repository;
    this.combatHook = combatHook;
    this.main = main;
    this.sounds = sounds;
  }

  private boolean allowed(Player player) {
    return !stopping
        && player.isOnline()
        && !player.isDead()
        && player.hasPermission("enthusiaexpress.use")
        && player.hasPermission("enthusiaexpress.inbox")
        && combatHook.mayUseMail(player);
  }

  public void open(Player player, MailType type) {
    open(player, type, 0);
  }

  private void open(Player player, MailType type, int page) {
    if (!allowed(player)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "mail-unavailable"));
      return;
    }
    if (page < 0 || page > 1_000_000) return;
    player.closeInventory();
    Inventory inv =
        Bukkit.createInventory(null, 54, TITLE_PREFIX + " - " + type + " " + (page + 1));
    Session session = new Session(inv, type, page);
    sessions.put(player.getUniqueId(), session);
    inv.setItem(0, icon(Material.ARROW, "\u00a7ePrevious page"));
    inv.setItem(1, icon(Material.CHEST, "\u00a76Packages"));
    inv.setItem(4, icon(Material.WRITABLE_BOOK, "\u00a7eLetters"));
    inv.setItem(7, icon(Material.BELL, "\u00a7bAnnouncements"));
    inv.setItem(8, icon(Material.ARROW, "\u00a7eNext page"));
    player.openInventory(inv);
    main.complete(
        repository.listInbox(player.getUniqueId(), type, page),
        (records, error) -> {
          if (!active(player, session)) return;
          if (error != null) {
            player.sendMessage(Text.msg(plugin.getConfig(), "database-error"));
            return;
          }
          int slot = 9;
          for (MailRecord record : records) {
            ItemStack item;
            try {
              item =
                  record.type() == MailType.PACKAGE
                      ? ItemCodec.decode(record.payload())
                      : icon(
                          Material.WRITTEN_BOOK,
                          (record.unread() ? "\u00a7e[Unread] " : "\u00a77[Read] ") + record.senderName());
              ItemMeta meta = item.getItemMeta();
              meta.setLore(
                  List.of(
                      "\u00a77From: " + record.senderName(),
                      "\u00a77Mail #" + record.id(),
                      record.type() == MailType.PACKAGE
                          ? "\u00a7aClick to claim"
                          : "\u00a7aClick to read"));
              item.setItemMeta(meta);
            } catch (RuntimeException e) {
              item = icon(Material.BARRIER, "\u00a7cUnreadable mail #" + record.id());
              plugin.getLogger().warning("Unreadable mail #" + record.id() + ": " + e);
            }
            inv.setItem(slot, item);
            session.records.put(slot++, record);
          }
          session.loaded = true;
          if (records.isEmpty()) player.sendMessage(Text.msg(plugin.getConfig(), "mailbox-empty"));
        });
  }

  private boolean active(Player player, Session session) {
    return allowed(player)
        && sessions.get(player.getUniqueId()) == session
        && player.getOpenInventory().getTopInventory() == session.inventory;
  }

  public boolean owns(Player player) {
    Session session = sessions.get(player.getUniqueId());
    return session != null && player.getOpenInventory().getTopInventory() == session.inventory;
  }

  public void deferClick(Player player, int slot) {
    Session session = sessions.get(player.getUniqueId());
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (session != null && active(player, session)) click(player, slot);
            });
  }

  public void click(Player player, int slot) {
    Session session = sessions.get(player.getUniqueId());
    if (session == null || !active(player, session)) {
      player.closeInventory();
      return;
    }
    if (slot == 1) {
      open(player, MailType.PACKAGE);
      return;
    }
    if (slot == 4) {
      open(player, MailType.LETTER);
      return;
    }
    if (slot == 7) {
      open(player, MailType.ANNOUNCEMENT);
      return;
    }
    if (slot == 0) {
      if (session.page > 0) open(player, session.type, session.page - 1);
      return;
    }
    if (slot == 8) {
      if (session.loaded && session.records.size() == 45)
        open(player, session.type, session.page + 1);
      return;
    }
    MailRecord visible = session.records.get(slot);
    if (visible == null || !claiming.add(player.getUniqueId())) return;
    main.complete(
        repository.get(visible.id()),
        (record, error) -> {
          if (error != null
              || record == null
              || !active(player, session)
              || !record.recipient().equals(player.getUniqueId())
              || (record.status() != MailStatus.UNCLAIMED
                  && record.status() != MailStatus.RETURNED)) {
            claiming.remove(player.getUniqueId());
            return;
          }
          ItemStack item;
          try {
            item = ItemCodec.decode(record.payload());
          } catch (RuntimeException e) {
            claiming.remove(player.getUniqueId());
            player.sendMessage("\u00a7cThis mail cannot be decoded; contact an administrator.");
            return;
          }
          if (record.type() == MailType.PACKAGE) claimPackage(player, record, item);
          else {
            try {
              player.closeInventory();
              player.openBook(item);
              main.complete(
                  repository.markRead(record.id(), player.getUniqueId()),
                  (ok, failure) -> {
                    claiming.remove(player.getUniqueId());
                    if (failure != null)
                      plugin.getLogger().warning("Cannot mark mail read: " + failure);
                    else if (Boolean.TRUE.equals(ok) && record.type() == MailType.LETTER)
                      sounds.play(player, SoundFeedback.Cue.LETTER_OPEN);
                  });
            } catch (RuntimeException e) {
              claiming.remove(player.getUniqueId());
              player.sendMessage("\u00a7cThis book could not be opened.");
            }
          }
        });
  }

  private void claimPackage(Player player, MailRecord record, ItemStack stack) {
    if (!player.hasPermission("enthusiaexpress.packages.claim")
        || player.getInventory().firstEmpty() == -1) {
      claiming.remove(player.getUniqueId());
      player.sendMessage("\u00a7cYou need claim permission and an empty inventory slot.");
      return;
    }
    main.complete(
        repository.claim(record.id(), player.getUniqueId()),
        (claimed, error) -> {
          if (error != null || !Boolean.TRUE.equals(claimed)) {
            claiming.remove(player.getUniqueId());
            player.sendMessage("\u00a7cThat package could not be claimed.");
            return;
          }
          if (!allowed(player)
              || !player.hasPermission("enthusiaexpress.packages.claim")
              || player.getInventory().firstEmpty() == -1) {
            main.complete(
                repository.restoreClaim(record),
                (restored, failure) -> {
                  claiming.remove(player.getUniqueId());
                  if (failure != null || !Boolean.TRUE.equals(restored))
                    plugin
                        .getLogger()
                        .severe(
                            "Could not restore undelivered claim #" + record.id() + ": " + failure);
                });
            return;
          }
          player
              .getInventory()
              .addItem(stack)
              .values()
              .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
          claiming.remove(player.getUniqueId());
          player.sendMessage("\u00a7aPackage claimed.");
          sounds.play(player, SoundFeedback.Cue.PACKAGE_CLAIM);
          if (owns(player)) open(player, MailType.PACKAGE);
        });
  }

  public void close(Player player, Inventory inventory) {
    Session session = sessions.get(player.getUniqueId());
    if (session != null && session.inventory == inventory) sessions.remove(player.getUniqueId());
  }

  public void shutdown() {
    stopping = true;
    for (Player player : Bukkit.getOnlinePlayers()) if (owns(player)) player.closeInventory();
    sessions.clear();
  }

  private static ItemStack icon(Material material, String name) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    meta.setDisplayName(name);
    stack.setItemMeta(meta);
    return stack;
  }
}
