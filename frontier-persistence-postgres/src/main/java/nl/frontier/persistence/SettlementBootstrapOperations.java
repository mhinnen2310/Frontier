package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import nl.frontier.city.SettlementGateway;
import nl.frontier.city.SettlementLevel;
import nl.frontier.domain.DomainException;

final class SettlementBootstrapOperations {
  private SettlementBootstrapOperations() {}

  static SettlementGateway.CitySnapshot create(
      Connection connection,
      UUID city,
      UUID owner,
      String name,
      UUID world,
      int chunkX,
      int chunkZ,
      Instant now)
      throws SQLException {
    SettlementGateway.CitySnapshot existing = find(connection, city);
    if (existing != null) {
      if (!existing.owner().equals(owner) || !existing.name().equals(name))
        throw new DomainException("settlement creation id is already in use");
      return existing;
    }
    if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", owner) > 0)
      throw new DomainException("you already belong to a settlement");
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
    update(
        connection,
        "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,new_value,occurred_at) VALUES(?,?,'CITY_CREATED','CITY',?,jsonb_build_object('name',?),?)",
        UUID.randomUUID(),
        owner,
        city,
        name,
        now);
    update(
        connection,
        "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'CITY',?,'SettlementCreated',jsonb_build_object('owner',?::text),?)",
        UUID.randomUUID(),
        city,
        owner,
        now);
    return new SettlementGateway.CitySnapshot(city, name, owner, SettlementLevel.CAMP, 1, 50, 1, 0);
  }

  private static SettlementGateway.CitySnapshot find(Connection connection, UUID city)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT id,name,owner_id,level,population,prosperity,civilization,version FROM cities WHERE id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        return new SettlementGateway.CitySnapshot(
            result.getObject(1, UUID.class),
            result.getString(2),
            result.getObject(3, UUID.class),
            SettlementLevel.values()[result.getInt(4) - 1],
            result.getInt(5),
            result.getInt(6),
            result.getInt(7),
            result.getLong(8));
      }
    }
  }

  private static long scalar(Connection connection, String sql, Object... values)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void update(Connection connection, String sql, Object... values)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      statement.executeUpdate();
    }
  }

  private static void bind(java.sql.PreparedStatement statement, Object... values)
      throws SQLException {
    for (int index = 0; index < values.length; index++) {
      Object value = values[index];
      if (value instanceof Instant instant)
        statement.setTimestamp(index + 1, Timestamp.from(instant));
      else statement.setObject(index + 1, value);
    }
  }
}
