package nl.frontier.bootstrap;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Observes accepted block changes only; all route decisions and database work live in services. */
final class InfrastructureDirtyListener implements Listener {
  private final InfrastructureDirtyTracker tracker;

  InfrastructureDirtyListener(InfrastructureDirtyTracker tracker) {
    this.tracker = tracker;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    mark(event.getBlock(), "BLOCK_PLACE");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    mark(event.getBlock(), "BLOCK_BREAK");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    mark(event.getBlock(), "BLOCK_BURN");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent event) {
    mark(event.getToBlock(), "FLUID_CHANGE");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    event.blockList().forEach(block -> mark(block, "BLOCK_EXPLOSION"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    event.blockList().forEach(block -> mark(block, "ENTITY_EXPLOSION"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    event.getBlocks().forEach(block -> mark(block, "PISTON_MOVE"));
    event
        .getBlocks()
        .forEach(block -> mark(block.getRelative(event.getDirection()), "PISTON_MOVE"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    event.getBlocks().forEach(block -> mark(block, "PISTON_MOVE"));
    event
        .getBlocks()
        .forEach(block -> mark(block.getRelative(event.getDirection()), "PISTON_MOVE"));
  }

  private void mark(Block block, String reason) {
    tracker.offer(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), reason);
  }
}
