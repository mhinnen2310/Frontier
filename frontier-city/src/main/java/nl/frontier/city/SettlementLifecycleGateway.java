package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementLifecycleGateway {
  void validateCore(CoreLocation core);

  FoundingExpedition createExpedition(
      UUID leader, String name, String charter, Instant now, Instant expiresAt);

  FoundingExpedition inviteFounder(
      UUID expedition, UUID actor, UUID player, Instant now, Instant expiresAt);

  FoundingExpedition acceptFounder(UUID expedition, UUID player, Instant now);

  FoundingExpedition selectCore(
      UUID expedition,
      UUID actor,
      CoreLocation core,
      int minimumDistance,
      int harborExclusionRadius,
      Instant now);

  FoundingReservation reserveExpedition(
      UUID expedition,
      UUID actor,
      long feeMinor,
      int minimumFounders,
      Instant now,
      Instant expiresAt);

  boolean claimMaterials(UUID expedition, UUID actor, Instant now);

  void confirmMaterials(UUID expedition, UUID actor, Instant now);

  void releaseMaterials(UUID expedition, UUID actor, Instant now);

  void confirmCorePlacement(UUID expedition, UUID actor, Instant now);

  void reviewExpedition(UUID expedition, UUID actor, String reason, Instant now);

  void completeExpedition(UUID expedition, UUID city, UUID actor, Instant now);

  FoundingExpedition cancelExpedition(UUID expedition, UUID actor, Instant now);

  Optional<FoundingExpedition> activeExpedition(UUID player, Instant now);

  List<FoundingExpedition> pendingExpeditions(int limit);

  FoundingReservation reserveFounding(UUID player, long feeMinor, Instant now, Instant expiresAt);

  void completeFounding(
      UUID reservation,
      UUID city,
      UUID founder,
      CoreLocation core,
      String charter,
      int minimumFounders,
      Instant now);

  void cancelFounding(UUID reservation, UUID player, Instant now);

  void touch(UUID player, Instant now);

  LifecycleSnapshot transfer(UUID city, UUID actor, UUID successor, Instant now);

  LifecycleSnapshot succeed(UUID city, UUID actor, Instant now);

  LifecycleSnapshot abandon(UUID city, UUID actor, Instant now);

  LifecycleSnapshot disband(UUID city, UUID actor, Instant now);

  LifecycleSnapshot recoverRuins(UUID city, UUID actor, Instant now);

  MergeProposal proposeMerge(UUID source, UUID actor, UUID target, Instant now, Instant expiresAt);

  LifecycleSnapshot acceptMerge(UUID proposal, UUID actor, Instant now);

  RecoveryReport recoverInactive(Instant inactiveBefore, Instant now, int limit);

  List<HistoryEntry> history(UUID city, UUID actor, int limit);

  record CoreLocation(UUID world, int x, int y, int z) {}

  record FoundingReservation(
      UUID id, UUID player, long feeMinor, String status, Instant expiresAt) {}

  record FoundingExpedition(
      UUID id,
      UUID city,
      UUID leader,
      String name,
      String charter,
      String status,
      CoreLocation core,
      int acceptedFounders,
      Instant expiresAt,
      UUID reservation) {}

  record LifecycleSnapshot(
      UUID city, UUID owner, String status, Instant abandonedAt, Instant ruinsUntil) {}

  record MergeProposal(UUID id, UUID source, UUID target, String status, Instant expiresAt) {}

  record RecoveryReport(int abandoned, int successions, int reservationsRefunded) {}

  record HistoryEntry(String event, UUID actor, String payload, Instant occurredAt) {}
}
