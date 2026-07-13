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
import nl.frontier.economy.ProductionGateway;

public final class PostgresProductionGateway implements ProductionGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "TREASURER", "ARCHITECT");
  private final TransactionalStore store;

  public PostgresProductionGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public ProductionOrder queue(
      UUID city,
      UUID actor,
      UUID building,
      String recipe,
      int quantity,
      int priority,
      UUID idempotencyKey,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          ProductionOrder existing = byIdempotency(connection, idempotencyKey);
          if (existing != null) return existing;
          BuildingRow asset = building(connection, city, building);
          RecipeRow definition = recipe(connection, recipe);
          if (!asset.category.equals(definition.category))
            throw new DomainException("recipe requires a " + definition.category + " building");
          if (asset.integrity < 15) throw new DomainException("production building is disabled");
          if (countQueue(connection, building) >= 8)
            throw new DomainException("production queue is full");
          UUID order = UUID.randomUUID();
          long target = Math.multiplyExact(definition.workUnits, quantity);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO production_orders(id,building_id,recipe_key,requested_quantity,completed_quantity,status,priority,progress_units,created_by,created_at,version,idempotency_key,target_units) VALUES(?,?,?,?,0,'QUEUED',?,0,?,?,0,?,?)")) {
            statement.setObject(1, order);
            statement.setObject(2, building);
            statement.setString(3, recipe);
            statement.setInt(4, quantity);
            statement.setInt(5, priority);
            statement.setObject(6, actor);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setObject(8, idempotencyKey);
            statement.setLong(9, target);
            statement.executeUpdate();
          }
          String status =
              reserveInputs(connection, order, city, recipe, quantity)
                  ? "ACTIVE"
                  : "PAUSED_NO_INPUT";
          if (!hasWorker(connection, city, definition.profession)) status = "PAUSED_NO_WORKERS";
          updateStatus(connection, order, status);
          audit(connection, actor, "PRODUCTION_QUEUED", "PRODUCTION_ORDER", order, now);
          return load(connection, order);
        });
  }

  @Override
  public WorkerSnapshot hire(
      UUID city, UUID actor, String profession, int skill, long dailySalaryMinor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor);
          if (!Set.of(
                  "BUILDER",
                  "GUARD",
                  "MERCHANT",
                  "FARMER",
                  "MINER",
                  "BLACKSMITH",
                  "COURIER",
                  "DOCTOR",
                  "ARCHITECT",
                  "SCHOLAR",
                  "STABLE_MASTER",
                  "LUMBERJACK")
              .contains(profession)) throw new DomainException("unknown worker profession");
          UUID id = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO workers(id,city_id,profession,skill,state,salary_minor,mood,experience,last_simulation_at,version) VALUES(?,?,?,?,'IDLE',?,70,0,?,0)")) {
            statement.setObject(1, id);
            statement.setObject(2, city);
            statement.setString(3, profession);
            statement.setInt(4, skill);
            statement.setLong(5, dailySalaryMinor);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
          }
          audit(connection, actor, "WORKER_HIRED", "WORKER", id, now);
          return new WorkerSnapshot(id, city, profession, skill, "IDLE", dailySalaryMinor);
        });
  }

  @Override
  public List<ProductionOrder> orders(UUID city) {
    return store.inTransaction(
        connection -> {
          List<ProductionOrder> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT p.id,p.building_id,p.recipe_key,p.requested_quantity,p.completed_quantity,p.status,p.priority,p.progress_units,p.target_units FROM production_orders p JOIN city_buildings b ON b.id=p.building_id WHERE b.city_id=? ORDER BY p.created_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(map(result));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public CycleReport cycle(int maximumOrders, Instant now) {
    int visited = 0;
    int completed = 0;
    int paused = 0;
    for (int index = 0; index < maximumOrders; index++) {
      CycleResult result = store.inTransaction(connection -> processOne(connection, now));
      if (result == CycleResult.NONE) break;
      visited++;
      if (result == CycleResult.COMPLETED) completed++;
      if (result == CycleResult.PAUSED) paused++;
    }
    return new CycleReport(visited, completed, paused);
  }

  private static CycleResult processOne(Connection connection, Instant now) throws SQLException {
    OrderRow order;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT p.id,p.building_id,p.recipe_key,p.requested_quantity,p.progress_units,p.target_units,b.city_id,b.integrity,r.worker_profession FROM production_orders p JOIN city_buildings b ON b.id=p.building_id JOIN recipes r ON r.recipe_key=p.recipe_key WHERE p.status IN ('QUEUED','ACTIVE','PAUSED_NO_INPUT','PAUSED_NO_WORKERS','PAUSED_UNSAFE') AND (p.lease_expires_at IS NULL OR p.lease_expires_at<?) ORDER BY p.priority DESC,p.created_at LIMIT 1 FOR UPDATE OF p SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return CycleResult.NONE;
        order =
            new OrderRow(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getString(3),
                result.getInt(4),
                result.getLong(5),
                result.getLong(6),
                result.getObject(7, UUID.class),
                result.getInt(8),
                result.getString(9));
      }
    }
    if (order.integrity < 15) {
      updateStatus(connection, order.id, "PAUSED_UNSAFE");
      return CycleResult.PAUSED;
    }
    if (!hasReservations(connection, order.id)
        && !reserveInputs(connection, order.id, order.city, order.recipe, order.quantity)) {
      updateStatus(connection, order.id, "PAUSED_NO_INPUT");
      return CycleResult.PAUSED;
    }
    int skill = bestWorkerSkill(connection, order.city, order.profession);
    if (skill == 0) {
      updateStatus(connection, order.id, "PAUSED_NO_WORKERS");
      return CycleResult.PAUSED;
    }
    long efficiency = Math.max(20, order.integrity);
    long work = Math.max(1, skill * efficiency / 10L);
    long progress = Math.min(order.target, Math.addExact(order.progress, work));
    if (progress < order.target) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE production_orders SET progress_units=?,status='ACTIVE',version=version+1 WHERE id=?")) {
        statement.setLong(1, progress);
        statement.setObject(2, order.id);
        statement.executeUpdate();
      }
      return CycleResult.ACTIVE;
    }
    complete(connection, order, now);
    return CycleResult.COMPLETED;
  }

  private static void complete(Connection connection, OrderRow order, Instant now)
      throws SQLException {
    UUID warehouse = warehouse(connection, order.city);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT s.id,s.commodity_key,s.quantity FROM production_reservations pr JOIN stock_reservations s ON s.id=pr.reservation_id WHERE pr.production_order_id=? FOR UPDATE OF s")) {
      statement.setObject(1, order.id);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          UUID reservation = result.getObject(1, UUID.class);
          String commodity = result.getString(2);
          long quantity = result.getLong(3);
          changeStock(connection, warehouse, commodity, 0, -quantity);
          try (PreparedStatement consume =
              connection.prepareStatement(
                  "UPDATE stock_reservations SET consumed=quantity,status='CONSUMED',version=version+1 WHERE id=?")) {
            consume.setObject(1, reservation);
            consume.executeUpdate();
          }
        }
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT commodity_key,quantity FROM recipe_outputs WHERE recipe_key=?")) {
      statement.setString(1, order.recipe);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          addStock(
              connection,
              warehouse,
              result.getString(1),
              Math.multiplyExact(result.getLong(2), order.quantity));
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE production_orders SET progress_units=target_units,completed_quantity=requested_quantity,status='COMPLETED',lease_owner=NULL,lease_expires_at=NULL,version=version+1 WHERE id=?")) {
      statement.setObject(1, order.id);
      statement.executeUpdate();
    }
    outbox(connection, "PRODUCTION_ORDER", order.id, "ProductionCompleted", now);
  }

  private static boolean reserveInputs(
      Connection connection, UUID order, UUID city, String recipe, int quantity)
      throws SQLException {
    if (hasReservations(connection, order)) return true;
    UUID warehouse = warehouse(connection, city);
    List<Input> inputs = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT commodity_key,quantity FROM recipe_inputs WHERE recipe_key=? ORDER BY commodity_key")) {
      statement.setString(1, recipe);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          inputs.add(
              new Input(result.getString(1), Math.multiplyExact(result.getLong(2), quantity)));
      }
    }
    for (Input input : inputs) {
      lockStock(connection, warehouse, input.commodity);
      if (available(connection, warehouse, input.commodity) < input.quantity) return false;
    }
    for (Input input : inputs) {
      changeStock(connection, warehouse, input.commodity, -input.quantity, input.quantity);
      UUID reservation = UUID.randomUUID();
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO stock_reservations(id,warehouse_id,owner_type,owner_id,commodity_key,quantity,consumed,status,version) VALUES(?,?,'PRODUCTION',?,?,?,0,'ACTIVE',0)")) {
        statement.setObject(1, reservation);
        statement.setObject(2, warehouse);
        statement.setObject(3, order);
        statement.setString(4, input.commodity);
        statement.setLong(5, input.quantity);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO production_reservations(production_order_id,reservation_id) VALUES(?,?)")) {
        statement.setObject(1, order);
        statement.setObject(2, reservation);
        statement.executeUpdate();
      }
    }
    return true;
  }

  private static boolean hasReservations(Connection connection, UUID order) throws SQLException {
    return scalar(
            connection,
            "SELECT count(*) FROM production_reservations WHERE production_order_id=?",
            order)
        > 0;
  }

  private static int bestWorkerSkill(Connection connection, UUID city, String profession)
      throws SQLException {
    return (int)
        scalar(
            connection,
            "SELECT COALESCE(max(skill),0) FROM workers WHERE city_id=? AND profession='"
                + profession.replace("'", "")
                + "' AND state='IDLE'",
            city);
  }

  private static boolean hasWorker(Connection connection, UUID city, String profession)
      throws SQLException {
    return bestWorkerSkill(connection, city, profession) > 0;
  }

  private static boolean hasReservationsUnsafe(Connection connection, UUID order)
      throws SQLException {
    return hasReservations(connection, order);
  }

  private static UUID warehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO warehouses(id,city_id,capacity,status,version) VALUES(?,?,100000,'ACTIVE',0) ON CONFLICT DO NOTHING")) {
      statement.setObject(1, id);
      statement.setObject(2, city);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    throw new DomainException("warehouse unavailable");
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
      Connection connection, UUID warehouse, String commodity, long available, long reserved)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE warehouse_stock SET available_quantity=available_quantity+?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity+?>=0 AND reserved_quantity+?>=0")) {
      statement.setLong(1, available);
      statement.setLong(2, reserved);
      statement.setObject(3, warehouse);
      statement.setString(4, commodity);
      statement.setLong(5, available);
      statement.setLong(6, reserved);
      if (statement.executeUpdate() != 1) throw new DomainException("stock invariant violated");
    }
  }

  private static void addStock(
      Connection connection, UUID warehouse, String commodity, long quantity) throws SQLException {
    lockStock(connection, warehouse, commodity);
    changeStock(connection, warehouse, commodity, quantity, 0);
  }

  private static BuildingRow building(Connection connection, UUID city, UUID building)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT category,integrity FROM city_buildings WHERE id=? AND city_id=? FOR SHARE")) {
      statement.setObject(1, building);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("production building not found");
        return new BuildingRow(result.getString(1), result.getInt(2));
      }
    }
  }

  private static RecipeRow recipe(Connection connection, String recipe) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT building_category,work_units_per_unit,worker_profession FROM recipes WHERE recipe_key=? AND enabled")) {
      statement.setString(1, recipe);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("production recipe not found");
        return new RecipeRow(result.getString(1), result.getLong(2), result.getString(3));
      }
    }
  }

  private static long countQueue(Connection connection, UUID building) throws SQLException {
    return scalar(
        connection,
        "SELECT count(*) FROM production_orders WHERE building_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",
        building);
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
          throw new DomainException("settlement role does not allow production management");
      }
    }
  }

  private static ProductionOrder byIdempotency(Connection connection, UUID key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,building_id,recipe_key,requested_quantity,completed_quantity,status,priority,progress_units,target_units FROM production_orders WHERE idempotency_key=?")) {
      statement.setObject(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? map(result) : null;
      }
    }
  }

  private static ProductionOrder load(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,building_id,recipe_key,requested_quantity,completed_quantity,status,priority,progress_units,target_units FROM production_orders WHERE id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("production order not found");
        return map(result);
      }
    }
  }

  private static ProductionOrder map(ResultSet result) throws SQLException {
    return new ProductionOrder(
        result.getObject(1, UUID.class),
        result.getObject(2, UUID.class),
        result.getString(3),
        result.getInt(4),
        result.getInt(5),
        result.getString(6),
        result.getInt(7),
        result.getLong(8),
        result.getLong(9));
  }

  private static void updateStatus(Connection connection, UUID id, String status)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE production_orders SET status=?,version=version+1 WHERE id=?")) {
      statement.setString(1, status);
      statement.setObject(2, id);
      statement.executeUpdate();
    }
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

  private static void outbox(Connection connection, String type, UUID id, String event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,?,?,?, '{}'::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, type);
      statement.setObject(3, id);
      statement.setString(4, event);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private enum CycleResult {
    NONE,
    ACTIVE,
    PAUSED,
    COMPLETED
  }

  private record BuildingRow(String category, int integrity) {}

  private record RecipeRow(String category, long workUnits, String profession) {}

  private record Input(String commodity, long quantity) {}

  private record OrderRow(
      UUID id,
      UUID building,
      String recipe,
      int quantity,
      long progress,
      long target,
      UUID city,
      int integrity,
      String profession) {}
}
