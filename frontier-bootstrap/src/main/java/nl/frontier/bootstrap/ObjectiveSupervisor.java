package nl.frontier.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.warfare.CampaignGateway;
import org.bukkit.GameMode;
import org.bukkit.Server;

final class ObjectiveSupervisor {
  private final Server server;
  private final SchedulerFacade schedulers;
  private final CampaignGateway campaigns;
  private final Duration interval;
  private final Logger logger;
  private final AtomicBoolean active = new AtomicBoolean();

  ObjectiveSupervisor(
      Server server,
      SchedulerFacade schedulers,
      CampaignGateway campaigns,
      Duration interval,
      Logger logger) {
    this.server = server;
    this.schedulers = schedulers;
    this.campaigns = campaigns;
    this.interval = interval;
    this.logger = logger;
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
          List<CampaignGateway.Presence> presence = new ArrayList<>();
          server
              .getOnlinePlayers()
              .forEach(
                  player -> {
                    var location = player.getLocation();
                    boolean eligible =
                        !player.isDead()
                            && player.getGameMode() != GameMode.SPECTATOR
                            && player.getGameMode() != GameMode.CREATIVE;
                    presence.add(
                        new CampaignGateway.Presence(
                            player.getUniqueId(),
                            player.getWorld().getUID(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ(),
                            eligible));
                  });
          schedulers
              .async(
                  () ->
                      campaigns.tickObjectives(
                          List.copyOf(presence), Math.max(1, interval.toSeconds()), Instant.now()))
              .whenComplete(
                  (report, failure) -> {
                    if (failure != null)
                      logger.log(Level.WARNING, "Campaign objective cycle failed", failure);
                    else if (report.completed() > 0)
                      logger.info("Completed " + report.completed() + " campaign objective(s).");
                    schedulers.later(interval, this::cycle);
                  });
        });
  }
}
