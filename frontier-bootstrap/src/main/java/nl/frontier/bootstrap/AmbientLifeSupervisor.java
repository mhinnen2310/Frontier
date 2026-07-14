package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.npc.AmbientLifeGateway;
import nl.frontier.npc.AmbientLifePolicy;
import nl.frontier.npc.PlayerObservation;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

final class AmbientLifeSupervisor {
  private static final int OBSERVATION_RADIUS = 128;

  private final Plugin plugin;
  private final Server server;
  private final SchedulerFacade schedulers;
  private final AmbientLifeGateway gateway;
  private final AmbientLifePolicy policy;
  private final int maximumWorkers;
  private final Duration interval;
  private final Duration announcementCooldown;
  private final Logger logger;
  private final NamespacedKey sceneKey;
  private final NamespacedKey cityKey;
  private final AtomicBoolean active = new AtomicBoolean();

  AmbientLifeSupervisor(
      Plugin plugin,
      SchedulerFacade schedulers,
      AmbientLifeGateway gateway,
      AmbientLifePolicy policy,
      int maximumWorkers,
      Duration interval,
      Duration announcementCooldown,
      Logger logger) {
    this.plugin = plugin;
    this.server = plugin.getServer();
    this.schedulers = schedulers;
    this.gateway = gateway;
    this.policy = policy;
    this.maximumWorkers = maximumWorkers;
    this.interval = interval;
    this.announcementCooldown = announcementCooldown;
    this.logger = logger;
    sceneKey = new NamespacedKey(plugin, "ambient_scene_id");
    cityKey = new NamespacedKey(plugin, "settlement_id");
  }

  void start() {
    if (active.compareAndSet(false, true)) cycle();
  }

  void stop() {
    active.set(false);
  }

  private void cycle() {
    if (!active.get()) return;
    schedulers.global(
        () -> {
          Set<PlayerObservation> observers = new HashSet<>();
          for (var player : server.getOnlinePlayers()) {
            Location location = player.getLocation();
            observers.add(
                new PlayerObservation(
                    player.getUniqueId(),
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()));
          }
          Set<UUID> daylightWorlds = new HashSet<>();
          for (World world : server.getWorlds()) {
            long time = world.getTime();
            if (time < 12_300 || time >= 23_850) daylightWorlds.add(world.getUID());
          }
          Instant now = Instant.now();
          schedulers
              .async(
                  () ->
                      gateway.cycle(
                          observers,
                          daylightWorlds,
                          policy,
                          maximumWorkers,
                          now.minus(announcementCooldown),
                          now))
              .whenComplete(
                  (report, failure) -> {
                    if (failure != null)
                      logger.log(Level.WARNING, "Ambient settlement-life cycle failed", failure);
                    else reconcile(report);
                    schedulers.later(interval, this::cycle);
                  });
        });
  }

  private void reconcile(AmbientLifeGateway.CycleReport report) {
    for (AmbientLifeGateway.Binding binding : report.retirements()) retire(binding);
    for (AmbientLifeGateway.Scene scene : report.scenes()) {
      if (scene.entity() == null) {
        spawn(scene);
        continue;
      }
      var existing = server.getEntity(scene.entity());
      if (existing == null || !existing.isValid()) {
        schedulers.async(
            () -> {
              gateway.unbind(scene.id(), scene.entity(), Instant.now());
              return null;
            });
        continue;
      }
      schedulers.forEntity(scene.entity(), () -> existing.customName(name(scene)), () -> {});
    }
    for (AmbientLifeGateway.Announcement announcement : report.announcements())
      announce(announcement);
  }

  private void spawn(AmbientLifeGateway.Scene scene) {
    BlockPos position = new BlockPos(new WorldId(scene.world()), scene.x(), scene.y(), scene.z());
    schedulers.at(
        position,
        () -> {
          World world = server.getWorld(scene.world());
          if (world == null) return;
          int y = scene.y() == 64 ? world.getHighestBlockYAt(scene.x(), scene.z()) + 1 : scene.y();
          Mannequin mannequin =
              world.spawn(
                  new Location(world, scene.x() + 0.5, y, scene.z() + 0.5),
                  Mannequin.class,
                  entity -> {
                    entity.customName(name(scene));
                    entity.setCustomNameVisible(true);
                    entity.setDescription(
                        Component.text("Ambient life of The Frontier", NamedTextColor.GRAY));
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.setCollidable(false);
                    entity.setPersistent(true);
                    entity
                        .getPersistentDataContainer()
                        .set(sceneKey, PersistentDataType.STRING, scene.id().toString());
                    entity
                        .getPersistentDataContainer()
                        .set(cityKey, PersistentDataType.STRING, scene.city().toString());
                  });
          schedulers
              .async(
                  () -> {
                    gateway.bind(scene.id(), mannequin.getUniqueId(), Instant.now());
                    return null;
                  })
              .exceptionally(
                  failure -> {
                    schedulers.forEntity(mannequin.getUniqueId(), mannequin::remove, () -> {});
                    logger.log(Level.WARNING, "Could not bind ambient presentation", failure);
                    return null;
                  });
        });
  }

  private void retire(AmbientLifeGateway.Binding binding) {
    schedulers.forEntity(
        binding.entity(),
        () -> {
          var entity = server.getEntity(binding.entity());
          if (entity != null) entity.remove();
        },
        () -> {});
    schedulers.async(
        () -> {
          gateway.unbind(binding.scene(), binding.entity(), Instant.now());
          return null;
        });
  }

  private void announce(AmbientLifeGateway.Announcement announcement) {
    schedulers.global(
        () ->
            server.getOnlinePlayers().stream()
                .filter(player -> player.getWorld().getUID().equals(announcement.world()))
                .filter(
                    player -> {
                      double x = player.getLocation().getX() - announcement.x();
                      double z = player.getLocation().getZ() - announcement.z();
                      return x * x + z * z <= OBSERVATION_RADIUS * OBSERVATION_RADIUS;
                    })
                .forEach(
                    player ->
                        player.sendMessage(
                            Component.text("[Settlement] ", NamedTextColor.GOLD)
                                .append(
                                    Component.text(announcement.message(), NamedTextColor.GRAY)))));
  }

  private static Component name(AmbientLifeGateway.Scene scene) {
    NamedTextColor color =
        switch (scene.type()) {
          case "SHORTAGE" -> NamedTextColor.RED;
          case "TOWN_HALL_EVENT" -> NamedTextColor.LIGHT_PURPLE;
          case "MARKET" -> NamedTextColor.GREEN;
          case "REPAIR" -> NamedTextColor.YELLOW;
          case "GUARD" -> NamedTextColor.AQUA;
          default -> NamedTextColor.GOLD;
        };
    return Component.text(scene.label(), color);
  }
}
