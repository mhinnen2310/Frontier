package nl.frontier.bootstrap;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.CaravanService;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class CaravanCombatListener implements Listener {
  private final SchedulerFacade schedulers;
  private final CaravanService caravans;
  private final NamespacedKey shipmentKey;

  CaravanCombatListener(JavaPlugin plugin, SchedulerFacade schedulers, CaravanService caravans) {
    this.schedulers = schedulers;
    this.caravans = caravans;
    this.shipmentKey = new NamespacedKey(plugin, CaravanPresentationSupervisor.SHIPMENT_KEY);
  }

  @EventHandler(ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent event) {
    String raw =
        event.getEntity().getPersistentDataContainer().get(shipmentKey, PersistentDataType.STRING);
    if (raw == null) return;
    event.setCancelled(true);
    UUID shipment = UUID.fromString(raw);
    UUID attacker =
        event.getDamager() instanceof Player player
            ? player.getUniqueId()
            : event.getDamager().getUniqueId();
    int damage = Math.max(1, Math.min(100, (int) Math.ceil(event.getFinalDamage())));
    schedulers.async(() -> caravans.damage(shipment, attacker, damage, Instant.now()));
  }
}
