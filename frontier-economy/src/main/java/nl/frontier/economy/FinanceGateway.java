package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FinanceGateway {
  long playerBalance(UUID player, Instant now);

  TransferReceipt payPlayer(
      UUID sender, UUID recipient, long amountMinor, UUID idempotency, Instant now);

  TransferReceipt depositToSettlement(
      UUID player, UUID settlement, long amountMinor, UUID idempotency, Instant now);

  TransferReceipt withdrawFromSettlement(
      UUID settlement, UUID actor, long amountMinor, UUID idempotency, Instant now);

  TransferReceipt payFromSettlement(
      UUID settlement, UUID actor, UUID recipient, long amountMinor, UUID idempotency, Instant now);

  List<LedgerLine> settlementAudit(UUID settlement, UUID actor, int limit);

  record TransferReceipt(
      UUID id,
      String transferType,
      UUID sourceOwner,
      UUID destinationOwner,
      long amountMinor,
      long sourceBalanceMinor,
      long destinationBalanceMinor,
      Instant occurredAt) {}

  record LedgerLine(
      UUID id,
      String type,
      long amountMinor,
      long balanceAfterMinor,
      UUID actor,
      UUID counterpartyAccount,
      String description,
      Instant occurredAt) {}
}
