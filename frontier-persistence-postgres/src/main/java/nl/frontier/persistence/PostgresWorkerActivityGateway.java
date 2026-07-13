package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.npc.PlayerObservation;
import nl.frontier.npc.WorkerActivityGateway;

public final class PostgresWorkerActivityGateway implements WorkerActivityGateway {
  private static final Duration WORK_DURATION = Duration.ofSeconds(2);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
  private static final Duration ACTIVITY_COOLDOWN = Duration.ofMinutes(5);
  private final TransactionalStore store;

  public PostgresWorkerActivityGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CycleReport cycle(
      Set<PlayerObservation> observers,
      UUID scheduler,
      int maximum,
      Instant now,
      Instant leaseUntil) {
    if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
    return store.inTransaction(
        connection -> {
          completeWorking(connection, maximum, now);
          int recovered = recoverExpired(connection, maximum, now);
          int queued = synchronize(connection, maximum, now);
          Set<UUID> visibleCities = visibleCities(connection, observers);
          int simulated = simulateFar(connection, visibleCities, maximum, now);
          List<UUID> leased =
              leaseVisible(connection, visibleCities, scheduler, maximum, leaseUntil, now);
          List<Activity> visible =
              activeVisible(connection, visibleCities, scheduler, maximum, leaseUntil);
          return new CycleReport(queued, leased.size(), simulated, recovered, visible);
        });
  }

