package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

final class ClaimProtectionListenerContractTest {
  @Test
  void everyAuditedPaperEventHasAnActiveAdapter() {
    Set<Class<?>> handled =
        Stream.of(ClaimProtectionListener.class, CampaignStructuralListener.class)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .map(Method::getParameterTypes)
            .filter(parameters -> parameters.length == 1)
            .map(parameters -> parameters[0])
            .filter(Event.class::isAssignableFrom)
            .collect(Collectors.toSet());
    Set<Class<? extends Event>> required =
        Set.of(
            BlockPlaceEvent.class,
            BlockBreakEvent.class,
            InventoryOpenEvent.class,
            InventoryMoveItemEvent.class,
            PlayerInteractEvent.class,
            PlayerInteractEntityEvent.class,
            EntityDamageByEntityEvent.class,
            EntityChangeBlockEvent.class,
            HangingPlaceEvent.class,
            HangingBreakEvent.class,
            PlayerArmorStandManipulateEvent.class,
            PlayerBucketEmptyEvent.class,
            PlayerBucketFillEvent.class,
            BlockSpreadEvent.class,
            BlockIgniteEvent.class,
            EntityPlaceEvent.class,
            BlockRedstoneEvent.class,
            BlockFromToEvent.class,
            BlockPistonExtendEvent.class,
            BlockPistonRetractEvent.class,
            EntityExplodeEvent.class,
            BlockExplodeEvent.class);

    required.forEach(type -> assertTrue(handled.contains(type), type.getSimpleName()));
  }

  @Test
  void specializedBlocksAndVehiclesUseRestrictiveActions() {
    assertEquals(
        nl.frontier.city.ClaimProtectionService.Action.TRAMPLE,
        ClaimProtectionListener.interactionAction(
            org.bukkit.event.block.Action.PHYSICAL, org.bukkit.Material.FARMLAND));
    assertEquals(
        nl.frontier.city.ClaimProtectionService.Action.REDSTONE,
        ClaimProtectionListener.interactionAction(
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.Material.LEVER));
    assertEquals(
        nl.frontier.city.ClaimProtectionService.Action.REDSTONE,
        ClaimProtectionListener.interactionAction(
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.Material.STONE_BUTTON));
    assertEquals(
        nl.frontier.city.ClaimProtectionService.Action.INTERACT,
        ClaimProtectionListener.interactionAction(
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.Material.OAK_DOOR));
    assertTrue(ClaimProtectionListener.isVehicle(org.bukkit.Material.OAK_BOAT));
    assertTrue(ClaimProtectionListener.isVehicle(org.bukkit.Material.MINECART));
  }
}
