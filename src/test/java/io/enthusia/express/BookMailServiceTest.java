package io.enthusia.express;

import static org.mockito.Mockito.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.mail.*;
import io.enthusia.express.util.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.OptionalLong;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;

class BookMailServiceTest {
  JavaPlugin plugin;
  MailRepository repository;
  CombatLogXHook combat;
  MainThread main;
  Player player;
  OfflinePlayer target;
  YamlConfiguration config;
  BookMailService service;
  ItemStack book;
  BookMeta meta;
  SoundFeedback sounds;

  @BeforeEach
  void setup() {
    plugin = mock(JavaPlugin.class);
    repository = mock(MailRepository.class);
    combat = mock(CombatLogXHook.class);
    main = mock(MainThread.class);
    player = mock(Player.class);
    target = mock(OfflinePlayer.class);
    config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    when(player.hasPermission(anyString())).thenReturn(true);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn("Sender");
    when(target.getUniqueId()).thenReturn(UUID.randomUUID());
    when(target.getName()).thenReturn("Recipient");
    when(combat.mayUseMail(player)).thenReturn(true);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    book = mock(ItemStack.class);
    when(inventory.getItemInMainHand()).thenReturn(book);
    when(book.clone()).thenReturn(book);
    when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
    meta = mock(BookMeta.class);
    when(book.getItemMeta()).thenReturn(meta);
    when(meta.hasPages()).thenReturn(true);
    when(meta.getPageCount()).thenReturn(2);
    sounds = mock(SoundFeedback.class);
    service = new BookMailService(plugin, repository, combat, main, sounds);
  }

  @Test
  void letterCopiesSignedBookAndRejectsDuplicatePendingSend() {
    try (var codec = mockStatic(ItemCodec.class)) {
      byte[] bytes = {1, 2};
      codec.when(() -> ItemCodec.encode(book)).thenReturn(bytes);
      when(repository.insertMailLimited(
              any(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean()))
          .thenReturn(new CompletableFuture<>());
      service.send(player, target, false, false);
      service.send(player, target, false, false);
      verify(repository, times(1))
          .insertMailLimited(
              player.getUniqueId(),
              "Sender",
              target.getUniqueId(),
              "Recipient",
              MailType.LETTER,
              bytes,
              0,
              false);
      verify(player.getInventory(), never()).setItemInMainHand(any());
    }
  }

  @Test
  void enabledLetterLimitUsesAtomicInsertAndRejectsWithoutSuccessFeedback() {
    config.set("mail.limits.one-outstanding-letter-per-recipient", true);
    try (var codec = mockStatic(ItemCodec.class)) {
      codec.when(() -> ItemCodec.encode(book)).thenReturn(new byte[] {3});
      when(repository.insertMailLimited(any(), anyString(), any(), anyString(), eq(MailType.LETTER), any(), eq(0), eq(true)))
          .thenReturn(CompletableFuture.completedFuture(OptionalLong.empty()));
      doAnswer(
              invocation -> {
                CompletableFuture<?> future = invocation.getArgument(0);
                java.util.function.BiConsumer<Object, Throwable> callback = invocation.getArgument(1);
                future.whenComplete(callback);
                return null;
              })
          .when(main)
          .complete(any(), any());
      service.send(player, target, false, false);
      verify(repository)
          .insertMailLimited(any(), anyString(), any(), anyString(), eq(MailType.LETTER), any(), eq(0), eq(true));
      verify(player).sendMessage(contains("outstanding-letter"));
      verifyNoInteractions(sounds);
    }
  }

  @Test
  void letterSoundRequiresAcceptedPersistence() {
    try (var codec = mockStatic(ItemCodec.class)) {
      codec.when(() -> ItemCodec.encode(book)).thenReturn(new byte[] {4});
      when(repository.insertMailLimited(any(), anyString(), any(), anyString(), eq(MailType.LETTER), any(), eq(0), eq(false)))
          .thenReturn(CompletableFuture.completedFuture(OptionalLong.of(7)));
      doAnswer(
              invocation -> {
                CompletableFuture<?> future = invocation.getArgument(0);
                java.util.function.BiConsumer<Object, Throwable> callback = invocation.getArgument(1);
                future.whenComplete(callback);
                return null;
              })
          .when(main)
          .complete(any(), any());
      service.send(player, target, false, false);
      verify(sounds).play(player, SoundFeedback.Cue.LETTER_SEND);
    }
  }

  @Test
  void failedLetterPersistenceProducesNoSuccessSound() {
    try (var codec = mockStatic(ItemCodec.class)) {
      codec.when(() -> ItemCodec.encode(book)).thenReturn(new byte[] {5});
      when(repository.insertMailLimited(any(), anyString(), any(), anyString(), eq(MailType.LETTER), any(), eq(0), eq(false)))
          .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("write failed")));
      doAnswer(
              invocation -> {
                CompletableFuture<?> future = invocation.getArgument(0);
                java.util.function.BiConsumer<Object, Throwable> callback = invocation.getArgument(1);
                future.whenComplete(callback);
                return null;
              })
          .when(main)
          .complete(any(), any());
      service.send(player, target, false, false);
      verifyNoInteractions(sounds);
      verify(player).sendMessage(contains("database-error"));
    }
  }

  @Test
  void ordinaryPlayerCannotPublishAnAnnouncement() {
    when(player.hasPermission("enthusiaexpress.admin.announce")).thenReturn(false);
    service.send(player, target, true, false);
    service.send(player, null, true, true);
    verifyNoInteractions(repository);
  }

  @Test
  void onlineRecipientAndCombatAndDisabledFeatureBlockLetters() {
    when(target.isOnline()).thenReturn(true);
    service.send(player, target, false, false);
    when(target.isOnline()).thenReturn(false);
    when(combat.mayUseMail(player)).thenReturn(false);
    service.send(player, target, false, false);
    when(combat.mayUseMail(player)).thenReturn(true);
    config.set("letters.enabled", false);
    service.send(player, target, false, false);
    verifyNoInteractions(repository);
  }

  @Test
  void oversizedOrUnsignedBooksAreRejected() {
    when(meta.getPageCount()).thenReturn(101);
    service.send(player, target, false, false);
    when(book.getType()).thenReturn(Material.WRITABLE_BOOK);
    service.send(player, target, false, false);
    verifyNoInteractions(repository);
  }

  @Test
  void broadcastSnapshotsKnownAndOnlineRecipients() {
    try (var bukkit = mockStatic(Bukkit.class);
        var codec = mockStatic(ItemCodec.class)) {
      bukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[] {target});
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
      byte[] bytes = {8};
      codec.when(() -> ItemCodec.encode(book)).thenReturn(bytes);
      when(repository.announce(any(), anyString(), anyMap(), any()))
          .thenReturn(CompletableFuture.completedFuture(2));
      service.send(player, null, true, true);
      verify(repository)
          .announce(
              player.getUniqueId(),
              "Sender",
              Map.of(target.getUniqueId(), "Recipient", player.getUniqueId(), "Sender"),
              bytes);
    }
  }
}
