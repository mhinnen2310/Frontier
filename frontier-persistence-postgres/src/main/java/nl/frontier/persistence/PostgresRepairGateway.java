package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.repair.ReconstructionPlanner;
import nl.frontier.repair.RepairGateway;
import nl.frontier.repair.RepairOrder;

public final class PostgresRepairGateway implements RepairGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "TREASURER", "BUILDER_MASTER");
  private final TransactionalStore store;

  public PostgresRepairGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Quote quote(
      UUID city, UUID actor, UUID campaign, RepairOrder.Priority priority, Instant now) {
    return store.inTransaction(
        connection -> {
          requireRepairAccess(connection, city, actor, campaign);
          return calculateQuote(connection, city, campaign, priority);
        });
  }

  @Override
  public RepairSnapshot purchase(
      UUID city,
      UUID actor,
      UUID campaign,
      RepairOrder.Priority priority,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRepairAccess(connection, city, actor, campaign);
          advisoryLock(connection, "repair-purchase:" + idempotency);
          RepairSnapshot existing = byIdempotency(connection, idempotency);
          if (existing != null) return existing;
          lockEligibleDamage(connection, city, campaign);
          Quote quote = calculateQuote(connection, city, campaign, priority);
          if (quote.tasks() == 0) throw new DomainException("campaign has no eligible damage");
          UUID depot = ensureDepot(connection, city);
          UUID account = cityAccount(connection, city);
          long balance = balance(connection, account);
          if (balance < quote.totalCostMinor())
            throw new DomainException("insufficient treasury for repair purchase");
          UUID order = UUID.randomUUID();
          setBalance(connection, account, balance - quote.totalCostMinor());
          ledger(
              connection,
              account,
              actor,
              -quote.totalCostMinor(),
              balance - quote.totalCostMinor(),
              order,
              idempotency,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO repair_orders(id,city_id,campaign_id,priority,status,estimate_minor,total_tasks,completed_tasks,version,created_by,created_at,paid_minor,idempotency_key) VALUES(?,?,?,?, 'REGISTERED',?, ?,0,0,?,?,?,?)")) {
            statement.setObject(1, order);
            statement.setObject(2, city);
            statement.setObject(3, campaign);
            statement.setString(4, priority.name());
            statement.setLong(5, quote.totalCostMinor());
            statement.setInt(6, quote.tasks());
            statement.setObject(7, actor);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setLong(9, quote.totalCostMinor());
            statement.setObject(10, idempotency);
            statement.executeUpdate();
          }
          List<DamageRow> damage = damage(connection, city, campaign);
          Map<PositionKey, UUID> tasksByPosition = new HashMap<>();
          for (DamageRow row : damage) {
            UUID task = UUID.randomUUID();
            ReconstructionPlanner.Layer layer = layer(row, damage);
            int score = priorityScore(priority, layer, row.target);
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO repair_tasks(id,repair_order_id,world_id,x,y,z,expected_current,target_data,commodity_key,layer,status,version,journal_id,priority_score,attempts,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?, 'READY',0,?,?,0,?)")) {
              statement.setObject(1, task);
              statement.setObject(2, order);
              statement.setObject(3, row.world);
              statement.setInt(4, row.x);
              statement.setInt(5, row.y);
              statement.setInt(6, row.z);
              statement.setString(7, row.current);
              statement.setString(8, row.target);
              statement.setString(9, row.commodity());
              statement.setString(10, layer.name());
              statement.setObject(11, row.journal);
              statement.setInt(12, score);
              statement.setTimestamp(13, Timestamp.from(now));
              statement.executeUpdate();
            }
            UUID below = tasksByPosition.get(new PositionKey(row.world, row.x, row.y - 1, row.z));
            if (below != null) dependency(connection, task, below);
            tasksByPosition.put(new PositionKey(row.world, row.x, row.y, row.z), task);
          }
          boolean completeReservation = reserveRequirements(connection, order, city, quote, now);
          if (!completeReservation) {
            updateOrderStatus(connection, order, "PAUSED_MATERIAL");
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE repair_tasks SET status='WAITING_MATERIAL',updated_at=? WHERE repair_order_id=?")) {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, order);
              statement.executeUpdate();
            }
          } else {
            updateOrderStatus(connection, order, "RESERVED");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE damage_journal SET repair_state='RESERVED',version=version+1 WHERE city_id=? AND campaign_id=? AND repair_state='REGISTERED' AND mutation_state='APPLIED'")) {
            statement.setObject(1, city);
            statement.setObject(2, campaign);
            statement.executeUpdate();
          }
          history(
              connection, order, null, "REPAIR_PURCHASED", "{\"depot\":\"" + depot + "\"}", now);
          audit(connection, actor, "REPAIR_PURCHASED", order, now);
          return load(connection, order);
        });
  }

  @Override
  public RepairSnapshot purchaseInfrastructure(
      UUID city, UUID actor, UUID maintenanceOrder, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          advisoryLock(connection, "infrastructure-repair:" + idempotency);
          RepairSnapshot existing = byIdempotency(connection, idempotency);
          if (existing != null) return existing;
          InfrastructureMaintenance maintenance =
              requireInfrastructureAccess(connection, city, actor, maintenanceOrder);
          if (maintenance.repairOrder != null) return load(connection, maintenance.repairOrder);
          List<MaintenanceTarget> targets = maintenanceTargets(connection, maintenanceOrder);
          if (targets.isEmpty())
            throw new DomainException("maintenance order has no repair targets");
          RepairOrder.Priority priority = RepairOrder.Priority.valueOf(maintenance.priority);
          Quote quote = infrastructureQuote(connection, city, targets, priority);
          UUID depot = ensureDepot(connection, city);
          UUID account = cityAccount(connection, city);
          long balance = balance(connection, account);
          if (balance < quote.totalCostMinor())
            throw new DomainException("insufficient treasury for infrastructure maintenance");
          UUID order = UUID.randomUUID();
          setBalance(connection, account, balance - quote.totalCostMinor());
          ledger(
              connection,
              account,
              actor,
              -quote.totalCostMinor(),
              balance - quote.totalCostMinor(),
              order,
              idempotency,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO repair_orders(id,city_id,campaign_id,priority,status,estimate_minor,total_tasks,completed_tasks,version,created_by,created_at,paid_minor,idempotency_key,source_type,source_id) VALUES(?,?,NULL,?,'REGISTERED',?,?,0,0,?,?,?,?, 'INFRASTRUCTURE',?)")) {
            statement.setObject(1, order);
            statement.setObject(2, city);
            statement.setString(3, priority.name());
            statement.setLong(4, quote.totalCostMinor());
            statement.setInt(5, targets.size());
            statement.setObject(6, actor);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setLong(8, quote.totalCostMinor());
            statement.setObject(9, idempotency);
            statement.setObject(10, maintenance.edge);
            statement.executeUpdate();
          }
          for (MaintenanceTarget target : targets) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO repair_tasks(id,repair_order_id,world_id,x,y,z,expected_current,target_data,commodity_key,layer,status,version,priority_score,attempts,updated_at,infrastructure_edge_id,maintenance_order_id) VALUES(?,?,?,?,?,?,?,?,?,'FLOOR','READY',0,?,0,?,?,?)")) {
              statement.setObject(1, UUID.randomUUID());
              statement.setObject(2, order);
              statement.setObject(3, target.world);
              statement.setInt(4, target.x);
              statement.setInt(5, target.y);
              statement.setInt(6, target.z);
              statement.setString(7, target.expected);
              statement.setString(8, target.target);
              statement.setString(9, target.commodity());
              statement.setInt(
                  10, priorityScore(priority, ReconstructionPlanner.Layer.FLOOR, target.target));
              statement.setTimestamp(11, Timestamp.from(now));
              statement.setObject(12, maintenance.edge);
              statement.setObject(13, maintenanceOrder);
              statement.executeUpdate();
            }
          }
          boolean completeReservation = reserveRequirements(connection, order, city, quote, now);
          if (completeReservation) updateOrderStatus(connection, order, "RESERVED");
          else {
            updateOrderStatus(connection, order, "PAUSED_MATERIAL");
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE repair_tasks SET status='WAITING_MATERIAL',updated_at=? WHERE repair_order_id=?")) {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, order);
              statement.executeUpdate();
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE infrastructure_maintenance_orders SET status='FUNDED',repair_order_id=?,estimate_minor=?,updated_at=? WHERE id=?")) {
            statement.setObject(1, order);
            statement.setLong(2, quote.totalCostMinor());
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, maintenanceOrder);
            statement.executeUpdate();
          }
          history(
              connection,
              order,
              null,
              "INFRASTRUCTURE_MAINTENANCE_FUNDED",
              "{\"maintenance\":\"" + maintenanceOrder + "\",\"depot\":\"" + depot + "\"}",
              now);
          audit(connection, actor, "INFRASTRUCTURE_REPAIR_PURCHASED", order, now);
          return load(connection, order);
        });
  }

  @Override
  public List<RepairSnapshot> orders(UUID city) {
    return store.inTransaction(
        connection -> {
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM repair_orders WHERE city_id=? ORDER BY created_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
          }
          List<RepairSnapshot> values = new ArrayList<>();
          for (UUID id : ids) values.add(load(connection, id));
          return List.copyOf(values);
        });
  }

  @Override
  public List<PreparedTask> leaseReady(
      UUID coordinator, int maximum, Instant now, Instant leaseUntil) {
    return store.inTransaction(
        connection -> {
          replenishPaused(connection, now);
          List<UUID> taskIds = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT t.id FROM repair_tasks t JOIN repair_orders o ON o.id=t.repair_order_id WHERE t.status IN ('READY','PREPARED') AND o.status IN ('RESERVED','REPAIRING') AND (t.lease_expires_at IS NULL OR t.lease_expires_at<?) AND NOT EXISTS (SELECT 1 FROM task_dependencies d JOIN repair_tasks p ON p.id=d.depends_on WHERE d.task_id=t.id AND p.status<>'COMPLETED') ORDER BY t.priority_score+coalesce((SELECT de.repair_priority_bonus FROM damage_journal j JOIN city_buildings b ON b.id=j.building_id JOIN district_effects de ON de.district_id=b.district_id WHERE j.id=t.journal_id),0) DESC,t.layer,t.y,t.id LIMIT ? FOR UPDATE OF t SKIP LOCKED")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, maximum);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) taskIds.add(result.getObject(1, UUID.class));
            }
          }
          List<PreparedTask> tasks = new ArrayList<>();
          for (UUID task : taskIds) {
            PreparedTask prepared = prepare(connection, coordinator, task, now, leaseUntil);
            if (prepared != null) tasks.add(prepared);
          }
          return List.copyOf(tasks);
        });
  }

  @Override
  public void commit(UUID coordinator, UUID task, Instant now) {
    store.inTransaction(
        connection -> {
          TaskRow row = rawTask(connection, task);
          if (row.status.equals("COMPLETED")) return null;
          if (!coordinator.equals(row.leaseOwner))
            throw new DomainException("repair task lease is owned by another coordinator");
          ConsumptionRow consumption = consumption(connection, row.consumption);
          if (consumption.status.equals("COMMITTED")) return null;
          if (!consumption.status.equals("PREPARED"))
            throw new DomainException("repair consumption is not prepared");
          ReservationRow reservation = reservation(connection, consumption.reservation);
          String stockSql =
              reservation.sourceType.equals("DEPOT")
                  ? "UPDATE builder_depot_stock SET reserved_quantity=reserved_quantity-1,version=version+1 WHERE depot_id=? AND commodity_key=? AND reserved_quantity>=1"
                  : "UPDATE warehouse_stock SET reserved_quantity=reserved_quantity-1,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND reserved_quantity>=1";
          try (PreparedStatement statement = connection.prepareStatement(stockSql)) {
            statement.setObject(1, reservation.source);
            statement.setString(2, reservation.commodity);
            if (statement.executeUpdate() != 1)
              throw new DomainException("reserved repair material missing");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE material_reservations SET consumed_quantity=consumed_quantity+1,status=CASE WHEN consumed_quantity+1=reserved_quantity THEN 'CONSUMED' ELSE 'ISSUED' END,version=version+1 WHERE id=? AND consumed_quantity<reserved_quantity")) {
            statement.setObject(1, reservation.id);
            if (statement.executeUpdate() != 1)
              throw new DomainException("material reservation exhausted");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE material_consumptions SET status='COMMITTED',committed_at=? WHERE id=? AND status='PREPARED'")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, consumption.id);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET status='COMPLETED',lease_owner=NULL,lease_expires_at=NULL,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, task);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE damage_journal SET repair_state='COMPLETED',version=version+1 WHERE id=? AND repair_state='REPAIRING'")) {
            statement.setObject(1, row.journal);
            statement.executeUpdate();
          }
          releaseWorker(connection, task, true);
          int completed = completedTasks(connection, row.order);
          int total = totalTasks(connection, row.order);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET completed_tasks=?,status=?,completed_at=CASE WHEN ? THEN ? ELSE completed_at END,version=version+1 WHERE id=?")) {
            statement.setInt(1, completed);
            statement.setString(2, completed == total ? "COMPLETED" : "REPAIRING");
            statement.setBoolean(3, completed == total);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setObject(5, row.order);
            statement.executeUpdate();
          }
          history(connection, row.order, task, "TASK_COMMITTED", "{}", now);
          if (completed == total) {
            history(connection, row.order, null, "REPAIR_COMPLETED", "{}", now);
            queueInfrastructureReinspection(connection, row.order, now);
          }
          return null;
        });
  }

  @Override
  public void release(UUID coordinator, UUID task, String reason, Instant now) {
    store.inTransaction(
        connection -> {
          TaskRow row = rawTask(connection, task);
          if (row.status.equals("COMPLETED")) return null;
          if (!coordinator.equals(row.leaseOwner))
            throw new DomainException("repair task lease is owned by another coordinator");
          releaseConsumption(connection, row.consumption);
          releaseWorker(connection, task, false);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET status=CASE WHEN attempts+1>=5 THEN 'REVIEW_REQUIRED' ELSE 'READY' END,prepared_consumption_id=NULL,lease_owner=NULL,lease_expires_at=NULL,last_error=?,updated_at=?,attempts=attempts+1,version=version+1 WHERE id=?")) {
            statement.setString(1, reason);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, task);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET status='REVIEW_REQUIRED',version=version+1 WHERE id=? AND EXISTS(SELECT 1 FROM repair_tasks WHERE id=? AND status='REVIEW_REQUIRED')")) {
            statement.setObject(1, row.order);
            statement.setObject(2, task);
            statement.executeUpdate();
          }
          history(connection, row.order, task, "TASK_RELEASED", jsonReason(reason), now);
          return null;
        });
  }

  @Override
  public void defer(UUID coordinator, UUID task, String reason, Instant retryAt, Instant now) {
    store.inTransaction(
        connection -> {
          TaskRow row = rawTask(connection, task);
          if (row.status.equals("COMPLETED")) return null;
          if (!coordinator.equals(row.leaseOwner))
            throw new DomainException("repair task lease is owned by another coordinator");
          releaseWorker(connection, task, false);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET lease_owner=NULL,lease_expires_at=?,last_error=?,updated_at=?,version=version+1 WHERE id=? AND status='PREPARED'")) {
            statement.setTimestamp(1, Timestamp.from(retryAt));
            statement.setString(2, reason);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, task);
            statement.executeUpdate();
          }
          history(connection, row.order, task, "TASK_DEFERRED", jsonReason(reason), now);
          return null;
        });
  }

  @Override
  public void conflict(UUID coordinator, UUID task, String actualBlockData, Instant now) {
    store.inTransaction(
        connection -> {
          TaskRow row = rawTask(connection, task);
          if (row.status.equals("COMPLETED")) return null;
          if (!coordinator.equals(row.leaseOwner))
            throw new DomainException("repair task lease is owned by another coordinator");
          releaseConsumption(connection, row.consumption);
          releaseWorker(connection, task, false);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO repair_conflicts(id,repair_task_id,expected_data,actual_data,target_data,detected_at) VALUES(?,?,?,?,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, task);
            statement.setString(3, row.expected);
            statement.setString(4, actualBlockData);
            statement.setString(5, row.target);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET status='REVIEW_REQUIRED',lease_owner=NULL,lease_expires_at=NULL,last_error='BLOCK_CONFLICT',updated_at=?,version=version+1 WHERE id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, task);
            statement.executeUpdate();
          }
          updateOrderStatus(connection, row.order, "REVIEW_REQUIRED");
          history(connection, row.order, task, "TASK_CONFLICT", "{}", now);
          return null;
        });
  }

  @Override
  public int archiveCompleted(Instant completedBefore, int maximum, Instant now) {
    return store.inTransaction(
        connection -> {
          List<UUID> orders = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT o.id FROM repair_orders o WHERE o.status='COMPLETED' AND o.completed_at<? ORDER BY o.completed_at,o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED")) {
            statement.setTimestamp(1, Timestamp.from(completedBefore));
            statement.setInt(2, maximum);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) orders.add(result.getObject(1, UUID.class));
            }
          }
          for (UUID order : orders) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE repair_orders SET status='ARCHIVED',archived_at=?,version=version+1 WHERE id=? AND status='COMPLETED'")) {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, order);
              statement.executeUpdate();
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE damage_journal SET repair_state='ARCHIVED',archived_at=?,version=version+1 WHERE id IN(SELECT journal_id FROM repair_tasks WHERE repair_order_id=?) AND repair_state='COMPLETED'")) {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, order);
              statement.executeUpdate();
            }
            history(connection, order, null, "REPAIR_ARCHIVED", "{}", now);
          }
          return orders.size();
        });
  }

  private static Quote calculateQuote(
      Connection connection, UUID city, UUID campaign, RepairOrder.Priority priority)
      throws SQLException {
    List<DamageRow> damage = damage(connection, city, campaign);
    Map<String, Long> required = new LinkedHashMap<>();
    for (DamageRow row : damage) required.merge(row.commodity(), 1L, Long::sum);
    List<Requirement> requirements = new ArrayList<>();
    long materialCost = 0;
    for (Map.Entry<String, Long> entry : required.entrySet()) {
      long available = available(connection, city, entry.getKey());
      long price = referencePrice(connection, city, entry.getKey());
      materialCost = Math.addExact(materialCost, Math.multiplyExact(entry.getValue(), price));
      requirements.add(
          new Requirement(
              entry.getKey(),
              entry.getValue(),
              available,
              Math.max(0, entry.getValue() - available)));
    }
    long labor = Math.multiplyExact(damage.size(), 25L);
    long base = Math.addExact(labor, materialCost);
    long total =
        switch (priority) {
          case CRITICAL -> Math.multiplyExact(base, 150) / 100;
          case HIGH -> Math.multiplyExact(base, 125) / 100;
          case NORMAL -> base;
          case LOW -> Math.multiplyExact(base, 90) / 100;
          case COSMETIC -> Math.multiplyExact(base, 80) / 100;
        };
    return new Quote(
        city, campaign, damage.size(), labor, materialCost, total, List.copyOf(requirements));
  }

  private static InfrastructureMaintenance requireInfrastructureAccess(
      Connection connection, UUID city, UUID actor, UUID maintenance) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT o.edge_id,o.priority,o.repair_order_id,c.level,m.role FROM infrastructure_maintenance_orders o JOIN cities c ON c.id=o.city_id JOIN city_members m ON m.city_id=c.id AND m.player_id=? WHERE o.id=? AND o.city_id=? AND o.status='OPEN' FOR UPDATE OF o,c,m")) {
      statement.setObject(1, actor);
      statement.setObject(2, maintenance);
      statement.setObject(3, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("open infrastructure maintenance not found");
        if (result.getInt(4) < 3)
          throw new DomainException("Builder Guild unlocks at city level 3");
        if (!ROLES.contains(result.getString(5)))
          throw new DomainException("settlement role cannot purchase infrastructure repairs");
        return new InfrastructureMaintenance(
            result.getObject(1, UUID.class), result.getString(2), result.getObject(3, UUID.class));
      }
    }
  }

  private static List<MaintenanceTarget> maintenanceTargets(Connection connection, UUID maintenance)
      throws SQLException {
    List<MaintenanceTarget> targets = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT world_id,x,y,z,expected_data,target_data FROM infrastructure_maintenance_targets WHERE maintenance_order_id=? ORDER BY y,x,z")) {
      statement.setObject(1, maintenance);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          targets.add(
              new MaintenanceTarget(
                  result.getObject(1, UUID.class),
                  result.getInt(2),
                  result.getInt(3),
                  result.getInt(4),
                  result.getString(5),
                  result.getString(6)));
      }
    }
    return List.copyOf(targets);
  }

  private static Quote infrastructureQuote(
      Connection connection,
      UUID city,
      List<MaintenanceTarget> targets,
      RepairOrder.Priority priority)
      throws SQLException {
    Map<String, Long> required = new LinkedHashMap<>();
    for (MaintenanceTarget target : targets) required.merge(target.commodity(), 1L, Long::sum);
    List<Requirement> requirements = new ArrayList<>();
    long materials = 0;
    for (Map.Entry<String, Long> entry : required.entrySet()) {
      long present = available(connection, city, entry.getKey());
      materials =
          Math.addExact(
              materials,
              Math.multiplyExact(
                  entry.getValue(), referencePrice(connection, city, entry.getKey())));
      requirements.add(
          new Requirement(
              entry.getKey(), entry.getValue(), present, Math.max(0, entry.getValue() - present)));
    }
    long labor = Math.multiplyExact(targets.size(), 25L);
    long base = Math.addExact(labor, materials);
    long total =
        switch (priority) {
          case CRITICAL -> Math.multiplyExact(base, 150) / 100;
          case HIGH -> Math.multiplyExact(base, 125) / 100;
          case NORMAL -> base;
          case LOW -> Math.multiplyExact(base, 90) / 100;
          case COSMETIC -> Math.multiplyExact(base, 80) / 100;
        };
    return new Quote(
        city, null, targets.size(), labor, materials, total, List.copyOf(requirements));
  }

  private static List<DamageRow> damage(Connection connection, UUID city, UUID campaign)
      throws SQLException {
    List<DamageRow> values = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,world_id,x,y,z,damaged_data,original_data FROM damage_journal WHERE city_id=? AND campaign_id=? AND repair_state='REGISTERED' AND mutation_state='APPLIED' ORDER BY y,x,z,id")) {
      statement.setObject(1, city);
      statement.setObject(2, campaign);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.add(
              new DamageRow(
                  result.getObject(1, UUID.class),
                  result.getObject(2, UUID.class),
                  result.getInt(3),
                  result.getInt(4),
                  result.getInt(5),
                  result.getString(6),
                  result.getString(7)));
      }
    }
    return List.copyOf(values);
  }

  private static boolean reserveRequirements(
      Connection connection, UUID order, UUID city, Quote quote, Instant now) throws SQLException {
    UUID warehouse = warehouse(connection, city);
    UUID depot = ensureDepot(connection, city);
    boolean full = true;
    for (Requirement requirement : quote.requirements()) {
      long depotAvailable = depotAvailable(connection, depot, requirement.commodity());
      long depotQuantity = Math.min(depotAvailable, requirement.required());
      if (depotQuantity > 0) {
        reserveDepotStock(connection, depot, order, requirement.commodity(), depotQuantity, now);
      }
      long remaining = requirement.required() - depotQuantity;
      lockStock(connection, warehouse, requirement.commodity());
      long available = stockAvailable(connection, warehouse, requirement.commodity());
      long quantity = Math.min(available, remaining);
      if (depotQuantity + quantity < requirement.required()) full = false;
      if (quantity == 0) continue;
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE warehouse_stock SET available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?")) {
        statement.setLong(1, quantity);
        statement.setLong(2, quantity);
        statement.setObject(3, warehouse);
        statement.setString(4, requirement.commodity());
        statement.setLong(5, quantity);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO material_reservations(id,repair_order_id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity,status,expires_at,version) VALUES(?,?,'WAREHOUSE',?,?,?,0,'RESERVED',?,0)")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, order);
        statement.setObject(3, warehouse);
        statement.setString(4, requirement.commodity());
        statement.setLong(5, quantity);
        statement.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)));
        statement.executeUpdate();
      }
    }
    return full;
  }

  private static void replenishPaused(Connection connection, Instant now) throws SQLException {
    List<UUID> orders = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM repair_orders WHERE status='PAUSED_MATERIAL' ORDER BY created_at LIMIT 32 FOR UPDATE SKIP LOCKED");
        ResultSet result = statement.executeQuery()) {
      while (result.next()) orders.add(result.getObject(1, UUID.class));
    }
    for (UUID order : orders) {
      RepairSnapshot snapshot = load(connection, order);
      if (snapshot.shortages().isEmpty()) {
        updateOrderStatus(connection, order, "RESERVED");
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE repair_tasks SET status='READY',updated_at=? WHERE repair_order_id=? AND status='WAITING_MATERIAL'")) {
          statement.setTimestamp(1, Timestamp.from(now));
          statement.setObject(2, order);
          statement.executeUpdate();
        }
        continue;
      }
      UUID warehouse = warehouse(connection, snapshot.city());
      UUID depot = ensureDepot(connection, snapshot.city());
      for (Map.Entry<String, Long> shortage : snapshot.shortages().entrySet()) {
        long depotQuantity =
            Math.min(depotAvailable(connection, depot, shortage.getKey()), shortage.getValue());
        if (depotQuantity > 0)
          reserveDepotStock(connection, depot, order, shortage.getKey(), depotQuantity, now);
        long remaining = shortage.getValue() - depotQuantity;
        lockStock(connection, warehouse, shortage.getKey());
        long quantity =
            Math.min(stockAvailable(connection, warehouse, shortage.getKey()), remaining);
        if (quantity == 0) continue;
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE warehouse_stock SET available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND available_quantity>=?")) {
          statement.setLong(1, quantity);
          statement.setLong(2, quantity);
          statement.setObject(3, warehouse);
          statement.setString(4, shortage.getKey());
          statement.setLong(5, quantity);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO material_reservations(id,repair_order_id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity,status,expires_at,version) VALUES(?,?,'WAREHOUSE',?,?,?,0,'RESERVED',?,0)")) {
          statement.setObject(1, UUID.randomUUID());
          statement.setObject(2, order);
          statement.setObject(3, warehouse);
          statement.setString(4, shortage.getKey());
          statement.setLong(5, quantity);
          statement.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)));
          statement.executeUpdate();
        }
      }
      if (load(connection, order).shortages().isEmpty()) {
        updateOrderStatus(connection, order, "RESERVED");
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE repair_tasks SET status='READY',updated_at=? WHERE repair_order_id=? AND status='WAITING_MATERIAL'")) {
          statement.setTimestamp(1, Timestamp.from(now));
          statement.setObject(2, order);
          statement.executeUpdate();
        }
      }
    }
  }

  private static PreparedTask prepare(
      Connection connection, UUID coordinator, UUID task, Instant now, Instant leaseUntil)
      throws SQLException {
    TaskRow row = rawTask(connection, task);
    if (row.status.equals("PREPARED") && row.consumption != null) {
      expirePackages(connection, task, now);
      WorkerSelection worker = packageWorker(connection, task, now);
      if (worker == null) {
        worker = availableBuilder(connection, row.city);
        if (worker == null) return null;
        issueWorkPackage(connection, row, worker, task, leaseUntil);
      } else renewWorkPackage(connection, task, worker.id, leaseUntil);
      leaseTask(connection, task, coordinator, leaseUntil, now);
      beginRepair(connection, row, now);
      return prepared(row, worker);
    }
    WorkerSelection worker = availableBuilder(connection, row.city);
    if (worker == null) return null;
    ReservationRow reservation = nextReservation(connection, row.order, row.commodity);
    if (reservation == null) {
      updateOrderStatus(connection, row.order, "PAUSED_MATERIAL");
      return null;
    }
    UUID proposedConsumption = UUID.randomUUID();
    UUID consumption;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO material_consumptions(id,reservation_id,repair_task_id,status,prepared_at,committed_at) VALUES(?,?,?,'PREPARED',?,NULL) ON CONFLICT(repair_task_id) DO UPDATE SET reservation_id=excluded.reservation_id,status='PREPARED',prepared_at=excluded.prepared_at,committed_at=NULL RETURNING id")) {
      statement.setObject(1, proposedConsumption);
      statement.setObject(2, reservation.id);
      statement.setObject(3, task);
      statement.setTimestamp(4, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        consumption = result.getObject(1, UUID.class);
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_tasks SET status='PREPARED',prepared_consumption_id=?,lease_owner=?,lease_expires_at=?,updated_at=?,version=version+1 WHERE id=?")) {
      statement.setObject(1, consumption);
      statement.setObject(2, coordinator);
      statement.setTimestamp(3, Timestamp.from(leaseUntil));
      statement.setTimestamp(4, Timestamp.from(now));
      statement.setObject(5, task);
      statement.executeUpdate();
    }
    issueWorkPackage(connection, row, worker, task, leaseUntil);
    beginRepair(connection, row, now);
    return new PreparedTask(
        row.id,
        row.order,
        row.city,
        worker.id,
        worker.entity,
        row.world,
        row.x,
        row.y,
        row.z,
        row.expected,
        row.target,
        row.commodity,
        ReconstructionPlanner.Layer.valueOf(row.layer),
        consumption);
  }

  private static PreparedTask prepared(TaskRow row, WorkerSelection worker) {
    return new PreparedTask(
        row.id,
        row.order,
        row.city,
        worker.id,
        worker.entity,
        row.world,
        row.x,
        row.y,
        row.z,
        row.expected,
        row.target,
        row.commodity,
        ReconstructionPlanner.Layer.valueOf(row.layer),
        row.consumption);
  }

  private static TaskRow lockTask(Connection connection, UUID task, UUID coordinator)
      throws SQLException {
    TaskRow row = rawTask(connection, task);
    if (!coordinator.equals(row.leaseOwner))
      throw new DomainException("repair task lease is owned by another coordinator");
    return row;
  }

  private static TaskRow rawTask(Connection connection, UUID task) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.id,t.repair_order_id,o.city_id,t.world_id,t.x,t.y,t.z,t.expected_current,t.target_data,t.commodity_key,t.layer,t.status,t.journal_id,t.prepared_consumption_id,t.lease_owner FROM repair_tasks t JOIN repair_orders o ON o.id=t.repair_order_id WHERE t.id=? FOR UPDATE OF t")) {
      statement.setObject(1, task);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("repair task not found");
        return new TaskRow(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getObject(3, UUID.class),
            result.getObject(4, UUID.class),
            result.getInt(5),
            result.getInt(6),
            result.getInt(7),
            result.getString(8),
            result.getString(9),
            result.getString(10),
            result.getString(11),
            result.getString(12),
            result.getObject(13, UUID.class),
            result.getObject(14, UUID.class),
            result.getObject(15, UUID.class));
      }
    }
  }

  private static ConsumptionRow consumption(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,reservation_id,status FROM material_consumptions WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("prepared consumption missing");
        return new ConsumptionRow(
            result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getString(3));
      }
    }
  }

  private static ReservationRow reservation(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity FROM material_reservations WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("material reservation missing");
        return new ReservationRow(
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getObject(3, UUID.class),
            result.getString(4),
            result.getLong(5),
            result.getLong(6));
      }
    }
  }

  private static ReservationRow nextReservation(Connection connection, UUID order, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity FROM material_reservations WHERE repair_order_id=? AND commodity_key=? AND status IN ('RESERVED','ISSUED') AND consumed_quantity<reserved_quantity ORDER BY source_type,id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setObject(1, order);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new ReservationRow(
                result.getObject(1, UUID.class),
                result.getString(2),
                result.getObject(3, UUID.class),
                result.getString(4),
                result.getLong(5),
                result.getLong(6))
            : null;
      }
    }
  }

  private static WorkerSelection availableBuilder(Connection connection, UUID city)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,presentation_entity_id FROM workers WHERE city_id=? AND profession='BUILDER' AND state='IDLE' ORDER BY skill DESC,id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new WorkerSelection(result.getObject(1, UUID.class), result.getObject(2, UUID.class))
            : null;
      }
    }
  }

  private static WorkerSelection packageWorker(Connection connection, UUID task, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT p.worker_id,w.presentation_entity_id FROM work_packages p JOIN workers w ON w.id=p.worker_id WHERE p.repair_task_id=? AND p.status IN ('ISSUED','ACTIVE') AND p.expires_at>=? ORDER BY p.id LIMIT 1")) {
      statement.setObject(1, task);
      statement.setTimestamp(2, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new WorkerSelection(result.getObject(1, UUID.class), result.getObject(2, UUID.class))
            : null;
      }
    }
  }

  private static void lockEligibleDamage(Connection connection, UUID city, UUID campaign)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM damage_journal WHERE city_id=? AND campaign_id=? AND repair_state='REGISTERED' AND mutation_state='APPLIED' ORDER BY id FOR UPDATE")) {
      statement.setObject(1, city);
      statement.setObject(2, campaign);
      statement.executeQuery().close();
    }
  }

  private static void expirePackages(Connection connection, UUID task, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE workers w SET state='IDLE',task_id=NULL,lease_expires_at=NULL,version=w.version+1 FROM work_packages p WHERE p.worker_id=w.id AND p.repair_task_id=? AND p.status IN ('ISSUED','ACTIVE') AND p.expires_at<?")) {
      statement.setObject(1, task);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE work_packages SET status='EXPIRED',version=version+1 WHERE repair_task_id=? AND status IN ('ISSUED','ACTIVE') AND expires_at<?")) {
      statement.setObject(1, task);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void issueWorkPackage(
      Connection connection, TaskRow row, WorkerSelection worker, UUID task, Instant leaseUntil)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE workers SET state='TRAVELLING',task_id=?,lease_expires_at=?,version=version+1 WHERE id=? AND state='IDLE'")) {
      statement.setObject(1, task);
      statement.setTimestamp(2, Timestamp.from(leaseUntil));
      statement.setObject(3, worker.id);
      if (statement.executeUpdate() != 1) throw new DomainException("builder is no longer idle");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO work_packages(id,repair_order_id,worker_id,status,expires_at,version,repair_task_id) VALUES(?,?,?,'ISSUED',?,0,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, row.order);
      statement.setObject(3, worker.id);
      statement.setTimestamp(4, Timestamp.from(leaseUntil));
      statement.setObject(5, task);
      statement.executeUpdate();
    }
  }

  private static void renewWorkPackage(
      Connection connection, UUID task, UUID worker, Instant leaseUntil) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE work_packages SET expires_at=?,version=version+1 WHERE repair_task_id=? AND worker_id=? AND status IN ('ISSUED','ACTIVE')")) {
      statement.setTimestamp(1, Timestamp.from(leaseUntil));
      statement.setObject(2, task);
      statement.setObject(3, worker);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE workers SET lease_expires_at=?,version=version+1 WHERE id=?")) {
      statement.setTimestamp(1, Timestamp.from(leaseUntil));
      statement.setObject(2, worker);
      statement.executeUpdate();
    }
  }

  private static void beginRepair(Connection connection, TaskRow row, Instant now)
      throws SQLException {
    updateOrderStatus(connection, row.order, "REPAIRING");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE damage_journal SET repair_state='REPAIRING',version=version+1 WHERE id=? AND repair_state='RESERVED'")) {
      statement.setObject(1, row.journal);
      statement.executeUpdate();
    }
    history(connection, row.order, row.id, "REPAIRING", "{}", now);
  }

  private static void releaseWorker(Connection connection, UUID task, boolean completed)
      throws SQLException {
    UUID worker = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT worker_id FROM work_packages WHERE repair_task_id=? AND status IN ('ISSUED','ACTIVE') FOR UPDATE")) {
      statement.setObject(1, task);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) worker = result.getObject(1, UUID.class);
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE work_packages SET status=?,version=version+1 WHERE repair_task_id=? AND status IN ('ISSUED','ACTIVE')")) {
      statement.setString(1, completed ? "COMPLETED" : "EXPIRED");
      statement.setObject(2, task);
      statement.executeUpdate();
    }
    if (worker != null) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE workers SET state='IDLE',task_id=NULL,lease_expires_at=NULL,experience=experience+?,version=version+1 WHERE id=?")) {
        statement.setInt(1, completed ? 1 : 0);
        statement.setObject(2, worker);
        statement.executeUpdate();
      }
    }
  }

  private static void releaseConsumption(Connection connection, UUID consumption)
      throws SQLException {
    if (consumption == null) return;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE material_consumptions SET status='RELEASED' WHERE id=? AND status='PREPARED'")) {
      statement.setObject(1, consumption);
      statement.executeUpdate();
    }
  }

  private static void leaseTask(
      Connection connection, UUID task, UUID coordinator, Instant leaseUntil, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_tasks SET lease_owner=?,lease_expires_at=?,updated_at=?,version=version+1 WHERE id=?")) {
      statement.setObject(1, coordinator);
      statement.setTimestamp(2, Timestamp.from(leaseUntil));
      statement.setTimestamp(3, Timestamp.from(now));
      statement.setObject(4, task);
      statement.executeUpdate();
    }
  }

  private static RepairSnapshot byIdempotency(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM repair_orders WHERE idempotency_key=?")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? load(connection, result.getObject(1, UUID.class)) : null;
      }
    }
  }

  private static void advisoryLock(Connection connection, String key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
      statement.setString(1, key);
      statement.executeQuery().close();
    }
  }

  private static RepairSnapshot load(Connection connection, UUID id) throws SQLException {
    UUID city;
    UUID campaign;
    RepairOrder.Priority priority;
    RepairOrder.Status status;
    long estimate;
    int total;
    int completed;
    long version;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT city_id,campaign_id,priority,status,estimate_minor,total_tasks,completed_tasks,version FROM repair_orders WHERE id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("repair order not found");
        city = result.getObject(1, UUID.class);
        campaign = result.getObject(2, UUID.class);
        priority = RepairOrder.Priority.valueOf(result.getString(3));
        status = RepairOrder.Status.valueOf(result.getString(4));
        estimate = result.getLong(5);
        total = result.getInt(6);
        completed = result.getInt(7);
        version = result.getLong(8);
      }
    }
    Map<String, Long> shortages = new LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.commodity_key,count(*)-coalesce((SELECT sum(r.reserved_quantity-r.consumed_quantity) FROM material_reservations r WHERE r.repair_order_id=? AND r.commodity_key=t.commodity_key AND r.status IN ('RESERVED','ISSUED')),0) shortage FROM repair_tasks t WHERE t.repair_order_id=? AND t.status NOT IN ('COMPLETED','CANCELLED') GROUP BY t.commodity_key HAVING count(*)>coalesce((SELECT sum(r.reserved_quantity-r.consumed_quantity) FROM material_reservations r WHERE r.repair_order_id=? AND r.commodity_key=t.commodity_key AND r.status IN ('RESERVED','ISSUED')),0)")) {
      statement.setObject(1, id);
      statement.setObject(2, id);
      statement.setObject(3, id);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) shortages.put(result.getString(1), result.getLong(2));
      }
    }
    return new RepairSnapshot(
        id,
        city,
        campaign,
        priority,
        status,
        estimate,
        total,
        completed,
        Map.copyOf(shortages),
        version);
  }

  private static void requireRepairAccess(
      Connection connection, UUID city, UUID actor, UUID campaign) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT c.level,m.role,ca.phase FROM cities c JOIN city_members m ON m.city_id=c.id AND m.player_id=? JOIN campaigns ca ON ca.id=? AND ca.defender_city_id=c.id WHERE c.id=? FOR SHARE OF c,m,ca")) {
      statement.setObject(1, actor);
      statement.setObject(2, campaign);
      statement.setObject(3, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("repair city or campaign not found");
        if (result.getInt(1) < 3)
          throw new DomainException("Builder Guild unlocks at city level 3");
        if (!ROLES.contains(result.getString(2)))
          throw new DomainException("settlement role cannot purchase repairs");
        if (!Set.of("CEASEFIRE", "RESOLUTION", "ENDED").contains(result.getString(3)))
          throw new DomainException("campaign is not safe for paid reconstruction");
      }
    }
  }

  private static UUID ensureDepot(Connection connection, UUID city) throws SQLException {
    advisoryLock(connection, "builder-depot:" + city);
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM builder_depots WHERE city_id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) return result.getObject(1, UUID.class);
      }
    }
    UUID building;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM city_buildings WHERE city_id=? AND category IN ('INFRASTRUCTURE','GOVERNMENT') AND integrity>=40 ORDER BY id LIMIT 1 FOR SHARE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new DomainException(
              "register an operational infrastructure building for the Builder Depot");
        building = result.getObject(1, UUID.class);
      }
    }
    UUID depot = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO builder_depots(id,city_id,building_id,tier,capacity_units,status,version) VALUES(?,?,?,1,10000,'ACTIVE',0)")) {
      statement.setObject(1, depot);
      statement.setObject(2, city);
      statement.setObject(3, building);
      statement.executeUpdate();
    }
    return depot;
  }

  private static UUID warehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("city warehouse is required for repairs");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static long available(Connection connection, UUID city, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(s.available_quantity),0) FROM warehouse_stock s JOIN warehouses w ON w.id=s.warehouse_id WHERE w.city_id=? AND w.status='ACTIVE' AND s.commodity_key=?")) {
      statement.setObject(1, city);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static long referencePrice(Connection connection, UUID city, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce((SELECT unit_price_minor FROM reference_prices WHERE settlement_id=? AND commodity_key=?),(SELECT reference_price_minor FROM commodity_definitions WHERE commodity_key=?),10)")) {
      statement.setObject(1, city);
      statement.setString(2, commodity);
      statement.setString(3, commodity);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static long depotAvailable(Connection connection, UUID depot, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(available_quantity,0) FROM builder_depot_stock WHERE depot_id=? AND commodity_key=? FOR UPDATE")) {
      statement.setObject(1, depot);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  private static void reserveDepotStock(
      Connection connection, UUID depot, UUID order, String commodity, long quantity, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE builder_depot_stock SET available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE depot_id=? AND commodity_key=? AND available_quantity>=?")) {
      statement.setLong(1, quantity);
      statement.setLong(2, quantity);
      statement.setObject(3, depot);
      statement.setString(4, commodity);
      statement.setLong(5, quantity);
      if (statement.executeUpdate() != 1)
        throw new DomainException("Builder Guild depot stock changed concurrently");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO material_reservations(id,repair_order_id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity,status,expires_at,version) VALUES(?,?,'DEPOT',?,?,?,0,'RESERVED',?,0)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, order);
      statement.setObject(3, depot);
      statement.setString(4, commodity);
      statement.setLong(5, quantity);
      statement.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)));
      statement.executeUpdate();
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

  private static long stockAvailable(Connection connection, UUID warehouse, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT available_quantity FROM warehouse_stock WHERE warehouse_id=? AND commodity_key=?")) {
      statement.setObject(1, warehouse);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static UUID cityAccount(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type='CITY' AND owner_id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("city treasury missing");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection connection, UUID account) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE")) {
      statement.setObject(1, account);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
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

  private static int completedTasks(Connection connection, UUID order) throws SQLException {
    return count(
        connection,
        "SELECT count(*) FROM repair_tasks WHERE repair_order_id=? AND status='COMPLETED'",
        order);
  }

  private static int totalTasks(Connection connection, UUID order) throws SQLException {
    return count(connection, "SELECT total_tasks FROM repair_orders WHERE id=?", order);
  }

  private static int count(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static void dependency(Connection connection, UUID task, UUID depends)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO task_dependencies(task_id,depends_on) VALUES(?,?) ON CONFLICT DO NOTHING")) {
      statement.setObject(1, task);
      statement.setObject(2, depends);
      statement.executeUpdate();
    }
  }

  private static ReconstructionPlanner.Layer layer(DamageRow row, List<DamageRow> all) {
    String block = row.target.toLowerCase(java.util.Locale.ROOT);
    int minY = all.stream().mapToInt(DamageRow::y).min().orElse(row.y);
    int maxY = all.stream().mapToInt(DamageRow::y).max().orElse(row.y);
    if (row.y == minY) return ReconstructionPlanner.Layer.FOUNDATION;
    if (block.contains("redstone") || block.contains("repeater") || block.contains("comparator"))
      return ReconstructionPlanner.Layer.REDSTONE;
    if (block.contains("door") || block.contains("bed") || block.contains("pane"))
      return ReconstructionPlanner.Layer.MULTIPART;
    if (row.y == maxY) return ReconstructionPlanner.Layer.ROOF;
    if (block.contains("flower") || block.contains("torch") || block.contains("banner"))
      return ReconstructionPlanner.Layer.DECORATION;
    return ReconstructionPlanner.Layer.STRUCTURE;
  }

  private static int priorityScore(
      RepairOrder.Priority priority, ReconstructionPlanner.Layer layer, String target) {
    int base =
        switch (priority) {
          case CRITICAL -> 500;
          case HIGH -> 400;
          case NORMAL -> 300;
          case LOW -> 200;
          case COSMETIC -> 100;
        };
    String block = target.toLowerCase(java.util.Locale.ROOT);
    if (block.contains("road") || block.contains("rail")) base += 100;
    if (layer == ReconstructionPlanner.Layer.FOUNDATION) base += 80;
    if (layer == ReconstructionPlanner.Layer.STRUCTURE) base += 60;
    return base;
  }

  private static void updateOrderStatus(Connection connection, UUID order, String status)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_orders SET status=?,version=version+1 WHERE id=?")) {
      statement.setString(1, status);
      statement.setObject(2, order);
      statement.executeUpdate();
    }
  }

  private static void ledger(
      Connection connection,
      UUID account,
      UUID actor,
      long amount,
      long after,
      UUID reference,
      UUID idempotency,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,'REPAIR_PURCHASE',?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, account);
      statement.setObject(3, actor);
      statement.setLong(4, amount);
      statement.setLong(5, after);
      statement.setObject(6, reference);
      statement.setObject(7, idempotency);
      statement.setTimestamp(8, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void history(
      Connection connection, UUID order, UUID task, String event, String payload, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO repair_history(id,repair_order_id,repair_task_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, order);
      statement.setObject(3, task);
      statement.setString(4, event);
      statement.setString(5, payload);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void audit(Connection connection, UUID actor, String action, UUID id, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,occurred_at) VALUES(?,?,?,'REPAIR_ORDER',?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setObject(4, id);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static String jsonReason(String reason) {
    return "{\"reason\":\"" + reason.replace("\"", "'") + "\"}";
  }

  private record DamageRow(
      UUID journal, UUID world, int x, int y, int z, String current, String target) {
    String commodity() {
      int properties = target.indexOf('[');
      return properties < 0 ? target : target.substring(0, properties);
    }
  }

  private static void queueInfrastructureReinspection(
      Connection connection, UUID order, Instant now) throws SQLException {
    UUID edge;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT source_id FROM repair_orders WHERE id=? AND source_type='INFRASTRUCTURE'")) {
      statement.setObject(1, order);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return;
        edge = result.getObject(1, UUID.class);
      }
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE road_edges SET route_state='DIRTY',integrity=100,bridge_integrity=100,capacity=0,blocked_at=coalesce(blocked_at,?),version=version+1 WHERE id=?")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, edge);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO dirty_road_edges(edge_id,reason,first_marked_at,last_marked_at,change_count) VALUES(?,'REPAIR_COMPLETED',?,?,1) ON CONFLICT(edge_id) DO UPDATE SET reason='REPAIR_COMPLETED',last_marked_at=excluded.last_marked_at,change_count=dirty_road_edges.change_count+1,lease_owner=NULL,lease_expires_at=NULL")) {
      statement.setObject(1, edge);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setTimestamp(3, Timestamp.from(now));
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE infrastructure_maintenance_orders SET status='REPAIRING',updated_at=? WHERE repair_order_id=? AND status='FUNDED'")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, order);
      statement.executeUpdate();
    }
  }

  private record InfrastructureMaintenance(UUID edge, String priority, UUID repairOrder) {}

  private record MaintenanceTarget(
      UUID world, int x, int y, int z, String expected, String target) {
    String commodity() {
      int properties = target.indexOf('[');
      return properties < 0 ? target : target.substring(0, properties);
    }
  }

  private record PositionKey(UUID world, int x, int y, int z) {}

  private record ReservationRow(
      UUID id, String sourceType, UUID source, String commodity, long reserved, long consumed) {}

  private record ConsumptionRow(UUID id, UUID reservation, String status) {}

  private record WorkerSelection(UUID id, UUID entity) {}

  private record TaskRow(
      UUID id,
      UUID order,
      UUID city,
      UUID world,
      int x,
      int y,
      int z,
      String expected,
      String target,
      String commodity,
      String layer,
      String status,
      UUID journal,
      UUID consumption,
      UUID leaseOwner) {}
}
