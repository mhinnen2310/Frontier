package nl.frontier.world;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface KingdomIntegrationGateway {
  enum Role {
    KING,
    COUNCIL,
    MARSHAL,
    DIPLOMAT
  }

  KingdomReport report(UUID kingdom);

  void assignRole(UUID kingdom, UUID actor, UUID player, Role role, Instant now);

  Vote createVote(
      UUID kingdom, UUID actor, String kind, String subjectJson, Instant closesAt, Instant now);

  Vote castVote(UUID vote, UUID city, UUID actor, boolean yes, Instant now);

  WarApproval approveWar(
      UUID kingdom,
      UUID actor,
      UUID targetCity,
      String approvalType,
      Instant expiresAt,
      Instant now);

  TreasuryResult deposit(
      UUID kingdom, UUID city, UUID actor, long amountMinor, UUID idempotency, Instant now);

  TreasuryResult withdraw(
      UUID kingdom, UUID city, UUID actor, long amountMinor, UUID idempotency, Instant now);

  void setTaxRate(UUID kingdom, UUID actor, int basisPoints, Instant now);

  TaxReport collectTaxes(UUID kingdom, LocalDate date, Instant now);

  void setPolicy(UUID kingdom, UUID actor, String key, String value, Instant now);

  Secession requestSecession(UUID kingdom, UUID city, UUID actor, Instant now);

  GovernanceCycle cycle(Instant now);

  record KingdomReport(
      UUID kingdom,
      long treasuryMinor,
      int taxBasisPoints,
      List<String> roles,
      List<String> policies,
      List<String> projects) {}

  record Vote(
      UUID id, UUID kingdom, String kind, String status, int yes, int no, int requiredYes) {}

  record WarApproval(UUID id, UUID kingdom, UUID targetCity, String type, Instant expiresAt) {}

  record TreasuryResult(long kingdomBalanceMinor, long cityBalanceMinor) {}

  record TaxReport(int assessed, int paid, long collectedMinor) {}

  record Secession(UUID id, UUID kingdom, UUID city, String status) {}

  record GovernanceCycle(int votesClosed, int treatiesExpired) {}
}
