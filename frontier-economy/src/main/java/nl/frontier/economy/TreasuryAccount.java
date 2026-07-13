package nl.frontier.economy;

import static nl.frontier.domain.Ids.PlayerId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Money;

/** In-memory aggregate mirror. Persistence must use an atomic conditional update/row lock. */
public final class TreasuryAccount {
  private final UUID id;
  private final Set<UUID> idempotencyKeys = new HashSet<>();
  private final List<LedgerEntry> ledger = new ArrayList<>();
  private Money balance;
  private long version;

  public TreasuryAccount(UUID id, Money openingBalance) {
    this.id = Objects.requireNonNull(id);
    this.balance = Objects.requireNonNull(openingBalance);
  }

  public synchronized LedgerEntry deposit(
      PlayerId actor, Money amount, String type, UUID reference, UUID idempotencyKey, Instant now) {
    requirePositive(amount);
    LedgerEntry previous = existing(idempotencyKey);
    if (previous != null) return previous;
    balance = balance.plus(amount);
    return append(actor, type, amount.cents(), reference, idempotencyKey, now);
  }

  public synchronized LedgerEntry debit(
      PlayerId actor, Money amount, String type, UUID reference, UUID idempotencyKey, Instant now) {
    requirePositive(amount);
    LedgerEntry previous = existing(idempotencyKey);
    if (previous != null) return previous;
    if (balance.compareTo(amount) < 0) throw new DomainException("insufficient treasury funds");
    balance = balance.minus(amount);
    return append(actor, type, -amount.cents(), reference, idempotencyKey, now);
  }

  private LedgerEntry append(
      PlayerId actor, String type, long signedAmount, UUID reference, UUID key, Instant now) {
    idempotencyKeys.add(key);
    version++;
    LedgerEntry entry =
        new LedgerEntry(
            UUID.randomUUID(),
            id,
            actor,
            type,
            signedAmount,
            balance,
            reference,
            key,
            now,
            version);
    ledger.add(entry);
    return entry;
  }

  private LedgerEntry existing(UUID key) {
    if (!idempotencyKeys.contains(key)) return null;
    return ledger.stream()
        .filter(entry -> entry.idempotencyKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static void requirePositive(Money amount) {
    if (amount.cents() <= 0) throw new IllegalArgumentException("amount must be positive");
  }

  public synchronized Money balance() {
    return balance;
  }

  public synchronized long version() {
    return version;
  }

  public synchronized List<LedgerEntry> ledger() {
    return List.copyOf(ledger);
  }

  public record LedgerEntry(
      UUID id,
      UUID accountId,
      PlayerId actor,
      String type,
      long signedAmount,
      Money balanceAfter,
      UUID reference,
      UUID idempotencyKey,
      Instant timestamp,
      long version) {}
}