  @Override
  public void arrived(UUID activity, UUID worker, UUID scheduler, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE worker_activity_tasks SET status='WORKING',path_state='READY',updated_at=?,version=version+1 WHERE id=? AND worker_id=? AND lease_owner=? AND status='TRAVELLING' AND lease_expires_at>=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, activity);
            statement.setObject(3, worker);
            statement.setObject(4, scheduler);
            statement.setTimestamp(5, Timestamp.from(now));
            if (statement.executeUpdate() != 1)
              throw new DomainException("worker activity lease is no longer active");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE workers SET state='WORKING',version=version+1 WHERE id=? AND current_activity_id=?")) {
            statement.setObject(1, worker);
            statement.setObject(2, activity);
            statement.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public void failed(UUID activity, UUID worker, UUID scheduler, String reason, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE worker_activity_tasks SET status='QUEUED',path_state='UNREACHABLE',simulation_mode='PENDING',lease_owner=NULL,lease_expires_at=NULL,available_at=?,last_error=?,attempts=attempts+1,updated_at=?,version=version+1 WHERE id=? AND worker_id=? AND lease_owner=? AND status='TRAVELLING'")) {
            statement.setTimestamp(1, Timestamp.from(now.plus(RETRY_DELAY)));
            statement.setString(2, bounded(reason));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, activity);
            statement.setObject(5, worker);
            statement.setObject(6, scheduler);
            statement.executeUpdate();
          }
          releaseWorker(connection, activity, worker, now);
          return null;
        });
  }

  private static int synchronize(Connection connection, int maximum, Instant now)
      throws SQLException {
    List<ActivitySeed> seeds = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT w.id,w.city_id,w.profession,w.state,w.task_id,w.assigned_building,coalesce(rt.world_id,(b.bounds->>'world')::uuid,(wb.bounds->>'world')::uuid,cl.world_id),CASE WHEN rt.id IS NOT NULL THEN rt.x WHEN w.state='WAITING_MATERIALS' AND wb.id IS NOT NULL THEN ((wb.bounds->>'minX')::int+(wb.bounds->>'maxX')::int)/2 WHEN w.profession='BUILDER' THEN cl.chunk_x*16+8 ELSE ((b.bounds->>'minX')::int+(b.bounds->>'maxX')::int)/2 END,CASE WHEN rt.id IS NOT NULL THEN rt.y WHEN w.state='WAITING_MATERIALS' AND wb.id IS NOT NULL THEN (wb.bounds->>'minY')::int+1 WHEN w.profession='BUILDER' THEN 64 ELSE (b.bounds->>'minY')::int+1 END,CASE WHEN rt.id IS NOT NULL THEN rt.z WHEN w.state='WAITING_MATERIALS' AND wb.id IS NOT NULL THEN ((wb.bounds->>'minZ')::int+(wb.bounds->>'maxZ')::int)/2 WHEN w.profession='BUILDER' THEN cl.chunk_z*16+8 ELSE ((b.bounds->>'minZ')::int+(b.bounds->>'maxZ')::int)/2 END FROM workers w JOIN city_claims cl ON cl.city_id=w.city_id AND cl.state='CAPITAL' LEFT JOIN repair_tasks rt ON rt.id=w.task_id LEFT JOIN city_buildings b ON b.id=w.assigned_building AND b.status IN ('ACTIVE','DAMAGED') LEFT JOIN LATERAL (SELECT b2.id,b2.bounds FROM warehouses wh JOIN city_buildings b2 ON b2.id=wh.building_id WHERE wh.city_id=w.city_id AND wh.status='ACTIVE' AND b2.status IN ('ACTIVE','DAMAGED') ORDER BY b2.id LIMIT 1) wb ON true WHERE w.current_activity_id IS NULL AND w.next_activity_at<=? AND w.state<>'UNAVAILABLE' AND NOT EXISTS(SELECT 1 FROM worker_activity_tasks a WHERE a.worker_id=w.id AND a.status IN ('QUEUED','TRAVELLING','WORKING')) AND (rt.id IS NOT NULL OR b.id IS NOT NULL OR (w.state='WAITING_MATERIALS' AND wb.id IS NOT NULL)) ORDER BY CASE WHEN rt.id IS NOT NULL THEN 100 WHEN w.state='WAITING_MATERIALS' THEN 80 WHEN w.profession='GUARD' THEN 60 WHEN w.profession='BUILDER' THEN 55 ELSE 40 END DESC,w.next_activity_at,w.id LIMIT ? FOR UPDATE OF w SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setInt(2, maximum);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          UUID task = result.getObject(5, UUID.class);
          String profession = result.getString(3);
          String state = result.getString(4);
          String type =
              task != null
                  ? "REPAIR"
                  : state.equals("WAITING_MATERIALS")
                      ? "WAREHOUSE_WAIT"
                      : profession.equals("BUILDER")
                          ? "GUILD_EXIT"
                          : profession.equals("FARMER")
                              ? "FARM_VISIT"
                              : profession.equals("GUARD") ? "GUARD_POST" : "WORK_SHIFT";
          int priority =
              task != null
                  ? 100
                  : state.equals("WAITING_MATERIALS")
                      ? 80
                      : profession.equals("GUARD") ? 60 : profession.equals("BUILDER") ? 55 : 40;
          seeds.add(
              new ActivitySeed(
                  result.getObject(1, UUID.class),
                  result.getObject(2, UUID.class),
                  type,
                  priority,
                  result.getObject(7, UUID.class),
                  result.getInt(8),
                  result.getInt(9),
                  result.getInt(10),
                  task != null ? "REPAIR_TASK" : "BUILDING",
                  task != null ? task : result.getObject(6, UUID.class)));
        }
      }
    }
    int inserted = 0;
    for (ActivitySeed seed : seeds) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO worker_activity_tasks(id,worker_id,city_id,activity_type,priority,status,simulation_mode,target_world,target_x,target_y,target_z,path_state,source_type,source_id,available_at,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED','PENDING',?,?,?,?, 'PENDING',?,?,?, ?,?) ON CONFLICT(worker_id) WHERE status IN ('QUEUED','TRAVELLING','WORKING') DO NOTHING")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, seed.worker);
        statement.setObject(3, seed.city);
        statement.setString(4, seed.type);
        statement.setInt(5, seed.priority);
        statement.setObject(6, seed.world);
        statement.setInt(7, seed.x);
        statement.setInt(8, seed.y);
        statement.setInt(9, seed.z);
        statement.setString(10, seed.sourceType);
        statement.setObject(11, seed.source);
        statement.setTimestamp(12, Timestamp.from(now));
        statement.setTimestamp(13, Timestamp.from(now));
        statement.setTimestamp(14, Timestamp.from(now));
        inserted += statement.executeUpdate();
      }
    }
    return inserted;
  }

  private static Set<UUID> visibleCities(Connection connection, Set<PlayerObservation> observers)
      throws SQLException {
    if (observers.isEmpty()) return Set.of();
    Set<UUID> values = new HashSet<>();
    String rows = observers.stream().map(ignored -> "(?,?,?,?)").collect(Collectors.joining(","));
    String sql =
        "WITH observers(player_id,world_id,x,z) AS (VALUES "
            + rows
            + ") SELECT DISTINCT c.id FROM cities c JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL' JOIN observers o ON o.world_id=cl.world_id AND abs(cl.chunk_x*16+8-o.x)<=128 AND abs(cl.chunk_z*16+8-o.z)<=128 WHERE c.lifecycle_status='ACTIVE'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (PlayerObservation observer : observers) {
        statement.setObject(index++, observer.player());
        statement.setObject(index++, observer.world());
        statement.setInt(index++, observer.x());
        statement.setInt(index++, observer.z());
      }
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.add(result.getObject(1, UUID.class));
      }
    }
    return Set.copyOf(values);
  }

  private static int simulateFar(Connection connection, Set<UUID> visible, int maximum, Instant now)
      throws SQLException {
    List<TaskWorker> tasks = queued(connection, visible, false, maximum, now);
    for (TaskWorker task : tasks) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE worker_activity_tasks SET status='COMPLETED',simulation_mode='SIMULATED',path_state='SIMULATED',completed_at=?,updated_at=?,version=version+1 WHERE id=? AND status='QUEUED'")) {
        statement.setTimestamp(1, Timestamp.from(now));
        statement.setTimestamp(2, Timestamp.from(now));
        statement.setObject(3, task.task);
        statement.executeUpdate();
      }
      coolDownWorker(connection, task.task, task.worker, now);
    }
    return tasks.size();
  }

  private static List<UUID> leaseVisible(
      Connection connection,
      Set<UUID> visible,
      UUID scheduler,
      int maximum,
      Instant leaseUntil,
      Instant now)
      throws SQLException {
    List<TaskWorker> tasks = queued(connection, visible, true, maximum, now);
    List<UUID> leased = new ArrayList<>();
    for (TaskWorker task : tasks) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE worker_activity_tasks SET status='TRAVELLING',simulation_mode='PHYSICAL',path_state='REQUESTED',lease_owner=?,lease_expires_at=?,last_error=NULL,updated_at=?,version=version+1 WHERE id=? AND status='QUEUED'")) {
        statement.setObject(1, scheduler);
        statement.setTimestamp(2, Timestamp.from(leaseUntil));
        statement.setTimestamp(3, Timestamp.from(now));
        statement.setObject(4, task.task);
        if (statement.executeUpdate() != 1) continue;
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE workers SET current_activity_id=?,state='TRAVELLING',version=version+1 WHERE id=? AND current_activity_id IS NULL")) {
        statement.setObject(1, task.task);
        statement.setObject(2, task.worker);
        if (statement.executeUpdate() != 1)
          throw new DomainException("worker received another activity concurrently");
      }
      leased.add(task.task);
    }
    return List.copyOf(leased);
  }

  private static List<TaskWorker> queued(
      Connection connection, Set<UUID> visible, boolean includeVisible, int maximum, Instant now)
      throws SQLException {
    List<TaskWorker> values = new ArrayList<>();
    String filter;
    if (visible.isEmpty()) filter = includeVisible ? " AND false" : "";
    else {
      String placeholders = visible.stream().map(ignored -> "?").collect(Collectors.joining(","));
      filter = " AND city_id " + (includeVisible ? "IN" : "NOT IN") + " (" + placeholders + ")";
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,worker_id FROM worker_activity_tasks WHERE status='QUEUED' AND available_at<=?"
                + filter
                + " ORDER BY priority DESC,created_at,id LIMIT ? FOR UPDATE SKIP LOCKED")) {
      int index = 1;
      statement.setTimestamp(index++, Timestamp.from(now));
      for (UUID city : visible) statement.setObject(index++, city);
      statement.setInt(index, maximum);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.add(
              new TaskWorker(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
      }
    }
    return List.copyOf(values);
  }

  private static List<Activity> activeVisible(
      Connection connection, Set<UUID> visible, UUID scheduler, int maximum, Instant leaseUntil)
      throws SQLException {
    if (visible.isEmpty()) return List.of();
    String placeholders = visible.stream().map(ignored -> "?").collect(Collectors.joining(","));
    List<Activity> values = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE worker_activity_tasks SET lease_expires_at=?,version=version+1 WHERE lease_owner=? AND status IN ('TRAVELLING','WORKING') AND city_id IN ("
                + placeholders
                + ")")) {
      int index = 1;
      statement.setTimestamp(index++, Timestamp.from(leaseUntil));
      statement.setObject(index++, scheduler);
      for (UUID city : visible) statement.setObject(index++, city);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT a.id,a.worker_id,a.city_id,a.activity_type,a.target_world,a.target_x,a.target_y,a.target_z,w.presentation_entity_id,a.status FROM worker_activity_tasks a JOIN workers w ON w.id=a.worker_id WHERE a.lease_owner=? AND a.status IN ('TRAVELLING','WORKING') AND a.city_id IN ("
                + placeholders
                + ") ORDER BY a.priority DESC,a.created_at LIMIT ?")) {
      int index = 1;
      statement.setObject(index++, scheduler);
      for (UUID city : visible) statement.setObject(index++, city);
      statement.setInt(index, maximum);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.add(
              new Activity(
                  result.getObject(1, UUID.class),
                  result.getObject(2, UUID.class),
                  result.getObject(3, UUID.class),
                  result.getString(4),
                  result.getObject(5, UUID.class),
                  result.getInt(6),
                  result.getInt(7),
                  result.getInt(8),
                  result.getObject(9, UUID.class),
                  result.getString(10)));
      }
    }
    return List.copyOf(values);
  }

  private static void completeWorking(Connection connection, int maximum, Instant now)
      throws SQLException {
    List<TaskWorker> tasks = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,worker_id FROM worker_activity_tasks WHERE status='WORKING' AND updated_at<=? ORDER BY updated_at,id LIMIT ? FOR UPDATE SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now.minus(WORK_DURATION)));
      statement.setInt(2, maximum);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          tasks.add(
              new TaskWorker(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
      }
    }
    for (TaskWorker task : tasks) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE worker_activity_tasks SET status='COMPLETED',completed_at=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=?,version=version+1 WHERE id=?")) {
        statement.setTimestamp(1, Timestamp.from(now));
        statement.setTimestamp(2, Timestamp.from(now));
        statement.setObject(3, task.task);
        statement.executeUpdate();
      }
      coolDownWorker(connection, task.task, task.worker, now);
    }
  }

  private static int recoverExpired(Connection connection, int maximum, Instant now)
      throws SQLException {
    List<TaskWorker> tasks = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,worker_id FROM worker_activity_tasks WHERE status IN ('TRAVELLING','WORKING') AND lease_expires_at<? ORDER BY lease_expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setInt(2, maximum);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          tasks.add(
              new TaskWorker(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
      }
    }
    for (TaskWorker task : tasks) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE worker_activity_tasks SET status='QUEUED',simulation_mode='PENDING',path_state='PENDING',lease_owner=NULL,lease_expires_at=NULL,available_at=?,attempts=attempts+1,last_error='LEASE_EXPIRED',updated_at=?,version=version+1 WHERE id=?")) {
        statement.setTimestamp(1, Timestamp.from(now));
        statement.setTimestamp(2, Timestamp.from(now));
        statement.setObject(3, task.task);
        statement.executeUpdate();
      }
      releaseWorker(connection, task.task, task.worker, now);
    }
    return tasks.size();
  }

  private static void coolDownWorker(Connection connection, UUID activity, UUID worker, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE workers SET current_activity_id=NULL,next_activity_at=?,state=CASE WHEN task_id IS NOT NULL THEN 'TRAVELLING' WHEN state='UNAVAILABLE' THEN state ELSE 'IDLE' END,version=version+1 WHERE id=? AND current_activity_id=?")) {
      statement.setTimestamp(1, Timestamp.from(now.plus(ACTIVITY_COOLDOWN)));
      statement.setObject(2, worker);
      statement.setObject(3, activity);
      statement.executeUpdate();
    }
  }

  private static void releaseWorker(Connection connection, UUID activity, UUID worker, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE workers SET current_activity_id=NULL,next_activity_at=?,state=CASE WHEN task_id IS NOT NULL THEN 'TRAVELLING' WHEN state='UNAVAILABLE' THEN state ELSE 'IDLE' END,version=version+1 WHERE id=? AND current_activity_id=?")) {
      statement.setTimestamp(1, Timestamp.from(now.plus(RETRY_DELAY)));
      statement.setObject(2, worker);
      statement.setObject(3, activity);
      statement.executeUpdate();
    }
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "PATH_FAILED";
    return value.substring(0, Math.min(500, value.length()));
  }

  private record ActivitySeed(
      UUID worker,
      UUID city,
      String type,
      int priority,
      UUID world,
      int x,
      int y,
      int z,
      String sourceType,
      UUID source) {}

  private record TaskWorker(UUID task, UUID worker) {}
}
