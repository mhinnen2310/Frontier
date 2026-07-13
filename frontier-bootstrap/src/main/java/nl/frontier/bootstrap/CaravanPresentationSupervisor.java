package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.CaravanGateway;
import nl.frontier.economy.CaravanService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class CaravanPresentationSupervisor {
  static final String SHIPMENT_KEY = "caravan_shipment";
  private final JavaPlugin plugin;
  private final SchedulerFacade schedulers;
  private final CaravanService caravans;
  private final Logger logger;
  private final NamespacedKey shipmentKey;
  private final Map<UUID, UUID> entities = new HashMap<>();
  private final Map<UUID, Chunk> tickets = new HashMap<>();
  private final AtomicBoolean active = new AtomicBoolean();

  CaravanPresentationSupervisor(
      JavaPlugin plugin, SchedulerFacade schedulers, CaravanService caravans, Logger logger) {
    this.plugin = plugin;
    this.schedulers = schedulers;
    this.caravans = caravans;
    this.logger = logger;
    this.shipmentKey = new NamespacedKey(plugin, SHIPMENT_KEY);
  }

  void start() {
    if (active.compareAndSet(false, true)) cycle();
  }

  void stop() {
    active.set(false);
    entities
        .values()
        .forEach(
            id -> {
              Entity entity = plugin.getServer().getEntity(id);
              if (entity != null) entity.remove();
            });
    tickets.values().forEach(chunk -> chunk.removePluginChunkTicket(plugin));
    entities.clear();
    tickets.clear();
  }

  private void cycle() {
    if (!active.get()) return;
    schedulers
        .async(
            () -> {
              caravans.cycle(128, Instant.now());
              return caravans.presentations(128);
            })
        .whenComplete(
            (presentations, failure) -> {
              if (failure != null) {
                logger.log(Level.WARNING, "Caravan simulation failed", failure);
              } else {
                schedulers.global(() -> render(presentations));
              }
              schedulers.later(Duration.ofSeconds(1), this::cycle);
            });
  }

  private void render(java.util.List<CaravanGateway.Presentation> presentations) {
    Set<UUID> current = new HashSet<>();
    for (CaravanGateway.Presentation presentation : presentations) {
      current.add(presentation.shipment());
      var world = plugin.getServer().getWorld(presentation.world());
      if (world == null) {
        dematerialize(presentation.shipment());
        continue;
      }
      Location location = new Location(world, presentation.x(), presentation.y(), presentation.z());
      boolean observed =
          world.getPlayers().stream()
              .anyMatch(player -> player.getLocation().distanceSquared(location) <= 96 * 96);
      if (!observed) {
        dematerialize(presentation.shipment());
        continue;
      }
      Entity entity = entity(presentation);
      if (entity == null || !entity.isValid()) entity = spawn(presentation, location);
      if (!entity.getWorld().equals(world) || entity.getLocation().distanceSquared(location) > 1)
        entity.teleport(location);
      entity.customName(
          Component.text(
              "Caravan "
                  + presentation.state()
                  + " ♥"
                  + presentation.health()
                  + " — "
                  + presentation.cargo()));
      updateTicket(presentation.shipment(), location.getChunk());
    }
    for (UUID shipment : new HashSet<>(entities.keySet()))
      if (!current.contains(shipment)) dematerialize(shipment);
  }

  private Entity entity(CaravanGateway.Presentation presentation) {
    UUID id = entities.getOrDefault(presentation.shipment(), presentation.entity());
    return id == null ? null : plugin.getServer().getEntity(id);
  }

  private Entity spawn(CaravanGateway.Presentation presentation, Location location) {
    Llama llama = location.getWorld().spawn(location, Llama.class);
    llama.setRemoveWhenFarAway(false);
    llama.setPersistent(false);
    llama.setCarryingChest(true);
    llama
        .getPersistentDataContainer()
        .set(shipmentKey, PersistentDataType.STRING, presentation.shipment().toString());
    entities.put(presentation.shipment(), llama.getUniqueId());
    schedulers.async(
        () -> {
          caravans.bind(presentation.shipment(), llama.getUniqueId(), Instant.now());
          return null;
        });
    return llama;
  }

  private void dematerialize(UUID shipment) {
    UUID entityId = entities.remove(shipment);
    if (entityId != null) {
      Entity entity = plugin.getServer().getEntity(entityId);
      if (entity != null) entity.remove();
      schedulers.async(
          () -> {
            caravans.unbind(shipment, entityId, Instant.now());
            return null;
          });
    }
    Chunk ticket = tickets.remove(shipment);
    if (ticket != null) ticket.removePluginChunkTicket(plugin);
  }

  private void updateTicket(UUID shipment, Chunk chunk) {
    Chunk previous = tickets.put(shipment, chunk);
    if (previous != null && !previous.equals(chunk)) previous.removePluginChunkTicket(plugin);
    chunk.addPluginChunkTicket(plugin);
  }
}
