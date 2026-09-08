package io.enthusia.express.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;

public final class GuiListener implements Listener {
  private final ShippingService shipping;
  private final MailboxService mailbox;

  public GuiListener(ShippingService shipping, MailboxService mailbox) {
    this.shipping = shipping;
    this.mailbox = mailbox;
  }

  @EventHandler(ignoreCancelled = true)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    Inventory top = event.getView().getTopInventory();
    if (shipping.owns(player, top)) {
      int raw = event.getRawSlot();
      boolean normal = event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT;
      boolean allowedSlot = raw == ShippingService.PACKAGE_SLOT || raw >= top.getSize();
      event.setCancelled(!normal || !allowedSlot);
      if (normal
          && raw == ShippingService.PACKAGE_SLOT
          && shipping.isPlaceholder(event.getCurrentItem())) {
        event.setCancelled(true);
        if (event.getCursor() != null
            && !event.getCursor().getType().isAir()
            && !shipping.isPlaceholder(event.getCursor())) {
          top.setItem(ShippingService.PACKAGE_SLOT, event.getCursor().clone());
          event.setCursor(null);
        }
      } else if (normal && raw == ShippingService.PACKAGE_SLOT) {
        shipping.deferPlaceholderRefresh(player, top);
      }
      if (normal && raw == ShippingService.CANCEL_SLOT) shipping.defer(player, top, false);
      if (normal && raw == ShippingService.CONFIRM_SLOT) shipping.defer(player, top, true);
    } else if (mailbox.owns(player)) {
      event.setCancelled(true);
      if (event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize())
        mailbox.deferClick(player, event.getRawSlot());
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    Inventory top = event.getView().getTopInventory();
    if (mailbox.owns(player) && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize()))
      event.setCancelled(true);
    if (shipping.owns(player, top)
        && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize()))
      event.setCancelled(true);
  }

  @EventHandler
  public void onClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    shipping.returnPackageOnClose(player, event.getInventory());
    mailbox.close(player, event.getInventory());
  }
}
