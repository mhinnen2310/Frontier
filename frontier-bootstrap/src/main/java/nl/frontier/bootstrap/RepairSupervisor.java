package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.repair.RepairGateway;
import nl.frontier.warfare.WarPolicyCache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

final class RepairSupervisor {
  private final SchedulerFacade schedulers;
  private final RepairGateway repairs;
  private final WarPolicyCache wars;
  private final Duration interval;
  private final Duration lease;
  private final int maximumTasks;
  private final double hostileRadius;
  private final Logger logger;
  private final UUID coordinator = UUID.randomUUID();
  private final AtomicBoolean active = new AtomicBoolean();

  RepairSupervisor(
      SchedulerFacade schedulers,
      RepairGateway repairs,
      WarPolicyCache wars,
      Duration interval,
      Duration lease,
      int maximumTasks,
      double hostileRadius,
      Logger logger) {
    this.schedulers = schedulers;
    this.repairs = repairs;
    this.wars = wars;
    this.interval = interval;
    this.lease = lease;
    this.maximumTasks = maximumTasks;
    this.hostileRadius = hostileRadius;
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
    Instant now = Instant.now();
    schedulers
        .async(() -> repairs.leaseReady(coordinator, maximumTasks, now, now.plus(lease)))
        .whenComplete(
            (tasks, failure) -> {
              if (failure != null) logger.log(Level.WARNING, "Repair task lease failed", failure);
              else tasks.forEach(this::execute);
              schedulers.later(interval, this::cycle);
            });
  }

  private void execute(RepairGateway.PreparedTask task) {
    BlockPos position = new BlockPos(new WorldId(task.world()), task.x(), task.y(), task.z());
    schedulers.at(
        position,
        () -> {
          World world = Bukkit.getWorld(task.world());
          if (world == null) {
            release(task, "WORLD_NOT_LOADED");
            return;
          }
          var block = world.getBlockAt(task.x(), task.y(), task.z());
          String actual = block.getBlockData().getAsString(true);
          String target = Bukkit.createBlockData(task.targetData()).getAsString(true);
          if (actual.equals(target)) {
            commit(task);
            return;
          }
          String expected = Bukkit.createBlockData(task.expectedCurrent()).getAsString(true);
          if (!actual.equals(expected)) {
            conflict(task, actual);
            return;
          }
          Location location = block.getLocation().add(0.5, 0.5, 0.5);
          boolean hostile =
              world.getNearbyPlayers(location, hostileRadius).stream()
                  .anyMatch(
                      player ->
                          wars.hostileCampaign(player.getUniqueId(), task.city()).isPresent());
          if (hostile) {
            release(task, "HOSTILE_NEARBY");
            return;
          }
          animate(task);
          block.setBlockData(Bukkit.createBlockData(task.targetData()), false);
          commit(task);
        });
  }

  private void animate(RepairGateway.PreparedTask task) {
    if (task.workerEntity() == null) return;
    schedulers.forEntity(
        task.workerEntity(),
        () -> {
          var entity = Bukkit.getEntity(task.workerEntity());
          if (entity instanceof LivingEntity living) living.swingMainHand();
        },
        () -> {});
  }

  private void commit(RepairGateway.PreparedTask task) {
    schedulers
        .async(
            () -> {
              repairs.commit(coordinator, task.id(), Instant.now());
              return null;
            })
        .exceptionally(
            failure -> {
              logger.log(
                  Level.WARNING,
                  "Repair block was placed; database reconciliation will retry task " + task.id(),
                  failure);
              return null;
            });
  }

  private void release(RepairGateway.PreparedTask task, String reason) {
    schedulers.async(
        () -> {
          repairs.release(coordinator, task.id(), reason, Instant.now());
          return null;
        });
  }

  private void conflict(RepairGateway.PreparedTask task, String actual) {
    schedulers.async(
        () -> {
          repairs.conflict(coordinator, task.id(), actual, Instant.now());
          return null;
        });
  }
}
