package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.ContractGateway;

public final class PostgresContractGateway implements ContractGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "TREASURER");
  private final TransactionalStore store;

  public PostgresContractGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public ContractSnapshot postDelivery(
      UUID city,
      UUID actor,
      UUID destinationWarehouse,
      String commodity,
      long quantity,
      long rewardMinor,
      Instant deadline,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          if (quantity <= 0 || rewardMinor <= 0 || !deadline.isAfter(now))
            throw new DomainException("invalid contract quantity, reward or deadline");
          if (!commodity.matches("[a-z0-9_.:-]{2,96}"))
            throw new DomainException("invalid commodity key");
          requireRole(connection, city, actor);
          ContractSnapshot existing = byIdempotency(connection, idempotency);
          if (existing != null) return existing;
          requireWarehouseOwner(connection, destinationWarehouse, city);
          UUID treasury = account(connection, "CITY", city, true, now);
          long balance = balance(connection, treasury);
          if (balance < rewardMinor) throw new DomainException("insufficient contract funding");
          UUID contract = UUID.randomUUID();
          UUID escrow = UUID.randomUUID();
          setBalance(connection, treasury, balance - rewardMinor);
          insertAccount(connection, escrow, "CONTRACT", contract, rewardMinor);
          ledger(
              connection,
              treasury,
              actor,
              "CONTRACT_ESCROW_DEBIT",
              -rewardMinor,
              balance - rewardMinor,
              contract,
              idempotency,
              now);
          String terms =
              "{\"commodity\":\""
                  + commodity
                  + "\",\"quantity\":"
                  + quantity
                  + ",\"rewardMinor\":"
                  + rewardMinor
                  + "}";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO contracts(id,issuer_id,contract_type,terms,status,deadline,version,issuer_city_id,escrow_account_id,idempotency_key,created_at) VALUES(? ,?,'DELIVERY',?::jsonb,'POSTED',?,0,?,?,?,?)")) {
            statement.setObject(1, contract);
            statement.setObject(2, actor);
            statement.setString(3, terms);
            statement.setTimestamp(4, Timestamp.from(deadline));
            statement.setObject(5, city);
            statement.setObject(6, escrow);
            statement.setObject(7, idempotency);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO delivery_contract_terms(contract_id,destination_warehouse_id,commodity_key,quantity,reward_minor) VALUES(?,?,?,?,?)")) {
            statement.setObject(1, contract);
            statement.setObject(2, destinationWarehouse);
            statement.setString(3, commodity);
            statement.setLong(4, quantity);
            statement.setLong(5, rewardMinor);
            statement.executeUpdate();
          }
          audit(connection, actor, "CONTRACT_POSTED", contract, now);
          return load(connection, contract);
        });
  }

  @Override
  public ContractSnapshot accept(UUID contract, UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          ContractSnapshot current = lock(connection, contract);
          if (current.status().equals("ACCEPTED") && player.equals(current.assignee()))
            return current;
          if (!current.status().equals("POSTED"))
            throw new DomainException("contract is not available");
          if (!current.deadline().isAfter(now)) throw new DomainException("contract has expired");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE contracts SET assignee_id=?,status='ACCEPTED',version=version+1 WHERE id=?")) {
            statement.setObject(1, player);
            statement.setObject(2, contract);
            statement.executeUpdate();
          }
          account(connection, "PLAYER", player, false, now);
          audit(connection, player, "CONTRACT_ACCEPTED", contract, now);
          return load(connection, contract);
        });
  }

  @Override
  public ContractSnapshot deliver(UUID contract, UUID player, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          if (completionExists(connection, contract, idempotency))
            return load(connection, contract);
          ContractSnapshot current = lock(connection, contract);
          if (!player.equals(current.assignee()))
            throw new DomainException("contract is assigned to another player");
          if (!current.status().equals("ACCEPTED") && !current.status().equals("IN_PROGRESS"))
            throw new DomainException("contract cannot be delivered");
          if (current.deadline() != null && now.isAfter(current.deadline()))
            throw new DomainException("contract deadline passed");
          Terms terms = terms(connection, contract);
          UUID source = playerWarehouse(connection, player);
          lockStock(connection, source, current.commodity());
          if (available(connection, source, current.commodity()) < current.quantity())
            throw new DomainException(
                "contract goods are not available in your settlement warehouse");
          lockStock(connection, terms.destination, current.commodity());
          changeStock(connection, source, current.commodity(), -current.quantity());
          changeStock(connection, terms.destination, current.commodity(), current.quantity());

          UUID escrow = contractEscrow(connection, contract);
          long escrowBalance = balance(connection, escrow);
          if (escrowBalance != current.rewardMinor())
            throw new DomainException("contract escrow invariant violated");
          UUID playerAccount = account(connection, "PLAYER", player, true, now);
          long playerBalance = balance(connection, playerAccount);
          setBalance(connection, escrow, 0);
          setBalance(connection, playerAccount, Math.addExact(playerBalance, escrowBalance));
          ledger(
              connection,
              playerAccount,
              player,
              "CONTRACT_REWARD",
              escrowBalance,
              playerBalance + escrowBalance,
              contract,
              idempotency,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO contract_completions(contract_id,idempotency_key,completed_at) VALUES(?,?,?)")) {
            statement.setObject(1, contract);
            statement.setObject(2, idempotency);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE contracts SET status='PAID',version=version+1 WHERE id=?")) {
            statement.setObject(1, contract);
            statement.executeUpdate();
          }
          audit(connection, player, "CONTRACT_PAID", contract, now);
          outbox(connection, contract, "ContractPaid", now);
          return load(connection, contract);
        });
  }

  @Override
  public List<ContractSnapshot> available(Instant now) {
    return store.inTransaction(
        connection -> {
          List<ContractSnapshot> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT c.id,c.issuer_id,c.assignee_id,c.contract_type,t.commodity_key,t.quantity,t.reward_minor,c.status,c.deadline FROM contracts c JOIN delivery_contract_terms t ON t.contract_id=c.id WHERE c.status IN ('POSTED','ACCEPTED','IN_PROGRESS') AND (c.deadline IS NULL OR c.deadline>?) ORDER BY c.deadline,c.created_at LIMIT 100")) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(map(result));
            }
          }
          return List.copyOf(values);
        });
  }

  private static ContractSnapshot byIdempotency(Connection connection, UUID key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(selectContract() + " WHERE c.idempotency_key=?")) {
      statement.setObject(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? map(result) : null;
      }
    }
  }

  private static ContractSnapshot load(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(selectContract() + " WHERE c.id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("contract not found");
        return map(result);
      }
    }
  }

  private static ContractSnapshot lock(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(selectContract() + " WHERE c.id=? FOR UPDATE OF c")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("contract not found");
        return map(result);
      }
    }
  }

  private static String selectContract() {
    return "SELECT c.id,c.issuer_id,c.assignee_id,c.contract_type,t.commodity_key,t.quantity,t.reward_minor,c.status,c.deadline FROM contracts c JOIN delivery_contract_terms t ON t.contract_id=c.id";
  }

  private static ContractSnapshot map(ResultSet result) throws SQLException {
    Timestamp deadline = result.getTimestamp(9);
    return new ContractSnapshot(
        result.getObject(1, UUID.class),
        result.getObject(2, UUID.class),
        result.getObject(3, UUID.class),
        result.getString(4),
        result.getString(5),
        result.getLong(6),
        result.getLong(7),
        result.getString(8),
        deadline == null ? null : deadline.toInstant());
  }

  private static Terms terms(Connection connection, UUID contract) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT destination_warehouse_id FROM delivery_contract_terms WHERE contract_id=? FOR SHARE")) {
      statement.setObject(1, contract);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("contract terms not found");
        return new Terms(result.getObject(1, UUID.class));
      }
    }
  }

  private static UUID playerWarehouse(Connection connection, UUID player) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT w.id FROM city_members m JOIN warehouses w ON w.city_id=m.city_id AND w.status='ACTIVE' WHERE m.player_id=? FOR UPDATE OF w")) {
      statement.setObject(1, player);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("player has no settlement warehouse");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static UUID contractEscrow(Connection connection, UUID contract) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT escrow_account_id FROM contracts WHERE id=? FOR SHARE")) {
      statement.setObject(1, contract);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("contract escrow not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static boolean completionExists(Connection connection, UUID contract, UUID key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM contract_completions WHERE contract_id=? AND idempotency_key=?")) {
      statement.setObject(1, contract);
      statement.setObject(2, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static UUID account(
      Connection connection, String type, UUID owner, boolean lock, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type=? AND owner_id=?"
                + (lock ? " FOR UPDATE" : ""))) {
      statement.setString(1, type);
      statement.setObject(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    UUID id = UUID.randomUUID();
    insertAccount(connection, id, type, owner, 0);
    return id;
  }

  private static void insertAccount(
      Connection connection, UUID id, String type, UUID owner, long balance) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,?,?,?,0)")) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setObject(3, owner);
      statement.setLong(4, balance);
      statement.executeUpdate();
    }
  }

  private static long balance(Connection connection, UUID account) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE")) {
      statement.setObject(1, account);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("account missing");
        return result.getLong(1);
      }
    }
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

  private static void requireRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=? FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !ROLES.contains(result.getString(1)))
          throw new DomainException("settlement role does not allow contract funding");
      }
    }
  }

  private static void requireWarehouseOwner(Connection connection, UUID warehouse, UUID city)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM warehouses WHERE id=? AND city_id=? AND status='ACTIVE' FOR SHARE")) {
      statement.setObject(1, warehouse);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("destination warehouse not owned by city");
      }
    }
  }

  private static void lockStock(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,?,0,0,0) ON CONFLICT DO NOTHING")) {
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

  private static void changeStock(
      Connection connection, UUID warehouse, String commodity, long delta) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity+?>=0")) {
      statement.setLong(1, delta);
      statement.setObject(2, warehouse);
      statement.setString(3, commodity);
      statement.setLong(4, delta);
      if (statement.executeUpdate() != 1) throw new DomainException("stock invariant violated");
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

  private static void audit(Connection connection, UUID actor, String action, UUID id, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,occurred_at) VALUES(?,?,?,'CONTRACT',?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setObject(4, id);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void outbox(Connection connection, UUID id, String event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'CONTRACT',?,?, '{}'::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, id);
      statement.setString(3, event);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private record Terms(UUID destination) {}
}
