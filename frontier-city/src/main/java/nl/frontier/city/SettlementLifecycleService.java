package nl.frontier.city;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class SettlementLifecycleService {
  public static final long FOUNDING_FEE_MINOR = 2_500;
  public static final int MINIMUM_FOUNDERS = 1;
  private final SettlementLifecycleGateway gateway;

  public SettlementLifecycleService(SettlementLifecycleGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public SettlementLifecycleGateway.FoundingReservation reserve(UUID player, Instant now) {
    return gateway.reserveFounding(
        player, FOUNDING_FEE_MINOR, now, now.plus(Duration.ofMinutes(5)));
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
    String clean = Objects.requireNonNull(charter).strip();
    if (clean.length() < 10 || clean.length() > 512)
      throw new DomainException("charter must be 10-512 characters");
    gateway.completeFounding(reservation, city, founder, core, clean, MINIMUM_FOUNDERS, now);
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
    return gateway.succeed(city, actor, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot abandon(UUID city, UUID actor, Instant now) {
    return gateway.abandon(city, actor, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot disband(UUID city, UUID actor, Instant now) {
    return gateway.disband(city, actor, now);
  }

  public SettlementLifecycleGateway.LifecycleSnapshot recoverRuins(
      UUID city, UUID actor, Instant now) {
    return gateway.recoverRuins(city, actor, now);
  }

  public SettlementLifecycleGateway.MergeProposal merge(
      UUID source, UUID actor, UUID target, Instant now) {
    return gateway.proposeMerge(source, actor, target, now, now.plus(Duration.ofHours(48)));
  }

  public SettlementLifecycleGateway.LifecycleSnapshot acceptMerge(
      UUID proposal, UUID actor, Instant now) {
    return gateway.acceptMerge(proposal, actor, now);
  }

  public SettlementLifecycleGateway.RecoveryReport recover(Instant now, int limit) {
    return gateway.recoverInactive(now.minus(Duration.ofDays(30)), now, limit);
  }

  public List<SettlementLifecycleGateway.HistoryEntry> history(UUID city, UUID actor) {
    return gateway.history(city, actor, 50);
  }
}
