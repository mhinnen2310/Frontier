package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarPolicyCache;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

final class CampaignCombatListener implements Listener {
  private final WarPolicyCache cache;
  private final CampaignGateway campaigns;
  private final SchedulerFacade schedulers;

  CampaignCombatListener(
      WarPolicyCache cache, CampaignGateway campaigns, SchedulerFacade schedulers) {
    this.cache = cache;
    this.campaigns = campaigns;
    this.schedulers = schedulers;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPlayerDamage(EntityDamageByEntityEvent event) {
    if (!(event.getEntity() instanceof Player victim)) return;
    Player attacker = attacker(event);
    if (attacker == null || attacker.equals(victim)) return;
    if (cache.friendly(attacker.getUniqueId(), victim.getUniqueId())) {
      event.setCancelled(true);
      return;
    }
    if (!cache.hostile(attacker.getUniqueId(), victim.getUniqueId())) return;
    record(attacker.getUniqueId());
    record(victim.getUniqueId());
  }

  private void record(UUID player) {
    schedulers.async(
        () -> {
          campaigns.recordCombat(player, Instant.now());
          return null;
        });
  }

  private static Player attacker(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player player) return player;
    if (event.getDamager() instanceof Projectile projectile
        && projectile.getShooter() instanceof Player player) return player;
    return null;
  }
}
