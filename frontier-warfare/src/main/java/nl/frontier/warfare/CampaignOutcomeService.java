package nl.frontier.warfare;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class CampaignOutcomeService {
  private final CampaignOutcomeGateway gateway;

  public CampaignOutcomeService(CampaignOutcomeGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public CampaignOutcomeGateway.CampaignResult apply(
      UUID campaign,
      UUID actor,
      CampaignOutcomeGateway.Outcome outcome,
      long amountMinor,
      Instant now) {
    if (amountMinor < 0) throw new DomainException("campaign outcome amount cannot be negative");
    if (java.util.Set.of(
                CampaignOutcomeGateway.Outcome.REPARATIONS, CampaignOutcomeGateway.Outcome.TRIBUTE)
            .contains(outcome)
        && amountMinor <= 0)
      throw new DomainException("reparations and tribute require a positive amount");
    return gateway.apply(campaign, actor, outcome, amountMinor, now);
  }

  public CampaignOutcomeGateway.TributeCycle cycle(int limit, Instant now) {
    return gateway.cycleTributes(Math.max(1, Math.min(500, limit)), now);
  }
}
