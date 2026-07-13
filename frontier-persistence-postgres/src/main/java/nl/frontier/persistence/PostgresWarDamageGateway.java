package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.warfare.StructuralDamagePolicy;
import nl.frontier.warfare.WarDamageGateway;

public final class PostgresWarDamageGateway implements WarDamageGateway {
  private final TransactionalStore store;
  private final StructuralDamagePolicy policy = new StructuralDamagePolicy();

  public PostgresWarDamageGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Decision authorizeAndJournal(
      DamageAttempt attempt, Duration breachWindow, int baseCapacity, int maximumCapacity) {
    return store.inTransaction(
        connection -> {
          if (!activeCampaign(connection, attempt))
            return new Decision(false, "campaign is not active here", 0, 0);
          UUID existing = existingDamage(connection, attempt);
          if (existing != null)
            return new Decision(
                true,
                "already journaled",
                0,
                remaining(
                    connection,
                    attempt.campaign(),
                    breachWindow,
                    baseCapacity,
                    maximumCapacity,
                    attempt.now()));
          double multiplier = policy.defenderMultiplier(attempt.activeDefenders());
          int charged = Math.max(1, (int) Math.ceil(attempt.baseCost() / multiplier));
          int remaining =
              remaining(
                  connection,
                  attempt.campaign(),
                  breachWindow,
                  baseCapacity,
                  maximumCapacity,
                  attempt.now());
          if (charged > remaining)
            return new Decision(false, "breach capacity exhausted", 0, remaining);
          UUID damage = UUID.randomUUID();
          UUID building = building(connection, attempt);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO damage_journal(id,campaign_id,city_id,building_id,world_id,x,y,z,original_data,damaged_data,source_id,cause,repair_state,occurred_at,charged_breach_points,authorized_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'DAMAGED',?,?,?)")) {
            statement.setObject(1, damage);
            statement.setObject(2, attempt.campaign());
            statement.setObject(3, attempt.defendingCity());
            statement.setObject(4, building);
            statement.setObject(5, attempt.world());
            statement.setInt(6, attempt.x());
            statement.setInt(7, attempt.y());
            statement.setInt(8, attempt.z());
            statement.setString(9, attempt.originalData());
            statement.setString(10, attempt.damagedData());
            statement.setObject(11, attempt.attacker());
            statement.setString(12, attempt.cause());
            statement.setTimestamp(13, Timestamp.from(attempt.now()));
            statement.setInt(14, charged);
            statement.setTimestamp(15, Timestamp.from(attempt.now()));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO breach_spends(id,campaign_id,points,occurred_at,actor_id,damage_id,effective_multiplier) VALUES(?,?,?,?,?,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, attempt.campaign());
            statement.setInt(3, charged);
            statement.setTimestamp(4, Timestamp.from(attempt.now()));
            statement.setObject(5, attempt.attacker());
            statement.setObject(6, damage);
            statement.setDouble(7, multiplier);
            statement.executeUpdate();
          }
          if (building != null) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE city_buildings SET integrity=greatest(0,integrity-1),status=CASE WHEN integrity-1<15 THEN 'DISABLED' WHEN integrity-1<40 THEN 'EMERGENCY' WHEN integrity-1<90 THEN 'DEGRADED' ELSE 'OPERATIONAL' END,version=version+1 WHERE id=?")) {
              statement.setObject(1, building);
              statement.executeUpdate();
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'DAMAGE',?,'StructuralDamageAuthorized',?::jsonb,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, damage);
            statement.setString(3, "{\"chargedPoints\":" + charged + "}");
            statement.setTimestamp(4, Timestamp.from(attempt.now()));
            statement.executeUpdate();
          }
          return new Decision(true, "authorized", charged, remaining - charged);
        });
  }

  private static boolean activeCampaign(java.sql.Connection connection, DamageAttempt attempt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM campaigns c JOIN city_members m ON m.city_id=c.attacker_city_id AND m.player_id=? JOIN campaign_objectives o ON o.campaign_id=c.id WHERE c.id=? AND c.defender_city_id=? AND c.phase='ACTIVE' AND o.state IN ('ACTIVE','CONTESTED') AND o.world_id=? AND ? BETWEEN (o.bounds->>'minX')::int AND (o.bounds->>'maxX')::int AND ? BETWEEN (o.bounds->>'minY')::int AND (o.bounds->>'maxY')::int AND ? BETWEEN (o.bounds->>'minZ')::int AND (o.bounds->>'maxZ')::int")) {
      statement.setObject(1, attempt.attacker());
      statement.setObject(2, attempt.campaign());
      statement.setObject(3, attempt.defendingCity());
      statement.setObject(4, attempt.world());
      statement.setInt(5, attempt.x());
      statement.setInt(6, attempt.y());
      statement.setInt(7, attempt.z());
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static UUID existingDamage(java.sql.Connection connection, DamageAttempt attempt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM damage_journal WHERE campaign_id=? AND world_id=? AND x=? AND y=? AND z=?")) {
      statement.setObject(1, attempt.campaign());
      statement.setObject(2, attempt.world());
      statement.setInt(3, attempt.x());
      statement.setInt(4, attempt.y());
      statement.setInt(5, attempt.z());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }

  private static int remaining(
      java.sql.Connection connection,
      UUID campaign,
      Duration window,
      int base,
      int maximum,
      java.time.Instant now)
      throws SQLException {
    int used;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(points),0) FROM breach_spends WHERE campaign_id=? AND occurred_at>=?")) {
      statement.setObject(1, campaign);
      statement.setTimestamp(2, Timestamp.from(now.minus(window)));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        used = result.getInt(1);
      }
    }
    return Math.max(0, Math.min(maximum, base) - used);
  }

  private static UUID building(java.sql.Connection connection, DamageAttempt attempt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM city_buildings WHERE city_id=? AND (bounds->>'world')::uuid=? AND ? BETWEEN (bounds->>'minX')::int AND (bounds->>'maxX')::int AND ? BETWEEN (bounds->>'minY')::int AND (bounds->>'maxY')::int AND ? BETWEEN (bounds->>'minZ')::int AND (bounds->>'maxZ')::int ORDER BY id LIMIT 1 FOR UPDATE")) {
      statement.setObject(1, attempt.defendingCity());
      statement.setObject(2, attempt.world());
      statement.setInt(3, attempt.x());
      statement.setInt(4, attempt.y());
      statement.setInt(5, attempt.z());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getObject(1, UUID.class) : null;
      }
    }
  }
}
