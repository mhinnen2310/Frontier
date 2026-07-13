package nl.frontier.city;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

/** Player-facing settlement commands with rules independent of Paper and SQL. */
public final class SettlementApplicationService {
  private static final EnumSet<GovernmentRole> GOVERNMENT = EnumSet.of(GovernmentRole.MAYOR);
  private static final EnumSet<GovernmentRole> INVITERS = EnumSet.of(GovernmentRole.MAYOR);
  private static final EnumSet<GovernmentRole> ARCHITECTS =
      EnumSet.of(GovernmentRole.MAYOR, GovernmentRole.ARCHITECT);
  private final SettlementGateway gateway;

  public SettlementApplicationService(SettlementGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public SettlementGateway.CitySnapshot create(
      UUID owner, String name, UUID world, int chunkX, int chunkZ, Instant now) {
    String clean = Objects.requireNonNull(name).strip();
    if (clean.length() < 3 || clean.length() > 32)
      throw new DomainException("settlement name must be 3-32 characters");
    return gateway.create(owner, clean, world, chunkX, chunkZ, now);
  }

  public SettlementGateway.Invitation invite(UUID city, UUID actor, UUID target, Instant now) {
    if (actor.equals(target)) throw new DomainException("you cannot invite yourself");
    return gateway.invite(city, actor, target, INVITERS, now.plus(Duration.ofHours(48)), now);
  }

  public SettlementGateway.CitySnapshot accept(UUID invitation, UUID player, Instant now) {
    return gateway.acceptInvitation(invitation, player, now);
  }

  public void role(UUID city, UUID actor, UUID target, GovernmentRole role, Instant now) {
    if (role == GovernmentRole.MAYOR)
      throw new DomainException("use ownership transfer for the mayor role");
    gateway.changeRole(city, actor, target, role, GOVERNMENT, now);
  }

  public SettlementGateway.BuildingSnapshot registerBuilding(
      UUID city,
      UUID actor,
      Building.Category category,
      SettlementGateway.Bounds bounds,
      Instant now) {
    return gateway.registerBuilding(city, actor, category, bounds, ARCHITECTS, now);
  }

  public SettlementGateway.ClaimSnapshot claim(
      UUID city,
      UUID actor,
      UUID world,
      int chunkX,
      int chunkZ,
      SettlementLevel level,
      Instant now) {
    return gateway.claim(city, actor, world, chunkX, chunkZ, level.claims(), ARCHITECTS, now);
  }

  public SettlementGateway.CitySnapshot upgrade(
      UUID city, UUID actor, UUID idempotencyKey, Instant now) {
    SettlementGateway.CitySnapshot snapshot =
        gateway
            .findByPlayer(actor)
            .filter(value -> value.id().equals(city))
            .orElseThrow(() -> new DomainException("not a settlement member"));
    SettlementLevel target = snapshot.level().next();
    Requirement requirement = requirement(target);
    return gateway.upgrade(
        city,
        actor,
        snapshot.level(),
        target,
        requirement.population(),
        50,
        target.level() * 10,
        requirement.building(),
        requirement.costMinor(),
        GOVERNMENT,
        idempotencyKey,
        now);
  }

  public void taxPolicy(UUID city, UUID actor, String profile, Instant now) {
    String normalized = profile.toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("LOW", "STANDARD", "HIGH").contains(normalized)) {
      throw new DomainException("tax profile must be LOW, STANDARD, or HIGH");
    }
    gateway.setPolicy(
        city,
        actor,
        "TAX_PROFILE",
        "\"" + normalized + "\"",
        now.plus(Duration.ofHours(24)),
        EnumSet.of(GovernmentRole.MAYOR, GovernmentRole.TREASURER),
        now);
  }

  public long treasury(UUID city) {
    return gateway.treasuryBalance(city);
  }

  public java.util.Optional<SettlementGateway.CitySnapshot> city(UUID player) {
    return gateway.findByPlayer(player);
  }

  private static Requirement requirement(SettlementLevel target) {
    return switch (target) {
      case CAMP -> throw new IllegalStateException("camp is the initial level");
      case OUTPOST -> new Requirement(10, null, 10_000);
      case SETTLEMENT -> new Requirement(25, Building.Category.ECONOMIC, 50_000);
      case TOWN -> new Requirement(60, Building.Category.GOVERNMENT, 150_000);
      case CITY -> new Requirement(120, Building.Category.MILITARY, 500_000);
      case CAPITAL -> new Requirement(250, Building.Category.CULTURE, 2_000_000);
    };
  }

  private record Requirement(int population, Building.Category building, long costMinor) {}
}
