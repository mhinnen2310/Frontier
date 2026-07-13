package nl.frontier.persistence;

import java.nio.charset.StandardCharsets;
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
import nl.frontier.economy.FinanceGateway;

public final class PostgresFinanceGateway implements FinanceGateway {
  private static final Set<String> FINANCE_ROLES = Set.of("MAYOR", "TREASURER");
  private final TransactionalStore store;

  public PostgresFinanceGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public long playerBalance(UUID player, Instant now) {
    return store.inTransaction(
        connection -> balance(connection, account(connection, "PLAYER", player, now)));
  }

  @Override
  public TransferReceipt payPlayer(
      UUID sender, UUID recipient, long amountMinor, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID source = account(connection, "PLAYER", sender, now);
          UUID destination = account(connection, "PLAYER", recipient, now);
          return transfer(
              connection,
              "PLAYER_PAYMENT",
              source,
              destination,
              sender,
              amountMinor,
              idempotency,
              "Player payment",
              now);
        });
  }

  @Override
  public TransferReceipt depositToSettlement(
      UUID player, UUID settlement, long amountMinor, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveSettlement(connection, settlement);
          requireMember(connection, settlement, player);
          UUID source = account(connection, "PLAYER", player, now);
          UUID destination = account(connection, "CITY", settlement, now);
          TransferReceipt receipt =
              transfer(
                  connection,
                  "TREASURY_DEPOSIT",
                  source,
                  destination,
                  player,
                  amountMinor,
                  idempotency,
                  "Member treasury deposit",
                  now);
          audit(connection, player, "TREASURY_DEPOSIT", settlement, amountMinor, now);
          return receipt;
        });
  }

  @Override
  public TransferReceipt withdrawFromSettlement(
      UUID settlement, UUID actor, long amountMinor, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveSettlement(connection, settlement);
          requireFinanceRole(connection, settlement, actor);
          UUID source = account(connection, "CITY", settlement, now);
          UUID destination = account(connection, "PLAYER", actor, now);
          TransferReceipt receipt =
              transfer(
                  connection,
                  "TREASURY_WITHDRAWAL",
                  source,
                  destination,
                  actor,
                  amountMinor,
                  idempotency,
                  "Authorized treasury withdrawal",
                  now);
          audit(connection, actor, "TREASURY_WITHDRAWAL", settlement, amountMinor, now);
          return receipt;
        });
  }

  @Override
  public TransferReceipt payFromSettlement(
      UUID settlement,
      UUID actor,
      UUID recipient,
      long amountMinor,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireActiveSettlement(connection, settlement);
          requireFinanceRole(connection, settlement, actor);
          UUID source = account(connection, "CITY", settlement, now);
          UUID destination = account(connection, "PLAYER", recipient, now);
          TransferReceipt receipt =
              transfer(
                  connection,
                  "SETTLEMENT_PAYMENT",
                  source,
                  destination,
                  actor,
                  amountMinor,
                  idempotency,
                  "Settlement payment to player",
                  now);
          audit(connection, actor, "SETTLEMENT_PAYMENT", settlement, amountMinor, now);
          return receipt;
        });
  }

  @Override
  public List<LedgerLine> settlementAudit(UUID settlement, UUID actor, int limit) {
    return store.inTransaction(
        connection -> {
          requireFinanceRole(connection, settlement, actor);
          UUID account = account(connection, "CITY", settlement, Instant.now());
          List<LedgerLine> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,entry_type,amount_minor,balance_after_minor,actor_id,counterparty_account_id,description,occurred_at FROM ledger_entries WHERE account_id=? ORDER BY occurred_at DESC,id LIMIT ?")) {
            statement.setObject(1, account);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                values.add(
                    new LedgerLine(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getLong(3),
                        result.getLong(4),
                        result.getObject(5, UUID.class),
                        result.getObject(6, UUID.class),
                        result.getString(7),
                        result.getTimestamp(8).toInstant()));
              }
            }
          }
          return List.copyOf(values);
        });
  }

  private static TransferReceipt transfer(
      Connection connection,
      String type,
      UUID source,
      UUID destination,
      UUID actor,
      long amount,
      UUID idempotency,
      String description,
      Instant now)
      throws SQLException {
    TransferReceipt existing = byIdempotency(connection, idempotency);
    if (existing != null) return existing;
    if (amount <= 0) throw new DomainException("amount must be positive");
    lockAccounts(connection, source, destination);
    long sourceBalance = balance(connection, source);
    long destinationBalance = balance(connection, destination);
    if (sourceBalance < amount) throw new DomainException("insufficient balance");
    long sourceAfter = sourceBalance - amount;
    long destinationAfter = Math.addExact(destinationBalance, amount);
    updateBalance(connection, source, sourceAfter);
    updateBalance(connection, destination, destinationAfter);
    UUID transfer = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO financial_transfers(id,transfer_type,source_account_id,destination_account_id,actor_id,amount_minor,idempotency_key,description,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
      statement.setObject(1, transfer);
      statement.setString(2, type);
      statement.setObject(3, source);
      statement.setObject(4, destination);
      statement.setObject(5, actor);
      statement.setLong(6, amount);
      statement.setObject(7, idempotency);
      statement.setString(8, description);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.executeUpdate();
    }
    ledger(
        connection,
        source,
        actor,
        type + "_DEBIT",
        -amount,
        sourceAfter,
        transfer,
        derived(idempotency, "debit"),
        destination,
        description,
        now);
    ledger(
        connection,
        destination,
        actor,
        type + "_CREDIT",
        amount,
        destinationAfter,
        transfer,
        derived(idempotency, "credit"),
        source,
        description,
        now);
    return new TransferReceipt(
        transfer,
        type,
        owner(connection, source),
        owner(connection, destination),
        amount,
        sourceAfter,
        destinationAfter,
        now);
  }

  private static TransferReceipt byIdempotency(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.id,t.transfer_type,sa.owner_id,da.owner_id,t.amount_minor,sa.balance_minor,da.balance_minor,t.occurred_at FROM financial_transfers t JOIN accounts sa ON sa.id=t.source_account_id JOIN accounts da ON da.id=t.destination_account_id WHERE t.idempotency_key=?")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new TransferReceipt(
                result.getObject(1, UUID.class),
                result.getString(2),
                result.getObject(3, UUID.class),
                result.getObject(4, UUID.class),
                result.getLong(5),
                result.getLong(6),
                result.getLong(7),
                result.getTimestamp(8).toInstant())
            : null;
      }
    }
  }

  private static UUID account(Connection connection, String type, UUID owner, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,?,?,0,0) ON CONFLICT(owner_type,owner_id) DO NOTHING")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, type);
      statement.setObject(3, owner);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM accounts WHERE owner_type=? AND owner_id=?")) {
      statement.setString(1, type);
      statement.setObject(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("financial account not found");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void lockAccounts(Connection connection, UUID first, UUID second)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      try (ResultSet result = statement.executeQuery()) {
        int count = 0;
        while (result.next()) count++;
        if (count != 2) throw new DomainException("financial account not found");
      }
    }
  }

  private static long balance(Connection connection, UUID account) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT balance_minor FROM accounts WHERE id=?")) {
      statement.setObject(1, account);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("financial account not found");
        return result.getLong(1);
      }
    }
  }

  private static UUID owner(Connection connection, UUID account) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT owner_id FROM accounts WHERE id=?")) {
      statement.setObject(1, account);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void updateBalance(Connection connection, UUID account, long balance)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?")) {
      statement.setLong(1, balance);
      statement.setObject(2, account);
      statement.executeUpdate();
    }
  }

  private static void ledger(
      Connection connection,
      UUID account,
      UUID actor,
      String type,
      long amount,
      long balanceAfter,
      UUID reference,
      UUID idempotency,
      UUID counterparty,
      String description,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,counterparty_account_id,description) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, account);
      statement.setObject(3, actor);
      statement.setString(4, type);
      statement.setLong(5, amount);
      statement.setLong(6, balanceAfter);
      statement.setObject(7, reference);
      statement.setObject(8, idempotency);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.setObject(10, counterparty);
      statement.setString(11, description);
      statement.executeUpdate();
    }
  }

  private static UUID derived(UUID idempotency, String suffix) {
    return UUID.nameUUIDFromBytes((idempotency + ":" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static void requireMember(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("you are not a settlement member");
      }
    }
  }

  private static void requireActiveSettlement(Connection connection, UUID city)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT lifecycle_status FROM cities WHERE id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("settlement not found");
        if (!result.getString(1).equals("ACTIVE"))
          throw new DomainException("settlement treasury is frozen while inactive");
      }
    }
  }

  private static void requireFinanceRole(Connection connection, UUID city, UUID actor)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !FINANCE_ROLES.contains(result.getString(1)))
          throw new DomainException("settlement role does not allow treasury spending");
      }
    }
  }

  private static void audit(
      Connection connection, UUID actor, String action, UUID settlement, long amount, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,?,'CITY',?,jsonb_build_object('amountMinor',?),'transactional treasury transfer',?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setObject(4, settlement);
      statement.setLong(5, amount);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
  }
}
