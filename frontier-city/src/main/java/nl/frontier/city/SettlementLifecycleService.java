package nl.frontier.city;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class SettlementLifecycleService {
  private final SettlementLifecycleGateway gateway;
  private final FoundingPolicy foundingPolicy;
  private final SettlementGovernancePolicy governancePolicy;

  public SettlementLifecycleService(SettlementLifecycleGateway gateway) {
    this(gateway, FoundingPolicy.defaults(), SettlementGovernancePolicy.defaults());
  }

  public SettlementLifecycleService(
      SettlementLifecycleGateway gateway, FoundingPolicy foundingPolicy) {
    this(gateway, foundingPolicy, SettlementGovernancePolicy.defaults());
  }

  public SettlementLifecycleService(
      SettlementLifecycleGateway gateway,
      FoundingPolicy foundingPolicy,
      SettlementGovernancePolicy governancePolicy) {
    this.gateway = Objects.requireNonNull(gateway);
    this.foundingPolicy = Objects.requireNonNull(foundingPolicy);
    this.governancePolicy = Objects.requireNonNull(governancePolicy);
  }

  public SettlementLifecycleGateway.FoundingExpedition createExpedition(
      UUID leader, String name, String charter, Instant now) {
    String cleanName = Objects.requireNonNull(name).strip();
    if (cleanName.length() < 3 || cleanName.length() > 32)
      throw new DomainException("settlement name must be 3-32 characters");
    String cleanCharter = cleanCharter(charter);
    return gateway.createExpedition(
        leader, cleanName, cleanCharter, now, now.plus(foundingPolicy.expeditionLifetime()));
  }

  public SettlementLifecycleGateway.FoundingExpedition inviteFounder(
      UUID expedition, UUID actor, UUID player, Instant now) {
    if (actor.equals(player))
      throw new DomainException("the expedition leader is already accepted");
    return gateway.inviteFounder(
        expedition, actor, player, now, now.plus(foundingPolicy.expeditionLifetime()));
  }

  public SettlementLifecycleGateway.FoundingExpedition acceptFounder(
      UUID expedition, UUID player, Instant now) {
    return gateway.acceptFounder(expedition, player, now);
  }

  public SettlementLifecycleGateway.FoundingReservation prepareFounding(
      UUID expedition, UUID actor, SettlementLifecycleGateway.CoreLocation core, Instant now) {
    gateway.selectCore(
        expedition,
        actor,
        core,
        foundingPolicy.minimumCoreDistance(),
        foundingPolicy.harborExclusionRadius(),
        now);
    return gateway.reserveExpedition(
        expedition,
        actor,
        foundingPolicy.feeMinor(),
        foundingPolicy.minimumFounders(),
        now,
        now.plus(foundingPolicy.reservationLifetime()));
  }

  public boolean claimMaterials(UUID expedition, UUID actor, Instant now) {
    return gateway.claimMaterials(expedition, actor, now);
  }

  public void confirmMaterials(UUID expedition, UUID actor, Instant now) {
    gateway.confirmMaterials(expedition, actor, now);
  }

  public void releaseMaterials(UUID expedition, UUID actor, Instant now) {
    gateway.releaseMaterials(expedition, actor, now);
  }

  public void confirmCorePlacement(UUID expedition, UUID actor, Instant now) {
    gateway.confirmCorePlacement(expedition, actor, now);
  }

  public void reviewExpedition(UUID expedition, UUID actor, String reason, Instant now) {
    String clean = Objects.requireNonNull(reason).strip();
    if (clean.isEmpty() || clean.length() > 200)
      throw new DomainException("founding review reason must be 1-200 characters");
    gateway.reviewExpedition(expedition, actor, clean, now);
  }

  public void completeExpedition(UUID expedition, UUID city, UUID actor, Instant now) {
    gateway.completeExpedition(expedition, city, actor, now);
  }

  public SettlementLifecycleGateway.FoundingExpedition cancelExpedition(
      UUID expedition, UUID actor, Instant now) {
    return gateway.cancelExpedition(expedition, actor, now);
  }

  public Optional<SettlementLifecycleGateway.FoundingExpedition> activeExpedition(
      UUID player, Instant now) {
    return gateway.activeExpedition(player, now);
  }

  public List<SettlementLifecycleGateway.FoundingExpedition> pendingExpeditions(int limit) {
    return gateway.pendingExpeditions(Math.max(1, Math.min(limit, 100)));
  }

  public SettlementLifecycleGateway.FoundingReservation reserve(UUID player, Instant now) {
    return gateway.reserveFounding(
        player, foundingPolicy.feeMinor(), now, now.plus(foundingPolicy.reservationLifetime()));
  }

  public void validateCore(SettlementLifecycleGateway.CoreLocation core) {
    gateway.validateCore(core);
  }

  public void complete(
      UUID reservation,
      UUID city,
      UUID founder,
      SettlementLifecycleGateway.CoreLocation core,
      String charter,
      Instant now) {
    gateway.completeFounding(
        reservation,
        city,
        founder,
        core,
        cleanCharter(charter),
        foundingPolicy.minimumFounders(),
        now);
  }

  public void cancel(UUID reservation, UUID player, Instant now) {
    gateway.cancelFounding(reservation, player, now);
  }

  public void touch(UUID player, Instant now) {
    gateway.touch(player, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot transfer(
      UUID city, UUID actor, UUID successor, Instant now) {
    if (actor.equals(successor)) throw new DomainException("successor is already the mayor");
    return gateway.transfer(city, actor, successor, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot succession(
      UUID city, UUID actor, Instant now) {
    return gateway.succeed(
        city,
        actor,
        now.minus(governancePolicy.mayorInactivity()),
        Set.of(
            GovernmentRole.TREASURER,
            GovernmentRole.GENERAL,
            GovernmentRole.ARCHITECT,
            GovernmentRole.BUILDER_MASTER,
            GovernmentRole.DIPLOMAT),
        now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot abandon(UUID city, UUID actor, Instant now) {
    return gateway.abandon(city, actor, now);
  }

  public SettlementLifecycleGateway.DisbandRequest requestDisband(
      UUID city, UUID actor, Instant now) {
    return gateway.requestDisband(
        city,
        actor,
        now,
        now.plus(governancePolicy.disbandConfirmationDelay()),
        now.plus(governancePolicy.disbandRequestLifetime()));
  }

  public SettlementLifecycleGateway.LifecycleSnapshot confirmDisband(
      UUID request, UUID actor, Instant now) {
    return gateway.confirmDisband(request, actor, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot recoverRuins(
      UUID city, UUID actor, Instant now) {
    return gateway.recoverRuins(city, actor, now);
  }

  public SettlementLifecycleGateway.MergeProposal merge(
      UUID source, UUID actor, UUID target, Instant now) {
    if (source.equals(target)) throw new DomainException("a settlement cannot merge into itself");
    return gateway.proposeMerge(source, actor, target, now, now.plus(Duration.ofHours(48)));
  }

  public SettlementLifecycleGateway.LifecycleSnapshot acceptMerge(
      UUID proposal, UUID actor, Instant now) {
    return gateway.acceptMerge(proposal, actor, now);
  }

  public SettlementLifecycleGateway.RecoveryReport recover(Instant now, int limit) {
    return gateway.recoverInactive(
        now.minus(governancePolicy.settlementInactivity()),
        now.minus(governancePolicy.mayorInactivity()),
        now,
        limit);
  }

  public List<SettlementLifecycleGateway.HistoryEntry> history(UUID city, UUID actor) {
    return gateway.history(city, actor, 50);
  }

  public FoundingPolicy foundingPolicy() {
    return foundingPolicy;
  }

  public SettlementGovernancePolicy governancePolicy() {
    return governancePolicy;
  }

  private static String cleanCharter(String charter) {
    String clean = Objects.requireNonNull(charter).strip();
    if (clean.length() < 10 || clean.length() > 512)
      throw new DomainException("charter must be 10-512 characters");
    return clean;
  }
}
