package nl.frontier.world;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CivilizationGateway {
  KingdomSnapshot createKingdom(UUID city, UUID actor, String name, Instant now);

  Invitation inviteCity(UUID kingdom, UUID actor, UUID city, Instant expiresAt, Instant now);

  KingdomSnapshot acceptInvitation(UUID invitation, UUID city, UUID actor, Instant now);

  TreatySnapshot proposeTreaty(
      UUID kingdom,
      UUID actor,
      UUID counterpart,
      String type,
      String termsJson,
      Instant expiresAt,
      Instant now);

  TreatySnapshot acceptTreaty(UUID treaty, UUID actor, Instant now);

  ResearchSnapshot startResearch(
      UUID kingdom, UUID actor, String branch, String project, long requiredPoints, Instant now);

  WonderSnapshot startWonder(
      UUID kingdom,
      UUID actor,
      String wonderKey,
      String commodity,
      long requiredUnits,
      Instant now);

  WonderSnapshot contributeWonder(
      UUID wonder, UUID city, UUID actor, long units, UUID idempotency, Instant now);

  MegaProjectSnapshot startMegaProject(
      UUID kingdom,
      UUID actor,
      String projectKey,
      String commodity,
      long requiredUnits,
      Instant now);

  MegaProjectSnapshot contributeMegaProject(
      UUID project, UUID city, UUID actor, long units, UUID idempotency, Instant now);

  List<KingdomSnapshot> kingdoms();

  List<TreatySnapshot> treaties(UUID kingdom);

  List<ResearchSnapshot> research(UUID kingdom);

  List<WonderSnapshot> wonders();

  List<MegaProjectSnapshot> megaProjects();

  List<GlobalObjectiveSnapshot> globalObjectives();

  CycleReport cycle(int maximumKingdoms, Instant now);

  record KingdomSnapshot(
      UUID id,
      String name,
      CivilizationProgression.Era era,
      long prestige,
      UUID leader,
      List<UUID> cities,
      long researchPoints,
      long version) {}

  record Invitation(UUID id, UUID kingdom, UUID city, String status, Instant expiresAt) {}

  record TreatySnapshot(
      UUID id,
      UUID first,
      UUID second,
      String type,
      String status,
      Instant expiresAt,
      long version) {}

  record ResearchSnapshot(
      UUID id,
      UUID kingdom,
      String branch,
      String project,
      long progress,
      long required,
      String status) {}

  record WonderSnapshot(
      UUID id,
      UUID kingdom,
      String key,
      String commodity,
      long contributed,
      long required,
      String status) {}

  record MegaProjectSnapshot(
      UUID id,
      UUID kingdom,
      String key,
      String commodity,
      long contributed,
      long required,
      String status) {}

  record GlobalObjectiveSnapshot(UUID id, String key, long progress, long target, String status) {}

  record CycleReport(int kingdoms, int researchCompleted, int erasAdvanced, long prestigeGranted) {}
}
