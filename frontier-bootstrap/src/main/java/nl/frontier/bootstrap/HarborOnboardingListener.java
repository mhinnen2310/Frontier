package nl.frontier.bootstrap;

import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.economy.HarborApplicationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class HarborOnboardingListener implements Listener {
  private final SchedulerFacade schedulers;
  private final HarborApplicationService harbor;

  HarborOnboardingListener(SchedulerFacade schedulers, HarborApplicationService harbor) {
    this.schedulers = schedulers;
    this.harbor = harbor;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    var player = event.getPlayer();
    schedulers
        .async(() -> harbor.onboard(player.getUniqueId(), Instant.now()))
        .thenAccept(
            tutorial -> {
              if (!tutorial.firstVisit()) return;
              schedulers.global(
                  () ->
                      player.sendMessage(
                          Component.text(
                              "Welcome to Frontier Harbor. Use /frontier harbor tutorial to begin without admin help.",
                              NamedTextColor.GOLD)));
            });
  }
}
