package nl.frontier.bootstrap;

import java.time.Instant;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.city.SettlementLifecycleService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class SettlementActivityListener implements Listener {
  private final SchedulerFacade schedulers;
  private final SettlementLifecycleService lifecycle;
  private final SettlementFoundingCoordinator founding;

  SettlementActivityListener(
      SchedulerFacade schedulers,
      SettlementLifecycleService lifecycle,
      SettlementFoundingCoordinator founding) {
    this.schedulers = schedulers;
    this.lifecycle = lifecycle;
    this.founding = founding;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    founding.resume(event.getPlayer());
    var player = event.getPlayer().getUniqueId();
    schedulers.async(
        () -> {
          lifecycle.touch(player, Instant.now());
          return null;
        });
  }
}
