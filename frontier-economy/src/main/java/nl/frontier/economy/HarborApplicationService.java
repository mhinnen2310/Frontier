package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class HarborApplicationService {
  private final HarborGateway gateway;

  public HarborApplicationService(HarborGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public HarborGateway.TutorialSnapshot onboard(UUID player, Instant now) {
    return gateway.onboard(player, now);
  }

  public List<HarborGateway.StarterJob> jobs(UUID player, Instant now) {
    gateway.onboard(player, now);
    return gateway.jobs(player, now);
  }

  public HarborGateway.JobReceipt complete(UUID player, UUID job, Instant now) {
    return gateway.completeJob(player, job, now);
  }

  public HarborGateway.JobReceipt completeFirst(UUID player, Instant now) {
    HarborGateway.StarterJob job =
        jobs(player, now).stream()
            .filter(value -> value.status().equals("POSTED"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no open starter job today"));
    return gateway.completeJob(player, job.id(), now);
  }

  public HarborGateway.HarborSnapshot status(Instant now) {
    return gateway.refresh(now);
  }
}
