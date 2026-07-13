package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            return new Decision(false, "campaign is not active here", 0, 0, null, false);
          ExistingDamage existing = existingDamage(connection, attempt);
          if (existing != null && !reusable(existing))
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
                    attempt.now()),
                existing.id,
                false);
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
            return new Decision(false, "breach capacity exhausted", 0, remaining, null, false);
          UUID damage = existing == null ? UUID.randomUUID() : existing.id;
          UUID building = building(connection, attempt);
          if (existing == null) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO damage_journal(id,campaign_id,city_id,building_id,world_id,x,y,z,original_data,damaged_data,source_id,cause,repair_state,occurred_at,charged_breach_points,authorized_at,mutation_state,generation,last_authorized_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'REGISTERED',?,?,?,'AUTHORIZED',1,?,0)")) {
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
              statement.setTimestamp(16, Timestamp.from(attempt.now()));
              statement.executeUpdate();
            }
          } else {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE damage_journal SET building_id=?,original_data=?,damaged_data=?,source_id=?,cause=?,repair_state='REGISTERED',mutation_state='AUTHORIZED',occurred_at=?,charged_breach_points=?,authorized_at=?,last_authorized_at=?,generation=generation+1,archived_at=NULL,rejection_reason=NULL,version=version+1 WHERE id=?")) {
              statement.setObject(1, building);
              statement.setString(2, attempt.originalData());
              statement.setString(3, attempt.damagedData());
              statement.setObject(4, attempt.attacker());
              statement.setString(5, attempt.cause());
              statement.setTimestamp(6, Timestamp.from(attempt.now()));
              statement.setInt(7, charged);
              statement.setTimestamp(8, Timestamp.from(attempt.now()));
              statement.setTimestamp(9, Timestamp.from(attempt.now()));
              statement.setObject(10, damage);
              statement.executeUpdate();
            }
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
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'DAMAGE',?,'StructuralDamageAuthorized',?::jsonb,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, damage);
            statement.setString(3, "{\"chargedPoints\":" + charged + "}");
            statement.setTimestamp(4, Timestamp.from(attempt.now()));
            statement.executeUpdate();
          }
          return new Decision(true, "authorized", charged, remaining - charged, damage, true);
        });
  }

  @Override
  public void confirmApplied(UUID damage, String actualData, Instant now) {
    store.inTransaction(
        connection -> {
          UUID building;
          String expected;
          String mutation;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT building_id,damaged_data,mutation_state FROM damage_journal WHERE id=? FOR UPDATE")) {
            statement.setObject(1, damage);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new IllegalStateException("damage journal not found");
              building = result.getObject(1, UUID.class);
              expected = result.getString(2);
              mutation = result.getString(3);
            }
          }
          if (mutation.equals("APPLIED")) return null;
          if (!mutation.equals("AUTHORIZED"))
            throw new IllegalStateException("damage mutation is not authorized");
          if (!expected.equals(actualData))
            throw new IllegalStateException("world mutation does not match damage journal");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE damage_journal SET mutation_state='APPLIED',version=version+1 WHERE id=? AND mutation_state='AUTHORIZED'")) {
            statement.setObject(1, damage);
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
          outbox(connection, damage, "StructuralDamageApplied", now);
          return null;
        });
  }

  @Override
  public void reject(UUID damage, String reason, Instant now) {
    store.inTransaction(
        connection -> {
          String mutation;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT mutation_state FROM damage_journal WHERE id=? FOR UPDATE")) {
            statement.setObject(1, damage);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return null;
              mutation = result.getString(1);
            }
          }
          if (!mutation.equals("AUTHORIZED")) return null;
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM breach_spends WHERE damage_id=?")) {
            statement.setObject(1, damage);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE damage_journal SET mutation_state='REJECTED',repair_state='ARCHIVED',rejection_reason=?,archived_at=?,version=version+1 WHERE id=?")) {
            statement.setString(1, reason);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, damage);
            statement.executeUpdate();
          }
          outbox(connection, damage, "StructuralDamageRejected", now);
          return null;
        });
  }

  @Override
  public List<PendingMutation> pendingMutations(int maximum) {
    return store.inTransaction(
        connection -> {
          List<PendingMutation> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,world_id,x,y,z,original_data,damaged_data FROM damage_journal WHERE mutation_state='AUTHORIZED' AND authorized_at<now()-interval '10 seconds' ORDER BY authorized_at,id LIMIT ?")) {
            statement.setInt(1, maximum);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new PendingMutation(
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

  private static ExistingDamage existingDamage(
      java.sql.Connection connection, DamageAttempt attempt) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,repair_state,mutation_state FROM damage_journal WHERE campaign_id=? AND world_id=? AND x=? AND y=? AND z=? FOR UPDATE")) {
      statement.setObject(1, attempt.campaign());
      statement.setObject(2, attempt.world());
      statement.setInt(3, attempt.x());
      statement.setInt(4, attempt.y());
      statement.setInt(5, attempt.z());
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new ExistingDamage(
                result.getObject(1, UUID.class), result.getString(2), result.getString(3))
            : null;
      }
    }
  }

  private static boolean reusable(ExistingDamage value) {
    return value.mutation.equals("REJECTED")
        || value.repairState.equals("COMPLETED")
        || value.repairState.equals("ARCHIVED");
  }

  private static void outbox(java.sql.Connection connection, UUID damage, String event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'DAMAGE',?,?, '{}'::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, damage);
      statement.setString(3, event);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.executeUpdate();
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

  private record ExistingDamage(UUID id, String repairState, String mutation) {}
}
