package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.DistrictGateway;
import nl.frontier.city.DistrictType;
import nl.frontier.city.SettlementGateway;
import nl.frontier.domain.DomainException;

public final class PostgresDistrictGateway implements DistrictGateway {
  private static final Set<String> PLANNERS = Set.of("MAYOR", "ARCHITECT");
  private static final Set<String> GOVERNMENT = Set.of("MAYOR");
  private static final Set<String> FINANCE = Set.of("MAYOR", "TREASURER");
  private final TransactionalStore store;

  public PostgresDistrictGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public DistrictSnapshot create(
      UUID city,
      UUID actor,
      String name,
      DistrictType type,
      SettlementGateway.Bounds bounds,
      Instant now) {
    return store.inTransaction(
        connection -> {
          lockCity(connection, city);
          requireRole(connection, city, actor, PLANNERS);
          validateBounds(connection, city, null, bounds);
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO city_districts(id,city_id,name,district_type,bounds,created_at,updated_at) VALUES(?,?,?,?,?::jsonb,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, city);
            statement.setString(3, name);
            statement.setString(4, type.name());
            statement.setString(5, bounds.json());
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, id, "CREATED", null, name + ":" + type, actor, now);
          return snapshot(connection, id);
        });
  }

  @Override
  public List<DistrictSnapshot> list(UUID city, UUID actor) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor);
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM city_districts WHERE city_id=? AND status='ACTIVE' ORDER BY priority DESC,name")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
          }
          List<DistrictSnapshot> districts = new ArrayList<>();
          for (UUID id : ids) districts.add(snapshot(connection, id));
          return List.copyOf(districts);
        });
  }

  @Override
  public DistrictReport report(UUID district, UUID actor) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot snapshot = snapshot(connection, district);
          requireMember(connection, snapshot.city(), actor);
          int workers = count(connection, "district_workers", "district_id", district);
          int buildings =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM city_buildings WHERE district_key=? AND status<>'DESTROYED'",
                  district.toString());
          long stored =
              scalarLong(
                  connection,
                  "SELECT coalesce(sum(quantity),0) FROM district_storage WHERE district_id=?",
                  district);
          long spent =
              scalarLong(
                  connection,
                  "SELECT coalesce(sum(-amount_minor),0) FROM district_budget WHERE district_id=? AND amount_minor<0",
                  district);
          List<HistoryEntry> history = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT action,coalesce(new_value,old_value,'{}'::jsonb)::text,actor_id,occurred_at FROM district_history WHERE district_id=? ORDER BY occurred_at DESC LIMIT 50")) {
            statement.setObject(1, district);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                history.add(
                    new HistoryEntry(
                        result.getString(1),
                        result.getString(2),
                        result.getObject(3, UUID.class),
                        result.getTimestamp(4).toInstant()));
            }
          }
          return new DistrictReport(
              snapshot, workers, buildings, stored, spent, List.copyOf(history));
        });
  }

  @Override
  public DistrictSnapshot rename(UUID district, UUID actor, String name, Instant now) {
    return changeText(district, actor, "name", name, "RENAMED", PLANNERS, now);
  }

  @Override
  public DistrictSnapshot resize(
      UUID district, UUID actor, SettlementGateway.Bounds bounds, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, PLANNERS);
          validateBounds(connection, before.city(), district, bounds);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_districts SET bounds=?::jsonb,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setString(1, bounds.json());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, district);
            statement.executeUpdate();
          }
          history(
              connection, district, "RESIZED", before.bounds().json(), bounds.json(), actor, now);
          return snapshot(connection, district);
        });
  }

  @Override
  public DistrictSnapshot assignManager(
      UUID district, UUID actor, UUID manager, boolean transfer, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, GOVERNMENT);
          requireMember(connection, before.city(), manager);
          if (!transfer && before.manager() != null)
            throw new DomainException("district already has a manager; use manager-transfer");
          if (transfer && before.manager() == null)
            throw new DomainException("district has no manager to transfer");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_districts SET manager_id=?,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setObject(1, manager);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, district);
            statement.executeUpdate();
          }
          history(
              connection,
              district,
              transfer ? "MANAGER_TRANSFERRED" : "MANAGER_ASSIGNED",
              String.valueOf(before.manager()),
              manager.toString(),
              actor,
              now);
          return snapshot(connection, district);
        });
  }

  @Override
  public DistrictSnapshot setBudget(UUID district, UUID actor, long budgetMinor, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, FINANCE);
          long other =
              scalarLong(
                  connection,
                  "SELECT coalesce(sum(budget_minor),0) FROM city_districts WHERE city_id=? AND id<>? AND status='ACTIVE'",
                  before.city(),
                  district);
          long treasury =
              scalarLong(
                  connection,
                  "SELECT balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=? FOR SHARE",
                  before.city());
          if (Math.addExact(other, budgetMinor) > treasury)
            throw new DomainException("district budgets exceed settlement treasury");
          updateNumber(connection, district, "budget_minor", budgetMinor, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO district_budget(id,district_id,amount_minor,category,reason,actor_id,occurred_at) VALUES(?,?,?,'ALLOCATION','district budget changed',?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, district);
            statement.setLong(3, budgetMinor - before.budgetMinor());
            statement.setObject(4, actor);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(
              connection,
              district,
              "BUDGET_CHANGED",
              Long.toString(before.budgetMinor()),
              Long.toString(budgetMinor),
              actor,
              now);
          return snapshot(connection, district);
        });
  }

  @Override
  public DistrictSnapshot setPriority(UUID district, UUID actor, int priority, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, PLANNERS);
          updateNumber(connection, district, "priority", priority, now);
          history(
              connection,
              district,
              "PRIORITY_CHANGED",
              Integer.toString(before.priority()),
              Integer.toString(priority),
              actor,
              now);
          return snapshot(connection, district);
        });
  }

  @Override
  public DistrictSnapshot setPolicy(
      UUID district, UUID actor, String key, String value, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, PLANNERS);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_districts SET policies=jsonb_set(policies,ARRAY[?],to_jsonb(?::text),true),updated_at=?,version=version+1 WHERE id=?")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, district);
            statement.executeUpdate();
          }
          history(
              connection,
              district,
              "POLICY_CHANGED",
              before.policies().get(key),
              key + "=" + value,
              actor,
              now);
          return snapshot(connection, district);
        });
  }

  @Override
  public void delete(UUID district, UUID actor, Instant now) {
    store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, GOVERNMENT);
          history(connection, district, "DELETED", before.name(), "DELETED", actor, now);
          try (PreparedStatement buildings =
                  connection.prepareStatement(
                      "UPDATE city_buildings SET district_key=NULL WHERE district_key=?");
              PreparedStatement delete =
                  connection.prepareStatement("DELETE FROM city_districts WHERE id=?")) {
            buildings.setString(1, district.toString());
            buildings.executeUpdate();
            delete.setObject(1, district);
            delete.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public WorkerAssignment assignWorker(
      UUID district, UUID actor, UUID worker, int priority, Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot snapshot = locked(connection, district);
          requireManagerOrPlanner(connection, snapshot, actor);
          if (scalarInt(
                  connection,
                  "SELECT count(*) FROM workers WHERE id=? AND city_id=?",
                  worker,
                  snapshot.city())
              != 1) throw new DomainException("worker does not belong to this settlement");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO district_workers(district_id,worker_id,priority,assigned_at) VALUES(?,?,?,?) ON CONFLICT(worker_id) DO UPDATE SET district_id=excluded.district_id,priority=excluded.priority,assigned_at=excluded.assigned_at")) {
            statement.setObject(1, district);
            statement.setObject(2, worker);
            statement.setInt(3, priority);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
          }
          history(connection, district, "WORKER_ASSIGNED", null, worker.toString(), actor, now);
          return new WorkerAssignment(district, worker, priority, now);
        });
  }

  @Override
  public void removeWorker(UUID district, UUID actor, UUID worker, Instant now) {
    store.inTransaction(
        connection -> {
          DistrictSnapshot snapshot = locked(connection, district);
          requireManagerOrPlanner(connection, snapshot, actor);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM district_workers WHERE district_id=? AND worker_id=?")) {
            statement.setObject(1, district);
            statement.setObject(2, worker);
            if (statement.executeUpdate() != 1)
              throw new DomainException("worker is not assigned to district");
          }
          history(connection, district, "WORKER_REMOVED", worker.toString(), null, actor, now);
          return null;
        });
  }

  private DistrictSnapshot changeText(
      UUID district,
      UUID actor,
      String column,
      String value,
      String action,
      Set<String> roles,
      Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, roles);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE city_districts SET "
                      + column
                      + "=?,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setString(1, value);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, district);
            statement.executeUpdate();
          }
          history(connection, district, action, before.name(), value, actor, now);
          return snapshot(connection, district);
        });
  }

  private static DistrictSnapshot locked(Connection connection, UUID district) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM city_districts WHERE id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, district);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("district not found");
      }
    }
    return snapshot(connection, district);
  }

  private static DistrictSnapshot snapshot(Connection connection, UUID district)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,city_id,name,district_type,(bounds->>'world')::uuid,(bounds->>'minX')::int,(bounds->>'minY')::int,(bounds->>'minZ')::int,(bounds->>'maxX')::int,(bounds->>'maxY')::int,(bounds->>'maxZ')::int,manager_id,budget_minor,priority,version FROM city_districts WHERE id=? AND status='ACTIVE'")) {
      statement.setObject(1, district);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("district not found");
        DistrictType type = DistrictType.valueOf(result.getString(4));
        return new DistrictSnapshot(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getString(3),
            type,
            new SettlementGateway.Bounds(
                result.getObject(5, UUID.class),
                result.getInt(6),
                result.getInt(7),
                result.getInt(8),
                result.getInt(9),
                result.getInt(10),
                result.getInt(11)),
            result.getObject(12, UUID.class),
            result.getLong(13),
            result.getInt(14),
            policies(connection, district),
            type.bonuses(),
            result.getLong(15));
      }
    }
  }

  private static Map<String, String> policies(Connection connection, UUID district)
      throws SQLException {
    Map<String, String> values = new LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT key,value FROM city_districts,jsonb_each_text(policies) WHERE id=? ORDER BY key")) {
      statement.setObject(1, district);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.put(result.getString(1), result.getString(2));
      }
    }
    return Map.copyOf(values);
  }

  private static void validateBounds(
      Connection connection, UUID city, UUID district, SettlementGateway.Bounds bounds)
      throws SQLException {
    int minX = Math.floorDiv(bounds.minX(), 16);
    int maxX = Math.floorDiv(bounds.maxX(), 16);
    int minZ = Math.floorDiv(bounds.minZ(), 16);
    int maxZ = Math.floorDiv(bounds.maxZ(), 16);
    long expected = (maxX - minX + 1L) * (maxZ - minZ + 1L);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM city_claims WHERE city_id=? AND world_id=? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ? AND state<>'WILDERNESS'")) {
      statement.setObject(1, city);
      statement.setObject(2, bounds.world());
      statement.setInt(3, minX);
      statement.setInt(4, maxX);
      statement.setInt(5, minZ);
      statement.setInt(6, maxZ);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        if (result.getLong(1) != expected)
          throw new DomainException("district must stay inside controlled claims");
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM city_districts WHERE city_id=? AND id<>coalesce(?,gen_random_uuid()) AND status='ACTIVE' AND (bounds->>'world')::uuid=? AND NOT ((bounds->>'maxX')::int<? OR (bounds->>'minX')::int>? OR (bounds->>'maxZ')::int<? OR (bounds->>'minZ')::int>?) LIMIT 1")) {
      statement.setObject(1, city);
      statement.setObject(2, district);
      statement.setObject(3, bounds.world());
      statement.setInt(4, bounds.minX());
      statement.setInt(5, bounds.maxX());
      statement.setInt(6, bounds.minZ());
      statement.setInt(7, bounds.maxZ());
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) throw new DomainException("district overlaps another district");
      }
    }
  }

  private static void requireManagerOrPlanner(
      Connection connection, DistrictSnapshot district, UUID actor) throws SQLException {
    if (actor.equals(district.manager())) return;
    requireRole(connection, district.city(), actor, PLANNERS);
  }

  private static void requireMember(Connection connection, UUID city, UUID actor)
      throws SQLException {
    if (scalarInt(
            connection,
            "SELECT count(*) FROM city_members WHERE city_id=? AND player_id=?",
            city,
            actor)
        != 1) throw new DomainException("not a settlement member");
  }

  private static void requireRole(Connection connection, UUID city, UUID actor, Set<String> roles)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=?")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !roles.contains(result.getString(1)))
          throw new DomainException("settlement role cannot change district");
      }
    }
  }

  private static void lockCity(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM cities WHERE id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("settlement not found");
      }
    }
  }

  private static void updateNumber(
      Connection connection, UUID district, String column, long value, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE city_districts SET "
                + column
                + "=?,updated_at=?,version=version+1 WHERE id=?")) {
      statement.setLong(1, value);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setObject(3, district);
      statement.executeUpdate();
    }
  }

  private static void history(
      Connection connection,
      UUID district,
      String action,
      String oldValue,
      String newValue,
      UUID actor,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO district_history(id,district_id,action,old_value,new_value,actor_id,occurred_at) VALUES(?,?,?,CASE WHEN ? IS NULL THEN NULL ELSE jsonb_build_object('value',?::text) END,CASE WHEN ? IS NULL THEN NULL ELSE jsonb_build_object('value',?::text) END,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, district);
      statement.setString(3, action);
      statement.setString(4, oldValue);
      statement.setString(5, oldValue);
      statement.setString(6, newValue);
      statement.setString(7, newValue);
      statement.setObject(8, actor);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static int count(Connection connection, String table, String column, UUID id)
      throws SQLException {
    return scalarInt(connection, "SELECT count(*) FROM " + table + " WHERE " + column + "=?", id);
  }

  private static int scalarInt(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static long scalarLong(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("required district value missing");
        return result.getLong(1);
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
  }
}
