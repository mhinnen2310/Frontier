package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.repair.BuilderGuildGateway;
import nl.frontier.repair.BuilderGuildPolicy;
import nl.frontier.warfare.WarPolicyCache;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Paper inventory/world handoff around the transactional Builder Guild service. */
final class BuilderGuildCoordinator implements Listener {
  private final Plugin plugin;
  private final SchedulerFacade schedulers;
  private final BuilderGuildGateway guilds;
  private final BuilderGuildPolicy policy;
  private final WarPolicyCache wars;
  private final double hostileRadius;
  private final Logger logger;
  private final Map<UUID, ActiveSession> sessions = new ConcurrentHashMap<>();

  BuilderGuildCoordinator(
      Plugin plugin,
      SchedulerFacade schedulers,
      BuilderGuildGateway guilds,
      BuilderGuildPolicy policy,
      WarPolicyCache wars,
      double hostileRadius,
      Logger logger) {
    this.plugin = plugin;
    this.schedulers = schedulers;
    this.guilds = guilds;
    this.policy = policy;
    this.wars = wars;
    this.hostileRadius = hostileRadius;
    this.logger = logger;
  }

  void begin(Player player, UUID city, UUID order) {
    Instant now = Instant.now();
    schedulers
        .async(
            () ->
                guilds.beginAssist(
                    city,
                    player.getUniqueId(),
                    order,
                    now,
                    now.plusSeconds(policy.assistSessionSeconds())))
        .whenComplete(
            (plan, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) {
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                        return;
                      }
                      sessions.put(player.getUniqueId(), ActiveSession.from(city, plan));
                      player.sendMessage(
                          Component.text(
                              "Controlled repair mode active for "
                                  + policy.assistSessionSeconds()
                                  + " seconds; "
                                  + plan.tasks().size()
                                  + " blocks are available.",
                              NamedTextColor.GREEN));
                    },
                    () -> {}));
  }

  void deliver(Player player, UUID city, UUID order, int amount) {
    if (amount <= 0 || amount > policy.maximumDeliveryUnits()) {
      player.sendMessage(
          Component.text(
              "delivery amount must be 1 to " + policy.maximumDeliveryUnits(), NamedTextColor.RED));
      return;
    }
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand.getType() == Material.AIR || hand.getAmount() < amount) {
      player.sendMessage(
          Component.text("hold at least " + amount + " material blocks", NamedTextColor.RED));
      return;
    }
    String commodity = hand.getType().getKey().toString();
    ItemStack removed = hand.clone();
    removed.setAmount(amount);
    hand.setAmount(hand.getAmount() - amount);
    UUID idempotency = UUID.randomUUID();
    schedulers
        .async(
            () ->
                guilds.deliver(
                    city,
                    player.getUniqueId(),
                    order,
                    commodity,
                    amount,
                    idempotency,
                    Instant.now()))
        .whenComplete(
            (contribution, failure) ->
                schedulers.forEntity(
                    player.getUniqueId(),
                    () -> {
                      if (failure != null) {
                        restore(player, removed);
                        player.sendMessage(
                            Component.text(rootMessage(failure), NamedTextColor.RED));
                      } else {
                        player.sendMessage(
                            Component.text(
                                "Delivered " + amount + " " + commodity + " to the Builder Guild.",
                                NamedTextColor.GREEN));
                      }
                    },
                    () -> {}));
  }

  void stop() {
    sessions.clear();
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onManualRepair(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    ActiveSession session = sessions.get(player.getUniqueId());
    if (session == null) return;
    if (session.expiresAt.isBefore(Instant.now())) {
      sessions.remove(player.getUniqueId(), session);
      player.sendMessage(Component.text("Controlled repair mode expired.", NamedTextColor.YELLOW));
      return;
    }
    var block = event.getBlockPlaced();
    PositionKey key =
        new PositionKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    BuilderGuildGateway.AssistTask task = session.tasks.remove(key);
    if (task == null) return;
    boolean hostile =
        block
            .getWorld()
            .getNearbyPlayers(block.getLocation().add(0.5, 0.5, 0.5), hostileRadius)
            .stream()
            .anyMatch(
                nearby -> wars.hostileCampaign(nearby.getUniqueId(), session.city).isPresent());
    if (hostile) {
      session.tasks.put(key, task);
      event.setCancelled(true);
      player.sendMessage(
          Component.text(
              "Controlled repair is blocked while hostiles are nearby.", NamedTextColor.RED));
      return;
    }
    String replaced = event.getBlockReplacedState().getBlockData().getAsString(true);
    if (!task.expectedData().equals(replaced)) {
      session.tasks.put(key, task);
      event.setCancelled(true);
      player.sendMessage(
          Component.text(
              "The repair block changed; inspect and resolve the conflict first.",
              NamedTextColor.RED));
      return;
    }
    String placed = block.getBlockData().getAsString(true);
    if (!task.targetData().equals(placed)) {
      session.tasks.put(key, task);
      event.setCancelled(true);
      player.sendMessage(
          Component.text(
              "That block does not match the controlled repair plan.", NamedTextColor.RED));
      return;
    }
    ItemStack consumed = event.getItemInHand().clone();
    consumed.setAmount(1);
    UUID idempotency = UUID.randomUUID();
    schedulers
        .async(
            () ->
                guilds.completeManual(
                    session.id,
                    player.getUniqueId(),
                    task.id(),
                    task.world(),
                    task.x(),
                    task.y(),
                    task.z(),
                    placed,
                    idempotency,
                    Instant.now()))
        .whenComplete(
            (result, failure) -> {
              if (failure == null) {
                schedulers.forEntity(
                    player.getUniqueId(),
                    () ->
                        player.sendMessage(
                            Component.text(
                                "Repair progress "
                                    + result.completed()
                                    + "/"
                                    + result.total()
                                    + ".",
                                NamedTextColor.GREEN)),
                    () -> {});
                return;
              }
              session.tasks.put(key, task);
              BlockPos position =
                  new BlockPos(new WorldId(task.world()), task.x(), task.y(), task.z());
              schedulers.at(
                  position,
                  () -> {
                    var world = plugin.getServer().getWorld(task.world());
                    if (world != null)
                      world
                          .getBlockAt(task.x(), task.y(), task.z())
                          .setBlockData(Bukkit.createBlockData(replaced), false);
                  });
              schedulers.forEntity(
                  player.getUniqueId(),
                  () -> {
                    restore(player, consumed);
                    player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                  },
                  () -> {});
              logger.log(Level.WARNING, "Manual repair was rolled back", failure);
            });
  }

  private static void restore(Player player, ItemStack item) {
    player
        .getInventory()
        .addItem(item)
        .values()
        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
  }

  private static String rootMessage(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }

  private record PositionKey(UUID world, int x, int y, int z) {}

  private record ActiveSession(
      UUID id,
      UUID city,
      UUID order,
      Instant expiresAt,
      Map<PositionKey, BuilderGuildGateway.AssistTask> tasks) {
    static ActiveSession from(UUID city, BuilderGuildGateway.AssistPlan plan) {
      Map<PositionKey, BuilderGuildGateway.AssistTask> values = new ConcurrentHashMap<>();
      plan.tasks()
          .forEach(
              task ->
                  values.put(new PositionKey(task.world(), task.x(), task.y(), task.z()), task));
      return new ActiveSession(plan.session(), city, plan.order(), plan.expiresAt(), values);
    }
  }
}
