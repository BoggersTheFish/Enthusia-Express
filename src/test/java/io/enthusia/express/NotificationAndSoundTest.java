package io.enthusia.express;

import static org.mockito.Mockito.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.mail.JoinNotificationService;
import io.enthusia.express.mail.MailSummary;
import io.enthusia.express.util.MainThread;
import io.enthusia.express.util.SoundFeedback;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class NotificationAndSoundTest {
  @Test
  void joinSummaryMessagesOnlyTheStillCurrentOnlineSession() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    MailRepository repository = mock(MailRepository.class);
    MainThread main = mock(MainThread.class);
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    YamlConfiguration config = new YamlConfiguration();
    config.set("messages.join-mail", "{packages}/{letters}/{announcements}/{total}");
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    CompletableFuture<MailSummary> observation = new CompletableFuture<>();
    when(repository.pendingMail(id)).thenReturn(observation);
    @SuppressWarnings("unchecked")
    BiConsumer<MailSummary, Throwable>[] callback = new BiConsumer[1];
    doAnswer(invocation -> {
          callback[0] = invocation.getArgument(1);
          return null;
        })
        .when(main)
        .complete(eq(observation), any());
    JoinNotificationService service = new JoinNotificationService(plugin, repository, main);
    service.onJoin(mockJoin(player));
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      callback[0].accept(new MailSummary(1, 2, 3), null);
      verify(player).sendMessage("1/2/3/6");
      clearInvocations(player);
      when(player.isOnline()).thenReturn(false);
      callback[0].accept(new MailSummary(1, 0, 0), null);
      verify(player, never()).sendMessage(anyString());
    }
  }

  @Test
  void zeroJoinSummaryAndDisabledNotificationStaySilent() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    MailRepository repository = mock(MailRepository.class);
    MainThread main = mock(MainThread.class);
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    YamlConfiguration config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    when(repository.pendingMail(id)).thenReturn(CompletableFuture.completedFuture(new MailSummary(0, 0, 0)));
    doAnswer(invocation -> {
          CompletableFuture<MailSummary> future = invocation.getArgument(0);
          BiConsumer<MailSummary, Throwable> callback = invocation.getArgument(1);
          future.whenComplete(callback);
          return null;
        })
        .when(main)
        .complete(any(), any());
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      new JoinNotificationService(plugin, repository, main).onJoin(mockJoin(player));
      verify(player, never()).sendMessage(anyString());
      config.set("notifications.join-mail.enabled", false);
      new JoinNotificationService(plugin, repository, main).onJoin(mockJoin(player));
      verify(repository, times(1)).pendingMail(id);
    }
  }

  @Test
  void configuredSoundPlaysAndMalformedCosmeticConfigDegradesSafely() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Player player = mock(Player.class);
    YamlConfiguration config = new YamlConfiguration();
    config.set("sounds.enabled", true);
    config.set("sounds.package-send.sound", "minecraft:block.note_block.pling");
    config.set("sounds.package-send.volume", 1.0);
    config.set("sounds.package-send.pitch", 1.0);
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(player.getLocation()).thenReturn(mock(Location.class));
    SoundFeedback feedback = new SoundFeedback(plugin);
    feedback.play(player, SoundFeedback.Cue.PACKAGE_SEND);
    verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.pling"), eq(1.0f), eq(1.0f));
    clearInvocations(player);
    config.set("sounds.enabled", false);
    feedback.play(player, SoundFeedback.Cue.PACKAGE_SEND);
    verify(player, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
    config.set("sounds.enabled", true);
    config.set("sounds.package-send.sound", "not a key");
    config.set("sounds.package-send.volume", Double.NaN);
    feedback.play(player, SoundFeedback.Cue.PACKAGE_SEND);
    verify(player, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
  }

  private static PlayerJoinEvent mockJoin(Player player) {
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }
}
