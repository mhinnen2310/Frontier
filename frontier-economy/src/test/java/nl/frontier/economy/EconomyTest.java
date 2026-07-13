package nl.frontier.economy;

import static nl.frontier.domain.Ids.PlayerId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import nl.frontier.domain.DomainException;
import nl.frontier.domain.Money;
import org.junit.jupiter.api.Test;

class EconomyTest {
  @Test
  void debitIsIdempotentAndCannotOverdraw() {
    TreasuryAccount account = new TreasuryAccount(UUID.randomUUID(), new Money(1_000));
    UUID key = UUID.randomUUID();
    account.debit(
        new PlayerId(UUID.randomUUID()),
        new Money(400),
        "REPAIR",
        UUID.randomUUID(),
        key,
        Instant.EPOCH);
    account.debit(
        new PlayerId(UUID.randomUUID()),
        new Money(400),
        "REPAIR",
        UUID.randomUUID(),
        key,
        Instant.EPOCH);
    assertEquals(new Money(600), account.balance());
    assertThrows(
        DomainException.class,
        () ->
            account.debit(
                new PlayerId(UUID.randomUUID()),
                new Money(601),
                "TEST",
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.EPOCH));
  }

  @Test
  void reservationsCannotSpendTheSameStockTwice() {
    WarehouseStock stock = new WarehouseStock();
    stock.add("stone_bricks", 100);
    stock.reserve(UUID.randomUUID(), UUID.randomUUID(), "stone_bricks", 100);
    assertThrows(
        DomainException.class,
        () -> stock.reserve(UUID.randomUUID(), UUID.randomUUID(), "stone_bricks", 1));
  }
}
