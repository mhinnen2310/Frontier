package nl.frontier.bootstrap;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.city.ClaimProtectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class ClaimProtectionListener implements Listener {
  private static final UUID ENVIRONMENT = new UUID(0, 0);
  private static final List<int[]> CARDINAL =
      List.of(
          new int[] {1, 0, 0},
          new int[] {-1, 0, 0},
          new int[] {0, 1, 0},
          new int[] {0, -1, 0},
          new int[] {0, 0, 1},
          new int[] {0, 0, -1});
  private final ClaimProtectionService protection;

  ClaimProtectionListener(ClaimProtectionService protection) {
    this.protection = protection;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (!allowed(
        event.getBlock().getLocation(), event.getPlayer(), ClaimProtectionService.Action.BUILD)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    if (!allowed(
        event.getBlock().getLocation(), event.getPlayer(), ClaimProtectionService.Action.BREAK)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    Location location = event.getInventory().getLocation();
    InventoryHolder holder = event.getInventory().getHolder();
    if (location == null && holder instanceof Entity entity) location = entity.getLocation();
    if (location != null && !allowed(location, player, ClaimProtectionService.Action.CONTAINER)) {
      event.setCancelled(true);
      denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryMove(InventoryMoveItemEvent event) {
    Location source = inventoryLocation(event.getSource());
    Location destination = inventoryLocation(event.getDestination());
    if (source != null
        && destination != null
        && !propagationAllowed(source, destination, ClaimProtectionService.Action.AUTOMATION))
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    Block block = event.getClickedBlock();
    if (block == null) return;
    if (event.getItem() != null && isVehicle(event.getItem().getType())) {
      Location target =
          event.getItem().getType().name().contains("MINECART")
              ? block.getLocation()
              : block.getRelative(event.getBlockFace()).getLocation();
      if (!allowed(target, event.getPlayer(), ClaimProtectionService.Action.VEHICLE)) {
        event.setCancelled(true);
        denied(event.getPlayer());
      }
      return;
    }
    ClaimProtectionService.Action action = interactionAction(event.getAction(), block.getType());
    if (!allowed(block.getLocation(), event.getPlayer(), action)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityInteract(PlayerInteractEntityEvent event) {
    if (!allowed(
        event.getRightClicked().getLocation(),
        event.getPlayer(),
        ClaimProtectionService.Action.ENTITY)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityDamage(EntityDamageByEntityEvent event) {
    if (event.getEntity() instanceof Player) return;
    Player player = playerSource(event.getDamager());
    if (player == null) return;
    if (!allowed(event.getEntity().getLocation(), player, ClaimProtectionService.Action.ENTITY)) {
      event.setCancelled(true);
      denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityTrample(EntityChangeBlockEvent event) {
    if (event.getBlock().getType() != Material.FARMLAND || event.getTo() != Material.DIRT) return;
    if (!allowed(event.getBlock().getLocation(), null, ClaimProtectionService.Action.TRAMPLE))
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onArmorStand(PlayerArmorStandManipulateEvent event) {
    if (!allowed(
        event.getRightClicked().getLocation(),
        event.getPlayer(),
        ClaimProtectionService.Action.ENTITY)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHangingPlace(HangingPlaceEvent event) {
    Player player = event.getPlayer();
    if (player != null
        && !allowed(
            event.getEntity().getLocation(), player, ClaimProtectionService.Action.HANGING)) {
      event.setCancelled(true);
      denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHangingBreak(HangingBreakEvent event) {
    Player player =
        event instanceof HangingBreakByEntityEvent byEntity
            ? playerSource(byEntity.getRemover())
            : null;
    if (!allowed(event.getEntity().getLocation(), player, ClaimProtectionService.Action.HANGING)) {
      event.setCancelled(true);
      if (player != null) denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    Location target = event.getBlockClicked().getRelative(event.getBlockFace()).getLocation();
    if (!allowed(target, event.getPlayer(), ClaimProtectionService.Action.BUCKET)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBucketFill(PlayerBucketFillEvent event) {
    if (!allowed(
        event.getBlockClicked().getLocation(),
        event.getPlayer(),
        ClaimProtectionService.Action.BUCKET)) {
      event.setCancelled(true);
      denied(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFireSpread(BlockSpreadEvent event) {
    if (event.getSource().getType() == Material.FIRE
        && !propagationAllowed(
            event.getSource().getLocation(),
            event.getBlock().getLocation(),
            ClaimProtectionService.Action.FIRE)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onIgnite(BlockIgniteEvent event) {
    Player player = event.getPlayer();
    if (!allowed(event.getBlock().getLocation(), player, ClaimProtectionService.Action.FIRE)) {
      event.setCancelled(true);
      if (player != null) denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityPlace(EntityPlaceEvent event) {
    Player player = event.getPlayer();
    if (player != null
        && !allowed(
            event.getEntity().getLocation(), player, ClaimProtectionService.Action.VEHICLE)) {
      event.setCancelled(true);
      denied(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onRedstone(BlockRedstoneEvent event) {
    if (event.getOldCurrent() == event.getNewCurrent()) return;
    Location origin = event.getBlock().getLocation();
    ClaimProtectionService.Decision environment =
        decision(origin, null, ClaimProtectionService.Action.REDSTONE);
    if (environment.reason() == ClaimProtectionService.Reason.WILDERNESS) return;
    for (int[] offset : CARDINAL) {
      Location adjacent = origin.clone().add(offset[0], offset[1], offset[2]);
      if (!propagationAllowed(origin, adjacent, ClaimProtectionService.Action.REDSTONE)) {
        event.setNewCurrent(event.getOldCurrent());
        return;
      }
    }
  }

  private boolean allowed(Location location, Player player, ClaimProtectionService.Action action) {
    return decision(location, player, action).allowed();
  }

  private ClaimProtectionService.Decision decision(
      Location location, Player player, ClaimProtectionService.Action action) {
    return protection.authorize(
        new ClaimProtectionService.Request(
            location.getWorld().getUID(),
            location.getBlockX() >> 4,
            location.getBlockZ() >> 4,
            player == null ? ENVIRONMENT : player.getUniqueId(),
            action,
            player != null && player.hasPermission("frontier.protection.bypass")));
  }

  private boolean propagationAllowed(
      Location source, Location target, ClaimProtectionService.Action action) {
    return protection
        .authorizePropagation(
            new ClaimProtectionService.PropagationRequest(
                source.getWorld().getUID(),
                source.getBlockX() >> 4,
                source.getBlockZ() >> 4,
                target.getWorld().getUID(),
                target.getBlockX() >> 4,
                target.getBlockZ() >> 4,
                action))
        .allowed();
  }

  static ClaimProtectionService.Action interactionAction(
      org.bukkit.event.block.Action eventAction, Material material) {
    if (eventAction == org.bukkit.event.block.Action.PHYSICAL) {
      if (material == Material.FARMLAND) return ClaimProtectionService.Action.TRAMPLE;
      return ClaimProtectionService.Action.REDSTONE;
    }
    String name = material.name();
    if (material == Material.LEVER || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE"))
      return ClaimProtectionService.Action.REDSTONE;
    return ClaimProtectionService.Action.INTERACT;
  }

  static boolean isVehicle(Material material) {
    String name = material.name();
    return name.contains("MINECART") || name.endsWith("_BOAT") || name.endsWith("_RAFT");
  }

  private static Location inventoryLocation(Inventory inventory) {
    Location location = inventory.getLocation();
    if (location == null && inventory.getHolder() instanceof Entity entity)
      return entity.getLocation();
    return location;
  }

  private static Player playerSource(org.bukkit.entity.Entity source) {
    if (source instanceof Player player) return player;
    if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player)
      return player;
    return null;
  }

  private static void denied(Player player) {
    player.sendActionBar(Component.text("This settlement claim is protected.", NamedTextColor.RED));
  }
}
