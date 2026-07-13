package nl.frontier.warfare;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CampaignGateway {
  CampaignSnapshot declare(
      UUID attacker,
      UUID actor,
      UUID defender,
      WarCampaign.Type type,
      ObjectiveSpec objective,
      long declarationCostMinor,
      Duration preparation,
      Duration maximumDuration,
      UUID idempotency,
      Instant now);

  CampaignSnapshot ceasefire(UUID campaign, UUID actor, Instant now);

  CampaignSnapshot resume(UUID campaign, UUID actor, Instant now);

  CampaignSnapshot resolve(UUID campaign, UUID actor, String reason, Instant now);

  CampaignSnapshot end(UUID campaign, UUID actor, String reason, Instant now);

  List<CampaignSnapshot> campaigns(UUID city);

  AdvanceReport advanceDue(int maximum, Instant now);

  WarPolicySnapshot policySnapshot(Instant now);

  void recordCombat(UUID player, Instant now);

  ObjectiveTickReport tickObjectives(List<Presence> players, long progressUnits, Instant now);

  record ObjectiveSpec(
      String type,
      UUID world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      long target,
      int minimumParticipants) {
    public ObjectiveSpec {
      if (minX > maxX || minY > maxY || minZ > maxZ || target <= 0 || minimumParticipants < 0)
        throw new IllegalArgumentException("invalid campaign objective");
    }
  }

  record ObjectiveSnapshot(
      UUID id,
      String type,
      String state,
      UUID world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      long progress,
      long target,
      int minimumParticipants) {}

  record CampaignSnapshot(
      UUID id,
      UUID attacker,
      UUID defender,
      WarCampaign.Type type,
      WarCampaign.Phase phase,
      Instant declaredAt,
      Instant scheduledActiveAt,
      Instant activeAt,
      Instant maximumEndsAt,
      Instant endedAt,
      long attackerScore,
      long defenderScore,
      List<ObjectiveSnapshot> objectives,
      long version) {}

  record ActiveWar(
      UUID campaign, UUID attacker, UUID defender, List<ObjectiveSnapshot> objectives) {}

  record Membership(UUID player, UUID city) {}

  record Presence(UUID player, UUID world, int x, int y, int z, boolean eligible) {}

  record WarPolicySnapshot(List<ActiveWar> wars, List<Membership> memberships, Instant loadedAt) {}

  record AdvanceReport(int activated, int resolving) {}

  record ObjectiveTickReport(int visited, int contested, int completed) {}
}
