package nl.frontier.city;

import java.util.Objects;
import java.util.UUID;

public final class ClaimProtectionService {
  private final ClaimProtectionCache cache;
  private final HostilityView hostility;
  private final TreatyView treaties;
  private final IncidentView incidents;
  private final TerritoryActionPolicy policy;

  public ClaimProtectionService(ClaimProtectionCache cache, HostilityView hostility) {
    this(
        cache,
        hostility,
        (player, city) -> TerritoryActionPolicy.TreatyContext.NONE,
        (player, city) -> TerritoryActionPolicy.IncidentContext.NONE);
  }

  public ClaimProtectionService(
      ClaimProtectionCache cache,
      HostilityView hostility,
      TreatyView treaties,
      IncidentView incidents) {
    this.cache = Objects.requireNonNull(cache);
    this.hostility = Objects.requireNonNull(hostility);
    this.treaties = Objects.requireNonNull(treaties);
    this.incidents = Objects.requireNonNull(incidents);
    this.policy = new TerritoryActionPolicy();
  }

  public Decision authorize(Request request) {
    UUID city = cache.city(request.world(), request.chunkX(), request.chunkZ()).orElse(null);
    UUID sourceCity =
        request.sourceWorld() == null
            ? null
            : cache
                .city(request.sourceWorld(), request.sourceChunkX(), request.sourceChunkZ())
                .orElse(null);
    return policy.decide(
        new TerritoryActionPolicy.Context(
            request.actor(),
            request.action(),
            city,
            sourceCity,
            city != null && cache.owner(city, request.actor()),
            city == null ? null : cache.role(city, request.actor()).orElse(null),
            city == null
                ? null
                : cache.override(city, request.actor(), request.action()).orElse(null),
            city != null && hostility.activeCampaign(request.actor(), city),
            city == null
                ? TerritoryActionPolicy.TreatyContext.NONE
                : treaties.context(request.actor(), city),
            city == null
                ? TerritoryActionPolicy.IncidentContext.NONE
                : incidents.context(request.actor(), city),
            request.bypass()));
  }

  public Decision authorizePropagation(PropagationRequest request) {
    UUID source =
        cache
            .city(request.sourceWorld(), request.sourceChunkX(), request.sourceChunkZ())
            .orElse(null);
    UUID target =
        cache
            .city(request.targetWorld(), request.targetChunkX(), request.targetChunkZ())
            .orElse(null);
    return policy.decidePropagation(
        new TerritoryActionPolicy.PropagationContext(
            request.action(),
            source,
            target,
            TerritoryActionPolicy.TreatyContext.NONE,
            TerritoryActionPolicy.IncidentContext.NONE));
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
    EXPLOSION,
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
    SAME_TERRITORY,
    CROSS_BOUNDARY,
    DENIED
  }

  public record Request(
      UUID world,
      int chunkX,
      int chunkZ,
      UUID sourceWorld,
      int sourceChunkX,
      int sourceChunkZ,
      UUID actor,
      Action action,
      boolean bypass) {
    public Request(UUID world, int chunkX, int chunkZ, UUID actor, Action action, boolean bypass) {
      this(world, chunkX, chunkZ, null, 0, 0, actor, action, bypass);
    }
  }

  public record PropagationRequest(
      UUID sourceWorld,
      int sourceChunkX,
      int sourceChunkZ,
      UUID targetWorld,
      int targetChunkX,
      int targetChunkZ,
      Action action) {}

  public record Decision(boolean allowed, UUID city, Reason reason) {}

  @FunctionalInterface
  public interface HostilityView {
    boolean activeCampaign(UUID player, UUID defendingCity);
  }

  @FunctionalInterface
  public interface TreatyView {
    TerritoryActionPolicy.TreatyContext context(UUID player, UUID defendingCity);
  }

  @FunctionalInterface
  public interface IncidentView {
    TerritoryActionPolicy.IncidentContext context(UUID player, UUID defendingCity);
  }
}
