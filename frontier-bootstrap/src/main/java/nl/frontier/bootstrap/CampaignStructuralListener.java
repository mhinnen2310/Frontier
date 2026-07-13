package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.domain.Position.ChunkPos;
import nl.frontier.influence.ChunkOwnershipCache;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarDamageGateway;
import nl.frontier.warfare.WarPolicyCache;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

final class CampaignStructuralListener implements Listener {
  private static final List<Material> PROTECTED =
      List.of(
          Material.BEDROCK,
          Material.BARRIER,
          Material.COMMAND_BLOCK,
          Material.CHAIN_COMMAND_BLOCK,
          Material.REPEATING_COMMAND_BLOCK,
          Material.STRUCTURE_BLOCK,
          Material.JIGSAW);
  private final SchedulerFacade schedulers;
  private final ChunkOwnershipCache ownership;
  private final WarPolicyCache wars;
  private final WarDamageGateway damage;
  private final Duration breachWindow;
  private final int baseCapacity;
  private final int maximumCapacity;

  CampaignStructuralListener(
      SchedulerFacade schedulers,
      ChunkOwnershipCache ownership,
      WarPolicyCache wars,
      WarDamageGateway damage,
      Duration breachWindow,
      int baseCapacity,
      int maximumCapacity) {
    this.schedulers = schedulers;
    this.ownership = ownership;
    this.wars = wars;
    this.damage = damage;
    this.breachWindow = breachWindow;
    this.baseCapacity = baseCapacity;
    this.maximumCapacity = maximumCapacity;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    var claim = claim(event.getBlock());
    if (claim == null || claim.owner() == null) return;
    UUID playerCity = wars.city(event.getPlayer().getUniqueId()).orElse(null);
    if (claim.owner().value().equals(playerCity)) return;
    event.setCancelled(true);
    authorize(
        event.getPlayer(),
        event.getBlock(),
        "PLAYER_BREAK",
        event.getPlayer().getInventory().getItemInMainHand().clone());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onExplosion(EntityExplodeEvent event) {
    Player attacker = source(event.getEntity());
    for (Block block : List.copyOf(event.blockList())) {
      var claim = claim(block);
      if (claim == null || claim.owner() == null) continue;
      event.blockList().remove(block);
      if (attacker != null) authorize(attacker, block, "EXPLOSION", new ItemStack(Material.AIR));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent event) {
    if (!sameOwner(event.getBlock(), event.getToBlock())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    for (Block block : event.getBlocks()) {
      if (!sameOwner(block, block.getRelative(event.getDirection()))) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    for (Block block : event.getBlocks()) {
      if (!sameOwner(block, block.getRelative(event.getDirection()))) {
        event.setCancelled(true);
        return;
      }
    }
  }

  private void authorize(Player attacker, Block block, String cause, ItemStack tool) {
    var claim = claim(block);
    if (claim == null || claim.owner() == null || PROTECTED.contains(block.getType())) {
      deny(attacker, "That structural block is protected.");
      return;
    }
    CampaignGateway.ActiveWar war =
        wars.hostileCampaign(attacker.getUniqueId(), claim.owner().value()).orElse(null);
    if (war == null) {
      deny(attacker, "No active campaign permits structural damage here.");
      return;
    }
    CampaignGateway.ObjectiveSnapshot objective =
        war.objectives().stream().filter(value -> contains(value, block)).findFirst().orElse(null);
    if (objective == null) {
      deny(attacker, "This block is outside the active campaign objective.");
      return;
    }
    String original = block.getBlockData().getAsString(true);
    BlockPos position =
        new BlockPos(
            new WorldId(block.getWorld().getUID()), block.getX(), block.getY(), block.getZ());
    int defenders = activeDefenders(claim.owner().value(), objective);
    WarDamageGateway.DamageAttempt attempt =
        new WarDamageGateway.DamageAttempt(
            war.campaign(),
            attacker.getUniqueId(),
            claim.owner().value(),
            block.getWorld().getUID(),
            block.getX(),
            block.getY(),
            block.getZ(),
            original,
            Material.AIR.getKey().asString(),
            cause,
            materialCost(block.getType()),
            defenders,
            Instant.now());
    schedulers
        .async(
            () -> damage.authorizeAndJournal(attempt, breachWindow, baseCapacity, maximumCapacity))
        .whenComplete(
            (decision, failure) -> {
              if (failure != null) {
                deny(attacker, rootMessage(failure));
                return;
              }
              if (!decision.allowed()) {
                deny(attacker, decision.reason());
                return;
              }
              schedulers.at(position, () -> applyAuthorizedBreak(attacker, block, tool, original));
            });
  }

  private void applyAuthorizedBreak(Player attacker, Block block, ItemStack tool, String expected) {
    if (!block.getBlockData().getAsString(true).equals(expected)) {
      deny(attacker, "The block changed before authorization completed.");
      return;
    }
    Collection<ItemStack> drops = block.getDrops(tool, attacker);
    if (block.getState() instanceof Container container) {
      for (ItemStack content : container.getInventory().getContents()) {
        if (content != null && !content.getType().isAir())
          block.getWorld().dropItemNaturally(block.getLocation(), content.clone());
      }
      container.getInventory().clear();
    }
    block.setType(Material.AIR, false);
    for (ItemStack drop : drops) block.getWorld().dropItemNaturally(block.getLocation(), drop);
    damageTool(attacker, tool);
  }

  private void damageTool(Player player, ItemStack expected) {
    if (expected.getType().isAir()) return;
    schedulers.forEntity(
        player.getUniqueId(),
        () -> {
          ItemStack current = player.getInventory().getItemInMainHand();
          if (current.getType() != expected.getType()) return;
          if (current.getItemMeta() instanceof Damageable damageable) {
            damageable.setDamage(damageable.getDamage() + 1);
            current.setItemMeta(damageable);
          }
        },
        () -> {});
  }

  private int activeDefenders(UUID city, CampaignGateway.ObjectiveSnapshot objective) {
    return (int)
        org.bukkit.Bukkit.getOnlinePlayers().stream()
            .filter(player -> wars.city(player.getUniqueId()).filter(city::equals).isPresent())
            .filter(player -> player.getWorld().getUID().equals(objective.world()))
            .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
            .filter(player -> contains(objective, player.getLocation().getBlock()))
            .count();
  }

  private ChunkOwnershipCache.Entry claim(Block block) {
    return ownership
        .get(
            new ChunkPos(
                new WorldId(block.getWorld().getUID()),
                block.getChunk().getX(),
                block.getChunk().getZ()))
        .orElse(null);
  }

  private boolean sameOwner(Block first, Block second) {
    ChunkOwnershipCache.Entry a = claim(first);
    ChunkOwnershipCache.Entry b = claim(second);
    UUID firstOwner = a == null || a.owner() == null ? null : a.owner().value();
    UUID secondOwner = b == null || b.owner() == null ? null : b.owner().value();
    return Objects.equals(firstOwner, secondOwner);
  }

  private static boolean contains(CampaignGateway.ObjectiveSnapshot value, Block block) {
    return value.world().equals(block.getWorld().getUID())
        && block.getX() >= value.minX()
        && block.getX() <= value.maxX()
        && block.getY() >= value.minY()
        && block.getY() <= value.maxY()
        && block.getZ() >= value.minZ()
        && block.getZ() <= value.maxZ();
  }

  private static int materialCost(Material material) {
    String name = material.name();
    if (name.contains("OBSIDIAN") || name.contains("NETHERITE")) return 20;
    if (name.contains("IRON") || name.contains("DEEPSLATE")) return 10;
    if (name.contains("STONE") || name.contains("BRICK") || name.contains("CONCRETE")) return 5;
    if (name.contains("WOOD") || name.contains("PLANK") || name.contains("LOG")) return 2;
    return 1;
  }

  private void deny(Player player, String message) {
    schedulers.forEntity(
        player.getUniqueId(),
        () -> player.sendActionBar(Component.text(message, NamedTextColor.RED)),
        () -> {});
  }

  private static Player source(Entity entity) {
    if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player)
      return player;
    return null;
  }

  private static String rootMessage(Throwable failure) {
    Throwable value = failure;
    while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null ? "Structural authorization failed." : value.getMessage();
  }
}
