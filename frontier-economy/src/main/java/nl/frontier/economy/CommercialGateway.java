package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommercialGateway {
  Company createCompany(
      UUID actor, UUID city, String name, long initialCapitalMinor, UUID idempotency, Instant now);

  List<Company> companies(UUID actor);

  Invoice issueInvoice(
      UUID company, UUID actor, UUID targetPlayer, long amountMinor, Instant dueAt, Instant now);

  Invoice payInvoice(UUID invoice, UUID payer, UUID idempotency, Instant now);

  Loan borrow(
      UUID company,
      UUID actor,
      long principalMinor,
      int annualInterestBasisPoints,
      UUID idempotency,
      Instant now);

  Loan repay(UUID loan, UUID actor, long amountMinor, UUID idempotency, Instant now);

  TaxRule setBusinessTax(UUID city, UUID actor, int basisPoints, Instant now);

  Procurement procure(
      UUID city,
      UUID actor,
      String commodity,
      long quantity,
      long maximumUnitPriceMinor,
      Instant now);

  Procurement fulfill(UUID procurement, UUID company, UUID actor, long quantity, Instant now);

  EmergencyPurchase emergencyBuy(
      UUID city, UUID actor, String commodity, long quantity, UUID idempotency, Instant now);

  CycleReport cycle(int limit, Instant now);

  List<HistoryEntry> history(UUID city, UUID actor, int limit);

  record Company(UUID id, UUID city, String name, UUID founder, long balanceMinor, String status) {}

  record Invoice(
      UUID id,
      UUID company,
      UUID targetPlayer,
      long amountMinor,
      String status,
      Instant dueAt,
      Instant paidAt) {}

  record Loan(
      UUID id,
      UUID company,
      long principalMinor,
      long outstandingMinor,
      int annualInterestBasisPoints,
      long accruedInterestMinor,
      String status) {}

  record TaxRule(UUID city, int basisPoints, Instant changedAt) {}

  record Procurement(
      UUID id,
      UUID city,
      String commodity,
      long quantity,
      long fulfilled,
      long maximumUnitPriceMinor,
      String status) {}

  record EmergencyPurchase(
      UUID id, UUID city, String commodity, long quantity, long unitPriceMinor, long totalMinor) {}

  record HistoryEntry(String type, UUID aggregate, String details, Instant occurredAt) {}

  record CycleReport(int interestAccruals, int taxesCollected, int overdueInvoices) {}
}
