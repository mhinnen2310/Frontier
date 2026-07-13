package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.Building;
import nl.frontier.city.GovernmentRole;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLevel;
import nl.frontier.domain.DomainException;

public final class PostgresSettlementGateway implements SettlementGateway {
  private final TransactionalStore store;

  public PostgresSettlementGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CitySnapshot create(
      UUID owner, String name, UUID world, int chunkX, int chunkZ, Instant now) {
    return store.inTransaction(
        connection -> {
          if (findByPlayer(connection, owner).isPresent())
            throw new DomainException("you already belong to a settlement");
          UUID city = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO cities(id,name,owner_id,level,population,prosperity,civilization,created_at) VALUES(?,?,?,1,1,50,1,?)",
              city,
              name,
              owner,
              now);
          update(
              connection,
              "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'MAYOR',?)",
              city,
              owner,
              now);
          update(
              connection,
              "INSERT INTO city_claims(city_id,world_id,chunk_x,chunk_z,state,influence) VALUES(?,?,?,?,'CAPITAL',100)",
              city,
              world,
              chunkX,
              chunkZ);
          UUID treasury = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'CITY',?,10000)",
              treasury,
              city);
          update(
              connection,
              "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,description) VALUES(?,?,?,'HARBOR_FOUNDING_GRANT',10000,10000,?,?,?,'Frontier Harbor settlement bootstrap')",
              UUID.randomUUID(),
              treasury,
              owner,
              city,
              UUID.nameUUIDFromBytes(
                  (city + ":founding-grant").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              now);
          UUID warehouse = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO warehouses(id,city_id,capacity,status,version) VALUES(?,?,1000,'ACTIVE',0)",
              warehouse,
              city);
          update(
              connection,
              "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) VALUES(?,'minecraft:wheat',64,0,0),(?,'minecraft:bread',16,0,0)",
              warehouse,
              warehouse);
          update(
              connection,
              "INSERT INTO dirty_settlements(city_id,reason,enqueued_at) VALUES(?,'CREATED',?)",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_simulation_state(city_id,next_cycle_at) VALUES(?,?)",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_world_simulation_state(city_id,region_key,next_cycle_at) VALUES(?,?,?)",
              city,
              world + ":" + Math.floorDiv(chunkX, 32) + ":" + Math.floorDiv(chunkZ, 32),
              now);
          audit(
              connection,
              owner,
              "CITY_CREATED",
              "CITY",
              city,
              null,
              "{\"name\":\"" + name + "\"}",
              now);
          outbox(
              connection, "CITY", city, "SettlementCreated", "{\"owner\":\"" + owner + "\"}", now);
          return new CitySnapshot(city, name, owner, SettlementLevel.CAMP, 1, 50, 1, 10_000);
        });
  }

  @Override
  public Optional<CitySnapshot> findByPlayer(UUID player) {
    return store.inTransaction(connection -> findByPlayer(connection, player));
  }

  @Override
  public Invitation invite(
      UUID city,
      UUID actor,
      UUID target,
      Set<GovernmentRole> allowedRoles,
      Instant expiresAt,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          if (findByPlayer(connection, target).isPresent())
            throw new DomainException("player already belongs to a settlement");
          UUID id = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO city_invitations(id,city_id,player_id,status,expires_at,created_by,created_at) VALUES(?,?,?,'PENDING',?,?,?)",
              id,
              city,
              target,
              expiresAt,
              actor,
              now);
          audit(
              connection,
              actor,
              "MEMBER_INVITED",
              "CITY",
              city,
              null,
              "{\"player\":\"" + target + "\"}",
              now);
          return new Invitation(id, city, target, "PENDING", expiresAt);
        });
  }

  @Override
  public CitySnapshot acceptInvitation(UUID invitation, UUID player, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID city;
          try (var statement =
              connection.prepareStatement(
                  "SELECT city_id,player_id,status,expires_at FROM city_invitations WHERE id=? FOR UPDATE")) {
            statement.setObject(1, invitation);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("invitation does not exist");
              if (!player.equals(result.getObject("player_id", UUID.class)))
                throw new DomainException("invitation belongs to another player");
              if (!"PENDING".equals(result.getString("status")))
                throw new DomainException("invitation is no longer pending");
              if (result
                  .getObject("expires_at", java.time.OffsetDateTime.class)
                  .toInstant()
                  .isBefore(now)) {
                throw new DomainException("invitation has expired");
              }
              city = result.getObject("city_id", UUID.class);
            }
          }
          if (findByPlayer(connection, player).isPresent())
            throw new DomainException("you already belong to a settlement");
          update(
              connection,
              "UPDATE city_invitations SET status='ACCEPTED',version=version+1 WHERE id=?",
              invitation);
          update(
              connection,
              "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'RECRUIT',?)",
              city,
              player,
              now);
          markDirty(connection, city, "MEMBERSHIP", now);
          audit(
              connection,
              player,
              "INVITATION_ACCEPTED",
              "CITY",
              city,
              null,
              "{\"player\":\"" + player + "\"}",
              now);
          outbox(connection, "CITY", city, "MemberJoined", "{\"player\":\"" + player + "\"}", now);
          return loadCity(connection, city, false);
        });
  }

  @Override
  public void changeRole(
      UUID city,
      UUID actor,
      UUID target,
      GovernmentRole role,
      Set<GovernmentRole> allowedRoles,
      Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          CitySnapshot snapshot = loadCity(connection, city, true);
          if (snapshot.owner().equals(target))
            throw new DomainException("the owner must remain mayor");
          int changed =
              update(
                  connection,
                  "UPDATE city_members SET role=? WHERE city_id=? AND player_id=?",
                  role.name(),
                  city,
                  target);
          if (changed != 1) throw new DomainException("target is not a settlement member");
          audit(
              connection,
              actor,
              "ROLE_CHANGED",
              "CITY",
              city,
              null,
              "{\"player\":\"" + target + "\",\"role\":\"" + role + "\"}",
              now);
          return null;
        });
  }

  @Override
  public BuildingSnapshot registerBuilding(
      UUID city,
      UUID actor,
      Building.Category category,
      Bounds bounds,
      Set<GovernmentRole> allowedRoles,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          if (bounds.maxX() - bounds.minX() > 255 || bounds.maxZ() - bounds.minZ() > 255) {
            throw new DomainException("building bounds exceed 256 blocks");
          }
          for (int x = Math.floorDiv(bounds.minX(), 16);
              x <= Math.floorDiv(bounds.maxX(), 16);
              x++) {
            for (int z = Math.floorDiv(bounds.minZ(), 16);
                z <= Math.floorDiv(bounds.maxZ(), 16);
                z++) {
              if (!ownsChunk(connection, city, bounds.world(), x, z)) {
                throw new DomainException("building bounds leave controlled territory");
              }
            }
          }
          UUID id = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO city_buildings(id,city_id,category,bounds,integrity,status) VALUES(?,?,?,?::jsonb,100,'OPERATIONAL')",
              id,
              city,
              category.name(),
              bounds.json());
          markDirty(connection, city, "BUILDING", now);
          audit(connection, actor, "BUILDING_REGISTERED", "BUILDING", id, null, bounds.json(), now);
          outbox(connection, "BUILDING", id, "BuildingRegistered", bounds.json(), now);
          return new BuildingSnapshot(id, category, 100, "OPERATIONAL");
        });
  }

  @Override
  public ClaimSnapshot claim(
      UUID city,
      UUID actor,
      UUID world,
      int chunkX,
      int chunkZ,
      int maximumClaims,
      Set<GovernmentRole> allowedRoles,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          int count =
              scalarInt(connection, "SELECT count(*) FROM city_claims WHERE city_id=?", city);
          if (count >= maximumClaims) throw new DomainException("settlement claim limit reached");
          if (scalarInt(
                  connection,
                  "SELECT count(*) FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=?",
                  world,
                  chunkX,
                  chunkZ)
              > 0) {
            throw new DomainException("chunk is already claimed or contested");
          }
          int adjacent =
              scalarInt(
                  connection,
                  "SELECT count(*) FROM city_claims WHERE city_id=? AND world_id=? AND ((abs(chunk_x-?)=1 AND chunk_z=?) OR (abs(chunk_z-?)=1 AND chunk_x=?))",
                  city,
                  world,
                  chunkX,
                  chunkZ,
                  chunkZ,
                  chunkX);
          if (adjacent == 0) throw new DomainException("claims must connect to existing territory");
          update(
              connection,
              "INSERT INTO city_claims(city_id,world_id,chunk_x,chunk_z,state,influence) VALUES(?,?,?,?,'INFLUENCED',1)",
              city,
              world,
              chunkX,
              chunkZ);
          update(
              connection,
              "INSERT INTO influence_scores(world_id,chunk_x,chunk_z,city_id,score,updated_at) VALUES(?,?,?,?,1,?)",
              world,
              chunkX,
              chunkZ,
              city,
              now);
          markDirty(connection, city, "CLAIM", now);
          audit(
              connection,
              actor,
              "TERRITORY_INFLUENCED",
              "CITY",
              city,
              null,
              "{\"world\":\"" + world + "\",\"x\":" + chunkX + ",\"z\":" + chunkZ + "}",
              now);
          return new ClaimSnapshot(city, world, chunkX, chunkZ, "INFLUENCED");
        });
  }

  @Override
  public CitySnapshot upgrade(
      UUID city,
      UUID actor,
      SettlementLevel expected,
      SettlementLevel target,
      int requiredPopulation,
      int requiredProsperity,
      int requiredCivilization,
      Building.Category requiredBuilding,
      long costMinor,
      Set<GovernmentRole> allowedRoles,
      UUID idempotencyKey,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          CitySnapshot snapshot = loadCity(connection, city, true);
          if (snapshot.level() == target && ledgerExists(connection, idempotencyKey))
            return snapshot;
          if (snapshot.level() != expected || target != expected.next())
            throw new DomainException("settlement level changed; retry upgrade");
          if (snapshot.population() < requiredPopulation
              || snapshot.prosperity() < requiredProsperity
              || snapshot.civilization() < requiredCivilization)
            throw new DomainException("upgrade statistics are not met");
          if (requiredBuilding != null
              && scalarInt(
                      connection,
                      "SELECT count(*) FROM city_buildings WHERE city_id=? AND category=? AND integrity>=40",
                      city,
                      requiredBuilding.name())
                  == 0) throw new DomainException("required operational building is missing");
          UUID account = lockTreasury(connection, city, costMinor);
          update(
              connection,
              "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=?",
              costMinor,
              account);
          long balance =
              scalarLong(connection, "SELECT balance_minor FROM accounts WHERE id=?", account);
          update(
              connection,
              "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,'UPGRADE',?,?,?,?,?)",
              UUID.randomUUID(),
              account,
              actor,
              -costMinor,
              balance,
              city,
              idempotencyKey,
              now);
          update(
              connection,
              "UPDATE cities SET level=?,version=version+1 WHERE id=? AND version=?",
              target.level(),
              city,
              snapshot.version());
          markDirty(connection, city, "UPGRADE", now);
          audit(
              connection,
              actor,
              "CITY_UPGRADED",
              "CITY",
              city,
              "{\"level\":" + expected.level() + "}",
              "{\"level\":" + target.level() + "}",
              now);
          outbox(
              connection,
              "CITY",
              city,
              "SettlementUpgraded",
              "{\"level\":" + target.level() + "}",
              now);
          return loadCity(connection, city, false);
        });
  }

  @Override
  public void setPolicy(
      UUID city,
      UUID actor,
      String key,
      String jsonValue,
      Instant cooldownUntil,
      Set<GovernmentRole> allowedRoles,
      Instant now) {
    store.inTransaction(
        connection -> {
          requireRole(connection, city, actor, allowedRoles);
          try (var statement =
              connection.prepareStatement(
                  "SELECT cooldown_until FROM city_policies WHERE city_id=? AND policy_key=? FOR UPDATE")) {
            statement.setObject(1, city);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
              if (result.next()
                  && result.getObject(1, java.time.OffsetDateTime.class) != null
                  && result.getObject(1, java.time.OffsetDateTime.class).toInstant().isAfter(now)) {
                throw new DomainException("policy is on cooldown");
              }
            }
          }
          update(
              connection,
              "INSERT INTO city_policies(city_id,policy_key,policy_value,changed_by,changed_at,cooldown_until) VALUES(?,?,?::jsonb,?,?,?) ON CONFLICT(city_id,policy_key) DO UPDATE SET policy_value=excluded.policy_value,changed_by=excluded.changed_by,changed_at=excluded.changed_at,cooldown_until=excluded.cooldown_until,version=city_policies.version+1",
              city,
              key,
              jsonValue,
              actor,
              now,
              cooldownUntil);
          markDirty(connection, city, "POLICY", now);
          audit(connection, actor, "POLICY_CHANGED", "CITY", city, null, jsonValue, now);
          return null;
        });
  }

  @Override
  public long treasuryBalance(UUID city) {
    return store.inTransaction(
        connection ->
            scalarLong(
                connection,
                "SELECT balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=?",
                city));
  }

  private static Optional<CitySnapshot> findByPlayer(Connection connection, UUID player)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT c.id,c.name,c.owner_id,c.level,c.population,c.prosperity,c.civilization,c.version FROM cities c JOIN city_members m ON m.city_id=c.id WHERE m.player_id=?")) {
      statement.setObject(1, player);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
      }
    }
  }

  private static CitySnapshot loadCity(Connection connection, UUID city, boolean lock)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT id,name,owner_id,level,population,prosperity,civilization,version FROM cities WHERE id=?"
                + (lock ? " FOR UPDATE" : ""))) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("settlement does not exist");
        return snapshot(result);
      }
    }
  }

  private static CitySnapshot snapshot(ResultSet result) throws SQLException {
    return new CitySnapshot(
        result.getObject("id", UUID.class),
        result.getString("name"),
        result.getObject("owner_id", UUID.class),
        SettlementLevel.values()[result.getInt("level") - 1],
        result.getInt("population"),
        result.getInt("prosperity"),
        result.getInt("civilization"),
        result.getLong("version"));
  }

  private static void requireRole(
      Connection connection, UUID city, UUID actor, Set<GovernmentRole> allowed)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=? FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !allowed.contains(GovernmentRole.valueOf(result.getString(1)))) {
          throw new DomainException("not authorized for this settlement action");
        }
      }
    }
  }

  private static boolean ownsChunk(Connection connection, UUID city, UUID world, int x, int z)
      throws SQLException {
    return scalarInt(
            connection,
            "SELECT count(*) FROM city_claims WHERE city_id=? AND world_id=? AND chunk_x=? AND chunk_z=? AND state IN ('CONTROLLED','CAPITAL')",
            city,
            world,
            x,
            z)
        == 1;
  }

  private static UUID lockTreasury(Connection connection, UUID city, long cost)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT id,balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=? FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getLong(2) < cost)
          throw new DomainException("insufficient treasury funds");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static boolean ledgerExists(Connection connection, UUID key) throws SQLException {
    return scalarInt(connection, "SELECT count(*) FROM ledger_entries WHERE idempotency_key=?", key)
        > 0;
  }

  private static void markDirty(Connection connection, UUID city, String reason, Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO dirty_settlements(city_id,reason,enqueued_at) VALUES(?,?,?) ON CONFLICT(city_id) DO UPDATE SET reason=excluded.reason,enqueued_at=excluded.enqueued_at",
        city,
        reason,
        now);
  }

  private static void audit(
      Connection connection,
      UUID actor,
      String action,
      String type,
      UUID aggregate,
      String before,
      String after,
      Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,previous_value,new_value,occurred_at) VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?)",
        UUID.randomUUID(),
        actor,
        action,
        type,
        aggregate,
        before,
        after,
        now);
  }

  private static void outbox(
      Connection connection, String type, UUID aggregate, String event, String payload, Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        type,
        aggregate,
        event,
        payload,
        now);
  }

  private static int scalarInt(Connection connection, String sql, Object... parameters)
      throws SQLException {
    return Math.toIntExact(scalarLong(connection, sql, parameters));
  }

  private static long scalarLong(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("required database value is missing");
        return result.getLong(1);
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
      if (value instanceof Instant instant) {
        statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
      } else {
        statement.setObject(index + 1, value);
      }
    }
  }
}
