package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.EconomyGateway;
import nl.frontier.economy.MarketEngine;

public final class PostgresEconomyGateway implements EconomyGateway {
  private static final Set<String> ECONOMY_ROLES = Set.of("MAYOR", "TREASURER");
  private static final Set<String> STOCK_ROLES = Set.of("MAYOR", "TREASURER", "ARCHITECT");
  private final TransactionalStore store;

  public PostgresEconomyGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public WarehouseSnapshot warehouse(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, STOCK_ROLES);
          return loadWarehouse(connection, ensureWarehouse(connection, city));
        });
  }

  @Override
  public WarehouseSnapshot deposit(
      UUID city, UUID actor, String commodity, long quantity, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, STOCK_ROLES);
          UUID warehouse = ensureWarehouse(connection, city);
          long used = usedCapacity(connection, warehouse);
          long capacity = capacity(connection, warehouse);
          if (used + quantity > capacity) throw new DomainException("warehouse capacity exceeded");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity) VALUES(?,?,?,0) ON CONFLICT(warehouse_id,commodity_key) DO UPDATE SET available_quantity=warehouse_stock.available_quantity+EXCLUDED.available_quantity,version=warehouse_stock.version+1")) {
            statement.setObject(1, warehouse);
            statement.setString(2, commodity);
            statement.setLong(3, quantity);
            statement.executeUpdate();
          }
          audit(connection, actor, "STOCK_DEPOSITED", "WAREHOUSE", warehouse, now);
          return loadWarehouse(connection, warehouse);
        });
  }

  @Override
  public OrderSnapshot placeOrder(
      UUID city,
      UUID actor,
      MarketEngine.Side side,
      String commodity,
      long quantity,
      long unitPriceMinor,
      UUID idempotencyKey,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, ECONOMY_ROLES);
          OrderSnapshot existing = byIdempotency(connection, idempotencyKey);
          if (existing != null) return existing;
          UUID order = UUID.randomUUID();
          UUID reservation = null;
          UUID escrow = null;
          if (side == MarketEngine.Side.SELL) {
            UUID warehouse = ensureWarehouse(connection, city);
            lockStock(connection, warehouse, commodity);
            long available = available(connection, warehouse, commodity);
            if (available < quantity) throw new DomainException("insufficient available stock");
            reservation = UUID.randomUUID();
            updateStock(connection, warehouse, commodity, -quantity, quantity);
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO stock_reservations(id,warehouse_id,owner_type,owner_id,commodity_key,quantity,consumed,status,version) VALUES(?,?,'MARKET_ORDER',?,?,?,0,'ACTIVE',0)")) {
              statement.setObject(1, reservation);
              statement.setObject(2, warehouse);
              statement.setObject(3, order);
              statement.setString(4, commodity);
              statement.setLong(5, quantity);
              statement.executeUpdate();
            }
          } else {
            long total = Math.multiplyExact(quantity, unitPriceMinor);
            UUID treasury = account(connection, "CITY", city, true);
            long balance = balance(connection, treasury);
            if (balance < total) throw new DomainException("insufficient treasury balance");
            escrow = UUID.randomUUID();
            setBalance(connection, treasury, balance - total);
            insertAccount(connection, escrow, "MARKET_ORDER", order, total);
            ledger(
                connection,
                treasury,
                actor,
                "MARKET_ESCROW_DEBIT",
                -total,
                balance - total,
                order,
                idempotencyKey,
                now);
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_orders(id,owner_id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at,reservation_id,escrow_account_id,idempotency_key,version) VALUES(?,?,?,?,?,?,?,?, 'OPEN',?,?,?,?,0)")) {
            statement.setObject(1, order);
            statement.setObject(2, city);
            statement.setObject(3, city);
            statement.setString(4, side.name());
            statement.setString(5, commodity);
            statement.setLong(6, quantity);
            statement.setLong(7, quantity);
            statement.setLong(8, unitPriceMinor);
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setObject(10, reservation);
            statement.setObject(11, escrow);
            statement.setObject(12, idempotencyKey);
            statement.executeUpdate();
          }
          audit(connection, actor, "MARKET_ORDER_PLACED", "MARKET_ORDER", order, now);
          return loadOrder(connection, order);
        });
  }

  @Override
  public void cancel(UUID city, UUID actor, UUID order, Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, ECONOMY_ROLES);
          LockedOrder value = lockOrder(connection, order);
          if (!value.city.equals(city)) throw new DomainException("order belongs to another city");
          if (!value.open()) return null;
          if (value.side == MarketEngine.Side.SELL) {
            UUID warehouse = reservationWarehouse(connection, value.reservation);
            updateStock(connection, warehouse, value.commodity, value.remaining, -value.remaining);
            closeReservation(connection, value.reservation, "CANCELLED");
          } else {
            refundEscrow(connection, value, actor, now);
          }
          setOrderState(connection, order, value.remaining, "CANCELLED");
          audit(connection, actor, "MARKET_ORDER_CANCELLED", "MARKET_ORDER", order, now);
          return null;
        });
  }

  @Override
  public List<OrderSnapshot> openOrders(UUID city) {
    return store.inTransaction(
        connection -> {
          List<OrderSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at FROM market_orders WHERE settlement_id=? AND status IN ('OPEN','PARTIAL') ORDER BY created_at")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(mapOrder(result));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public int match(int maximumTrades, Instant now) {
    int matched = 0;
    for (int count = 0; count < maximumTrades; count++) {
      Boolean result = store.inTransaction(connection -> matchOne(connection, now));
      if (!result) break;
      matched++;
    }
    return matched;
  }

  private static boolean matchOne(Connection connection, Instant now) throws SQLException {
    LockedOrder buy =
        best(
            connection,
            "BUY",
            "unit_price_minor DESC,coalesce((SELECT max(de.trade_bonus) FROM district_effects de WHERE de.city_id=o.settlement_id),0) DESC,created_at,id");
    if (buy == null) return false;
    LockedOrder sell = bestSell(connection, buy.commodity, buy.unitPrice);
    if (sell == null) return false;
    if (sell.city.equals(buy.city)) return false;
    long quantity = Math.min(buy.remaining, sell.remaining);
    long total = Math.multiplyExact(quantity, sell.unitPrice);
    long escrowBalance = balance(connection, buy.escrow);
    if (escrowBalance < total) throw new DomainException("market escrow invariant violated");
    setBalance(connection, buy.escrow, escrowBalance - total);
    UUID sellerTreasury = account(connection, "CITY", sell.city, true);
    long sellerBalance = balance(connection, sellerTreasury);
    setBalance(connection, sellerTreasury, Math.addExact(sellerBalance, total));
    ledger(
        connection,
        sellerTreasury,
        null,
        "MARKET_SALE",
        total,
        sellerBalance + total,
        sell.id,
        UUID.randomUUID(),
        now);

    UUID sellerWarehouse = reservationWarehouse(connection, sell.reservation);
    UUID buyerWarehouse = ensureWarehouse(connection, buy.city);
    if (usedCapacity(connection, buyerWarehouse) + quantity > capacity(connection, buyerWarehouse))
      throw new DomainException("buyer warehouse capacity exceeded");
    createMarketShipment(
        connection, sell, buy, sellerWarehouse, buyerWarehouse, quantity, total, now);

    long buyLeft = buy.remaining - quantity;
    long sellLeft = sell.remaining - quantity;
    setOrderState(connection, buy.id, buyLeft, buyLeft == 0 ? "FILLED" : "PARTIAL");
    setOrderState(connection, sell.id, sellLeft, sellLeft == 0 ? "FILLED" : "PARTIAL");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO trades(id,buy_order_id,sell_order_id,quantity,unit_price_minor,occurred_at) VALUES(?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, buy.id);
      statement.setObject(3, sell.id);
      statement.setLong(4, quantity);
      statement.setLong(5, sell.unitPrice);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
    recordPrice(connection, buy.city, buy.commodity, sell.unitPrice, quantity, now);
    if (buyLeft == 0) refundEscrow(connection, buy, null, now);
    return true;
  }

  private static void createMarketShipment(
      Connection connection,
      LockedOrder sell,
      LockedOrder buy,
      UUID originWarehouse,
      UUID destinationWarehouse,
      long quantity,
      long declaredValue,
      Instant now)
      throws SQLException {
    UUID originNode = roadNode(connection, sell.city);
    UUID destinationNode = roadNode(connection, buy.city);
    if (originNode == null || destinationNode == null)
      throw new DomainException("regional market delivery requires road nodes in both cities");
    UUID shipment = UUID.randomUUID();
    UUID reservation = sell.reservation;
    if (quantity < sell.remaining) {
      reservation = UUID.randomUUID();
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE stock_reservations SET quantity=quantity-?,version=version+1 WHERE id=? AND status='ACTIVE' AND quantity>?")) {
        statement.setLong(1, quantity);
        statement.setObject(2, sell.reservation);
        statement.setLong(3, quantity);
        if (statement.executeUpdate() != 1)
          throw new DomainException("market reservation split invariant violated");
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO stock_reservations(id,warehouse_id,owner_type,owner_id,commodity_key,quantity,consumed,status,version) VALUES(?,?,'SHIPMENT',?,?,?,0,'ACTIVE',0)")) {
        statement.setObject(1, reservation);
        statement.setObject(2, originWarehouse);
        statement.setObject(3, shipment);
        statement.setString(4, sell.commodity);
        statement.setLong(5, quantity);
        statement.executeUpdate();
      }
    } else {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE stock_reservations SET owner_type='SHIPMENT',owner_id=?,version=version+1 WHERE id=? AND status='ACTIVE'")) {
        statement.setObject(1, shipment);
        statement.setObject(2, reservation);
        if (statement.executeUpdate() != 1)
          throw new DomainException("market reservation transfer invariant violated");
      }
    }
    String manifest = "{\"" + sell.commodity + "\":" + quantity + "}";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO shipments(id,origin_storage_id,destination_storage_id,manifest,carrier_type,status,declared_value_minor,insured_value_minor,departed_at,version,owner_city_id,origin_node_id,destination_node_id,idempotency_key) VALUES(?,?,?,?::jsonb,'MARKET_CARAVAN','WAITING_ROUTE',?,0,?,0,?,?,?,?)")) {
      statement.setObject(1, shipment);
      statement.setObject(2, originWarehouse);
      statement.setObject(3, destinationWarehouse);
      statement.setString(4, manifest);
      statement.setLong(5, declaredValue);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setObject(7, buy.city);
      statement.setObject(8, originNode);
      statement.setObject(9, destinationNode);
      statement.setObject(10, UUID.randomUUID());
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO shipment_items(shipment_id,commodity_key,quantity,reservation_id) VALUES(?,?,?,?)")) {
      statement.setObject(1, shipment);
      statement.setString(2, sell.commodity);
      statement.setLong(3, quantity);
      statement.setObject(4, reservation);
      statement.executeUpdate();
    }
  }

  private static UUID roadNode(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM road_nodes WHERE city_id=? AND integrity>=10 ORDER BY CASE WHEN node_type='WAREHOUSE' THEN 0 ELSE 1 END,id LIMIT 1")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }

  private static LockedOrder best(Connection connection, String side, String ordering)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT o.id,o.settlement_id,o.side,o.commodity_key,o.remaining_quantity,o.unit_price_minor,o.reservation_id,o.escrow_account_id,o.status FROM market_orders o WHERE o.side=? AND o.status IN ('OPEN','PARTIAL') AND EXISTS(SELECT 1 FROM road_nodes n WHERE n.city_id=o.settlement_id AND n.integrity>=10) AND EXISTS(SELECT 1 FROM market_orders s WHERE s.side='SELL' AND s.status IN ('OPEN','PARTIAL') AND s.commodity_key=o.commodity_key AND s.unit_price_minor<=o.unit_price_minor AND s.settlement_id<>o.settlement_id AND EXISTS(SELECT 1 FROM road_nodes sn WHERE sn.city_id=s.settlement_id AND sn.integrity>=10)) ORDER BY "
                + ordering
                + " LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setString(1, side);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? mapLocked(result) : null;
      }
    }
  }

  private static LockedOrder bestSell(Connection connection, String commodity, long maximum)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT o.id,o.settlement_id,o.side,o.commodity_key,o.remaining_quantity,o.unit_price_minor,o.reservation_id,o.escrow_account_id,o.status FROM market_orders o WHERE o.side='SELL' AND o.commodity_key=? AND o.unit_price_minor<=? AND o.status IN ('OPEN','PARTIAL') AND EXISTS(SELECT 1 FROM road_nodes n WHERE n.city_id=o.settlement_id AND n.integrity>=10) ORDER BY o.unit_price_minor,coalesce((SELECT max(de.trade_bonus) FROM district_effects de WHERE de.city_id=o.settlement_id),0) DESC,o.created_at,o.id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setString(1, commodity);
      statement.setLong(2, maximum);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? mapLocked(result) : null;
      }
    }
  }

  private static LockedOrder lockOrder(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,settlement_id,side,commodity_key,remaining_quantity,unit_price_minor,reservation_id,escrow_account_id,status FROM market_orders WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("market order not found");
        return mapLocked(result);
      }
    }
  }

  private static LockedOrder mapLocked(ResultSet result) throws SQLException {
    return new LockedOrder(
        result.getObject("id", UUID.class),
        result.getObject("settlement_id", UUID.class),
        MarketEngine.Side.valueOf(result.getString("side")),
        result.getString("commodity_key"),
        result.getLong("remaining_quantity"),
        result.getLong("unit_price_minor"),
        result.getObject("reservation_id", UUID.class),
        result.getObject("escrow_account_id", UUID.class),
        result.getString("status"));
  }

  private static UUID ensureWarehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      select.setObject(1, city);
      try (ResultSet result = select.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO warehouses(id,city_id,capacity,status,version) VALUES(?,?,100000,'ACTIVE',0) ON CONFLICT DO NOTHING")) {
      insert.setObject(1, id);
      insert.setObject(2, city);
      insert.executeUpdate();
    }
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      select.setObject(1, city);
      try (ResultSet result = select.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    throw new DomainException("could not create warehouse");
  }

  private static WarehouseSnapshot loadWarehouse(Connection connection, UUID warehouse)
      throws SQLException {
    long capacity = capacity(connection, warehouse);
    List<Stock> stock = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT commodity_key,available_quantity,reserved_quantity FROM warehouse_stock WHERE warehouse_id=? ORDER BY commodity_key")) {
      statement.setObject(1, warehouse);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          stock.add(new Stock(result.getString(1), result.getLong(2), result.getLong(3)));
      }
    }
    return new WarehouseSnapshot(warehouse, capacity, List.copyOf(stock));
  }

  private static long capacity(Connection connection, UUID warehouse) throws SQLException {
    return scalar(connection, "SELECT capacity FROM warehouses WHERE id=?", warehouse);
  }

  private static long usedCapacity(Connection connection, UUID warehouse) throws SQLException {
    return scalar(
        connection,
        "SELECT COALESCE(sum(available_quantity+reserved_quantity),0) FROM warehouse_stock WHERE warehouse_id=?",
        warehouse);
  }

  private static long available(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT available_quantity FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=?")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  private static void lockStock(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity) VALUES(?,?,0,0) ON CONFLICT DO NOTHING")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=? FOR UPDATE")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      statement.executeQuery().close();
    }
  }

  private static void updateStock(
      Connection connection,
      UUID warehouse,
      String commodity,
      long availableDelta,
      long reservedDelta)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity+?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity+?>=0 AND reserved_quantity+?>=0")) {
      statement.setLong(1, availableDelta);
      statement.setLong(2, reservedDelta);
      statement.setObject(3, warehouse);
      statement.setString(4, commodity);
      statement.setLong(5, availableDelta);
      statement.setLong(6, reservedDelta);
      if (statement.executeUpdate() != 1) throw new DomainException("stock invariant violated");
    }
  }

  private static void addAvailable(
      Connection connection, UUID warehouse, String commodity, long quantity) throws SQLException {
    lockStock(connection, warehouse, commodity);
    updateStock(connection, warehouse, commodity, quantity, 0);
  }

  private static UUID account(Connection connection, String ownerType, UUID owner, boolean lock)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type=? AND owner_id=?"
                + (lock ? " FOR UPDATE" : ""))) {
      statement.setString(1, ownerType);
      statement.setObject(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("account not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection connection, UUID account) throws SQLException {
    return scalar(connection, "SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE", account);
  }

  private static void setBalance(Connection connection, UUID account, long balance)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?")) {
      statement.setLong(1, balance);
      statement.setObject(2, account);
      statement.executeUpdate();
    }
  }

  private static void insertAccount(
      Connection connection, UUID id, String ownerType, UUID owner, long balance)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,?,?,?,0)")) {
      statement.setObject(1, id);
      statement.setString(2, ownerType);
      statement.setObject(3, owner);
      statement.setLong(4, balance);
      statement.executeUpdate();
    }
  }

  private static void refundEscrow(
      Connection connection, LockedOrder order, UUID actor, Instant now) throws SQLException {
    long amount = balance(connection, order.escrow);
    if (amount == 0) return;
    UUID treasury = account(connection, "CITY", order.city, true);
    long cityBalance = balance(connection, treasury);
    setBalance(connection, order.escrow, 0);
    setBalance(connection, treasury, Math.addExact(cityBalance, amount));
    ledger(
        connection,
        treasury,
        actor,
        "MARKET_ESCROW_REFUND",
        amount,
        cityBalance + amount,
        order.id,
        UUID.randomUUID(),
        now);
  }

  private static UUID reservationWarehouse(Connection connection, UUID reservation)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT warehouse_id FROM stock_reservations WHERE id=? FOR UPDATE")) {
      statement.setObject(1, reservation);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("stock reservation not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void consumeReservation(
      Connection connection, UUID reservation, long quantity, boolean complete)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE stock_reservations SET consumed=consumed+?,status=?,version=version+1 WHERE id=? AND consumed+?<=quantity")) {
      statement.setLong(1, quantity);
      statement.setString(2, complete ? "CONSUMED" : "ACTIVE");
      statement.setObject(3, reservation);
      statement.setLong(4, quantity);
      if (statement.executeUpdate() != 1)
        throw new DomainException("reservation invariant violated");
    }
  }

  private static void closeReservation(Connection connection, UUID reservation, String status)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE stock_reservations SET status=?,version=version+1 WHERE id=?")) {
      statement.setString(1, status);
      statement.setObject(2, reservation);
      statement.executeUpdate();
    }
  }

  private static void setOrderState(
      Connection connection, UUID order, long remaining, String status) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE market_orders SET remaining_quantity=?,status=?,version=version+1 WHERE id=?")) {
      statement.setLong(1, remaining);
      statement.setString(2, status);
      statement.setObject(3, order);
      statement.executeUpdate();
    }
  }

  private static OrderSnapshot byIdempotency(Connection connection, UUID key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at FROM market_orders WHERE idempotency_key=?")) {
      statement.setObject(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? mapOrder(result) : null;
      }
    }
  }

  private static OrderSnapshot loadOrder(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at FROM market_orders WHERE id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("market order not found");
        return mapOrder(result);
      }
    }
  }

  private static OrderSnapshot mapOrder(ResultSet result) throws SQLException {
    return new OrderSnapshot(
        result.getObject("id", UUID.class),
        result.getObject("settlement_id", UUID.class),
        MarketEngine.Side.valueOf(result.getString("side").toUpperCase(Locale.ROOT)),
        result.getString("commodity_key"),
        result.getLong("quantity"),
        result.getLong("remaining_quantity"),
        result.getLong("unit_price_minor"),
        result.getString("status"),
        result.getTimestamp("created_at").toInstant());
  }

  private static long scalar(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("required row not found");
        return result.getLong(1);
      }
    }
  }

  private static void requireRole(Connection connection, UUID city, UUID actor, Set<String> allowed)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=? FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !allowed.contains(result.getString(1)))
          throw new DomainException("settlement role does not allow this economy action");
      }
    }
  }

  private static void ledger(
      Connection connection,
      UUID account,
      UUID actor,
      String type,
      long amount,
      long after,
      UUID reference,
      UUID idempotency,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, account);
      statement.setObject(3, actor);
      statement.setString(4, type);
      statement.setLong(5, amount);
      statement.setLong(6, after);
      statement.setObject(7, reference);
      statement.setObject(8, idempotency);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void audit(
      Connection connection, UUID actor, String action, String type, UUID id, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,occurred_at) VALUES(?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setString(4, type);
      statement.setObject(5, id);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void recordPrice(
      Connection connection,
      UUID settlement,
      String commodity,
      long unitPrice,
      long quantity,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO price_history(id,settlement_id,commodity_key,unit_price_minor,quantity,occurred_at) VALUES(?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, settlement);
      statement.setString(3, commodity);
      statement.setLong(4, unitPrice);
      statement.setLong(5, quantity);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
    long volume;
    long median;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(quantity),0),coalesce(percentile_cont(0.5) WITHIN GROUP(ORDER BY unit_price_minor),0)::bigint FROM price_history WHERE settlement_id=? AND commodity_key=? AND occurred_at>=?")) {
      statement.setObject(1, settlement);
      statement.setString(2, commodity);
      statement.setTimestamp(3, Timestamp.from(now.minusSeconds(72 * 3_600L)));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        volume = result.getLong(1);
        median = result.getLong(2);
      }
    }
    if (volume < 10 || median <= 0) return;
    Long previous = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT unit_price_minor FROM reference_prices WHERE settlement_id=? AND commodity_key=? FOR UPDATE")) {
      statement.setObject(1, settlement);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) previous = result.getLong(1);
      }
    }
    long bounded = median;
    if (previous != null) {
      long movement = Math.max(1, Math.round(previous * 0.08));
      bounded = Math.max(previous - movement, Math.min(previous + movement, median));
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO reference_prices(settlement_id,commodity_key,unit_price_minor,sample_volume,calculated_at,version) VALUES(?,?,?,?,?,0) ON CONFLICT(settlement_id,commodity_key) DO UPDATE SET unit_price_minor=excluded.unit_price_minor,sample_volume=excluded.sample_volume,calculated_at=excluded.calculated_at,version=reference_prices.version+1")) {
      statement.setObject(1, settlement);
      statement.setString(2, commodity);
      statement.setLong(3, bounded);
      statement.setLong(4, volume);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private record LockedOrder(
      UUID id,
      UUID city,
      MarketEngine.Side side,
      String commodity,
      long remaining,
      long unitPrice,
      UUID reservation,
      UUID escrow,
      String status) {
    boolean open() {
      return status.equals("OPEN") || status.equals("PARTIAL");
    }
  }
}
