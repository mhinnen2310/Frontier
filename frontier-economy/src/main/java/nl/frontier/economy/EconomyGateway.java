package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transactional economy boundary. Money, stock, escrow and order state change atomically. */
public interface EconomyGateway {
  WarehouseSnapshot warehouse(UUID city, UUID actor, Instant now);

  WarehouseSnapshot deposit(UUID city, UUID actor, String commodity, long quantity, Instant now);

  OrderSnapshot placeOrder(
      UUID city,
      UUID actor,
      MarketEngine.Side side,
      String commodity,
      long quantity,
      long unitPriceMinor,
      UUID idempotencyKey,
      Instant now);

  void cancel(UUID city, UUID actor, UUID order, Instant now);

  List<OrderSnapshot> openOrders(UUID city);

  int match(int maximumTrades, Instant now);

  record Stock(String commodity, long available, long reserved) {}

  record WarehouseSnapshot(UUID id, long capacity, List<Stock> stock) {}

  record OrderSnapshot(
      UUID id,
      UUID city,
      MarketEngine.Side side,
      String commodity,
      long quantity,
      long remaining,
      long unitPriceMinor,
      String status,
      Instant createdAt) {}
}
