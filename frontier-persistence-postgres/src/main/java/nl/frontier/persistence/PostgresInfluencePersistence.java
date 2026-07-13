package nl.frontier.persistence;

import static nl.frontier.domain.Ids.SettlementId;
import static nl.frontier.domain.Ids.WorldId;
import static nl.frontier.domain.Position.ChunkPos;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.SettlementLevel;
import nl.frontier.domain.DomainException;
import nl.frontier.influence.ChunkOwnershipCache;
import nl.frontier.influence.InfluenceEngine;
import nl.frontier.influence.InfluencePersistence;
import nl.frontier.influence.TerritoryState;

public final class PostgresInfluencePersistence implements InfluencePersistence {
  private final TransactionalStore store;

  public PostgresInfluencePersistence(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public List<Dirty> leaseDirty(UUID worker, int limit, Instant now, Instant leaseUntil) {
    return store.inTransaction(
        connection -> {
          List<Dirty> result = new ArrayList<>();
          try (var statement =
              connection.prepareStatement(
                  "SELECT city_id,reason FROM dirty_settlements WHERE lease_expires_at IS NULL OR lease_expires_at<? ORDER BY enqueued_at LIMIT ? FOR UPDATE SKIP LOCKED")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(now));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next())
                result.add(
                    new Dirty(new SettlementId(rows.getObject(1, UUID.class)), rows.getString(2)));
            }
          }
          for (Dirty item : result)
            update(
                connection,
                "UPDATE dirty_settlements SET lease_owner=?,lease_expires_at=? WHERE city_id=?",
                worker,
                leaseUntil,
                item.city().value());
          return List.copyOf(result);
        });
  }

  @Override
  public Context load(SettlementId city) {
    return store.inTransaction(
        connection -> {
          int level;
          int population;
          int prosperity;
          try (var statement =
              connection.prepareStatement(
                  "SELECT level,population,prosperity FROM cities WHERE id=?")) {
            statement.setObject(1, city.value());
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) throw new DomainException("dirty settlement no longer exists");
              level = row.getInt(1);
              population = row.getInt(2);
              prosperity = row.getInt(3);
            }
          }
          ChunkPos core;
          try (var statement =
              connection.prepareStatement(
                  "SELECT world_id,chunk_x,chunk_z FROM city_claims WHERE city_id=? AND state='CAPITAL'")) {
            statement.setObject(1, city.value());
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) throw new DomainException("settlement core claim is missing");
              core =
                  new ChunkPos(
                      new WorldId(row.getObject(1, UUID.class)), row.getInt(2), row.getInt(3));
            }
          }
          int buildings =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM city_buildings WHERE city_id=? AND integrity>=40",
                  city.value());
          int unpaid =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM maintenance_invoices WHERE city_id=? AND status IN ('DUE','OVERDUE')",
                  city.value());
          int damage =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM damage_journal WHERE city_id=? AND repair_state NOT IN ('REPAIRED','IGNORED')",
                  city.value());
          int claims =
              scalarInt(
                  connection, "SELECT count(*) FROM city_claims WHERE city_id=?", city.value());
          int maximum = SettlementLevel.values()[level - 1].claims();
          int corruption = Math.max(0, claims - maximum) * 2;
          InfluenceEngine.Factors factors =
              new InfluenceEngine.Factors(
                  maximum,
                  population / 10,
                  prosperity / 10,
                  buildings * 2,
                  unpaid * 5,
                  Math.min(20, damage / 25),
                  corruption);
          Set<ChunkPos> blocked =
              queryPositions(
                  connection,
                  "SELECT world_id,chunk_x,chunk_z FROM city_claims WHERE world_id=? AND state='CAPITAL' AND city_id<>?",
                  core.world().value(),
                  city.value());
          Set<ChunkPos> roads =
              queryPositions(
                  connection,
                  "SELECT world_id,floor(x/16.0)::int,floor(z/16.0)::int FROM road_nodes WHERE city_id=? AND integrity>=30",
                  city.value());
          Set<ChunkPos> previous =
              queryPositions(
                  connection,
                  "SELECT world_id,chunk_x,chunk_z FROM influence_scores WHERE city_id=?",
                  city.value());
          return new Context(city, core, maximum, factors, blocked, roads, previous);
        });
  }

  @Override
  public Map<ChunkPos, Map<SettlementId, Score>> scores(Set<ChunkPos> positions) {
    return store.inTransaction(
        connection -> {
          Map<ChunkPos, Map<SettlementId, Score>> result = new HashMap<>();
          for (ChunkPos position : positions) {
            Map<SettlementId, Score> values = new HashMap<>();
            try (var statement =
                connection.prepareStatement(
                    "SELECT city_id,score,consecutive_lead_cycles FROM influence_scores WHERE world_id=? AND chunk_x=? AND chunk_z=?")) {
              bind(statement, position.world().value(), position.x(), position.z());
              try (ResultSet rows = statement.executeQuery()) {
                while (rows.next())
                  values.put(
                      new SettlementId(rows.getObject(1, UUID.class)),
                      new Score(rows.getInt(2), rows.getInt(3)));
              }
            }
            result.put(position, Map.copyOf(values));
          }
          return Map.copyOf(result);
        });
  }

  @Override
  public Map<ChunkPos, ChunkOwnershipCache.Entry> ownership(Set<ChunkPos> positions) {
    return store.inTransaction(
        connection -> {
          Map<ChunkPos, ChunkOwnershipCache.Entry> result = new HashMap<>();
          for (ChunkPos position : positions)
            loadOwnership(connection, position).ifPresent(value -> result.put(position, value));
          return Map.copyOf(result);
        });
  }

  @Override
  public Map<ChunkPos, ChunkOwnershipCache.Entry> allOwnership() {
    return store.inTransaction(
        connection -> {
          Map<ChunkPos, ChunkOwnershipCache.Entry> result = new HashMap<>();
          try (var statement =
              connection.prepareStatement(
                  "SELECT world_id,chunk_x,chunk_z,city_id,state FROM city_claims")) {
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                ChunkPos position =
                    new ChunkPos(
                        new WorldId(rows.getObject(1, UUID.class)), rows.getInt(2), rows.getInt(3));
                UUID owner = rows.getObject(4, UUID.class);
                result.put(
                    position,
                    new ChunkOwnershipCache.Entry(
                        owner == null ? null : new SettlementId(owner),
                        TerritoryState.valueOf(rows.getString(5))));
              }
            }
          }
          return Map.copyOf(result);
        });
  }

  @Override
  public Map<ChunkPos, ChunkOwnershipCache.Entry> commit(
      SettlementId city,
      Map<ChunkPos, Score> exactScores,
      Map<ChunkPos, InfluenceEngine.Resolution> resolutions,
      Instant now) {
    return store.inTransaction(
        connection -> {
          Set<ChunkPos> previous =
              queryPositions(
                  connection,
                  "SELECT world_id,chunk_x,chunk_z FROM influence_scores WHERE city_id=?",
                  city.value());
          for (ChunkPos position : previous)
            if (!exactScores.containsKey(position))
              update(
                  connection,
                  "DELETE FROM influence_scores WHERE city_id=? AND world_id=? AND chunk_x=? AND chunk_z=?",
                  city.value(),
                  position.world().value(),
                  position.x(),
                  position.z());
          for (Map.Entry<ChunkPos, Score> entry : exactScores.entrySet()) {
            ChunkPos position = entry.getKey();
            update(
                connection,
                "INSERT INTO influence_scores(world_id,chunk_x,chunk_z,city_id,score,consecutive_lead_cycles,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world_id,chunk_x,chunk_z,city_id) DO UPDATE SET score=excluded.score,consecutive_lead_cycles=excluded.consecutive_lead_cycles,updated_at=excluded.updated_at",
                position.world().value(),
                position.x(),
                position.z(),
                city.value(),
                entry.getValue().value(),
                entry.getValue().leadCycles(),
                now);
          }
          Map<ChunkPos, ChunkOwnershipCache.Entry> changed = new HashMap<>();
          for (Map.Entry<ChunkPos, InfluenceEngine.Resolution> entry : resolutions.entrySet()) {
            ChunkPos position = entry.getKey();
            advisoryLock(connection, position);
            ChunkOwnershipCache.Entry before =
                loadOwnership(connection, position)
                    .orElse(new ChunkOwnershipCache.Entry(null, TerritoryState.WILDERNESS));
            InfluenceEngine.Resolution resolution = entry.getValue();
            TerritoryState targetState = resolution.state();
            SettlementId targetOwner = resolution.owner();
            if (before.state() == TerritoryState.CAPITAL) {
              targetState = TerritoryState.CAPITAL;
              targetOwner = before.owner();
            }
            update(
                connection,
                "UPDATE influence_scores SET consecutive_lead_cycles=CASE WHEN city_id=? THEN ? ELSE 0 END WHERE world_id=? AND chunk_x=? AND chunk_z=?",
                resolution.leader() == null ? null : resolution.leader().value(),
                resolution.consecutiveLeadCycles(),
                position.world().value(),
                position.x(),
                position.z());
            int topScore =
                scalarInt(
                    connection,
                    "SELECT coalesce(max(score),0) FROM influence_scores WHERE world_id=? AND chunk_x=? AND chunk_z=?",
                    position.world().value(),
                    position.x(),
                    position.z());
            if (targetState == TerritoryState.WILDERNESS) {
              update(
                  connection,
                  "DELETE FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=? AND state<>'CAPITAL'",
                  position.world().value(),
                  position.x(),
                  position.z());
            } else {
              update(
                  connection,
                  "INSERT INTO city_claims(city_id,world_id,chunk_x,chunk_z,state,influence) VALUES(?,?,?,?,?,?) ON CONFLICT(world_id,chunk_x,chunk_z) DO UPDATE SET city_id=excluded.city_id,state=excluded.state,influence=excluded.influence,version=city_claims.version+1",
                  targetOwner == null ? null : targetOwner.value(),
                  position.world().value(),
                  position.x(),
                  position.z(),
                  targetState.name(),
                  topScore);
            }
            ChunkOwnershipCache.Entry after =
                new ChunkOwnershipCache.Entry(targetOwner, targetState);
            if (!before.equals(after)) {
              changed.put(position, after);
              update(
                  connection,
                  "INSERT INTO influence_history(id,world_id,chunk_x,chunk_z,previous_city_id,new_city_id,previous_state,new_state,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  position.world().value(),
                  position.x(),
                  position.z(),
                  before.owner() == null ? null : before.owner().value(),
                  after.owner() == null ? null : after.owner().value(),
                  before.state().name(),
                  after.state().name(),
                  "SIMULATION",
                  now);
              UUID aggregate =
                  after.owner() != null
                      ? after.owner().value()
                      : before.owner() != null ? before.owner().value() : city.value();
              update(
                  connection,
                  "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'CITY',?,'BorderChanged',?::jsonb,?)",
                  UUID.randomUUID(),
                  aggregate,
                  borderJson(position, before, after),
                  now);
            }
          }
          update(
              connection,
              "DELETE FROM dirty_settlements WHERE city_id=? AND lease_owner=?",
              city.value(),
              workerFromLease(connection, city.value()));
          return Map.copyOf(changed);
        });
  }

  @Override
  public void release(SettlementId city, UUID worker) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "UPDATE dirty_settlements SET lease_owner=NULL,lease_expires_at=NULL WHERE city_id=? AND lease_owner=?",
              city.value(),
              worker);
          return null;
        });
  }

  private static UUID workerFromLease(Connection connection, UUID city) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT lease_owner FROM dirty_settlements WHERE city_id=?")) {
      statement.setObject(1, city);
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? row.getObject(1, UUID.class) : null;
      }
    }
  }

  private static java.util.Optional<ChunkOwnershipCache.Entry> loadOwnership(
      Connection connection, ChunkPos position) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT city_id,state FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=?")) {
      bind(statement, position.world().value(), position.x(), position.z());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return java.util.Optional.empty();
        UUID owner = row.getObject(1, UUID.class);
        return java.util.Optional.of(
            new ChunkOwnershipCache.Entry(
                owner == null ? null : new SettlementId(owner),
                TerritoryState.valueOf(row.getString(2))));
      }
    }
  }

  private static Set<ChunkPos> queryPositions(
      Connection connection, String sql, Object... parameters) throws SQLException {
    Set<ChunkPos> result = new HashSet<>();
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next())
          result.add(
              new ChunkPos(
                  new WorldId(rows.getObject(1, UUID.class)), rows.getInt(2), rows.getInt(3)));
      }
    }
    return Set.copyOf(result);
  }

  private static void advisoryLock(Connection connection, ChunkPos position) throws SQLException {
    long key = ((long) position.world().value().hashCode() << 32) ^ position.packed();
    try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      statement.setLong(1, key);
      statement.execute();
    }
  }

  private static String borderJson(
      ChunkPos position, ChunkOwnershipCache.Entry before, ChunkOwnershipCache.Entry after) {
    return "{\"world\":\""
        + position.world().value()
        + "\",\"x\":"
        + position.x()
        + ",\"z\":"
        + position.z()
        + ",\"before\":\""
        + before.state()
        + "\",\"after\":\""
        + after.state()
        + "\"}";
  }

  private static int scalarInt(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("required database value is missing");
        return result.getInt(1);
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
