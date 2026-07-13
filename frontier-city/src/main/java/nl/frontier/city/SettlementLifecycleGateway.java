package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementLifecycleGateway {
  void validateCore(CoreLocation core);

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

  record LifecycleSnapshot(
      UUID city, UUID owner, String status, Instant abandonedAt, Instant ruinsUntil) {}

  record MergeProposal(UUID id, UUID source, UUID target, String status, Instant expiresAt) {}

  record RecoveryReport(int abandoned, int successions, int reservationsRefunded) {}

  record HistoryEntry(String event, UUID actor, String payload, Instant occurredAt) {}
}
