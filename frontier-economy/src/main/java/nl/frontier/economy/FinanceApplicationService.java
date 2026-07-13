package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.frontier.domain.DomainException;

public final class FinanceApplicationService {
  private final FinanceGateway gateway;

  public FinanceApplicationService(FinanceGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public long balance(UUID player, Instant now) {
    return gateway.playerBalance(player, now);
  }

  public FinanceGateway.TransferReceipt pay(
      UUID sender, UUID recipient, long amountMinor, UUID idempotency, Instant now) {
    requireAmount(amountMinor);
    if (sender.equals(recipient)) throw new DomainException("cannot pay yourself");
    return gateway.payPlayer(sender, recipient, amountMinor, idempotency, now);
  }

  public FinanceGateway.TransferReceipt deposit(
      UUID player, UUID settlement, long amountMinor, UUID idempotency, Instant now) {
    requireAmount(amountMinor);
    return gateway.depositToSettlement(player, settlement, amountMinor, idempotency, now);
  }

  public FinanceGateway.TransferReceipt withdraw(
      UUID settlement, UUID actor, long amountMinor, UUID idempotency, Instant now) {
    requireAmount(amountMinor);
    return gateway.withdrawFromSettlement(settlement, actor, amountMinor, idempotency, now);
  }

  public FinanceGateway.TransferReceipt settlementPay(
      UUID settlement,
      UUID actor,
      UUID recipient,
      long amountMinor,
      UUID idempotency,
      Instant now) {
    requireAmount(amountMinor);
    return gateway.payFromSettlement(settlement, actor, recipient, amountMinor, idempotency, now);
  }

  public List<FinanceGateway.LedgerLine> audit(UUID settlement, UUID actor, int limit) {
    if (limit < 1 || limit > 100) throw new DomainException("audit limit must be 1..100");
    return gateway.settlementAudit(settlement, actor, limit);
  }

  private static void requireAmount(long amountMinor) {
    if (amountMinor <= 0) throw new DomainException("amount must be positive");
  }
}
