package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.repair.BuilderGuildGateway;
import nl.frontier.repair.BuilderGuildPolicy;
import nl.frontier.repair.RepairOrder;

public final class PostgresBuilderGuildGateway implements BuilderGuildGateway {
  private static final Set<String> MANAGERS =
      Set.of("MAYOR", "ARCHITECT", "BUILDER_MASTER", "TREASURER");
  private final TransactionalStore store;
  private final BuilderGuildPolicy policy;

  public PostgresBuilderGuildGateway(TransactionalStore store) {
    this(store, BuilderGuildPolicy.defaults());
  }

  public PostgresBuilderGuildGateway(TransactionalStore store, BuilderGuildPolicy policy) {
    this.store = store;
    this.policy = policy;
  }

  @Override
  public Overview overview(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, false);
          UUID depot = ensureDepot(connection, city, policy);
          expireSessions(connection, now);
          return overview(connection, depot);
        });
  }

  @Override
  public Overview appointForeman(UUID city, UUID actor, UUID worker, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, true);
          UUID depot = ensureDepot(connection, city, policy);
          requireBuilder(connection, city, worker);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE builder_depots SET foreman_worker_id=?,version=version+1 WHERE id=?")) {
            statement.setObject(1, worker);
            statement.setObject(2, depot);
            statement.executeUpdate();
          }
          guildHistory(connection, depot, actor, "FOREMAN", "{\"worker\":\"" + worker + "\"}", now);
          return overview(connection, depot);
        });
  }

  @Override
  public Team createTeam(
      UUID city, UUID actor, String name, UUID foreman, List<UUID> builders, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, true);
          UUID depot = ensureDepot(connection, city, policy);
          String normalized = name == null ? "" : name.trim();
          if (normalized.isEmpty() || normalized.length() > 48)
            throw new DomainException("team name must contain 1 to 48 characters");
          LinkedHashSet<UUID> roster = new LinkedHashSet<>(builders);
          roster.add(foreman);
          int tier;
          int teamCapacity;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT tier,team_capacity FROM builder_depots WHERE id=? FOR UPDATE"); ) {
            statement.setObject(1, depot);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              tier = result.getInt(1);
              teamCapacity = result.getInt(2);
            }
          }
          if (count(
                  connection,
                  "SELECT count(*) FROM builder_teams WHERE depot_id=? AND status='ACTIVE'",
                  depot)
              >= teamCapacity) throw new DomainException("Builder Guild team capacity is full");
          int workerCapacity = policy.baseWorkersPerTeam() + tier;
          if (roster.isEmpty() || roster.size() > workerCapacity)
            throw new DomainException("team exceeds tier worker capacity " + workerCapacity);
          for (UUID worker : roster) requireBuilder(connection, city, worker);
          UUID team = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO builder_teams(id,depot_id,name,foreman_worker_id,priority,worker_capacity,status,created_at,updated_at,version) VALUES(?,?,?,?,50,?,'ACTIVE',?,?,0)")) {
            statement.setObject(1, team);
            statement.setObject(2, depot);
            statement.setString(3, normalized);
            statement.setObject(4, foreman);
            statement.setInt(5, workerCapacity);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          for (UUID worker : roster) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO builder_team_workers(team_id,worker_id,assigned_at) VALUES(?,?,?)")) {
              statement.setObject(1, team);
              statement.setObject(2, worker);
              statement.setTimestamp(3, Timestamp.from(now));
              statement.executeUpdate();
            }
          }
          guildHistory(
              connection, depot, actor, "TEAM_CREATED", "{\"team\":\"" + team + "\"}", now);
          return new Team(team, normalized, foreman, roster.size(), workerCapacity, 50);
        });
  }

  @Override
  public Project prioritize(
      UUID city, UUID actor, UUID order, RepairOrder.Priority priority, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, true);
          requireOrder(connection, city, order, true);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET priority=?,version=version+1 WHERE id=?")) {
            statement.setString(1, priority.name());
            statement.setObject(2, order);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET priority_score=?,updated_at=?,version=version+1 WHERE repair_order_id=? AND status NOT IN ('COMPLETED','CANCELLED')")) {
            statement.setInt(1, priorityScore(priority));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, order);
            statement.executeUpdate();
          }
          history(
              connection,
              order,
              null,
              "GUILD_PRIORITY",
              "{\"priority\":\"" + priority + "\"}",
              now,
              city);
          return project(connection, order);
        });
  }

  @Override
  public Project emergency(UUID city, UUID actor, UUID order, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, true);
          UUID depot = ensureDepot(connection, city, policy);
          requireOrder(connection, city, order, true);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET emergency=TRUE,priority='CRITICAL',contribution_boost=greatest(contribution_boost,100),version=version+1 WHERE id=?")) {
            statement.setObject(1, order);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET priority_score=greatest(priority_score,1000),updated_at=?,version=version+1 WHERE repair_order_id=? AND status NOT IN ('COMPLETED','CANCELLED')")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, order);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE builder_depots SET emergency_order_id=?,version=version+1 WHERE id=?")) {
            statement.setObject(1, order);
            statement.setObject(2, depot);
            statement.executeUpdate();
          }
          history(connection, order, null, "GUILD_EMERGENCY", "{}", now, city);
          guildHistory(connection, depot, actor, "EMERGENCY", "{\"order\":\"" + order + "\"}", now);
          return project(connection, order);
        });
  }

  @Override
  public Contribution deliver(
      UUID city,
      UUID actor,
      UUID order,
      String commodity,
      long units,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          idempotencyLock(connection, idempotency);
          requireMember(connection, city, actor, false);
          UUID depot = ensureDepot(connection, city, policy);
          requireOrder(connection, city, order, true);
          if (units <= 0 || units > policy.maximumDeliveryUnits())
            throw new DomainException(
                "delivery units must be 1 to " + policy.maximumDeliveryUnits());
          String normalized = commodity.toLowerCase(Locale.ROOT);
          Contribution replay = contribution(connection, idempotency);
          if (replay != null) return replay;
          long capacity;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT capacity_units FROM builder_depots WHERE id=? FOR UPDATE")) {
            statement.setObject(1, depot);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              capacity = result.getLong(1);
            }
          }
          long stored =
              countLong(
                  connection,
                  "SELECT coalesce(sum(available_quantity+reserved_quantity),0) FROM builder_depot_stock WHERE depot_id=?",
                  depot);
          if (stored + units > capacity)
            throw new DomainException("Builder Guild material depot is full");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO builder_depot_stock(depot_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,?,?,0,0) ON CONFLICT(depot_id,commodity_key) DO UPDATE SET available_quantity=builder_depot_stock.available_quantity+excluded.available_quantity,version=builder_depot_stock.version+1")) {
            statement.setObject(1, depot);
            statement.setString(2, normalized);
            statement.setLong(3, units);
            statement.executeUpdate();
          }
          long shortage = shortage(connection, order, normalized);
          long reserved = Math.min(units, shortage);
          if (reserved > 0) reserveDepot(connection, depot, order, normalized, reserved, now);
          UUID contribution = UUID.randomUUID();
          insertContribution(
              connection,
              contribution,
              depot,
              order,
              actor,
              "MATERIAL",
              normalized,
              units,
              idempotency,
              now);
          replenishOrder(connection, order, now);
          guildHistory(
              connection,
              depot,
              actor,
              "MATERIAL",
              "{\"order\":\"" + order + "\",\"units\":" + units + "}",
              now);
          return new Contribution(contribution, order, "MATERIAL", normalized, units, "COMMITTED");
        });
  }

  @Override
  public Contribution boost(
      UUID city, UUID actor, UUID order, int points, UUID idempotency, Instant now) {
    return store.inTransaction(
        connection -> {
          idempotencyLock(connection, idempotency);
          requireMember(connection, city, actor, false);
          UUID depot = ensureDepot(connection, city, policy);
          requireOrder(connection, city, order, true);
          if (points <= 0 || points > policy.maximumBoostPerAction())
            throw new DomainException(
                "boost points must be 1 to " + policy.maximumBoostPerAction());
          Contribution replay = contribution(connection, idempotency);
          if (replay != null) return replay;
          Instant day = now.truncatedTo(ChronoUnit.DAYS);
          long used;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT coalesce(sum(units),0) FROM builder_guild_contributions WHERE player_id=? AND repair_order_id=? AND contribution_kind='LABOR' AND contributed_at>=?")) {
            statement.setObject(1, actor);
            statement.setObject(2, order);
            statement.setTimestamp(3, Timestamp.from(day));
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              used = result.getLong(1);
            }
          }
          if (used + points > policy.dailyBoostLimit())
            throw new DomainException("daily project boost limit is " + policy.dailyBoostLimit());
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET contribution_boost=least(10000,contribution_boost+?),version=version+1 WHERE id=?")) {
            statement.setInt(1, points);
            statement.setObject(2, order);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET priority_score=least(10000,priority_score+?),updated_at=?,version=version+1 WHERE repair_order_id=? AND status NOT IN ('COMPLETED','CANCELLED')")) {
            statement.setInt(1, points);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, order);
            statement.executeUpdate();
          }
          UUID contribution = UUID.randomUUID();
          insertContribution(
              connection,
              contribution,
              depot,
              order,
              actor,
              "LABOR",
              null,
              points,
              idempotency,
              now);
          guildHistory(
              connection,
              depot,
              actor,
              "BOOST",
              "{\"order\":\"" + order + "\",\"points\":" + points + "}",
              now);
          return new Contribution(contribution, order, "LABOR", null, points, "COMMITTED");
        });
  }

  @Override
  public RepairZone inspect(UUID city, UUID actor, UUID world, int x, int y, int z, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, false);
          return zone(connection, city, world, x, y, z);
        });
  }

  @Override
  public RepairZone resolveConflict(UUID city, UUID actor, UUID conflict, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, true);
          UUID task;
          UUID world;
          int x;
          int y;
          int z;
          String actual;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT t.id,t.world_id,t.x,t.y,t.z,c.actual_data FROM repair_conflicts c JOIN repair_tasks t ON t.id=c.repair_task_id JOIN repair_orders o ON o.id=t.repair_order_id WHERE c.id=? AND o.city_id=? AND c.resolved_at IS NULL FOR UPDATE OF c,t")) {
            statement.setObject(1, conflict);
            statement.setObject(2, city);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("open repair conflict not found");
              task = result.getObject(1, UUID.class);
              world = result.getObject(2, UUID.class);
              x = result.getInt(3);
              y = result.getInt(4);
              z = result.getInt(5);
              actual = result.getString(6);
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_conflicts SET resolved_at=? WHERE id=? AND resolved_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, conflict);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET expected_current=?,status='READY',attempts=0,last_error=NULL,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setString(1, actual);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, task);
            statement.executeUpdate();
          }
          return zone(connection, city, world, x, y, z);
        });
  }

  @Override
  public AssistPlan beginAssist(UUID city, UUID actor, UUID order, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor, false);
          ensureDepot(connection, city, policy);
          requireOrder(connection, city, order, true);
          if (!expiresAt.isAfter(now)
              || expiresAt.isAfter(now.plusSeconds(policy.assistSessionSeconds())))
            throw new DomainException(
                "repair mode duration must be at most "
                    + policy.assistSessionSeconds()
                    + " seconds");
          expireSessions(connection, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_assist_sessions SET status='CANCELLED',version=version+1 WHERE player_id=? AND status='ACTIVE'")) {
            statement.setObject(1, actor);
            statement.executeUpdate();
          }
          List<AssistTask> tasks = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT t.id,t.world_id,t.x,t.y,t.z,t.expected_current,t.target_data FROM repair_tasks t WHERE t.repair_order_id=? AND t.status IN ('READY','WAITING_MATERIAL') AND t.lease_owner IS NULL AND NOT EXISTS(SELECT 1 FROM repair_conflicts c WHERE c.repair_task_id=t.id AND c.resolved_at IS NULL) ORDER BY t.priority_score DESC,t.layer,t.y,t.id LIMIT ?")) {
            statement.setObject(1, order);
            statement.setInt(2, policy.maximumAssistTasks());
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                tasks.add(
                    new AssistTask(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        result.getInt(3),
                        result.getInt(4),
                        result.getInt(5),
                        result.getString(6),
                        result.getString(7)));
            }
          }
          if (tasks.isEmpty())
            throw new DomainException("repair order has no player-repairable tasks");
          UUID session = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO repair_assist_sessions(id,city_id,repair_order_id,player_id,status,created_at,expires_at,version) VALUES(?,?,?,?,'ACTIVE',?,?,0)")) {
            statement.setObject(1, session);
            statement.setObject(2, city);
            statement.setObject(3, order);
            statement.setObject(4, actor);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(expiresAt));
            statement.executeUpdate();
          }
          return new AssistPlan(session, order, expiresAt, tasks);
        });
  }

  @Override
  public ManualResult completeManual(
      UUID session,
      UUID actor,
      UUID task,
      UUID world,
      int x,
      int y,
      int z,
      String placedData,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          idempotencyLock(connection, idempotency);
          ManualResult replay = manualReplay(connection, idempotency);
          if (replay != null) return replay;
          UUID city;
          UUID order;
          UUID depot;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT s.city_id,s.repair_order_id,d.id FROM repair_assist_sessions s JOIN builder_depots d ON d.city_id=s.city_id WHERE s.id=? AND s.player_id=? AND s.status='ACTIVE' AND s.expires_at>=? FOR UPDATE OF s")) {
            statement.setObject(1, session);
            statement.setObject(2, actor);
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("repair mode is no longer active");
              city = result.getObject(1, UUID.class);
              order = result.getObject(2, UUID.class);
              depot = result.getObject(3, UUID.class);
            }
          }
          requireMember(connection, city, actor, false);
          UUID journal;
          String target;
          String commodity;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT journal_id,target_data,commodity_key FROM repair_tasks WHERE id=? AND repair_order_id=? AND world_id=? AND x=? AND y=? AND z=? AND status IN ('READY','WAITING_MATERIAL') AND lease_owner IS NULL FOR UPDATE")) {
            statement.setObject(1, task);
            statement.setObject(2, order);
            statement.setObject(3, world);
            statement.setInt(4, x);
            statement.setInt(5, y);
            statement.setInt(6, z);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next())
                throw new DomainException("manual repair task is no longer available");
              journal = result.getObject(1, UUID.class);
              target = result.getString(2);
              commodity = result.getString(3);
            }
          }
          if (!target.equals(placedData))
            throw new DomainException("placed block does not match repair plan");
          UUID claim = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO manual_repair_claims(id,session_id,repair_task_id,player_id,placed_data,status,idempotency_key,completed_at) VALUES(?,?,?,?,?,'COMMITTED',?,?)")) {
            statement.setObject(1, claim);
            statement.setObject(2, session);
            statement.setObject(3, task);
            statement.setObject(4, actor);
            statement.setString(5, placedData);
            statement.setObject(6, idempotency);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_tasks SET status='COMPLETED',last_error=NULL,updated_at=?,version=version+1 WHERE id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, task);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE damage_journal SET repair_state='COMPLETED',version=version+1 WHERE id=?")) {
            statement.setObject(1, journal);
            statement.executeUpdate();
          }
          releaseOneReservation(connection, order, commodity);
          int completed =
              count(
                  connection,
                  "SELECT count(*) FROM repair_tasks WHERE repair_order_id=? AND status='COMPLETED'",
                  order);
          int total =
              count(connection, "SELECT count(*) FROM repair_tasks WHERE repair_order_id=?", order);
          String status = completed == total ? "COMPLETED" : "REPAIRING";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE repair_orders SET completed_tasks=?,status=?,completed_at=CASE WHEN ?='COMPLETED' THEN ? ELSE completed_at END,version=version+1 WHERE id=?")) {
            statement.setInt(1, completed);
            statement.setString(2, status);
            statement.setString(3, status);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setObject(5, order);
            statement.executeUpdate();
          }
          insertContribution(
              connection,
              UUID.randomUUID(),
              depot,
              order,
              actor,
              "MANUAL_REPAIR",
              commodity,
              1,
              idempotency,
              now);
          if (completed == total) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE repair_assist_sessions SET status='COMPLETED',version=version+1 WHERE id=?")) {
              statement.setObject(1, session);
              statement.executeUpdate();
            }
          }
          history(
              connection,
              order,
              task,
              "PLAYER_REPAIR",
              "{\"player\":\"" + actor + "\"}",
              now,
              city);
          guildHistory(
              connection, depot, actor, "MANUAL_REPAIR", "{\"task\":\"" + task + "\"}", now);
          return new ManualResult(task, order, completed, total, status);
        });
  }

  private Overview overview(Connection connection, UUID depot) throws SQLException {
    UUID building;
    int tier;
    long capacity;
    int teamCapacity;
    UUID foreman;
    UUID city;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT building_id,tier,capacity_units,team_capacity,foreman_worker_id,city_id FROM builder_depots WHERE id=?")) {
      statement.setObject(1, depot);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        building = result.getObject(1, UUID.class);
        tier = result.getInt(2);
        capacity = result.getLong(3);
        teamCapacity = result.getInt(4);
        foreman = result.getObject(5, UUID.class);
        city = result.getObject(6, UUID.class);
      }
    }
    long stored =
        countLong(
            connection,
            "SELECT coalesce(sum(available_quantity+reserved_quantity),0) FROM builder_depot_stock WHERE depot_id=?",
            depot);
    int availableBuilders =
        count(
            connection,
            "SELECT count(*) FROM workers WHERE city_id=? AND profession='BUILDER' AND state='IDLE' AND employment_status='EMPLOYED'",
            city);
    int readyTasks =
        count(
            connection,
            "SELECT count(*) FROM repair_tasks t JOIN repair_orders o ON o.id=t.repair_order_id WHERE o.city_id=? AND o.status NOT IN ('COMPLETED','ARCHIVED','CANCELLED') AND t.status IN ('READY','WAITING_MATERIAL')",
            city);
    List<Team> teams = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.id,t.name,t.foreman_worker_id,count(w.worker_id),t.worker_capacity,t.priority FROM builder_teams t LEFT JOIN builder_team_workers w ON w.team_id=t.id WHERE t.depot_id=? AND t.status='ACTIVE' GROUP BY t.id ORDER BY t.priority DESC,t.name"); ) {
      statement.setObject(1, depot);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          teams.add(
              new Team(
                  result.getObject(1, UUID.class),
                  result.getString(2),
                  result.getObject(3, UUID.class),
                  result.getInt(4),
                  result.getInt(5),
                  result.getInt(6)));
      }
    }
    List<Project> projects = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM repair_orders WHERE city_id=? AND status NOT IN ('ARCHIVED','CANCELLED') ORDER BY emergency DESC,priority,created_at")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) projects.add(project(connection, result.getObject(1, UUID.class)));
      }
    }
    return new Overview(
        depot,
        building,
        tier,
        capacity,
        stored,
        teamCapacity,
        foreman,
        availableBuilders,
        Math.max(
            0,
            Math.min(readyTasks, teamCapacity * (policy.baseWorkersPerTeam() + tier))
                - availableBuilders),
        teams,
        projects);
  }

  private static Project project(Connection connection, UUID order) throws SQLException {
    RepairOrder.Priority priority;
    String status;
    int completed;
    int total;
    boolean emergency;
    int boost;
    UUID city;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT priority,status,completed_tasks,total_tasks,emergency,contribution_boost,city_id FROM repair_orders WHERE id=?")) {
      statement.setObject(1, order);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("repair order not found");
        priority = RepairOrder.Priority.valueOf(result.getString(1));
        status = result.getString(2);
        completed = result.getInt(3);
        total = result.getInt(4);
        emergency = result.getBoolean(5);
        boost = result.getInt(6);
        city = result.getObject(7, UUID.class);
      }
    }
    LinkedHashSet<String> blocked = new LinkedHashSet<>();
    if (status.equals("WAITING_PAYMENT")) blocked.add("WAITING_PAYMENT");
    if (status.equals("PAUSED_UNSAFE")) blocked.add("UNSAFE_ZONE");
    if (status.equals("PAUSED_MATERIAL") || hasShortage(connection, order))
      blocked.add("MATERIAL_SHORTAGE");
    if (count(
            connection,
            "SELECT count(*) FROM repair_conflicts c JOIN repair_tasks t ON t.id=c.repair_task_id WHERE t.repair_order_id=? AND c.resolved_at IS NULL",
            order)
        > 0) blocked.add("CONFLICT");
    if (count(
                connection,
                "SELECT count(*) FROM workers WHERE city_id=? AND profession='BUILDER' AND state='IDLE' AND employment_status='EMPLOYED'",
                city)
            == 0
        && completed < total) blocked.add("WORKER_SHORTAGE");
    return new Project(
        order, priority, status, completed, total, emergency, boost, List.copyOf(blocked));
  }

  private static RepairZone zone(Connection connection, UUID city, UUID world, int x, int y, int z)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.id,t.repair_order_id,t.status,t.expected_current,t.target_data,c.id,t.last_error FROM repair_tasks t JOIN repair_orders o ON o.id=t.repair_order_id LEFT JOIN repair_conflicts c ON c.repair_task_id=t.id AND c.resolved_at IS NULL WHERE o.city_id=? AND t.world_id=? AND t.x=? AND t.y=? AND t.z=? ORDER BY o.created_at DESC LIMIT 1")) {
      statement.setObject(1, city);
      statement.setObject(2, world);
      statement.setInt(3, x);
      statement.setInt(4, y);
      statement.setInt(5, z);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("target block is not in a repair zone");
        String blocked = result.getString(7);
        if (result.getObject(6) != null) blocked = "CONFLICT";
        return new RepairZone(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getString(3),
            result.getString(4),
            result.getString(5),
            result.getObject(6, UUID.class),
            blocked);
      }
    }
  }

  private static UUID ensureDepot(Connection connection, UUID city, BuilderGuildPolicy policy)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended('builder-depot:' || ?::text,0))")) {
      statement.setObject(1, city);
      statement.executeQuery().close();
    }
    int level;
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT level FROM cities WHERE id=? FOR SHARE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("settlement not found");
        level = result.getInt(1);
      }
    }
    int tier = Math.max(1, Math.min(policy.maximumTier(), level - policy.levelOffset()));
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM builder_depots WHERE city_id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          UUID depot = result.getObject(1, UUID.class);
          try (PreparedStatement update =
              connection.prepareStatement(
                  "UPDATE builder_depots SET tier=greatest(tier,?),capacity_units=greatest(capacity_units,?),team_capacity=greatest(team_capacity,?),version=version+1 WHERE id=?")) {
            update.setInt(1, tier);
            update.setLong(2, tier * policy.capacityUnitsPerTier());
            update.setInt(3, tier * policy.teamsPerTier());
            update.setObject(4, depot);
            update.executeUpdate();
          }
          return depot;
        }
      }
    }
    UUID building;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM city_buildings WHERE city_id=? AND building_type='BUILDER_GUILD' AND status IN ('ACTIVE','DAMAGED') AND integrity>=40 ORDER BY integrity DESC,id LIMIT 1 FOR SHARE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("an operational Builder Guild is required");
        building = result.getObject(1, UUID.class);
      }
    }
    UUID depot = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO builder_depots(id,city_id,building_id,tier,capacity_units,status,version,team_capacity) VALUES(?,?,?,?,?,'ACTIVE',0,?)")) {
      statement.setObject(1, depot);
      statement.setObject(2, city);
      statement.setObject(3, building);
      statement.setInt(4, tier);
      statement.setLong(5, tier * policy.capacityUnitsPerTier());
      statement.setInt(6, tier * policy.teamsPerTier());
      statement.executeUpdate();
    }
    return depot;
  }

  private static void requireMember(Connection connection, UUID city, UUID actor, boolean manage)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT m.role,c.lifecycle_status FROM city_members m JOIN cities c ON c.id=m.city_id WHERE m.city_id=? AND m.player_id=? FOR SHARE OF m,c")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("you are not a settlement member");
        if (!result.getString(2).equals("ACTIVE"))
          throw new DomainException("settlement is not active");
        if (manage && !MANAGERS.contains(result.getString(1)))
          throw new DomainException("settlement role cannot manage the Builder Guild");
      }
    }
  }

  private static void requireBuilder(Connection connection, UUID city, UUID worker)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM workers WHERE id=? AND city_id=? AND profession='BUILDER' AND employment_status='EMPLOYED' AND state<>'UNAVAILABLE' FOR SHARE")) {
      statement.setObject(1, worker);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("active settlement Builder not found");
      }
    }
  }

  private static void requireOrder(Connection connection, UUID city, UUID order, boolean open)
      throws SQLException {
    String sql =
        "SELECT status FROM repair_orders WHERE id=? AND city_id=?"
            + (open ? " AND status NOT IN ('COMPLETED','ARCHIVED','CANCELLED')" : "")
            + " FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, order);
      statement.setObject(2, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("open settlement repair order not found");
      }
    }
  }

  private static void insertContribution(
      Connection connection,
      UUID id,
      UUID depot,
      UUID order,
      UUID actor,
      String kind,
      String commodity,
      long units,
      UUID idempotency,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO builder_guild_contributions(id,depot_id,repair_order_id,player_id,contribution_kind,commodity_key,units,status,idempotency_key,contributed_at) VALUES(?,?,?,?,?,?,?,'COMMITTED',?,?)")) {
      statement.setObject(1, id);
      statement.setObject(2, depot);
      statement.setObject(3, order);
      statement.setObject(4, actor);
      statement.setString(5, kind);
      statement.setString(6, commodity);
      statement.setLong(7, units);
      statement.setObject(8, idempotency);
      statement.setTimestamp(9, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static Contribution contribution(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,repair_order_id,contribution_kind,commodity_key,units,status FROM builder_guild_contributions WHERE idempotency_key=?")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new Contribution(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getString(3),
                result.getString(4),
                result.getLong(5),
                result.getString(6))
            : null;
      }
    }
  }

  private static long shortage(Connection connection, UUID order, String commodity)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT greatest(0,(SELECT count(*) FROM repair_tasks WHERE repair_order_id=? AND commodity_key=? AND status NOT IN ('COMPLETED','CANCELLED'))-coalesce((SELECT sum(reserved_quantity-consumed_quantity) FROM material_reservations WHERE repair_order_id=? AND commodity_key=? AND status IN ('RESERVED','ISSUED')),0))")) {
      statement.setObject(1, order);
      statement.setString(2, commodity);
      statement.setObject(3, order);
      statement.setString(4, commodity);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static boolean hasShortage(Connection connection, UUID order) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM repair_tasks t WHERE t.repair_order_id=? AND t.status NOT IN ('COMPLETED','CANCELLED') GROUP BY t.commodity_key HAVING count(*)>coalesce((SELECT sum(r.reserved_quantity-r.consumed_quantity) FROM material_reservations r WHERE r.repair_order_id=? AND r.commodity_key=t.commodity_key AND r.status IN ('RESERVED','ISSUED')),0))")) {
      statement.setObject(1, order);
      statement.setObject(2, order);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private static void reserveDepot(
      Connection connection, UUID depot, UUID order, String commodity, long units, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE builder_depot_stock SET available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1 WHERE depot_id=? AND commodity_key=? AND available_quantity>=?")) {
      statement.setLong(1, units);
      statement.setLong(2, units);
      statement.setObject(3, depot);
      statement.setString(4, commodity);
      statement.setLong(5, units);
      if (statement.executeUpdate() != 1)
        throw new DomainException("depot contribution disappeared");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO material_reservations(id,repair_order_id,source_type,source_id,commodity_key,reserved_quantity,consumed_quantity,status,expires_at,version) VALUES(?,?,'DEPOT',?,?,?,0,'RESERVED',?,0)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, order);
      statement.setObject(3, depot);
      statement.setString(4, commodity);
      statement.setLong(5, units);
      statement.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)));
      statement.executeUpdate();
    }
  }

  private static void replenishOrder(Connection connection, UUID order, Instant now)
      throws SQLException {
    if (hasShortage(connection, order)) return;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_orders SET status=CASE WHEN status='PAUSED_MATERIAL' THEN 'RESERVED' ELSE status END,version=version+1 WHERE id=?")) {
      statement.setObject(1, order);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_tasks SET status='READY',updated_at=?,version=version+1 WHERE repair_order_id=? AND status='WAITING_MATERIAL'")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setObject(2, order);
      statement.executeUpdate();
    }
  }

  private static void releaseOneReservation(Connection connection, UUID order, String commodity)
      throws SQLException {
    UUID reservation;
    String sourceType;
    UUID source;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,source_type,source_id FROM material_reservations WHERE repair_order_id=? AND commodity_key=? AND status IN ('RESERVED','ISSUED') AND reserved_quantity>consumed_quantity ORDER BY source_type,id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setObject(1, order);
      statement.setString(2, commodity);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return;
        reservation = result.getObject(1, UUID.class);
        sourceType = result.getString(2);
        source = result.getObject(3, UUID.class);
      }
    }
    String stockSql =
        sourceType.equals("DEPOT")
            ? "UPDATE builder_depot_stock SET reserved_quantity=reserved_quantity-1,available_quantity=available_quantity+1,version=version+1 WHERE depot_id=? AND commodity_key=? AND reserved_quantity>=1"
            : "UPDATE warehouse_stock SET reserved_quantity=reserved_quantity-1,available_quantity=available_quantity+1,version=version+1 WHERE warehouse_id=? AND commodity_key=? AND reserved_quantity>=1";
    try (PreparedStatement statement = connection.prepareStatement(stockSql)) {
      statement.setObject(1, source);
      statement.setString(2, commodity);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE material_reservations SET reserved_quantity=CASE WHEN reserved_quantity>1 THEN reserved_quantity-1 ELSE reserved_quantity END,consumed_quantity=CASE WHEN reserved_quantity=1 AND consumed_quantity=0 THEN 1 ELSE consumed_quantity END,status=CASE WHEN reserved_quantity-consumed_quantity=1 THEN 'CONSUMED' ELSE status END,version=version+1 WHERE id=? AND reserved_quantity>consumed_quantity")) {
      statement.setObject(1, reservation);
      statement.executeUpdate();
    }
  }

  private static void expireSessions(Connection connection, Instant now) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE repair_assist_sessions SET status='EXPIRED',version=version+1 WHERE status='ACTIVE' AND expires_at<?")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static ManualResult manualReplay(Connection connection, UUID idempotency)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT t.id,t.repair_order_id,o.completed_tasks,o.total_tasks,o.status FROM manual_repair_claims c JOIN repair_tasks t ON t.id=c.repair_task_id JOIN repair_orders o ON o.id=t.repair_order_id WHERE c.idempotency_key=? AND c.status='COMMITTED'")) {
      statement.setObject(1, idempotency);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new ManualResult(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getInt(3),
                result.getInt(4),
                result.getString(5))
            : null;
      }
    }
  }

  private static int priorityScore(RepairOrder.Priority priority) {
    return switch (priority) {
      case CRITICAL -> 500;
      case HIGH -> 300;
      case NORMAL -> 200;
      case LOW -> 100;
      case COSMETIC -> 25;
    };
  }

  private static void idempotencyLock(Connection connection, UUID idempotency) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended('builder-guild:' || ?::text,0))")) {
      statement.setObject(1, idempotency);
      statement.executeQuery().close();
    }
  }

  private static int count(Connection connection, String sql, UUID value) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, value);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static long countLong(Connection connection, String sql, UUID value) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, value);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void history(
      Connection connection,
      UUID order,
      UUID task,
      String event,
      String payload,
      Instant now,
      UUID city)
      throws SQLException {
    if (order == null) return;
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

  private static void guildHistory(
      Connection connection, UUID depot, UUID actor, String event, String payload, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO builder_guild_history(id,depot_id,actor_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, depot);
      statement.setObject(3, actor);
      statement.setString(4, event);
      statement.setString(5, payload);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.executeUpdate();
    }
  }
}
