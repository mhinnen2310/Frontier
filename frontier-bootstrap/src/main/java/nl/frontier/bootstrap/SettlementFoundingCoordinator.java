package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLifecycleGateway;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class SettlementFoundingCoordinator {
  private static final Map<Material, Integer> MATERIALS =
      Map.of(Material.STONE_BRICKS, 64, Material.OAK_LOG, 16, Material.BELL, 1);
  private final SchedulerFacade schedulers;
  private final SettlementApplicationService settlements;
  private final SettlementLifecycleService lifecycle;

  SettlementFoundingCoordinator(
      SchedulerFacade schedulers,
      SettlementApplicationService settlements,
      SettlementLifecycleService lifecycle) {
    this.schedulers = schedulers;
    this.settlements = settlements;
    this.lifecycle = lifecycle;
  }

  void found(
      Player player,
      String name,
      String charter,
      Consumer<SettlementGateway.CitySnapshot> success,
      Consumer<Throwable> failure) {
    try {
      var location = player.getLocation().getBlock().getLocation();
      validatePhysical(player, location.getBlockX(), location.getBlockY(), location.getBlockZ());
      takeMaterials(player);
      UUID playerId = player.getUniqueId();
      var core =
          new SettlementLifecycleGateway.CoreLocation(
              player.getWorld().getUID(),
              location.getBlockX(),
              location.getBlockY(),
              location.getBlockZ());
      schedulers
          .async(
              () -> {
                SettlementLifecycleGateway.FoundingReservation reservation = null;
                SettlementGateway.CitySnapshot city = null;
                try {
                  lifecycle.validateCore(core);
                  reservation = lifecycle.reserve(playerId, Instant.now());
                  city =
                      settlements.create(
                          playerId,
                          name,
                          core.world(),
                          Math.floorDiv(core.x(), 16),
                          Math.floorDiv(core.z(), 16),
                          Instant.now());
                  lifecycle.complete(
                      reservation.id(), city.id(), playerId, core, charter, Instant.now());
                  return city;
                } catch (RuntimeException error) {
                  if (city != null) {
                    try {
                      lifecycle.disband(city.id(), playerId, Instant.now());
                    } catch (RuntimeException suppressed) {
                      error.addSuppressed(suppressed);
                    }
                  }
                  if (reservation != null) {
                    try {
                      lifecycle.cancel(reservation.id(), playerId, Instant.now());
                    } catch (RuntimeException suppressed) {
                      error.addSuppressed(suppressed);
                    }
                  }
                  throw error;
                }
              })
          .whenComplete(
              (city, error) ->
                  schedulers.forEntity(
                      playerId,
                      () -> {
                        if (error != null) {
                          refundMaterials(player);
                          failure.accept(error);
                          return;
                        }
                        schedulers.at(
                            new BlockPos(new WorldId(core.world()), core.x(), core.y(), core.z()),
                            () ->
                                player
                                    .getWorld()
                                    .getBlockAt(core.x(), core.y(), core.z())
                                    .setType(Material.BELL));
                        success.accept(city);
                      },
                      () -> {}));
    } catch (RuntimeException error) {
      failure.accept(error);
    }
  }

  private static void validatePhysical(Player player, int x, int y, int z) {
    var world = player.getWorld();
    if (!world.getWorldBorder().isInside(player.getLocation()))
      throw new IllegalArgumentException("settlement core must be inside the world border");
    if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1)
      throw new IllegalArgumentException("settlement core height is unsafe");
    if (!world.getBlockAt(x, y, z).isEmpty() || !world.getBlockAt(x, y - 1, z).getType().isSolid())
      throw new IllegalArgumentException("stand on solid ground with an empty core block");
    if (world.getBiome(x, y, z).getKey().value().contains("ocean"))
      throw new IllegalArgumentException("settlement core cannot be founded in an ocean biome");
  }

  private static void takeMaterials(Player player) {
    for (var entry : MATERIALS.entrySet())
      if (!player.getInventory().containsAtLeast(new ItemStack(entry.getKey()), entry.getValue()))
        throw new IllegalArgumentException(
            "founding requires 64 stone bricks, 16 oak logs, 1 bell, and 2500 cents");
    MATERIALS.forEach(
        (material, amount) -> player.getInventory().removeItem(new ItemStack(material, amount)));
  }

  private static void refundMaterials(Player player) {
    MATERIALS.forEach(
        (material, amount) -> player.getInventory().addItem(new ItemStack(material, amount)));
  }
}
