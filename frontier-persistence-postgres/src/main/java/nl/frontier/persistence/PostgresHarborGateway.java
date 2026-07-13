package nl.frontier.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.economy.HarborGateway;
import nl.frontier.economy.HarborPolicy;

public final class PostgresHarborGateway implements HarborGateway {
  private static final UUID HARBOR_CITY = named("frontier:harbor:city");
  private static final UUID HARBOR_ACTOR = named("frontier:harbor:system-actor");
  private final TransactionalStore store;
  private final HarborPolicy policy;

  public PostgresHarborGateway(TransactionalStore store) {
    this(store, HarborPolicy.defaults());
  }

  public PostgresHarborGateway(TransactionalStore store, HarborPolicy policy) {
    this.store = store;
    this.policy = policy;
  }

  @Override
  public HarborSnapshot bootstrap(UUID world, int chunkX, int chunkZ, Instant now) {
    return store.inTransaction(
        connection -> {
          if (!exists(connection, "SELECT 1 FROM harbor_state WHERE singleton")) {
            insertHarbor(connection, world, chunkX, chunkZ, now);
          }
          refreshBudget(connection, now);
          refreshMarket(connection, now);
          return snapshot(connection);
        });
  }

  @Override
  public TutorialSnapshot onboard(UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          requireHarbor(connection);
          account(connection, "PLAYER", player, 0);
          boolean first =
              !exists(connection, "SELECT 1 FROM player_tutorials WHERE player_id=?", player);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO player_tutorials(player_id,stage,first_seen_at,updated_at,version) VALUES(?,'HARBOR_INTRO',?,?,0) ON CONFLICT(player_id) DO UPDATE SET updated_at=excluded.updated_at,version=player_tutorials.version+1")) {
            statement.setObject(1, player);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
          }
          ensureJobs(connection, player, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT t.stage,a.balance_minor FROM player_tutorials t JOIN accounts a ON a.owner_type='PLAYER' AND a.owner_id=t.player_id WHERE t.player_id=?")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              return new TutorialSnapshot(player, result.getString(1), first, result.getLong(2));
            }
          }
        });
  }

  @Override
  public List<StarterJob> jobs(UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          requireHarbor(connection);
          account(connection, "PLAYER", player, 0);
          ensureJobs(connection, player, now);
          List<StarterJob> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,job_type,description,reward_minor,status,expires_at FROM harbor_jobs WHERE player_id=? AND expires_at>? ORDER BY reward_minor,id")) {
            statement.setObject(1, player);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                values.add(
                    new StarterJob(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getString(3),
                        result.getLong(4),
                        result.getString(5),
                        result.getTimestamp(6).toInstant()));
              }
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public JobReceipt completeJob(UUID player, UUID job, Instant now) {
    return store.inTransaction(
        connection -> {
          requireHarbor(connection);
          refreshBudget(connection, now);
          JobRow row = lockJob(connection, job);
          if (!row.player.equals(player))
            throw new DomainException("starter job belongs to another player");
          if (row.status.equals("COMPLETED")) return receipt(connection, row);
          if (!row.status.equals("POSTED") || !row.expiresAt.isAfter(now))
            throw new DomainException("starter job is no longer available");
          if (completedRewardToday(connection, player, now) + row.reward
              > policy.maximumPlayerRewardPerDayMinor())
            throw new DomainException("Frontier Harbor player daily reward cap is exhausted");
          HarborRow harbor = lockHarbor(connection);
          if (harbor.spent + row.reward > harbor.budget)
            throw new DomainException("Frontier Harbor daily job budget is exhausted");
          UUID source = account(connection, "CITY", harbor.city, 0);
          UUID destination = account(connection, "PLAYER", player, 0);
          lockAccounts(connection, source, destination);
          long sourceBalance = balance(connection, source);
          long playerBalance = balance(connection, destination);
          if (sourceBalance < row.reward) throw new DomainException("Frontier Harbor is insolvent");
          setBalance(connection, source, sourceBalance - row.reward);
          setBalance(connection, destination, Math.addExact(playerBalance, row.reward));
          UUID transfer = named("harbor-job-transfer:" + job);
          UUID idempotency = named("harbor-job:" + job);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO financial_transfers(id,transfer_type,source_account_id,destination_account_id,actor_id,amount_minor,idempotency_key,description,occurred_at) VALUES(?,'HARBOR_STARTER_JOB',?,?,?,?,?,'Frontier Harbor starter contract',?) ON CONFLICT(idempotency_key) DO NOTHING")) {
            statement.setObject(1, transfer);
            statement.setObject(2, source);
            statement.setObject(3, destination);
            statement.setObject(4, player);
            statement.setLong(5, row.reward);
            statement.setObject(6, idempotency);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          ledger(
              connection,
              source,
              player,
              "HARBOR_JOB_DEBIT",
              -row.reward,
              sourceBalance - row.reward,
              transfer,
              named(idempotency + ":debit"),
              destination,
              now);
          ledger(
              connection,
              destination,
              player,
              "HARBOR_JOB_REWARD",
              row.reward,
              playerBalance + row.reward,
              transfer,
              named(idempotency + ":credit"),
              source,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE harbor_jobs SET status='COMPLETED',completed_at=?,version=version+1 WHERE id=? AND status='POSTED'")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, job);
            if (statement.executeUpdate() != 1) throw new DomainException("starter job raced");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE harbor_state SET spent_today_minor=spent_today_minor+?,version=version+1 WHERE singleton")) {
            statement.setLong(1, row.reward);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE player_tutorials SET stage='CONTRACT_COMPLETED',updated_at=?,version=version+1 WHERE player_id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, player);
            statement.executeUpdate();
          }
          audit(connection, player, "HARBOR_STARTER_JOB_COMPLETED", job, row.reward, now);
          return new JobReceipt(
              job,
              row.reward,
              playerBalance + row.reward,
              harbor.budget - harbor.spent - row.reward);
        });
  }

  @Override
  public HarborSnapshot refresh(Instant now) {
    return store.inTransaction(
        connection -> {
          requireHarbor(connection);
          refreshBudget(connection, now);
          refreshMarket(connection, now);
          return snapshot(connection);
        });
  }

  private void insertHarbor(Connection connection, UUID world, int chunkX, int chunkZ, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO cities(id,name,owner_id,level,population,prosperity,civilization,created_at,version,settlement_kind) VALUES(?,'Frontier Harbor',?,4,250,75,60,?,0,'SERVER')")) {
      statement.setObject(1, HARBOR_CITY);
      statement.setObject(2, HARBOR_ACTOR);
      statement.setTimestamp(3, Timestamp.from(now));
      statement.executeUpdate();
    }
    execute(
        connection,
        "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'MAYOR',?)",
        HARBOR_CITY,
        HARBOR_ACTOR,
        now);
    execute(
        connection,
        "INSERT INTO city_claims(city_id,world_id,chunk_x,chunk_z,state,influence) VALUES(?,?,?,?,'CAPITAL',100)",
        HARBOR_CITY,
        world,
        chunkX,
        chunkZ);
    UUID account = account(connection, "CITY", HARBOR_CITY, policy.initialCapitalMinor());
    ledger(
        connection,
        account,
        null,
        "HARBOR_INITIAL_CAPITAL",
        policy.initialCapitalMinor(),
        policy.initialCapitalMinor(),
        HARBOR_CITY,
        named("harbor:initial-capital"),
        null,
        now);
    UUID warehouse = named("frontier:harbor:warehouse");
    execute(
        connection,
        "INSERT INTO warehouses(id,city_id,capacity,status,version) VALUES(?,?,1000000,'ACTIVE',0)",
        warehouse,
        HARBOR_CITY);
    for (var entry : policy.initialStock().entrySet())
      stock(connection, warehouse, entry.getKey(), entry.getValue());
    UUID road = named("frontier:harbor:road-node");
    execute(
        connection,
        "INSERT INTO road_nodes(id,city_id,world_id,x,y,z,node_type,integrity,version) VALUES(?,?,?,?,64,?,'HARBOR',100,0)",
        road,
        HARBOR_CITY,
        world,
        chunkX * 16 + 8,
        chunkZ * 16 + 8);
    insertWorker(connection, "CLERK", 80);
    insertWorker(connection, "MERCHANT", 70);
    insertWorker(connection, "COURIER", 60);
    execute(
        connection,
        "INSERT INTO harbor_state(singleton,city_id,system_actor_id,daily_budget_minor,spent_today_minor,budget_resets_at,version) VALUES(true,?,?,?,0,?,0)",
        HARBOR_CITY,
        HARBOR_ACTOR,
        policy.dailyBudgetMinor(),
        nextDay(now));
    execute(
        connection,
        "INSERT INTO city_simulation_state(city_id,next_cycle_at) VALUES(?,?)",
        HARBOR_CITY,
        nextDay(now));
    execute(
        connection,
        "INSERT INTO city_world_simulation_state(city_id,region_key,next_cycle_at) VALUES(?,?,?)",
        HARBOR_CITY,
        world + ":harbor",
        nextDay(now));
    audit(connection, HARBOR_ACTOR, "FRONTIER_HARBOR_BOOTSTRAPPED", HARBOR_CITY, 0, now);
  }

  private static void insertWorker(Connection connection, String profession, int skill)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO workers(id,city_id,profession,skill,state,salary_minor,version,experience) VALUES(?,?,?,?,'IDLE',0,0,0)",
        named("frontier:harbor:worker:" + profession),
        HARBOR_CITY,
        profession,
        skill);
  }

  private void refreshBudget(Connection connection, Instant now) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE harbor_state SET daily_budget_minor=?,version=version+1 WHERE singleton AND daily_budget_minor<>?")) {
      statement.setLong(1, policy.dailyBudgetMinor());
      statement.setLong(2, policy.dailyBudgetMinor());
      statement.executeUpdate();
    }
    HarborRow current = lockHarbor(connection);
    if (now.isBefore(current.resetsAt)) return;
    Instant next = nextDay(now);
    UUID account = account(connection, "CITY", current.city, 0);
    long balance = balance(connection, account);
    long grant =
        Math.min(
            policy.maximumDailySourceMinor(),
            Math.max(0, policy.dailyBudgetMinor() - Math.min(policy.dailyBudgetMinor(), balance)));
    if (grant > 0) {
      setBalance(connection, account, balance + grant);
      ledger(
          connection,
          account,
          null,
          "HARBOR_DAILY_SOURCE",
          grant,
          balance + grant,
          current.city,
          named("harbor:daily:" + LocalDate.ofInstant(now, ZoneOffset.UTC)),
          null,
          now);
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE harbor_state SET spent_today_minor=0,budget_resets_at=?,version=version+1 WHERE singleton")) {
      statement.setTimestamp(1, Timestamp.from(next));
      statement.executeUpdate();
    }
  }

  private void refreshMarket(Connection connection, Instant now) throws SQLException {
    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    for (HarborPolicy.MarketOffer offer : policy.sellOrders())
      seedSell(connection, day, offer.commodity(), offer.quantity(), offer.unitPriceMinor(), now);
    for (HarborPolicy.MarketOffer offer : policy.buyOrders())
      seedBuy(connection, day, offer.commodity(), offer.quantity(), offer.unitPriceMinor(), now);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE harbor_state SET last_market_refresh_at=?,version=version+1 WHERE singleton")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void seedSell(
      Connection connection,
      LocalDate day,
      String commodity,
      long quantity,
      long unitPrice,
      Instant now)
      throws SQLException {
    UUID key = named("harbor:sell:" + day + ":" + commodity);
    if (exists(connection, "SELECT 1 FROM market_orders WHERE idempotency_key=?", key)) return;
    UUID warehouse = named("frontier:harbor:warehouse");
    UUID order = UUID.randomUUID();
    UUID reservation = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?")) {
      statement.setLong(1, quantity);
      statement.setLong(2, quantity);
      statement.setObject(3, warehouse);
      statement.setString(4, commodity);
      statement.setLong(5, quantity);
      if (statement.executeUpdate() != 1) return;
    }
    execute(
        connection,
        "INSERT INTO stock_reservations(id,warehouse_id,owner_type,owner_id,commodity_key,quantity,consumed,status,version) VALUES(?,?,'MARKET_ORDER',?,?,?,0,'ACTIVE',0)",
        reservation,
        warehouse,
        order,
        commodity,
        quantity);
    insertOrder(
        connection, order, "SELL", commodity, quantity, unitPrice, reservation, null, key, now);
  }

  private static void seedBuy(
      Connection connection,
      LocalDate day,
      String commodity,
      long quantity,
      long unitPrice,
      Instant now)
      throws SQLException {
    UUID key = named("harbor:buy:" + day + ":" + commodity);
    if (exists(connection, "SELECT 1 FROM market_orders WHERE idempotency_key=?", key)) return;
    UUID treasury = account(connection, "CITY", HARBOR_CITY, 0);
    long total = Math.multiplyExact(quantity, unitPrice);
    long balance = balance(connection, treasury);
    if (balance < total) return;
    UUID order = UUID.randomUUID();
    UUID escrow = UUID.randomUUID();
    setBalance(connection, treasury, balance - total);
    execute(
        connection,
        "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,'MARKET_ORDER',?,?,0)",
        escrow,
        order,
        total);
    ledger(
        connection,
        treasury,
        HARBOR_ACTOR,
        "HARBOR_MARKET_ESCROW",
        -total,
        balance - total,
        order,
        key,
        escrow,
        now);
    insertOrder(connection, order, "BUY", commodity, quantity, unitPrice, null, escrow, key, now);
  }

  private static void insertOrder(
      Connection connection,
      UUID order,
      String side,
      String commodity,
      long quantity,
      long price,
      UUID reservation,
      UUID escrow,
      UUID key,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_orders(id,owner_id,settlement_id,side,commodity_key,quantity,remaining_quantity,unit_price_minor,status,created_at,reservation_id,escrow_account_id,idempotency_key,version) VALUES(?,?,?,?,?,?,?,?,'OPEN',?,?,?,?,0)")) {
      statement.setObject(1, order);
      statement.setObject(2, HARBOR_CITY);
      statement.setObject(3, HARBOR_CITY);
      statement.setString(4, side);
      statement.setString(5, commodity);
      statement.setLong(6, quantity);
      statement.setLong(7, quantity);
      statement.setLong(8, price);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.setObject(10, reservation);
      statement.setObject(11, escrow);
      statement.setObject(12, key);
      statement.executeUpdate();
    }
  }

  private void ensureJobs(Connection connection, UUID player, Instant now) throws SQLException {
    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant expires = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    for (HarborPolicy.StarterJobDefinition job : policy.starterJobs())
      insertJob(connection, player, day, job.type(), job.description(), job.rewardMinor(), expires);
  }

  private static long completedRewardToday(Connection connection, UUID player, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(reward_minor),0) FROM harbor_jobs WHERE player_id=? AND job_day=? AND status='COMPLETED'")) {
      statement.setObject(1, player);
      statement.setDate(2, Date.valueOf(LocalDate.ofInstant(now, ZoneOffset.UTC)));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void insertJob(
      Connection connection,
      UUID player,
      LocalDate day,
      String type,
      String description,
      long reward,
      Instant expires)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO harbor_jobs(id,player_id,job_day,job_type,description,reward_minor,status,expires_at,version) VALUES(?,?,?,?,?,?,'POSTED',?,0) ON CONFLICT(player_id,job_day,job_type) DO NOTHING")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, player);
      statement.setDate(3, Date.valueOf(day));
      statement.setString(4, type);
      statement.setString(5, description);
      statement.setLong(6, reward);
      statement.setTimestamp(7, Timestamp.from(expires));
      statement.executeUpdate();
    }
  }

  private static JobRow lockJob(Connection connection, UUID job) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT player_id,reward_minor,status,expires_at FROM harbor_jobs WHERE id=? FOR UPDATE")) {
      statement.setObject(1, job);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("starter job not found");
        return new JobRow(
            job,
            result.getObject(1, UUID.class),
            result.getLong(2),
            result.getString(3),
            result.getTimestamp(4).toInstant());
      }
    }
  }

  private static JobReceipt receipt(Connection connection, JobRow row) throws SQLException {
    HarborRow harbor = lockHarbor(connection);
    return new JobReceipt(
        row.id,
        row.reward,
        balance(connection, account(connection, "PLAYER", row.player, 0)),
        harbor.budget - harbor.spent);
  }

  private static HarborSnapshot snapshot(Connection connection) throws SQLException {
    HarborRow harbor = lockHarbor(connection);
    int jobs = scalar(connection, "SELECT count(*) FROM harbor_jobs WHERE status='POSTED'");
    int buys =
        scalar(
            connection,
            "SELECT count(*) FROM market_orders WHERE settlement_id='"
                + HARBOR_CITY
                + "' AND side='BUY' AND status IN ('OPEN','PARTIAL')");
    int sells =
        scalar(
            connection,
            "SELECT count(*) FROM market_orders WHERE settlement_id='"
                + HARBOR_CITY
                + "' AND side='SELL' AND status IN ('OPEN','PARTIAL')");
    return new HarborSnapshot(
        harbor.city,
        "Frontier Harbor",
        Math.max(0, harbor.budget - harbor.spent),
        harbor.resetsAt,
        jobs,
        buys,
        sells);
  }

  private static HarborRow lockHarbor(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT city_id,daily_budget_minor,spent_today_minor,budget_resets_at FROM harbor_state WHERE singleton FOR UPDATE");
        ResultSet result = statement.executeQuery()) {
      if (!result.next()) throw new DomainException("Frontier Harbor has not been initialized");
      return new HarborRow(
          result.getObject(1, UUID.class),
          result.getLong(2),
          result.getLong(3),
          result.getTimestamp(4).toInstant());
    }
  }

  private static void requireHarbor(Connection connection) throws SQLException {
    if (!exists(connection, "SELECT 1 FROM harbor_state WHERE singleton"))
      throw new DomainException("Frontier Harbor has not been initialized");
  }

  private static UUID account(Connection connection, String type, UUID owner, long openingBalance)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts(id,owner_type,owner_id,balance_minor,version) VALUES(?,?,?,?,0) ON CONFLICT(owner_type,owner_id) DO NOTHING")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, type);
      statement.setObject(3, owner);
      statement.setLong(4, openingBalance);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM accounts WHERE owner_type=? AND owner_id=?")) {
      statement.setString(1, type);
      statement.setObject(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void stock(Connection connection, UUID warehouse, String commodity, long quantity)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,?,?,0,0)",
        warehouse,
        commodity,
        quantity);
  }

  private static void lockAccounts(Connection connection, UUID first, UUID second)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      statement.executeQuery().close();
    }
  }

  private static long balance(Connection connection, UUID account) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT balance_minor FROM accounts WHERE id=?")) {
      statement.setObject(1, account);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void setBalance(Connection connection, UUID account, long balance)
      throws SQLException {
    execute(
        connection,
        "UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?",
        balance,
        account);
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
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,counterparty_account_id,description) VALUES(?,?,?,?,?,?,?,?,?,?,'Frontier Harbor') ON CONFLICT(idempotency_key) DO NOTHING")) {
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
      statement.executeUpdate();
    }
  }

  private static void audit(
      Connection connection, UUID actor, String action, UUID aggregate, long amount, Instant now)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,reason,occurred_at) VALUES(?,?,?,'HARBOR',?,jsonb_build_object('amountMinor',?),'Frontier Harbor economy',?)",
        UUID.randomUUID(),
        actor,
        action,
        aggregate,
        amount,
        now);
  }

  private static boolean exists(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static int scalar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object... values) throws SQLException {
    for (int index = 0; index < values.length; index++) {
      Object value = values[index];
      if (value instanceof Instant instant)
        statement.setTimestamp(index + 1, Timestamp.from(instant));
      else statement.setObject(index + 1, value);
    }
  }

  private static Instant nextDay(Instant now) {
    return LocalDate.ofInstant(now, ZoneOffset.UTC)
        .plusDays(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.SECONDS);
  }

  private static UUID named(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private record HarborRow(UUID city, long budget, long spent, Instant resetsAt) {}

  private record JobRow(UUID id, UUID player, long reward, String status, Instant expiresAt) {}
}
