package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.npc.Navigator;
import nl.frontier.npc.NpcMaterializationGateway;
import nl.frontier.npc.PlayerObservation;
import nl.frontier.npc.WorkerActivityGateway;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

final class NpcMaterializationSupervisor {
  private final Plugin plugin;
  private final Server server;
  private final SchedulerFacade schedulers;
  private final NpcMaterializationGateway gateway;
  private final WorkerActivityGateway activities;
  private final Navigator navigator;
  private final Duration interval;
  private final Duration activityLease;
  private final int maximumActivities;
  private final int maximumPerSettlement;
  private final Logger logger;
  private final NamespacedKey workerKey;
  private final NamespacedKey cityKey;
  private final AtomicBoolean active = new AtomicBoolean();
  private final Set<UUID> navigating = ConcurrentHashMap.newKeySet();
  private final UUID scheduler = UUID.randomUUID();

  NpcMaterializationSupervisor(
      Plugin plugin,
      SchedulerFacade schedulers,
      NpcMaterializationGateway gateway,
      WorkerActivityGateway activities,
      Navigator navigator,
      Duration interval,
      Duration activityLease,
      int maximumActivities,
      int maximumPerSettlement,
      Logger logger) {
    this.plugin = plugin;
    this.server = plugin.getServer();
    this.schedulers = schedulers;
    this.gateway = gateway;
    this.activities = activities;
    this.navigator = navigator;
    this.interval = interval;
    this.activityLease = activityLease;
    this.maximumActivities = maximumActivities;
    this.maximumPerSettlement = maximumPerSettlement;
    this.logger = logger;
    workerKey = new NamespacedKey(plugin, "worker_id");
    cityKey = new NamespacedKey(plugin, "settlement_id");
  }

  void start() {
    if (active.compareAndSet(false, true)) cycle();
  }

  void stop() {
    active.set(false);
    if (navigator instanceof PaperWorkerNavigator paper) paper.stop();
  }

  private void cycle() {
    if (!active.get()) return;
    schedulers.global(
        () -> {
          Set<PlayerObservation> observers = new HashSet<>();
          server
              .getOnlinePlayers()
              .forEach(
                  player -> {
                    Location location = player.getLocation();
                    observers.add(
                        new PlayerObservation(
                            player.getUniqueId(),
                            location.getWorld().getUID(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()));
                  });
          Instant now = Instant.now();
          schedulers
              .async(
                  () ->
                      new ProjectionBatch(
                          gateway.candidates(observers, maximumPerSettlement),
                          gateway.retirements(observers),
                          activities.cycle(
                              observers,
                              scheduler,
                              maximumActivities,
                              now,
                              now.plus(activityLease))))
              .whenComplete(
                  (batch, failure) -> {
                    if (failure != null)
                      logger.log(Level.WARNING, "NPC projection query failed", failure);
                    else reconcile(batch);
                    schedulers.later(interval, this::cycle);
                  });
        });
  }

  private void reconcile(ProjectionBatch batch) {
    for (NpcMaterializationGateway.Binding binding : batch.retirements()) retire(binding);
    for (NpcMaterializationGateway.Candidate candidate : batch.candidates()) {
      if (candidate.entity() != null) {
        var existing = server.getEntity(candidate.entity());
        if (existing != null && existing.isValid()) continue;
        schedulers.async(
            () -> {
              gateway.unbind(candidate.worker(), candidate.entity(), Instant.now());
              return null;
            });
        continue;
      }
      spawn(candidate);
    }
    for (WorkerActivityGateway.Activity activity : batch.activities().visible()) navigate(activity);
  }

  private void navigate(WorkerActivityGateway.Activity activity) {
    if (!"TRAVELLING".equals(activity.status()) || activity.entity() == null) return;
    if (!navigating.add(activity.id())) return;
    BlockPos destination =
        new BlockPos(new WorldId(activity.world()), activity.x(), activity.y(), activity.z());
    navigator
        .navigate(activity.entity(), destination)
        .whenComplete(
            (result, failure) -> {
              navigating.remove(activity.id());
              if (!active.get()) return;
              Instant now = Instant.now();
              schedulers
                  .async(
                      () -> {
                        if (failure == null && result == Navigator.Result.ARRIVED)
                          activities.arrived(activity.id(), activity.worker(), scheduler, now);
                        else
                          activities.failed(
                              activity.id(),
                              activity.worker(),
                              scheduler,
                              failure == null ? result.name() : failure.getClass().getSimpleName(),
                              now);
                        return null;
                      })
                  .exceptionally(
                      error -> {
                        logger.log(Level.WARNING, "Worker activity handoff failed", error);
                        return null;
                      });
            });
  }

  private void spawn(NpcMaterializationGateway.Candidate candidate) {
    BlockPos position =
        new BlockPos(new WorldId(candidate.world()), candidate.x(), candidate.y(), candidate.z());
    schedulers.at(
        position,
        () -> {
          World world = server.getWorld(candidate.world());
          if (world == null) return;
          int y = world.getHighestBlockYAt(candidate.x(), candidate.z()) + 1;
          Mannequin mannequin =
              world.spawn(
                  new Location(world, candidate.x() + 0.5, y, candidate.z() + 0.5),
                  Mannequin.class,
                  entity -> {
                    entity.customName(
                        Component.text(
                            title(candidate.profession()) + " • " + candidate.skill(),
                            NamedTextColor.GOLD));
                    entity.setCustomNameVisible(true);
                    entity.setDescription(
                        Component.text("Worker of The Frontier", NamedTextColor.GRAY));
                    entity
                        .getPersistentDataContainer()
                        .set(workerKey, PersistentDataType.STRING, candidate.worker().toString());
                    entity
                        .getPersistentDataContainer()
                        .set(cityKey, PersistentDataType.STRING, candidate.city().toString());
                  });
          schedulers
              .async(
                  () -> {
                    gateway.bind(candidate.worker(), mannequin.getUniqueId(), Instant.now());
                    return null;
                  })
              .exceptionally(
                  failure -> {
                    schedulers.forEntity(mannequin.getUniqueId(), mannequin::remove, () -> {});
                    logger.log(Level.WARNING, "Could not bind NPC presentation", failure);
                    return null;
                  });
        });
  }

  private void retire(NpcMaterializationGateway.Binding binding) {
    schedulers.forEntity(
        binding.entity(),
        () -> {
          var entity = server.getEntity(binding.entity());
          if (entity != null) entity.remove();
        },
        () -> {});
    schedulers.async(
        () -> {
          gateway.unbind(binding.worker(), binding.entity(), Instant.now());
          return null;
        });
  }

  private static String title(String profession) {
    String normalized = profession.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
  }

  private record ProjectionBatch(
      List<NpcMaterializationGateway.Candidate> candidates,
      List<NpcMaterializationGateway.Binding> retirements,
      WorkerActivityGateway.CycleReport activities) {}
}
