package nl.frontier.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class CommercialService {
  private final CommercialGateway gateway;

  public CommercialService(CommercialGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public CommercialGateway.Company createCompany(
      UUID actor, UUID city, String name, long capital, UUID key, Instant now) {
    String clean = Objects.requireNonNull(name).strip();
    if (clean.length() < 3 || clean.length() > 48)
      throw new DomainException("company name must be 3-48 characters");
    if (capital < 0) throw new DomainException("company capital cannot be negative");
    return gateway.createCompany(actor, city, clean, capital, key, now);
  }

  public List<CommercialGateway.Company> companies(UUID actor) {
    return gateway.companies(actor);
  }

  public CommercialGateway.Invoice invoice(
      UUID company, UUID actor, UUID target, long amount, int dueDays, Instant now) {
    if (amount <= 0) throw new DomainException("invoice amount must be positive");
    if (dueDays < 1 || dueDays > 90) throw new DomainException("invoice due days must be 1-90");
    return gateway.issueInvoice(
        company, actor, target, amount, now.plus(Duration.ofDays(dueDays)), now);
  }

  public CommercialGateway.Invoice payInvoice(UUID invoice, UUID payer, UUID key, Instant now) {
    return gateway.payInvoice(invoice, payer, key, now);
  }

  public CommercialGateway.Loan borrow(
      UUID company, UUID actor, long amount, int interestBasisPoints, UUID key, Instant now) {
    if (amount <= 0) throw new DomainException("loan principal must be positive");
    if (interestBasisPoints < 0 || interestBasisPoints > 5_000)
      throw new DomainException("annual interest must be 0-5000 basis points");
    return gateway.borrow(company, actor, amount, interestBasisPoints, key, now);
  }

  public CommercialGateway.Loan repay(UUID loan, UUID actor, long amount, UUID key, Instant now) {
    if (amount <= 0) throw new DomainException("repayment must be positive");
    return gateway.repay(loan, actor, amount, key, now);
  }

  public CommercialGateway.TaxRule tax(UUID city, UUID actor, int basisPoints, Instant now) {
    if (basisPoints < 0 || basisPoints > 2_500)
      throw new DomainException("business tax must be 0-2500 basis points");
    return gateway.setBusinessTax(city, actor, basisPoints, now);
  }

  public CommercialGateway.Procurement procure(
      UUID city, UUID actor, String commodity, long quantity, long maximumPrice, Instant now) {
    if (quantity <= 0 || maximumPrice <= 0) throw new DomainException("invalid procurement terms");
    return gateway.procure(city, actor, commodity(commodity), quantity, maximumPrice, now);
  }

  public CommercialGateway.Procurement fulfill(
      UUID procurement, UUID company, UUID actor, long quantity, Instant now) {
    if (quantity <= 0) throw new DomainException("fulfillment quantity must be positive");
    return gateway.fulfill(procurement, company, actor, quantity, now);
  }

  public CommercialGateway.EmergencyPurchase emergencyBuy(
      UUID city, UUID actor, String commodity, long quantity, UUID key, Instant now) {
    if (quantity <= 0) throw new DomainException("emergency quantity must be positive");
    return gateway.emergencyBuy(city, actor, commodity(commodity), quantity, key, now);
  }

  public CommercialGateway.CycleReport cycle(int limit, Instant now) {
    return gateway.cycle(Math.max(1, Math.min(500, limit)), now);
  }

  public List<CommercialGateway.HistoryEntry> history(UUID city, UUID actor) {
    return gateway.history(city, actor, 100);
  }

  private static String commodity(String value) {
    String clean = Objects.requireNonNull(value).strip().toLowerCase(Locale.ROOT);
    if (!clean.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new DomainException("invalid commodity key");
    return clean;
  }
}
