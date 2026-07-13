package nl.frontier.bootstrap;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.npc.Navigator;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Small, bounded presentation navigator. Worker state remains authoritative in PostgreSQL. */
final class PaperWorkerNavigator implements Navigator {
  private final Server server;
  private final SchedulerFacade schedulers;
  private final Duration stepInterval;
  private final int maximumSteps;
  private final AtomicBoolean active = new AtomicBoolean(true);

  PaperWorkerNavigator(
      Plugin plugin, SchedulerFacade schedulers, Duration stepInterval, int maximumSteps) {
    this.server = plugin.getServer();
    this.schedulers = schedulers;
    this.stepInterval = stepInterval;
    this.maximumSteps = maximumSteps;
  }

  @Override
  public CompletableFuture<Result> navigate(UUID presentationEntity, BlockPos destination) {
    CompletableFuture<Result> result = new CompletableFuture<>();
    step(presentationEntity, destination, 0, result);
    return result;
  }

  void stop() {
    active.set(false);
  }

  private void step(
      UUID entityId, BlockPos destination, int completedSteps, CompletableFuture<Result> result) {
    if (result.isDone()) return;
    if (!active.get()) {
      result.complete(Result.CANCELLED);
      return;
    }
    if (completedSteps >= maximumSteps) {
      result.complete(Result.BLOCKED);
      return;
    }
    schedulers.forEntity(
        entityId,
        () -> {
          Entity entity = server.getEntity(entityId);
          if (entity == null || !entity.isValid()) {
            result.complete(Result.RETIRED);
            return;
          }
          Location current = entity.getLocation();
          if (!current.getWorld().getUID().equals(destination.world().value())) {
            result.complete(Result.BLOCKED);
            return;
          }
          double dx = destination.x() + 0.5 - current.getX();
          double dz = destination.z() + 0.5 - current.getZ();
          double distance = Math.hypot(dx, dz);
          if (distance <= 1.25) {
            result.complete(Result.ARRIVED);
            return;
          }
          double scale = Math.min(1.0, distance) / distance;
          double nextX = current.getX() + dx * scale;
          double nextZ = current.getZ() + dz * scale;
          int surface =
              current
                  .getWorld()
                  .getHighestBlockYAt((int) Math.floor(nextX), (int) Math.floor(nextZ));
          Location next = new Location(current.getWorld(), nextX, surface + 1.0, nextZ);
          entity
              .teleportAsync(next)
              .whenComplete(
                  (moved, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(moved)) {
                      result.complete(Result.BLOCKED);
                      return;
                    }
                    schedulers.later(
                        stepInterval,
                        () -> step(entityId, destination, completedSteps + 1, result));
                  });
        },
        () -> result.complete(Result.RETIRED));
  }
}
