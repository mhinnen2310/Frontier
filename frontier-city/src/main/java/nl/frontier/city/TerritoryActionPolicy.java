package nl.frontier.city;

import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

/** Pure, auditable claim policy. Bukkit listeners only translate events into this context. */
public final class TerritoryActionPolicy {
  private static final EnumSet<ClaimProtectionService.Action> COMMON_MEMBER_ACTIONS =
      EnumSet.of(
          ClaimProtectionService.Action.BUILD,
          ClaimProtectionService.Action.BREAK,
          ClaimProtectionService.Action.CONTAINER,
          ClaimProtectionService.Action.INTERACT,
          ClaimProtectionService.Action.ENTITY,
          ClaimProtectionService.Action.BUCKET,
          ClaimProtectionService.Action.TRAMPLE,
          ClaimProtectionService.Action.VEHICLE);
  private static final EnumSet<ClaimProtectionService.Action> TRUSTED_MEMBER_ACTIONS =
      EnumSet.of(
          ClaimProtectionService.Action.AUTOMATION,
          ClaimProtectionService.Action.HANGING,
          ClaimProtectionService.Action.FIRE,
          ClaimProtectionService.Action.EXPLOSION,
          ClaimProtectionService.Action.REDSTONE);

  public ClaimProtectionService.Decision decide(Context context) {
    Objects.requireNonNull(context);
    if (context.targetCity() == null)
      return decision(true, null, ClaimProtectionService.Reason.WILDERNESS);
    if (context.bypass())
      return decision(true, context.targetCity(), ClaimProtectionService.Reason.BYPASS);
    if (context.owner())
      return decision(true, context.targetCity(), ClaimProtectionService.Reason.OWNER);
    if (context.override() != null)
      return decision(
          context.override(),
          context.targetCity(),
          context.override()
              ? ClaimProtectionService.Reason.OVERRIDE
              : ClaimProtectionService.Reason.DENIED);
    if (context.role() != null) {
      if (context.role() == GovernmentRole.RECRUIT)
        return decision(
            context.action() == ClaimProtectionService.Action.INTERACT,
            context.targetCity(),
            context.action() == ClaimProtectionService.Action.INTERACT
                ? ClaimProtectionService.Reason.MEMBER
                : ClaimProtectionService.Reason.DENIED);
      if (COMMON_MEMBER_ACTIONS.contains(context.action()))
        return decision(true, context.targetCity(), ClaimProtectionService.Reason.MEMBER);
      boolean trusted =
          context.role() == GovernmentRole.MAYOR
              || context.role() == GovernmentRole.ARCHITECT
              || context.role() == GovernmentRole.BUILDER_MASTER;
      if (trusted && TRUSTED_MEMBER_ACTIONS.contains(context.action()))
        return decision(true, context.targetCity(), ClaimProtectionService.Reason.ROLE);
      return decision(false, context.targetCity(), ClaimProtectionService.Reason.DENIED);
    }
    // Campaigns only unlock the structural damage gateway. Treaties and recorded incidents are
    // context for audits and future diplomacy/crime rules, never implicit property permissions.
    if ((context.action() == ClaimProtectionService.Action.BREAK
            || context.action() == ClaimProtectionService.Action.EXPLOSION)
        && context.activeCampaign())
      return decision(true, context.targetCity(), ClaimProtectionService.Reason.CAMPAIGN);
    return decision(false, context.targetCity(), ClaimProtectionService.Reason.DENIED);
  }

  public ClaimProtectionService.Decision decidePropagation(PropagationContext context) {
    Objects.requireNonNull(context);
    boolean allowed = Objects.equals(context.sourceCity(), context.targetCity());
    return decision(
        allowed,
        context.targetCity(),
        allowed
            ? ClaimProtectionService.Reason.SAME_TERRITORY
            : ClaimProtectionService.Reason.CROSS_BOUNDARY);
  }

  private static ClaimProtectionService.Decision decision(
      boolean allowed, UUID city, ClaimProtectionService.Reason reason) {
    return new ClaimProtectionService.Decision(allowed, city, reason);
  }

  public record Context(
      UUID actor,
      ClaimProtectionService.Action action,
      UUID targetCity,
      UUID sourceCity,
      boolean owner,
      GovernmentRole role,
      Boolean override,
      boolean activeCampaign,
      TreatyContext treaty,
      IncidentContext incident,
      boolean bypass) {
    public Context {
      Objects.requireNonNull(actor);
      Objects.requireNonNull(action);
      Objects.requireNonNull(treaty);
      Objects.requireNonNull(incident);
    }
  }

  public record PropagationContext(
      ClaimProtectionService.Action action,
      UUID sourceCity,
      UUID targetCity,
      TreatyContext treaty,
      IncidentContext incident) {
    public PropagationContext {
      Objects.requireNonNull(action);
      Objects.requireNonNull(treaty);
      Objects.requireNonNull(incident);
    }
  }

  public enum TreatyContext {
    NONE,
    ALLIED,
    ACCESS,
    HOSTILE
  }

  public enum IncidentContext {
    NONE,
    OPEN,
    RESOLVED
  }
}
