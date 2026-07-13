package nl.frontier.city;

import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

public final class ClaimProtectionService {
  private static final EnumSet<Action> COMMON_MEMBER_ACTIONS =
      EnumSet.of(
          Action.BUILD,
          Action.BREAK,
          Action.CONTAINER,
          Action.INTERACT,
          Action.ENTITY,
          Action.BUCKET,
          Action.TRAMPLE,
          Action.VEHICLE);
  private static final EnumSet<Action> TRUSTED_MEMBER_ACTIONS =
      EnumSet.of(Action.AUTOMATION, Action.HANGING, Action.FIRE, Action.REDSTONE);
  private final ClaimProtectionCache cache;
  private final HostilityView hostility;

  public ClaimProtectionService(ClaimProtectionCache cache, HostilityView hostility) {
    this.cache = Objects.requireNonNull(cache);
    this.hostility = Objects.requireNonNull(hostility);
  }

  public Decision authorize(Request request) {
    UUID city = cache.city(request.world(), request.chunkX(), request.chunkZ()).orElse(null);
    if (city == null) return new Decision(true, null, Reason.WILDERNESS);
    if (request.bypass()) return new Decision(true, city, Reason.BYPASS);
    if (cache.owner(city, request.actor())) return new Decision(true, city, Reason.OWNER);
    var override = cache.override(city, request.actor(), request.action());
    if (override.isPresent())
      return new Decision(
          override.orElseThrow(), city, override.orElseThrow() ? Reason.OVERRIDE : Reason.DENIED);
    GovernmentRole role = cache.role(city, request.actor()).orElse(null);
    if (role != null) {
      if (role == GovernmentRole.RECRUIT)
        return new Decision(
            request.action() == Action.INTERACT,
            city,
            request.action() == Action.INTERACT ? Reason.MEMBER : Reason.DENIED);
      if (COMMON_MEMBER_ACTIONS.contains(request.action()))
        return new Decision(true, city, Reason.MEMBER);
      boolean trusted =
          role == GovernmentRole.MAYOR
              || role == GovernmentRole.ARCHITECT
              || role == GovernmentRole.BUILDER_MASTER;
      if (trusted && TRUSTED_MEMBER_ACTIONS.contains(request.action()))
        return new Decision(true, city, Reason.ROLE);
      return new Decision(false, city, Reason.DENIED);
    }
    if (request.action() == Action.BREAK && hostility.activeCampaign(request.actor(), city))
      return new Decision(true, city, Reason.CAMPAIGN);
    return new Decision(false, city, Reason.DENIED);
  }

  public boolean sameOwner(
      UUID firstWorld,
      int firstChunkX,
      int firstChunkZ,
      UUID secondWorld,
      int secondChunkX,
      int secondChunkZ) {
    UUID first = cache.city(firstWorld, firstChunkX, firstChunkZ).orElse(null);
    UUID second = cache.city(secondWorld, secondChunkX, secondChunkZ).orElse(null);
    return Objects.equals(first, second);
  }

  public enum Action {
    BUILD,
    BREAK,
    CONTAINER,
    AUTOMATION,
    INTERACT,
    ENTITY,
    HANGING,
    BUCKET,
    FIRE,
    TRAMPLE,
    VEHICLE,
    REDSTONE
  }

  public enum Reason {
    WILDERNESS,
    BYPASS,
    OWNER,
    MEMBER,
    ROLE,
    OVERRIDE,
    CAMPAIGN,
    DENIED
  }

  public record Request(
      UUID world, int chunkX, int chunkZ, UUID actor, Action action, boolean bypass) {}

  public record Decision(boolean allowed, UUID city, Reason reason) {}

  @FunctionalInterface
  public interface HostilityView {
    boolean activeCampaign(UUID player, UUID defendingCity);
  }
}
