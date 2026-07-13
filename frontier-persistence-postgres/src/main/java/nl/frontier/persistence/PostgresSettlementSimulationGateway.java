package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.SettlementDailySimulation;
import nl.frontier.city.SettlementLevel;
import nl.frontier.city.SettlementSimulationGateway;
import nl.frontier.domain.DomainException;

public final class PostgresSettlementSimulationGateway implements SettlementSimulationGateway {
  private final TransactionalStore store;

  public PostgresSettlementSimulationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public List<Snapshot> leaseDue(UUID worker, int limit, Instant now, Instant leaseUntil) {
    return store.inTransaction(
        connection -> {
          List<UUID> cities = new ArrayList<>();
          try (var statement =
              connection.prepareStatement(
                  "SELECT city_id FROM city_simulation_state WHERE next_cycle_at<=? AND (lease_expires_at IS NULL OR lease_expires_at<?) ORDER BY next_cycle_at LIMIT ? FOR UPDATE SKIP LOCKED")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(now));
            statement.setTimestamp(2, java.sql.Timestamp.from(now));
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) cities.add(rows.getObject(1, UUID.class));
            }
          }
          List<Snapshot> result = new ArrayList<>();
          for (UUID city : cities) {
            update(
                connection,
                "UPDATE city_simulation_state SET lease_owner=?,lease_expires_at=? WHERE city_id=?",
                worker,
                leaseUntil,
                city);
            result.add(load(connection, city));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public void apply(
      UUID worker,
      Snapshot snapshot,
      SettlementDailySimulation.Result result,
      UUID cycleKey,
      Instant nextCycle,
      Instant now) {
    store.inTransaction(
        connection -> {
          if (scalarInt(
                  connection,
                  "SELECT count(*) FROM maintenance_invoices WHERE idempotency_key=?",
                  cycleKey)
              > 0) {
            updateState(connection, worker, snapshot.city(), nextCycle, now);
            return null;
          }
          try (var statement =
              connection.prepareStatement(
                  "SELECT balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=? FOR UPDATE")) {
            statement.setObject(1, snapshot.city());
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) throw new DomainException("settlement treasury is missing");
              long current = row.getLong(1);
              long afterTax = Math.addExact(current, result.taxIncomeMinor());
              long afterMaintenance =
                  result.maintenancePaid() ? afterTax - result.maintenanceMinor() : afterTax;
              long after =
                  result.wagesPaid()
                      ? afterMaintenance - result.workerWagesMinor()
                      : afterMaintenance;
              update(
                  connection,
                  "UPDATE accounts SET balance_minor=?,version=version+1 WHERE owner_type='CITY' AND owner_id=?",
                  after,
                  snapshot.city());
              UUID account =
                  scalarUuid(
                      connection,
                      "SELECT id FROM accounts WHERE owner_type='CITY' AND owner_id=?",
                      snapshot.city());
              insertLedger(
                  connection,
                  account,
                  snapshot.city(),
                  "DAILY_TAX",
                  result.taxIncomeMinor(),
                  afterTax,
                  UUID.nameUUIDFromBytes(
                      (cycleKey + ":tax").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                  now);
              if (result.maintenancePaid())
                insertLedger(
                    connection,
                    account,
                    snapshot.city(),
                    "MAINTENANCE",
                    -result.maintenanceMinor(),
                    after,
                    UUID.nameUUIDFromBytes(
                        (cycleKey + ":maintenance")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    now);
              if (result.wagesPaid() && result.workerWagesMinor() > 0)
                insertLedger(
                    connection,
                    account,
                    snapshot.city(),
                    "WORKER_WAGES",
                    -result.workerWagesMinor(),
                    after,
                    UUID.nameUUIDFromBytes(
                        (cycleKey + ":wages").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    now);
            }
          }
          consumeFood(connection, snapshot.city(), result);
          update(
              connection,
              "UPDATE workers SET mood=greatest(0,least(100,mood+?)),happiness=greatest(0,least(100,happiness+?)),experience=experience+?,state=CASE WHEN ? THEN CASE WHEN state='WAITING_PAYMENT' THEN 'IDLE' ELSE state END ELSE CASE WHEN state='IDLE' THEN 'WAITING_PAYMENT' ELSE state END END,last_simulation_at=?,version=version+1 WHERE city_id=?",
              result.wagesPaid() ? 2 : -10,
              result.foodSatisfied() ? 1 : -8,
              result.wagesPaid() ? 1 : 0,
              result.wagesPaid(),
              now,
              snapshot.city());
          String invoiceStatus = result.maintenancePaid() ? "PAID" : "OVERDUE";
          update(
              connection,
              "INSERT INTO maintenance_invoices(id,city_id,amount_minor,status,due_at,paid_at,idempotency_key) VALUES(?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              snapshot.city(),
              result.maintenanceMinor(),
              invoiceStatus,
              now,
              result.maintenancePaid() ? now : null,
              cycleKey);
          int population =
              Math.max(0, Math.addExact(snapshot.population(), result.populationDelta()));
          int prosperity = clamp(Math.addExact(snapshot.prosperity(), result.prosperityDelta()));
          int civilization =
              clamp(Math.addExact(snapshot.civilization(), result.civilizationDelta()));
          int changed =
              update(
                  connection,
                  "UPDATE cities SET population=?,prosperity=?,civilization=?,version=version+1 WHERE id=? AND version=?",
                  population,
                  prosperity,
                  civilization,
                  snapshot.city(),
                  snapshot.version());
          if (changed != 1)
            throw new DomainException("settlement changed during daily simulation; retry");
          update(
              connection,
              "INSERT INTO population_history(id,city_id,population_before,population_after,prosperity_before,prosperity_after,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              snapshot.city(),
              snapshot.population(),
              population,
              snapshot.prosperity(),
              prosperity,
              result.maintenancePaid() ? "DAILY_CYCLE" : "UNPAID_MAINTENANCE",
              now);
          update(
              connection,
              "INSERT INTO economic_daily_history(id,city_id,cycle_key,tax_income_minor,maintenance_minor,wages_minor,food_required,food_satisfied,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              snapshot.city(),
              cycleKey,
              result.taxIncomeMinor(),
              result.maintenanceMinor(),
              result.workerWagesMinor(),
              result.requiredFoodUnits(),
              result.foodSatisfied(),
              now);
          update(
              connection,
              "INSERT INTO dirty_settlements(city_id,reason,enqueued_at) VALUES(?,'DAILY_SIMULATION',?) ON CONFLICT(city_id) DO UPDATE SET reason=excluded.reason,enqueued_at=excluded.enqueued_at",
              snapshot.city(),
              now);
          update(
              connection,
              "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'CITY',?,'SettlementDayProcessed',?::jsonb,?)",
              UUID.randomUUID(),
              snapshot.city(),
              "{\"maintenancePaid\":"
                  + result.maintenancePaid()
                  + ",\"wagesPaid\":"
                  + result.wagesPaid()
                  + ",\"foodSatisfied\":"
                  + result.foodSatisfied()
                  + "}",
              now);
          updateState(connection, worker, snapshot.city(), nextCycle, now);
          return null;
        });
  }

  @Override
  public void release(UUID worker, UUID city) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "UPDATE city_simulation_state SET lease_owner=NULL,lease_expires_at=NULL WHERE city_id=? AND lease_owner=?",
              city,
              worker);
          return null;
        });
  }

  private static Snapshot load(Connection connection, UUID city) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT c.level,c.population,c.prosperity,c.civilization,c.version,a.balance_minor,"
                + "(SELECT count(*) FROM city_buildings b WHERE b.city_id=c.id),"
                + "(SELECT count(*) FROM workers w WHERE w.city_id=c.id),"
                + "(SELECT count(*) FROM road_edges e JOIN road_nodes n ON n.id=e.from_node WHERE n.city_id=c.id),"
                + "(SELECT coalesce(sum(w.salary_minor),0) FROM workers w WHERE w.city_id=c.id),"
                + "(SELECT coalesce(sum(s.available_quantity),0) FROM warehouse_stock s JOIN warehouses w ON w.id=s.warehouse_id WHERE w.city_id=c.id AND w.status='ACTIVE' AND s.commodity_key='minecraft:wheat'),"
                + "(SELECT coalesce(sum(s.available_quantity),0) FROM warehouse_stock s JOIN warehouses w ON w.id=s.warehouse_id WHERE w.city_id=c.id AND w.status='ACTIVE' AND s.commodity_key='minecraft:bread'),"
                + "coalesce((SELECT trim(both '\"' from policy_value::text) FROM city_policies p WHERE p.city_id=c.id AND p.policy_key='TAX_PROFILE'),'STANDARD'),"
                + "coalesce((SELECT max(housing_bonus) FROM district_effects d WHERE d.city_id=c.id),0),"
                + "coalesce((SELECT max(maintenance_bonus) FROM district_effects d WHERE d.city_id=c.id),0),"
                + "coalesce((SELECT sum(maintenance_penalty_percent) FROM district_effects d WHERE d.city_id=c.id),0),"
                + "coalesce((SELECT sum(wage_penalty_percent) FROM district_effects d WHERE d.city_id=c.id),0) "
                + "FROM cities c JOIN accounts a ON a.owner_type='CITY' AND a.owner_id=c.id WHERE c.id=?")) {
      statement.setObject(1, city);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new DomainException("settlement simulation snapshot is missing");
        return new Snapshot(
            city,
            SettlementLevel.values()[row.getInt(1) - 1],
            row.getInt(2),
            row.getInt(3),
            row.getInt(4),
            row.getLong(6),
            row.getInt(7),
            row.getInt(8),
            row.getInt(9),
            row.getLong(10),
            row.getLong(11),
            row.getLong(12),
            row.getString(13),
            row.getInt(14),
            row.getInt(15),
            row.getInt(16),
            row.getInt(17),
            row.getLong(5));
      }
    }
  }

  private static void updateState(
      Connection connection, UUID worker, UUID city, Instant next, Instant now)
      throws SQLException {
    update(
        connection,
        "UPDATE city_simulation_state SET last_cycle_at=?,next_cycle_at=?,lease_owner=NULL,lease_expires_at=NULL,version=version+1 WHERE city_id=? AND lease_owner=?",
        now,
        next,
        city,
        worker);
  }

  private static void insertLedger(
      Connection connection,
      UUID account,
      UUID city,
      String type,
      long amount,
      long balance,
      UUID key,
      Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO ledger_entries(id,account_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        account,
        type,
        amount,
        balance,
        city,
        key,
        now);
  }

  private static void consumeFood(
      Connection connection, UUID city, SettlementDailySimulation.Result result)
      throws SQLException {
    if (result.wheatConsumed() == 0 && result.breadConsumed() == 0) return;
    UUID warehouse =
        scalarUuid(
            connection,
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' ORDER BY id LIMIT 1 FOR UPDATE",
            city);
    consumeCommodity(connection, warehouse, "minecraft:wheat", result.wheatConsumed());
    consumeCommodity(connection, warehouse, "minecraft:bread", result.breadConsumed());
  }

  private static void consumeCommodity(
      Connection connection, UUID warehouse, String commodity, long quantity) throws SQLException {
    if (quantity == 0) return;
    int changed =
        update(
            connection,
            "UPDATE warehouse_stock SET available_quantity=available_quantity-?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?",
            quantity,
            warehouse,
            commodity,
            quantity);
    if (changed != 1) throw new DomainException("food stock changed during daily simulation");
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(100, value));
  }

  private static int scalarInt(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static UUID scalarUuid(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("required database value is missing");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static int update(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    }
  }

  private static void bind(java.sql.PreparedStatement statement, Object... parameters)
      throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      Object value = parameters[index];
      if (value instanceof Instant instant)
        statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
      else statement.setObject(index + 1, value);
    }
  }
}
