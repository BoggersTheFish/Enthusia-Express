package io.enthusia.express;

import static org.mockito.Mockito.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.gui.MailboxService;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.mail.*;
import io.enthusia.express.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockito.*;

class MailboxServiceTest {
  JavaPlugin plugin;
  MailRepository repository;
  CombatLogXHook combat;
  MainThread main;
  Player player;
  PlayerInventory inventory;
  Inventory top;
  InventoryView view;
  MailboxService service;
  SoundFeedback sounds;
  UUID id;
  MockedStatic<Bukkit> bukkit;
  MockedStatic<ItemCodec> codec;
  MockedConstruction<ItemStack> icons;
  List<Runnable> callbacks;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() {
    plugin = mock(JavaPlugin.class);
    repository = mock(MailRepository.class);
    combat = mock(CombatLogXHook.class);
    main = mock(MainThread.class);
    when(plugin.getConfig()).thenReturn(new YamlConfiguration());
    when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    player = mock(Player.class);
    id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    when(player.hasPermission(anyString())).thenReturn(true);
    when(combat.mayUseMail(player)).thenReturn(true);
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.firstEmpty()).thenReturn(0);
    top = mock(Inventory.class);
    view = mock(InventoryView.class);
    when(player.getOpenInventory()).thenReturn(view);
    when(view.getTopInventory()).thenReturn(top);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.createInventory(isNull(), eq(54), anyString())).thenReturn(top);
    codec = mockStatic(ItemCodec.class);
    icons =
        mockConstruction(
            ItemStack.class,
            (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
    callbacks = new ArrayList<>();
    doAnswer(
            invocation -> {
              CompletableFuture<Object> future = invocation.getArgument(0);
              BiConsumer<Object, Throwable> consumer = invocation.getArgument(1);
              future.whenComplete(
                  (value, error) -> callbacks.add(() -> consumer.accept(value, error)));
              return null;
            })
        .when(main)
        .complete(any(), any());
    sounds = mock(SoundFeedback.class);
    service = new MailboxService(plugin, repository, combat, main, sounds);
  }

  void drain() {
    while (!callbacks.isEmpty()) callbacks.removeFirst().run();
  }

  MailRecord record(MailType type) {
    return new MailRecord(
        1,
        UUID.randomUUID(),
        "Sender",
        id,
        "Recipient",
        type,
        MailStatus.UNCLAIMED,
        new byte[] {1},
        1,
        1,
        1,
        true,
        false);
  }

  void open(MailRecord record) {
    when(repository.listInbox(id, record.type(), 0))
        .thenReturn(CompletableFuture.completedFuture(List.of(record)));
    when(repository.get(1)).thenReturn(CompletableFuture.completedFuture(record));
    service.open(player, record.type());
    drain();
  }

  @AfterEach
  void cleanup() {
    icons.close();
    codec.close();
    bukkit.close();
  }

  @Test
  void lettersOpenAsBooksAndPersistReadWithoutClaiming() {
    MailRecord record = record(MailType.LETTER);
    ItemStack book = mock(ItemStack.class);
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(book);
    when(repository.markRead(1, id)).thenReturn(CompletableFuture.completedFuture(true));
    open(record);
    service.click(player, 9);
    drain();
    verify(player).openBook(book);
    verify(repository).markRead(1, id);
    verify(repository, never()).claim(anyLong(), any());
    verify(sounds).play(player, SoundFeedback.Cue.LETTER_OPEN);
  }

  @Test
  void rejectedReadDoesNotProduceSuccessSound() {
    MailRecord record = record(MailType.LETTER);
    ItemStack book = mock(ItemStack.class);
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(book);
    when(repository.markRead(1, id)).thenReturn(CompletableFuture.completedFuture(false));
    open(record);
    service.click(player, 9);
    drain();
    verify(player).openBook(book);
    verifyNoInteractions(sounds);
  }

  @Test
  void successfulPackageDeliveryProducesClaimSound() {
    MailRecord record = record(MailType.PACKAGE);
    ItemStack stack = mock(ItemStack.class);
    when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class));
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(stack);
    when(repository.claim(1, id)).thenReturn(CompletableFuture.completedFuture(true));
    when(inventory.addItem(stack)).thenReturn(new HashMap<>());
    open(record);
    service.click(player, 9);
    drain();
    verify(sounds).play(player, SoundFeedback.Cue.PACKAGE_CLAIM);
  }

  @Test
  void failedClaimProducesNoSuccessSound() {
    MailRecord record = record(MailType.PACKAGE);
    ItemStack stack = mock(ItemStack.class);
    when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class));
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(stack);
    when(repository.claim(1, id)).thenReturn(CompletableFuture.completedFuture(false));
    open(record);
    service.click(player, 9);
    drain();
    verifyNoInteractions(sounds);
    verify(inventory, never()).addItem(any(ItemStack.class));
  }

  @Test
  void announcementsUseTheSameBookReader() {
    MailRecord record = record(MailType.ANNOUNCEMENT);
    ItemStack book = mock(ItemStack.class);
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(book);
    when(repository.markRead(1, id)).thenReturn(CompletableFuture.completedFuture(true));
    open(record);
    service.click(player, 9);
    drain();
    verify(player).openBook(book);
  }

  @Test
  void disconnectDuringClaimRestoresInsteadOfLosingPackage() {
    MailRecord record = record(MailType.PACKAGE);
    ItemStack stack = mock(ItemStack.class);
    when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class));
    codec.when(() -> ItemCodec.decode(record.payload())).thenReturn(stack);
    CompletableFuture<Boolean> claim = new CompletableFuture<>();
    when(repository.claim(1, id)).thenReturn(claim);
    when(repository.restoreClaim(record)).thenReturn(CompletableFuture.completedFuture(true));
    open(record);
    service.click(player, 9);
    drain();
    when(player.isOnline()).thenReturn(false);
    claim.complete(true);
    drain();
    verify(repository).restoreClaim(record);
    verify(inventory, never()).addItem(any(ItemStack.class));
  }

  @Test
  void combatStartingDuringReadPreventsOpeningBook() {
    MailRecord record = record(MailType.LETTER);
    open(record);
    service.click(player, 9);
    when(combat.mayUseMail(player)).thenReturn(false);
    drain();
    verify(player, never()).openBook(any(ItemStack.class));
    verify(repository, never()).markRead(anyLong(), any());
  }

  @Test
  void closingInboxBeforeLoadPreventsStaleResultRendering() {
    CompletableFuture<List<MailRecord>> load = new CompletableFuture<>();
    when(repository.listInbox(id, MailType.LETTER, 0)).thenReturn(load);
    service.open(player, MailType.LETTER);
    service.close(player, top);
    load.complete(List.of(record(MailType.LETTER)));
    drain();
    verify(top, never()).setItem(eq(9), any());
  }
}
