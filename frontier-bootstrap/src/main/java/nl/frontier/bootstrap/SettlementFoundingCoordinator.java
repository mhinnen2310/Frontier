package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.SettlementApplicationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLifecycleGateway;
import nl.frontier.city.SettlementLifecycleService;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class SettlementFoundingCoordinator {
  private final SchedulerFacade schedulers;
  private final SettlementApplicationService settlements;
  private final SettlementLifecycleService lifecycle;
  private final Map<Material, Integer> materials;
  private final Set<String> allowedEnvironments;
  private final Logger logger;

  SettlementFoundingCoordinator(
      SchedulerFacade schedulers,
      SettlementApplicationService settlements,
      SettlementLifecycleService lifecycle,
      Map<Material, Integer> materials,
      Set<String> allowedEnvironments,
      Logger logger) {
    this.schedulers = schedulers;
    this.settlements = settlements;
    this.lifecycle = lifecycle;
    this.materials = Map.copyOf(materials);
    this.allowedEnvironments = Set.copyOf(allowedEnvironments);
    this.logger = logger;
  }

  void createExpedition(
      Player player,
      String name,
      String charter,
      Consumer<SettlementLifecycleGateway.FoundingExpedition> success,
      Consumer<Throwable> failure) {
    UUID playerId = player.getUniqueId();
    schedulers
        .async(() -> lifecycle.createExpedition(playerId, name, charter, Instant.now()))
        .whenComplete(
            (expedition, error) ->
                schedulers.forEntity(
                    playerId,
                    () -> {
                      if (error != null) failure.accept(error);
                      else success.accept(expedition);
                    },
                    () -> {}));
  }

  void found(
      Player player,
      UUID expeditionId,
      Consumer<SettlementGateway.CitySnapshot> success,
      Consumer<Throwable> failure) {
    try {
      var location = player.getLocation().getBlock().getLocation();
      validatePhysical(
          player.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
      requireMaterials(player);
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
                lifecycle.prepareFounding(expeditionId, playerId, core, Instant.now());
                if (!lifecycle.claimMaterials(expeditionId, playerId, Instant.now()))
                  throw new IllegalStateException("founding material claim is already in progress");
                return lifecycle.activeExpedition(playerId, Instant.now()).orElseThrow();
              })
          .whenComplete(
              (expedition, error) ->
                  schedulers.forEntity(
                      playerId,
                      () -> {
                        if (error != null) {
                          failure.accept(error);
                          return;
                        }
                        consumeAndPlace(player, expedition, success, failure, false);
                      },
                      () -> {}));
    } catch (RuntimeException error) {
      failure.accept(error);
    }
  }

  void resume(Player player) {
    UUID playerId = player.getUniqueId();
    schedulers
        .async(() -> lifecycle.activeExpedition(playerId, Instant.now()))
        .whenComplete(
            (result, error) -> {
              if (error != null || result.isEmpty()) return;
              var expedition = result.get();
              if (!expedition.leader().equals(playerId)
                  || !expedition.status().equals("MATERIALS_CLAIMED")) return;
              schedulers.forEntity(
                  playerId,
                  () -> consumeAndPlace(player, expedition, ignored -> {}, ignored -> {}, true),
                  () -> {});
            });
  }

  void recoverPending() {
    schedulers
        .async(() -> lifecycle.pendingExpeditions(32))
        .whenComplete(
            (pending, error) -> {
              if (error != null) {
                logger.log(Level.WARNING, "Settlement founding recovery lookup failed", error);
                return;
              }
              for (var expedition : pending)
                placeAndComplete(expedition, null, ignored -> {}, ignored -> {}, true);
            });
  }

  private void consumeAndPlace(
      Player player,
      SettlementLifecycleGateway.FoundingExpedition expedition,
      Consumer<SettlementGateway.CitySnapshot> success,
      Consumer<Throwable> failure,
      boolean recovery) {
    boolean consumed = false;
    try {
      if (hasMaterials(player)) {
        takeMaterials(player);
        consumed = true;
      } else if (!recovery) {
        throw new IllegalArgumentException(materialRequirement());
      }
    } catch (RuntimeException error) {
      releaseAndRefund(player, expedition, consumed, error, failure);
      return;
    }
    boolean removed = consumed;
    schedulers
        .async(
            () -> {
              lifecycle.confirmMaterials(expedition.id(), expedition.leader(), Instant.now());
              return lifecycle.activeExpedition(expedition.leader(), Instant.now()).orElseThrow();
            })
        .whenComplete(
            (reserved, error) -> {
              if (error != null) {
                releaseAndRefund(player, expedition, removed, error, failure);
                return;
              }
              placeAndComplete(reserved, player, success, failure, recovery);
            });
  }

  private void placeAndComplete(
      SettlementLifecycleGateway.FoundingExpedition expedition,
      Player player,
      Consumer<SettlementGateway.CitySnapshot> success,
      Consumer<Throwable> failure,
      boolean recovery) {
    var core = expedition.core();
    if (core == null) {
      review(expedition, "durable core location is missing");
      failure.accept(new IllegalStateException("durable core location is missing"));
      return;
    }
    BlockPos position = new BlockPos(new WorldId(core.world()), core.x(), core.y(), core.z());
    schedulers.at(
        position,
        () -> {
          World world = org.bukkit.Bukkit.getWorld(core.world());
          if (world == null) {
            failure.accept(
                new IllegalStateException("founding world is not loaded; recovery queued"));
            return;
          }
          var block = world.getBlockAt(core.x(), core.y(), core.z());
          if (block.isEmpty()) block.setType(Material.BELL);
          else if (block.getType() != Material.BELL) {
            if (player != null && !recovery)
              releaseAndRefund(
                  player,
                  expedition,
                  true,
                  new IllegalStateException("selected core block changed before placement"),
                  failure);
            else review(expedition, "selected core block conflicts with " + block.getType());
            return;
          }
          schedulers
              .async(
                  () -> {
                    lifecycle.confirmCorePlacement(
                        expedition.id(), expedition.leader(), Instant.now());
                    lifecycle.completeExpedition(
                        expedition.id(), expedition.city(), expedition.leader(), Instant.now());
                    return settlements.city(expedition.leader()).orElseThrow();
                  })
              .whenComplete(
                  (city, error) -> {
                    if (error != null) {
                      logger.log(
                          Level.WARNING,
                          "Settlement founding completion remains queued for " + expedition.id(),
                          error);
                      failure.accept(error);
                    } else success.accept(city);
                  });
        });
  }

  private void releaseAndRefund(
      Player player,
      SettlementLifecycleGateway.FoundingExpedition expedition,
      boolean refundItems,
      Throwable cause,
      Consumer<Throwable> failure) {
    schedulers
        .async(
            () -> {
              lifecycle.releaseMaterials(expedition.id(), expedition.leader(), Instant.now());
              return null;
            })
        .whenComplete(
            (ignored, releaseError) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (releaseError == null && refundItems) refundMaterials(player);
                      if (releaseError != null) cause.addSuppressed(releaseError);
                      failure.accept(cause);
                    },
                    () -> {}));
  }

  private void review(SettlementLifecycleGateway.FoundingExpedition expedition, String reason) {
    schedulers.async(
        () -> {
          lifecycle.reviewExpedition(expedition.id(), expedition.leader(), reason, Instant.now());
          return null;
        });
  }

  private void validatePhysical(World world, int x, int y, int z) {
    if (!allowedEnvironments.contains(world.getEnvironment().name()))
      throw new IllegalArgumentException(
          "settlements cannot be founded in environment " + world.getEnvironment());
    if (!world.getWorldBorder().isInside(new org.bukkit.Location(world, x, y, z)))
      throw new IllegalArgumentException("settlement core must be inside the world border");
    if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1)
      throw new IllegalArgumentException("settlement core height is unsafe");
    if (!world.getBlockAt(x, y, z).isEmpty() || !world.getBlockAt(x, y - 1, z).getType().isSolid())
      throw new IllegalArgumentException("stand on solid ground with an empty core block");
    if (world.getBiome(x, y, z).getKey().value().contains("ocean"))
      throw new IllegalArgumentException("settlement core cannot be founded in an ocean biome");
  }

  private void requireMaterials(Player player) {
    if (!hasMaterials(player)) throw new IllegalArgumentException(materialRequirement());
  }

  private boolean hasMaterials(Player player) {
    for (var entry : materials.entrySet())
      if (!player.getInventory().containsAtLeast(new ItemStack(entry.getKey()), entry.getValue()))
        return false;
    return true;
  }

  private void takeMaterials(Player player) {
    materials.forEach(
        (material, amount) -> player.getInventory().removeItem(new ItemStack(material, amount)));
  }

  private void refundMaterials(Player player) {
    materials.forEach(
        (material, amount) -> player.getInventory().addItem(new ItemStack(material, amount)));
  }

  private String materialRequirement() {
    return "founding requires "
        + materials.getOrDefault(Material.STONE_BRICKS, 0)
        + " stone bricks, "
        + materials.getOrDefault(Material.OAK_LOG, 0)
        + " oak logs, "
        + materials.getOrDefault(Material.BELL, 0)
        + " bell(s), and "
        + lifecycle.foundingPolicy().feeMinor()
        + " cents";
  }
}
