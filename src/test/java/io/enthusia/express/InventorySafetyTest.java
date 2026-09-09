package io.enthusia.express;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.gui.*;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.util.MainThread;
import io.enthusia.express.util.ContainerScanner;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class InventorySafetyTest {
  ItemStack item(Material type, int amount) {
    ItemStack item = mock(ItemStack.class);
    Material material = mock(Material.class);
    when(material.name()).thenReturn(type.name());
    when(item.getType()).thenReturn(material);
    when(item.getAmount()).thenReturn(amount);
    return item;
  }

  ItemStack bundle(ItemStack... contents) {
    ItemStack item = item(Material.BUNDLE, 1);
    BundleMeta meta = mock(BundleMeta.class);
    when(item.getItemMeta()).thenReturn(meta);
    when(meta.getItems()).thenReturn(List.of(contents));
    return item;
  }

  @Test
  void nestedCountIncludesNestedContainerAndItsContents() {
    assertEquals(
        66,
        ContainerScanner.countPackedItems(
            bundle(item(Material.STONE, 64), bundle(item(Material.DIAMOND, 1))), 8));
    assertEquals(0, ContainerScanner.countPackedItems(bundle(), 8));
  }

  @Test
  void stackedContainersMultiplyContentsAndRejectOverflow() {
    ItemStack nested = bundle(item(Material.DIAMOND, 3));
    when(nested.getAmount()).thenReturn(2);
    assertEquals(8, ContainerScanner.countPackedItems(bundle(nested), 8));
    ItemStack oversized = bundle(item(Material.DIAMOND, Integer.MAX_VALUE));
    assertThrows(
        ArithmeticException.class, () -> ContainerScanner.countPackedItems(bundle(oversized), 8));
    assertThrows(
        ArithmeticException.class,
        () ->
            ContainerScanner.countPackedItems(
                bundle(item(Material.STONE, Integer.MAX_VALUE), item(Material.STONE, 1)), 8));
  }

  @Test
  void cyclicContainerRejectsAtDepthLimitWithoutRecursiveCalls() {
    ItemStack cyclic = bundle();
    BundleMeta meta = (BundleMeta) cyclic.getItemMeta();
    when(meta.getItems()).thenReturn(List.of(cyclic));
    assertThrows(
        IllegalArgumentException.class, () -> ContainerScanner.countPackedItems(cyclic, 10_000));
  }

  @Test
  void shulkerContentsRespectDepthBoundaryAndIgnoreEmptySlots() {
    ItemStack shulker = item(Material.SHULKER_BOX, 1);
    org.bukkit.inventory.meta.BlockStateMeta meta =
        mock(org.bukkit.inventory.meta.BlockStateMeta.class);
    org.bukkit.block.ShulkerBox block = mock(org.bukkit.block.ShulkerBox.class);
    Inventory inventory = mock(Inventory.class);
    when(shulker.getItemMeta()).thenReturn(meta);
    when(meta.getBlockState()).thenReturn(block);
    when(block.getInventory()).thenReturn(inventory);
    ItemStack air = item(Material.AIR, 1);
    when(air.getType().isAir()).thenReturn(true);
    ItemStack stone = item(Material.STONE, 4);
    when(inventory.getContents()).thenReturn(new ItemStack[] {null, air, stone});
    assertEquals(4, ContainerScanner.countPackedItems(shulker, 1));
    assertEquals(5, ContainerScanner.countPackedItems(bundle(shulker), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerScanner.countPackedItems(bundle(shulker), 1));
    assertEquals(0, ContainerScanner.countPackedItems(null, 1));
  }

  @Test
  void excessiveDepthRejectsInsteadOfUndercharging() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerScanner.countPackedItems(bundle(bundle(item(Material.DIAMOND, 64))), 1));
  }

  @Test
  void bundleRecognitionUsesMetadataForColoredVariants() {
    ItemStack item = item(Material.PAPER, 1);
    when(item.getItemMeta()).thenReturn(mock(BundleMeta.class));
    assertTrue(ContainerScanner.isAllowedShippingContainer(item));
    assertFalse(ContainerScanner.isAllowedShippingContainer(null));
    assertFalse(ContainerScanner.isAllowedShippingContainer(item(Material.STONE, 1)));
  }

  @Test
  void shippingAllowsCursorPickupAndRejectsShiftNumberAndDoubleClicks() {
    ShippingService shipping = mock(ShippingService.class);
    MailboxService mailbox = mock(MailboxService.class);
    GuiListener listener = new GuiListener(shipping, mailbox);
    Player player = mock(Player.class);
    Inventory inventory = mock(Inventory.class);
    when(inventory.getSize()).thenReturn(27);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(inventory);
    when(shipping.owns(player, inventory)).thenReturn(true);
    for (ClickType click :
        List.of(
            ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK)) {
      InventoryClickEvent event = mock(InventoryClickEvent.class);
      when(event.getWhoClicked()).thenReturn(player);
      when(event.getView()).thenReturn(view);
      when(event.getRawSlot()).thenReturn(30);
      when(event.getClick()).thenReturn(click);
      listener.onClick(event);
      verify(event).setCancelled(click != ClickType.LEFT);
    }
  }

  @Test
  void dragCannotOverwriteControlsOrExtractMailboxIcons() {
    ShippingService shipping = mock(ShippingService.class);
    MailboxService mailbox = mock(MailboxService.class);
    GuiListener listener = new GuiListener(shipping, mailbox);
    Player player = mock(Player.class);
    Inventory top = mock(Inventory.class);
    when(top.getSize()).thenReturn(27);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    InventoryDragEvent event = mock(InventoryDragEvent.class);
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getView()).thenReturn(view);
    when(event.getRawSlots()).thenReturn(Set.of(13, 15));
    when(shipping.owns(player, top)).thenReturn(true);
    listener.onDrag(event);
    verify(event).setCancelled(true);
    reset(event);
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getView()).thenReturn(view);
    when(event.getRawSlots()).thenReturn(Set.of(10));
    when(shipping.owns(player, top)).thenReturn(false);
    when(mailbox.owns(player)).thenReturn(true);
    listener.onDrag(event);
    verify(event).setCancelled(true);
  }

  @Test
  void placeholderCannotBePickedUpAndRealCargoTriggersRestorationCheck() {
    ShippingService shipping = mock(ShippingService.class);
    MailboxService mailbox = mock(MailboxService.class);
    GuiListener listener = new GuiListener(shipping, mailbox);
    Player player = mock(Player.class);
    Inventory top = mock(Inventory.class);
    when(top.getSize()).thenReturn(27);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    when(shipping.owns(player, top)).thenReturn(true);
    ItemStack placeholder = item(Material.GRAY_STAINED_GLASS_PANE, 1);
    ItemStack cargo = item(Material.BUNDLE, 1);
    when(cargo.clone()).thenReturn(cargo);
    when(shipping.isPlaceholder(placeholder)).thenReturn(true);

    InventoryClickEvent place = mock(InventoryClickEvent.class);
    when(place.getWhoClicked()).thenReturn(player);
    when(place.getView()).thenReturn(view);
    when(place.getRawSlot()).thenReturn(ShippingService.PACKAGE_SLOT);
    when(place.getClick()).thenReturn(ClickType.LEFT);
    when(place.getCurrentItem()).thenReturn(placeholder);
    when(place.getCursor()).thenReturn(cargo);
    listener.onClick(place);
    verify(place).setCancelled(true);
    verify(top).setItem(ShippingService.PACKAGE_SLOT, cargo);
    verify(place).setCursor(null);

    InventoryClickEvent remove = mock(InventoryClickEvent.class);
    when(remove.getWhoClicked()).thenReturn(player);
    when(remove.getView()).thenReturn(view);
    when(remove.getRawSlot()).thenReturn(ShippingService.PACKAGE_SLOT);
    when(remove.getClick()).thenReturn(ClickType.RIGHT);
    when(remove.getCurrentItem()).thenReturn(cargo);
    listener.onClick(remove);
    verify(remove).setCancelled(false);
    verify(shipping).deferPlaceholderRefresh(player, top);
  }

  @Test
  void placeholderIdentityRequiresPrivatePersistentMarker() {
    org.bukkit.plugin.java.JavaPlugin plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
    when(plugin.getName()).thenReturn("EnthusiaExpress");
    ShippingService service =
        new ShippingService(
            plugin,
            mock(MailRepository.class),
            mock(CombatLogXHook.class),
            mock(MainThread.class));
    ItemStack pane = mock(ItemStack.class);
    ItemMeta meta = mock(ItemMeta.class);
    PersistentDataContainer pdc = mock(PersistentDataContainer.class);
    when(pane.getType()).thenReturn(Material.GRAY_STAINED_GLASS_PANE);
    when(pane.getItemMeta()).thenReturn(meta);
    when(meta.getPersistentDataContainer()).thenReturn(pdc);
    when(pdc.has(any(), eq(PersistentDataType.BYTE))).thenReturn(false, true);
    assertFalse(service.isPlaceholder(pane));
    assertTrue(service.isPlaceholder(pane));
  }
}
