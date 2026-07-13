package nl.frontier.city;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic persistence boundary. Implementations recheck actor roles inside each transaction. */
public interface SettlementGateway {
  default CitySnapshot create(
      UUID owner, String name, UUID world, int chunkX, int chunkZ, Instant now) {
    return create(UUID.randomUUID(), owner, name, world, chunkX, chunkZ, now);
  }

  CitySnapshot create(
      UUID city, UUID owner, String name, UUID world, int chunkX, int chunkZ, Instant now);

  Optional<CitySnapshot> findByPlayer(UUID player);

  Invitation invite(
      UUID city,
      UUID actor,
      UUID target,
      Set<GovernmentRole> allowedRoles,
      Instant expiresAt,
      Instant now);

  CitySnapshot acceptInvitation(UUID invitation, UUID player, Instant now);

  void changeRole(
      UUID city,
      UUID actor,
      UUID target,
      GovernmentRole role,
      Set<GovernmentRole> allowedRoles,
      Instant now);

  BuildingSnapshot registerBuilding(
      UUID city,
      UUID actor,
      Building.Category category,
      Bounds bounds,
      Set<GovernmentRole> allowedRoles,
      Instant now);

  ClaimSnapshot claim(
      UUID city,
      UUID actor,
      UUID world,
      int chunkX,
      int chunkZ,
      int maximumClaims,
      Set<GovernmentRole> allowedRoles,
      Instant now);

  CitySnapshot upgrade(
      UUID city,
      UUID actor,
      SettlementLevel expected,
      SettlementLevel target,
      int requiredPopulation,
      int requiredProsperity,
      int requiredCivilization,
      Building.Category requiredBuilding,
      long costMinor,
      Set<GovernmentRole> allowedRoles,
      UUID idempotencyKey,
      Instant now);

  void setPolicy(
      UUID city,
      UUID actor,
      String key,
      String jsonValue,
      Instant cooldownUntil,
      Set<GovernmentRole> allowedRoles,
      Instant now);

  long treasuryBalance(UUID city);

  record CitySnapshot(
      UUID id,
      String name,
      UUID owner,
      SettlementLevel level,
      int population,
      int prosperity,
      int civilization,
      long version) {}

  record Invitation(UUID id, UUID city, UUID player, String status, Instant expiresAt) {}

  record Bounds(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
      if (minX > maxX || minY > maxY || minZ > maxZ)
        throw new IllegalArgumentException("invalid bounds");
    }

    public String json() {
      return "{\"world\":\""
          + world
          + "\",\"minX\":"
          + minX
          + ",\"minY\":"
          + minY
          + ",\"minZ\":"
          + minZ
          + ",\"maxX\":"
          + maxX
          + ",\"maxY\":"
          + maxY
          + ",\"maxZ\":"
          + maxZ
          + "}";
    }
  }

  record BuildingSnapshot(UUID id, Building.Category category, int integrity, String status) {}

  record ClaimSnapshot(UUID city, UUID world, int chunkX, int chunkZ, String state) {}
}
