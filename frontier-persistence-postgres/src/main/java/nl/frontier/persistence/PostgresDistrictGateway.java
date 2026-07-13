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
import nl.frontier.city.DistrictRole;
import nl.frontier.city.DistrictStatus;
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
      long maintenanceMinor,
      Instant now) {
    return store.inTransaction(
        connection -> {
          lockCity(connection, city);
          requireRole(connection, city, actor, PLANNERS);
          validateBounds(connection, city, null, bounds);
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO districts(id,city_id,name,district_type,maintenance_minor,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, city);
            statement.setString(3, name);
            statement.setString(4, type.name());
            statement.setLong(5, maintenanceMinor);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          upsertRegion(connection, id, bounds);
          insertDefaultRoles(connection, id, now);
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
                  "SELECT id FROM districts WHERE city_id=? AND status='ACTIVE' ORDER BY priority DESC,name")) {
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
          int members = count(connection, "district_memberships", "district_id", district);
          int buildings =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM city_buildings WHERE district_id=? AND status<>'DESTROYED'",
                  district);
          long stored =
              scalarLong(
                  connection,
                  "SELECT coalesce(sum(quantity),0) FROM district_storage WHERE district_id=?",
                  district);
          long spent =
              scalarLong(
                  connection,
                  "SELECT coalesce(sum(-amount_minor),0) FROM district_budgets WHERE district_id=? AND amount_minor<0",
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
              snapshot, members, workers, buildings, stored, spent, List.copyOf(history));
        });
  }

  @Override
  public DistrictSnapshot rename(UUID district, UUID actor, String name, Instant now) {
    return changeText(district, actor, "name", name, "RENAMED", PLANNERS, now);
  }

  @Override
  public DistrictSnapshot resize(
      UUID district,
      UUID actor,
      SettlementGateway.Bounds bounds,
      long maintenanceMinor,
      Instant now) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot before = locked(connection, district);
          requireRole(connection, before.city(), actor, PLANNERS);
          validateBounds(connection, before.city(), district, bounds);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE districts SET maintenance_minor=?,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setLong(1, maintenanceMinor);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, district);
            statement.executeUpdate();
          }
          upsertRegion(connection, district, bounds);
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
                  "UPDATE districts SET manager_id=?,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setObject(1, manager);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, district);
            statement.executeUpdate();
          }
          if (before.manager() != null) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "DELETE FROM district_memberships WHERE district_id=? AND player_id=? AND role_key='MANAGER'")) {
              statement.setObject(1, district);
              statement.setObject(2, before.manager());
              statement.executeUpdate();
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO district_memberships(district_id,player_id,role_key,assigned_by,joined_at) VALUES(?,?,'MANAGER',?,?) ON CONFLICT(district_id,player_id) DO UPDATE SET role_key='MANAGER',assigned_by=excluded.assigned_by,joined_at=excluded.joined_at,version=district_memberships.version+1")) {
            statement.setObject(1, district);
            statement.setObject(2, manager);
            statement.setObject(3, actor);
            statement.setTimestamp(4, Timestamp.from(now));
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
                  "SELECT coalesce(sum(budget_minor),0) FROM districts WHERE city_id=? AND id<>? AND status='ACTIVE'",
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
                  "INSERT INTO district_budgets(id,district_id,amount_minor,category,reason,actor_id,occurred_at) VALUES(?,?,?,'ALLOCATION','district budget changed',?,?)")) {
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
                  "UPDATE districts SET updated_at=?,version=version+1 WHERE id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, district);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO district_policies(district_id,policy_key,policy_value,updated_by,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(district_id,policy_key) DO UPDATE SET policy_value=excluded.policy_value,updated_by=excluded.updated_by,updated_at=excluded.updated_at,version=district_policies.version+1")) {
            statement.setObject(1, district);
            statement.setString(2, key);
            statement.setString(3, value);
            statement.setObject(4, actor);
            statement.setTimestamp(5, Timestamp.from(now));
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
                      "UPDATE city_buildings SET district_id=NULL WHERE district_id=?");
              PreparedStatement delete =
                  connection.prepareStatement("DELETE FROM districts WHERE id=?")) {
            buildings.setObject(1, district);
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

  @Override
  public List<DistrictMembership> memberships(UUID district, UUID actor) {
    return store.inTransaction(
        connection -> {
          DistrictSnapshot current = snapshot(connection, district);
          requireMember(connection, current.city(), actor);
          List<DistrictMembership> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT player_id,role_key,assigned_by,joined_at FROM district_memberships WHERE district_id=? ORDER BY CASE role_key WHEN 'MANAGER' THEN 0 WHEN 'OFFICER' THEN 1 ELSE 2 END,joined_at,player_id")) {
            statement.setObject(1, district);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new DistrictMembership(
                        district,
                        result.getObject(1, UUID.class),
                        DistrictRole.valueOf(result.getString(2)),
                        result.getObject(3, UUID.class),
                        result.getTimestamp(4).toInstant()));
            }
          }
          return List.copyOf(values);
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
                  "UPDATE districts SET "
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
            "SELECT id FROM districts WHERE id=? AND status='ACTIVE' FOR UPDATE")) {
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
            "SELECT d.id,d.city_id,d.name,d.district_type,r.world_id,r.min_x,r.min_y,r.min_z,r.max_x,r.max_y,r.max_z,r.center_x,r.center_y,r.center_z,d.manager_id,d.status,d.tier,d.budget_minor,d.maintenance_minor,d.priority,d.version FROM districts d JOIN district_regions r ON r.district_id=d.id WHERE d.id=? AND d.status='ACTIVE'")) {
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
            new DistrictCenter(
                result.getObject(5, UUID.class),
                result.getInt(12),
                result.getInt(13),
                result.getInt(14)),
            result.getObject(15, UUID.class),
            DistrictStatus.valueOf(result.getString(16)),
            result.getInt(17),
            result.getLong(18),
            result.getLong(19),
            result.getInt(20),
            policies(connection, district),
            type.bonuses(),
            result.getLong(21));
      }
    }
  }

  private static Map<String, String> policies(Connection connection, UUID district)
      throws SQLException {
    Map<String, String> values = new LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT policy_key,policy_value FROM district_policies WHERE district_id=? ORDER BY policy_key")) {
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
            "SELECT 1 FROM districts d JOIN district_regions r ON r.district_id=d.id WHERE d.city_id=? AND d.id<>coalesce(?,gen_random_uuid()) AND d.status='ACTIVE' AND r.world_id=? AND NOT (r.max_x<? OR r.min_x>? OR r.max_z<? OR r.min_z>?) LIMIT 1")) {
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

  private static void upsertRegion(
      Connection connection, UUID district, SettlementGateway.Bounds bounds) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO district_regions(district_id,world_id,min_x,min_y,min_z,max_x,max_y,max_z,center_x,center_y,center_z) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(district_id) DO UPDATE SET world_id=excluded.world_id,min_x=excluded.min_x,min_y=excluded.min_y,min_z=excluded.min_z,max_x=excluded.max_x,max_y=excluded.max_y,max_z=excluded.max_z,center_x=excluded.center_x,center_y=excluded.center_y,center_z=excluded.center_z,version=district_regions.version+1")) {
      statement.setObject(1, district);
      statement.setObject(2, bounds.world());
      statement.setInt(3, bounds.minX());
      statement.setInt(4, bounds.minY());
      statement.setInt(5, bounds.minZ());
      statement.setInt(6, bounds.maxX());
      statement.setInt(7, bounds.maxY());
      statement.setInt(8, bounds.maxZ());
      statement.setInt(9, Math.floorDiv(bounds.minX() + bounds.maxX(), 2));
      statement.setInt(10, Math.floorDiv(bounds.minY() + bounds.maxY(), 2));
      statement.setInt(11, Math.floorDiv(bounds.minZ() + bounds.maxZ(), 2));
      statement.executeUpdate();
    }
  }

  private static void insertDefaultRoles(Connection connection, UUID district, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO district_roles(district_id,role_key,permissions,created_at) VALUES(?,?::text,?::jsonb,?)")) {
      for (var role :
          Map.of(
                  "MANAGER", "[\"MANAGE\",\"BUDGET\",\"POLICY\",\"ASSIGN\"]",
                  "OFFICER", "[\"ASSIGN\",\"REPORT\"]",
                  "RESIDENT", "[\"REPORT\"]",
                  "WORKER", "[\"WORK\",\"REPORT\"]")
              .entrySet()) {
        statement.setObject(1, district);
        statement.setString(2, role.getKey());
        statement.setString(3, role.getValue());
        statement.setTimestamp(4, Timestamp.from(now));
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void updateNumber(
      Connection connection, UUID district, String column, long value, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE districts SET " + column + "=?,updated_at=?,version=version+1 WHERE id=?")) {
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
