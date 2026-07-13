package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.warfare.WarDamageGateway;
import org.bukkit.Bukkit;

final class DamageRecoverySupervisor {
  private final SchedulerFacade schedulers;
  private final WarDamageGateway damage;
  private final Duration interval;
  private final int maximum;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  DamageRecoverySupervisor(
      SchedulerFacade schedulers,
      WarDamageGateway damage,
      Duration interval,
      int maximum,
      Logger logger) {
    this.schedulers = schedulers;
    this.damage = damage;
    this.interval = interval;
    this.maximum = maximum;
    this.logger = logger;
  }

  void start() {
    if (active.compareAndSet(false, true)) cycle();
  }

  void stop() {
    active.set(false);
  }

  private void cycle() {
    if (!active.get()) return;
    schedulers
        .async(() -> damage.pendingMutations(maximum))
        .whenComplete(
            (pending, failure) -> {
              if (failure != null)
                logger.log(Level.WARNING, "Damage reconciliation query failed", failure);
              else pending.forEach(this::inspect);
              schedulers.later(interval, this::cycle);
            });
  }

  private void inspect(WarDamageGateway.PendingMutation pending) {
    try {
      schedulers.at(
          new BlockPos(new WorldId(pending.world()), pending.x(), pending.y(), pending.z()),
          () -> {
            var world = Bukkit.getWorld(pending.world());
            if (world == null) return;
            String actual =
                world
                    .getBlockAt(pending.x(), pending.y(), pending.z())
                    .getBlockData()
                    .getAsString(true);
            schedulers.async(
                () -> {
                  if (actual.equals(pending.damagedData()))
                    damage.confirmApplied(pending.damage(), actual, Instant.now());
                  else if (actual.equals(pending.originalData()))
                    damage.reject(
                        pending.damage(), "PHANTOM_MUTATION_AFTER_RESTART", Instant.now());
                  else
                    damage.reject(pending.damage(), "WORLD_CONFLICT_AFTER_RESTART", Instant.now());
                  return null;
                });
          });
    } catch (IllegalStateException unloaded) {
      logger.fine("Damage reconciliation waits for world " + pending.world());
    }
  }
}
