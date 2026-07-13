package nl.frontier.warfare;

import java.time.Instant;
import java.util.UUID;

public interface CampaignOutcomeGateway {
  CampaignResult apply(UUID campaign, UUID actor, Outcome outcome, long amountMinor, Instant now);

  TributeCycle cycleTributes(int limit, Instant now);

  enum Outcome {
    OCCUPATION,
    LIBERATION,
    CONQUEST,
    ANNEXATION,
    TERRITORY_CONCESSION,
    REPARATIONS,
    TRIBUTE,
    INDEPENDENCE,
    CIVIL_WAR,
    KINGDOM_INTERVENTION
  }

  record CampaignResult(
      UUID id,
      UUID campaign,
      Outcome outcome,
      UUID winner,
      UUID loser,
      int claims,
      int buildings,
      int roads,
      int workers,
      long storageUnits,
      long amountMinor,
      Instant appliedAt) {}

  record TributeCycle(int paid, int overdue) {}
}
