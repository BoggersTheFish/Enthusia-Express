package io.enthusia.express.mail;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.util.ItemCodec;
import io.enthusia.express.util.MainThread;
import io.enthusia.express.util.Text;
import io.enthusia.express.util.SoundFeedback;
import io.enthusia.express.util.SoundFeedback.Cue;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends an immutable copy of the signed book held in the main hand. */
public final class BookMailService {
  private final JavaPlugin plugin;
  private final MailRepository repository;
  private final CombatLogXHook combat;
  private final MainThread main;
  private final Set<UUID> pending = new HashSet<>();
  private final Map<UUID, Long> lastSent = new HashMap<>();
  private final SoundFeedback sounds;

  public BookMailService(
      JavaPlugin plugin, MailRepository repository, CombatLogXHook combat, MainThread main) {
    this(plugin, repository, combat, main, new SoundFeedback(plugin));
  }

  public BookMailService(
      JavaPlugin plugin,
      MailRepository repository,
      CombatLogXHook combat,
      MainThread main,
      SoundFeedback sounds) {
    this.plugin = plugin;
    this.repository = repository;
    this.combat = combat;
    this.main = main;
    this.sounds = sounds;
  }

  public void send(Player player, OfflinePlayer target, boolean announcement, boolean broadcast) {
    String permission =
        announcement ? "enthusiaexpress.admin.announce" : "enthusiaexpress.letters.send";
    if (!player.hasPermission("enthusiaexpress.use") || !player.hasPermission(permission)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "no-permission"));
      return;
    }
    if (!combat.mayUseMail(player)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "combat-blocked"));
      return;
    }
    String section = announcement ? "announcements" : "letters";
    if (!plugin.getConfig().getBoolean(section + ".enabled", true)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "feature-disabled"));
      return;
    }
    if (!announcement
        && (target == null
            || target.isOnline()
            || target.getUniqueId().equals(player.getUniqueId()))) {
      player.sendMessage(Text.msg(plugin.getConfig(), "target-online"));
      return;
    }
    if (pending.contains(player.getUniqueId())) {
      player.sendMessage("\u00a7eYour previous message is still being saved.");
      return;
    }
    long now = System.currentTimeMillis();
    long cooldown = plugin.getConfig().getLong(section + ".cooldown-seconds", 10) * 1000L;
    if (now - lastSent.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
      player.sendMessage(Text.msg(plugin.getConfig(), "book-cooldown"));
      return;
    }
    ItemStack book = player.getInventory().getItemInMainHand().clone();
    if (book.getType() != Material.WRITTEN_BOOK
        || !(book.getItemMeta() instanceof BookMeta meta)
        || !meta.hasPages()) {
      player.sendMessage(Text.msg(plugin.getConfig(), "hold-signed-book"));
      return;
    }
    if (meta.getPageCount() > plugin.getConfig().getInt("letters.max-pages", 50)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "book-too-large"));
      return;
    }
    book.setAmount(1);
    byte[] payload = ItemCodec.encode(book);
    if (payload.length > plugin.getConfig().getInt("letters.max-payload-bytes", 262144)) {
      player.sendMessage(Text.msg(plugin.getConfig(), "book-too-large"));
      return;
    }
    CompletableFuture<Integer> result;
    if (broadcast) {
      Map<UUID, String> recipients = new HashMap<>();
      for (OfflinePlayer recipient : Bukkit.getOfflinePlayers()) {
        if (recipient.getName() != null)
          recipients.put(recipient.getUniqueId(), recipient.getName());
      }
      for (Player recipient : Bukkit.getOnlinePlayers())
        recipients.put(recipient.getUniqueId(), recipient.getName());
      result = repository.announce(player.getUniqueId(), player.getName(), recipients, payload);
    } else {
      if (target == null) return;
      if (announcement) {
        result =
            repository
                .insertMail(
                    player.getUniqueId(),
                    player.getName(),
                    target.getUniqueId(),
                    Objects.requireNonNullElse(target.getName(), target.getUniqueId().toString()),
                    MailType.ANNOUNCEMENT,
                    payload,
                    0,
                    false)
                .thenApply(id -> 1);
      } else {
        result =
            repository
                .insertMailLimited(
                    player.getUniqueId(),
                    player.getName(),
                    target.getUniqueId(),
                    Objects.requireNonNullElse(target.getName(), target.getUniqueId().toString()),
                    MailType.LETTER,
                    payload,
                    0,
                    plugin
                        .getConfig()
                        .getBoolean("mail.limits.one-outstanding-letter-per-recipient", false))
                .thenApply(id -> id.isPresent() ? 1 : 0);
      }
    }
    pending.add(player.getUniqueId());
    main.complete(
        result,
        (count, error) -> {
          pending.remove(player.getUniqueId());
          if (error != null) {
            plugin.getLogger().severe("Book delivery failed: " + error);
            player.sendMessage(Text.msg(plugin.getConfig(), "database-error"));
          } else if (count == 0 && !announcement) {
            player.sendMessage(Text.msg(plugin.getConfig(), "outstanding-letter"));
          } else {
            lastSent.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(
                Text.msg(plugin.getConfig(), "book-sent", Map.of("count", count.toString())));
            if (!announcement) sounds.play(player, Cue.LETTER_SEND);
          }
        });
  }
}
