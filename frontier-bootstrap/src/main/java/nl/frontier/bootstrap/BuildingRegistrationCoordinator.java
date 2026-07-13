package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.BuildingType;
import nl.frontier.city.BuildingValidationGateway;
import nl.frontier.city.BuildingValidationResult;
import nl.frontier.city.BuildingValidationService;
import nl.frontier.city.SettlementGateway;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

final class BuildingRegistrationCoordinator {
  private final SchedulerFacade schedulers;
  private final PaperBuildingSurveyor surveyor;
  private final BuildingValidationService buildings;
  private final Duration selectionTimeout;
  private final NamespacedKey selectionToolKey;
  private final Map<UUID, BuildingSelectionSession> sessions = new ConcurrentHashMap<>();

  BuildingRegistrationCoordinator(
      Plugin plugin,
      SchedulerFacade schedulers,
      PaperBuildingSurveyor surveyor,
      BuildingValidationService buildings,
      Duration selectionTimeout) {
    this.schedulers = schedulers;
    this.surveyor = surveyor;
    this.buildings = buildings;
    this.selectionTimeout = selectionTimeout;
    if (selectionTimeout.isZero() || selectionTimeout.isNegative())
      throw new IllegalArgumentException("selection timeout must be positive");
    selectionToolKey = new NamespacedKey(plugin, "building_selection_tool");
  }

