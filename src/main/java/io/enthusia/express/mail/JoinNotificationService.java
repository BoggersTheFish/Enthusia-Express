package io.enthusia.express.mail;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.util.MainThread;
import io.enthusia.express.util.Text;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Asynchronous observation followed by main-thread session identity revalidation. */
public final class JoinNotificationService implements Listener {
  private final JavaPlugin plugin;
  private final MailRepository repository;
  private final MainThread main;

  public JoinNotificationService(JavaPlugin plugin, MailRepository repository, MainThread main) {
    this.plugin = plugin;
    this.repository = repository;
    this.main = main;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player session = event.getPlayer();
    if (!plugin.getConfig().getBoolean("notifications.join-mail.enabled", true)) return;
    UUID id = session.getUniqueId();
    main.complete(
        repository.pendingMail(id),
        (summary, error) -> {
          if (error != null) {
            plugin.getLogger().warning("Could not check joining player's mail: " + error);
            return;
          }
          if (!session.isOnline() || Bukkit.getPlayer(id) != session || summary.total() == 0) return;
          session.sendMessage(
              Text.msg(
                  plugin.getConfig(),
                  "join-mail",
                  Map.of(
                      "packages", String.valueOf(summary.packages()),
                      "letters", String.valueOf(summary.letters()),
                      "announcements", String.valueOf(summary.announcements()),
                      "total", String.valueOf(summary.total()))));
        });
  }
}
