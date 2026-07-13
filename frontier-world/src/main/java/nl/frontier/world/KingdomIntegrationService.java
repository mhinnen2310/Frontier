package nl.frontier.world;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class KingdomIntegrationService {
  private final KingdomIntegrationGateway gateway;

  public KingdomIntegrationService(KingdomIntegrationGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public KingdomIntegrationGateway.KingdomReport report(UUID kingdom) {
    return gateway.report(kingdom);
  }

  public void assignRole(
      UUID kingdom, UUID actor, UUID player, KingdomIntegrationGateway.Role role, Instant now) {
    gateway.assignRole(kingdom, actor, player, role, now);
  }

  public KingdomIntegrationGateway.Vote createVote(
      UUID kingdom, UUID actor, String kind, String subjectJson, Instant closesAt, Instant now) {
    if (!closesAt.isAfter(now)) throw new DomainException("vote must close in the future");
    return gateway.createVote(kingdom, actor, normalizedKey(kind), subjectJson, closesAt, now);
  }

  public KingdomIntegrationGateway.Vote castVote(
      UUID vote, UUID city, UUID actor, boolean yes, Instant now) {
    return gateway.castVote(vote, city, actor, yes, now);
  }

  public KingdomIntegrationGateway.WarApproval approveWar(
      UUID kingdom, UUID actor, UUID targetCity, String type, Instant expiresAt, Instant now) {
    if (!expiresAt.isAfter(now))
      throw new DomainException("war approval must expire in the future");
    return gateway.approveWar(kingdom, actor, targetCity, normalizedKey(type), expiresAt, now);
  }

  public KingdomIntegrationGateway.TreasuryResult deposit(
      UUID kingdom, UUID city, UUID actor, long amount, UUID idempotency, Instant now) {
    positive(amount);
    return gateway.deposit(kingdom, city, actor, amount, idempotency, now);
  }

  public KingdomIntegrationGateway.TreasuryResult withdraw(
      UUID kingdom, UUID city, UUID actor, long amount, UUID idempotency, Instant now) {
    positive(amount);
    return gateway.withdraw(kingdom, city, actor, amount, idempotency, now);
  }

  public void setTaxRate(UUID kingdom, UUID actor, int basisPoints, Instant now) {
    if (basisPoints < 0 || basisPoints > 2_500)
      throw new DomainException("kingdom tax must be between 0 and 2500 basis points");
    gateway.setTaxRate(kingdom, actor, basisPoints, now);
  }

  public KingdomIntegrationGateway.TaxReport collectTaxes(
      UUID kingdom, LocalDate date, Instant now) {
    return gateway.collectTaxes(kingdom, date, now);
  }

  public void setPolicy(UUID kingdom, UUID actor, String key, String value, Instant now) {
    if (value == null || value.isBlank() || value.length() > 64)
      throw new DomainException("policy value must be 1-64 characters");
    gateway.setPolicy(kingdom, actor, normalizedKey(key), value.trim(), now);
  }

  public KingdomIntegrationGateway.Secession requestSecession(
      UUID kingdom, UUID city, UUID actor, Instant now) {
    return gateway.requestSecession(kingdom, city, actor, now);
  }

  public KingdomIntegrationGateway.GovernanceCycle cycle(Instant now) {
    return gateway.cycle(now);
  }

  private static String normalizedKey(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{1,47}"))
      throw new DomainException("key must be 2-48 simple characters");
    return value.toUpperCase(Locale.ROOT);
  }

  private static void positive(long value) {
    if (value <= 0) throw new DomainException("amount must be positive");
  }
}