  void start(Player player, UUID city, BuildingType type, String district) {
    schedulers
        .async(
            () -> {
              buildings.authorize(city, player.getUniqueId());
              return null;
            })
        .whenComplete(
            (ignored, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) {
                        failure(player, failure);
                        return;
                      }
                      Instant expiresAt = Instant.now().plus(selectionTimeout);
                      sessions.put(
                          player.getUniqueId(),
                          new BuildingSelectionSession(
                              city, type, district, null, null, expiresAt));
                      giveSelectionTool(player);
                      player.sendMessage(
                          Component.text(
                              "Building selection started for "
                                  + display(type)
                                  + ". Left-click first corner, right-click second corner.",
                              NamedTextColor.AQUA));
                      scheduleExpiry(player.getUniqueId(), expiresAt);
                    },
                    () -> sessions.remove(player.getUniqueId())));
  }

  boolean select(Player player, Location location, boolean firstPoint) {
    BuildingSelectionSession current = active(player);
    if (current == null) return false;
    var point =
        new BuildingSelectionSession.SelectionPoint(
            location.getWorld().getUID(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ());
    try {
      BuildingSelectionSession updated = current.select(point, firstPoint);
      sessions.put(player.getUniqueId(), updated);
      player.sendMessage(
          Component.text(
              (firstPoint ? "First" : "Second")
                  + " corner selected at "
                  + point.x()
                  + ", "
                  + point.y()
                  + ", "
                  + point.z(),
              NamedTextColor.AQUA));
      if (updated.bounds().isPresent()) preview(player);
    } catch (RuntimeException failure) {
      failure(player, failure);
    }
    return true;
  }

  boolean isSelectionTool(ItemStack item) {
    if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
    return item.getItemMeta()
        .getPersistentDataContainer()
        .has(selectionToolKey, PersistentDataType.BYTE);
  }

  boolean hasSession(Player player) {
    return active(player) != null;
  }

  void preview(Player player) {
    BuildingSelectionSession session = requireComplete(player);
    SettlementGateway.Bounds bounds = session.bounds().orElseThrow();
    atBounds(
        bounds,
        () -> {
          var survey = surveyor.survey(bounds);
          schedulers
              .async(
                  () ->
                      buildings.preview(
                          session.city(),
                          player.getUniqueId(),
                          session.type(),
                          bounds,
                          session.district(),
                          survey))
              .whenComplete(
                  (result, failure) ->
                      schedulers.forEntity(
                          player.getUniqueId(),
                          () -> {
                            if (!session.equals(sessions.get(player.getUniqueId()))) return;
                            if (failure != null) failure(player, failure);
                            else showReport(player, bounds, result);
                          },
                          () -> sessions.remove(player.getUniqueId())));
        },
        player);
  }

  void confirm(Player player) {
    BuildingSelectionSession session = requireComplete(player);
    SettlementGateway.Bounds bounds = session.bounds().orElseThrow();
    atBounds(
        bounds,
        () -> {
          var survey = surveyor.survey(bounds);
          schedulers
              .async(
                  () ->
                      buildings.validateAndRegister(
                          session.city(),
                          player.getUniqueId(),
                          session.type(),
                          bounds,
                          session.district(),
                          survey,
                          Instant.now()))
              .whenComplete(
                  (registered, failure) ->
                      schedulers.forEntity(
                          player.getUniqueId(),
                          () -> {
                            if (failure != null) {
                              failure(player, failure);
                              return;
                            }
                            sessions.remove(player.getUniqueId(), session);
                            visualize(player, bounds, true);
                            player.sendMessage(
                                Component.text(
                                    "Validated and activated "
                                        + display(registered.type())
                                        + " building "
                                        + registered.id(),
                                    NamedTextColor.GREEN));
                          },
                          () -> sessions.remove(player.getUniqueId())));
        },
        player);
  }

  boolean cancel(Player player) {
    boolean removed = sessions.remove(player.getUniqueId()) != null;
    player.sendMessage(
        Component.text(
            removed ? "Building selection cancelled." : "No active building selection.",
            removed ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
    return removed;
  }

  void revalidate(Player player, UUID city, UUID building) {
    schedulers
        .async(() -> buildings.building(city, player.getUniqueId(), building))
        .whenComplete(
            (registered, lookupFailure) -> {
              if (lookupFailure != null) {
                entityFailure(player, lookupFailure);
                return;
              }
              atBounds(
                  registered.bounds(),
                  () -> {
                    var survey = surveyor.survey(registered.bounds());
                    schedulers
                        .async(
                            () ->
                                buildings.revalidate(
                                    city, player.getUniqueId(), building, survey, Instant.now()))
                        .whenComplete(
                            (result, failure) ->
                                schedulers.forEntity(
                                    player.getUniqueId(),
                                    () -> {
                                      if (failure != null) failure(player, failure);
                                      else {
                                        visualize(player, result.bounds(), true);
                                        player.sendMessage(
                                            Component.text(
                                                "Revalidated "
                                                    + display(result.type())
                                                    + " "
                                                    + result.id()
                                                    + " as "
                                                    + result.state(),
                                                NamedTextColor.GREEN));
                                      }
                                    },
                                    () -> {}));
                  },
                  player);
            });
  }

  void unregister(Player player, UUID city, UUID building) {
    schedulers
        .async(() -> buildings.unregister(city, player.getUniqueId(), building, Instant.now()))
        .whenComplete(
            (result, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) failure(player, failure);
                      else
                        player.sendMessage(
                            Component.text(
                                "Unregistered building " + result.id() + "; history was retained.",
                                NamedTextColor.YELLOW));
                    },
                    () -> {}));
  }

  void history(Player player, UUID city, UUID building, int limit) {
    schedulers
        .async(() -> buildings.history(city, player.getUniqueId(), building, limit))
        .whenComplete(
            (rows, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) {
                        failure(player, failure);
                        return;
                      }
                      if (rows.isEmpty()) {
                        player.sendMessage(
                            Component.text("No building history.", NamedTextColor.GRAY));
                        return;
                      }
                      player.sendMessage(
                          Component.text("Building history " + building, NamedTextColor.GOLD));
                      rows.forEach(
                          row ->
                              player.sendMessage(
                                  Component.text(
                                      row.occurredAt()
                                          + " "
                                          + (row.from() == null ? "NEW" : row.from())
                                          + " -> "
                                          + row.to()
                                          + " by "
                                          + row.actor(),
                                      NamedTextColor.GRAY)));
                    },
                    () -> {}));
  }

  void proposeTransfer(Player player, UUID city, UUID building, UUID targetCity) {
    schedulers
        .async(
            () ->
                buildings.proposeTransfer(
                    city, player.getUniqueId(), building, targetCity, Instant.now()))
        .whenComplete(
            (proposal, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) failure(player, failure);
                      else
                        player.sendMessage(
                            Component.text(
                                "Transfer proposal "
                                    + proposal.id()
                                    + " sent to settlement "
                                    + proposal.targetCity()
                                    + "; expires "
                                    + proposal.expiresAt(),
                                NamedTextColor.GREEN));
                    },
                    () -> {}));
  }

  void acceptTransfer(Player player, UUID proposal) {
    schedulers
        .async(() -> buildings.acceptTransfer(player.getUniqueId(), proposal, Instant.now()))
        .whenComplete(
            (result, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) failure(player, failure);
                      else
                        player.sendMessage(
                            Component.text(
                                "Accepted building "
                                    + result.id()
                                    + " into settlement "
                                    + result.city(),
                                NamedTextColor.GREEN));
                    },
                    () -> {}));
  }

  void register(
      Player player,
      UUID city,
      BuildingType type,
      SettlementGateway.Bounds bounds,
      String district,
      Consumer<BuildingValidationGateway.RegisteredBuilding> success,
      Consumer<Throwable> failure) {
    atBounds(
        bounds,
        () -> {
          var survey = surveyor.survey(bounds);
          schedulers
              .async(
                  () ->
                      buildings.validateAndRegister(
                          city,
                          player.getUniqueId(),
                          type,
                          bounds,
                          district,
                          survey,
                          Instant.now()))
              .whenComplete(
                  (result, error) ->
                      schedulers.forEntity(
                          player.getUniqueId(),
                          () -> {
                            if (error == null) success.accept(result);
                            else failure.accept(error);
                          },
                          () -> {}));
        },
        player);
  }

  private void atBounds(SettlementGateway.Bounds bounds, Runnable action, Player player) {
    try {
      schedulers.at(
          new BlockPos(new WorldId(bounds.world()), bounds.minX(), bounds.minY(), bounds.minZ()),
          () -> {
            try {
              action.run();
            } catch (RuntimeException failure) {
              entityFailure(player, failure);
            }
          });
    } catch (RuntimeException failure) {
      entityFailure(player, failure);
    }
  }

  private BuildingSelectionSession active(Player player) {
    BuildingSelectionSession session = sessions.get(player.getUniqueId());
    if (session != null && session.expired(Instant.now())) {
      sessions.remove(player.getUniqueId(), session);
      player.sendMessage(Component.text("Building selection expired.", NamedTextColor.YELLOW));
      return null;
    }
    return session;
  }

  private BuildingSelectionSession requireComplete(Player player) {
    BuildingSelectionSession session = active(player);
    if (session == null) throw new DomainException("no active building selection");
    if (session.bounds().isEmpty()) throw new DomainException("select both building corners first");
    return session;
  }

  private void scheduleExpiry(UUID player, Instant expiresAt) {
    schedulers.later(
        selectionTimeout,
        () ->
            schedulers.forEntity(
                player,
                () -> {
                  BuildingSelectionSession session = sessions.get(player);
                  if (session != null && session.expiresAt().equals(expiresAt)) {
                    sessions.remove(player, session);
                    Player online = org.bukkit.Bukkit.getPlayer(player);
                    if (online != null)
                      online.sendMessage(
                          Component.text("Building selection expired.", NamedTextColor.YELLOW));
                  }
                },
                () -> sessions.remove(player)));
  }

  private void giveSelectionTool(Player player) {
    ItemStack tool = new ItemStack(Material.BLAZE_ROD);
    var meta = tool.getItemMeta();
    meta.displayName(Component.text("Frontier Building Selector", NamedTextColor.GOLD));
    meta.getPersistentDataContainer().set(selectionToolKey, PersistentDataType.BYTE, (byte) 1);
    tool.setItemMeta(meta);
    player.getInventory().addItem(tool);
  }

  private static void showReport(
      Player player, SettlementGateway.Bounds bounds, BuildingValidationResult result) {
    visualize(player, bounds, result.valid());
    player.sendMessage(
        Component.text(
            "Validation preview: "
                + display(result.type())
                + " "
                + result.inspection().survey().width()
                + "x"
                + result.inspection().survey().height()
                + "x"
                + result.inspection().survey().depth()
                + (result.valid() ? " — READY" : " — NOT READY"),
            result.valid() ? NamedTextColor.GREEN : NamedTextColor.RED));
    if (result.valid())
      player.sendMessage(
          Component.text(
              "Run /frontier city building confirm to register it.", NamedTextColor.AQUA));
    else
      result
          .violations()
          .forEach(
              violation ->
                  player.sendMessage(Component.text("• " + violation, NamedTextColor.RED)));
  }

  private static void visualize(Player player, SettlementGateway.Bounds bounds, boolean valid) {
    Particle particle = valid ? Particle.HAPPY_VILLAGER : Particle.ANGRY_VILLAGER;
    int[] xs = {bounds.minX(), bounds.maxX()};
    int[] ys = {bounds.minY(), bounds.maxY()};
    int[] zs = {bounds.minZ(), bounds.maxZ()};
    for (int x : xs)
      for (int y : ys)
        for (int z : zs) player.spawnParticle(particle, x + 0.5, y + 0.5, z + 0.5, 4);
  }

  private void entityFailure(Player player, Throwable failure) {
    schedulers.forEntity(
        player.getUniqueId(),
        () -> failure(player, failure),
        () -> sessions.remove(player.getUniqueId()));
  }

  private static void failure(Player player, Throwable failure) {
    player.sendMessage(Component.text(FrontierCommand.rootMessage(failure), NamedTextColor.RED));
  }

  private static String display(BuildingType type) {
    return type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
