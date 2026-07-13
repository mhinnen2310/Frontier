package nl.frontier.bootstrap;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

final class BuildingSelectionListener implements Listener {
  private final BuildingRegistrationCoordinator coordinator;

  BuildingSelectionListener(BuildingRegistrationCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    Action action = event.getAction();
    if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
    if (!coordinator.hasSession(event.getPlayer())
        || !coordinator.isSelectionTool(event.getItem())
        || event.getClickedBlock() == null) return;
    event.setCancelled(true);
    coordinator.select(
        event.getPlayer(),
        event.getClickedBlock().getLocation(),
        action == Action.LEFT_CLICK_BLOCK);
  }
}
