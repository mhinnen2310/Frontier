package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.warfare.CampaignGateway;
import nl.frontier.warfare.WarCampaign;

public final class PostgresCampaignGateway implements CampaignGateway {
  private static final Set<String> WAR_ROLES = Set.of("MAYOR", "GENERAL");
  private static final Set<String> PEACE_ROLES = Set.of("MAYOR", "GENERAL", "DIPLOMAT");
  private final TransactionalStore store;

  public PostgresCampaignGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CampaignSnapshot declare(
      UUID attacker,
      UUID actor,
      UUID defender,
      WarCampaign.Type type,
      ObjectiveSpec objective,
      long declarationCostMinor,
      Duration preparation,
      Duration maximumDuration,
      UUID idempotency,
      Instant now) {
    return store.inTransaction(
        connection -> {
          requireRole(connection, attacker, actor, WAR_ROLES);
          CampaignSnapshot existing = byIdempotency(connection, idempotency);
          if (existing != null) return existing;
          if (attacker.equals(defender)) throw new DomainException("a city cannot attack itself");
          requireCity(connection, defender);
          if (activePair(connection, attacker, defender))
            throw new DomainException("these cities already have an unresolved campaign");
          UUID account = cityAccount(connection, attacker);
          long balance = balance(connection, account);
          if (balance < declarationCostMinor)
            throw new DomainException("insufficient treasury for campaign declaration");
          UUID campaign = UUID.randomUUID();
          Instant scheduled = now.plus(preparation);
          Instant maximumEnds = scheduled.plus(maximumDuration);
          setBalance(connection, account, balance - declarationCostMinor);
          ledger(
              connection,
              account,
              actor,
              -declarationCostMinor,
              balance - declarationCostMinor,
              campaign,
              idempotency,
              now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO campaigns(id,attacker_city_id,defender_city_id,campaign_type,phase,declared_at,version,scheduled_active_at,maximum_ends_at,declaration_cost_minor,baseline_finalized,idempotency_key) VALUES(?,?,?,?,'PREPARATION',?,0,?,?,?,false,?)")) {
            statement.setObject(1, campaign);
            statement.setObject(2, attacker);
            statement.setObject(3, defender);
            statement.setString(4, type.name());
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(scheduled));
            statement.setTimestamp(7, Timestamp.from(maximumEnds));
            statement.setLong(8, declarationCostMinor);
            statement.setObject(9, idempotency);
            statement.executeUpdate();
          }
          UUID objectiveId = UUID.randomUUID();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO campaign_objectives(id,campaign_id,objective_type,state,world_id,bounds,progress,target,version,minimum_participants,expires_at) VALUES(?, ?,?,'AVAILABLE',?,?::jsonb,0,?,0,?,?)")) {
            statement.setObject(1, objectiveId);
            statement.setObject(2, campaign);
            statement.setString(3, objective.type());
            statement.setObject(4, objective.world());
            statement.setString(5, bounds(objective));
            statement.setLong(6, objective.target());
            statement.setInt(7, objective.minimumParticipants());
            statement.setTimestamp(8, Timestamp.from(maximumEnds));
            statement.executeUpdate();
          }
          audit(connection, actor, "CAMPAIGN_DECLARED", campaign, now);
          outbox(connection, campaign, "CampaignDeclared", now);
          return load(connection, campaign);
        });
  }

  @Override
  public CampaignSnapshot ceasefire(UUID campaign, UUID actor, Instant now) {
    return transition(campaign, actor, "ACTIVE", "CEASEFIRE", "CampaignCeasefire", now);
  }

  @Override
  public CampaignSnapshot resume(UUID campaign, UUID actor, Instant now) {
    return transition(campaign, actor, "CEASEFIRE", "ACTIVE", "CampaignResumed", now);
  }

  @Override
  public CampaignSnapshot resolve(UUID campaign, UUID actor, String reason, Instant now) {
    return store.inTransaction(
        connection -> {
          CampaignRow row = lockCampaign(connection, campaign);
          requireEitherRole(connection, row, actor, PEACE_ROLES);
          if (!row.phase.equals("ACTIVE") && !row.phase.equals("CEASEFIRE"))
            throw new DomainException("campaign cannot resolve from " + row.phase);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE campaigns SET phase='RESOLUTION',resolution_reason=?,version=version+1 WHERE id=?")) {
            statement.setString(1, reason);
            statement.setObject(2, campaign);
            statement.executeUpdate();
          }
          outbox(connection, campaign, "CampaignResolving", now);
          return load(connection, campaign);
        });
  }

  @Override
  public CampaignSnapshot end(UUID campaign, UUID actor, String reason, Instant now) {
    return store.inTransaction(
        connection -> {
          CampaignRow row = lockCampaign(connection, campaign);
          requireEitherRole(connection, row, actor, PEACE_ROLES);
          if (!row.phase.equals("RESOLUTION"))
            throw new DomainException("campaign is not resolving");
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE campaigns SET phase='ENDED',ended_at=?,resolution_reason=coalesce(?,resolution_reason),version=version+1 WHERE id=?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, reason);
            statement.setObject(3, campaign);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE campaign_objectives SET state=CASE WHEN state='COMPLETED' THEN state ELSE 'EXPIRED' END,version=version+1 WHERE campaign_id=?")) {
            statement.setObject(1, campaign);
            statement.executeUpdate();
          }
          audit(connection, actor, "CAMPAIGN_ENDED", campaign, now);
          outbox(connection, campaign, "CampaignEnded", now);
          return load(connection, campaign);
        });
  }

  @Override
  public List<CampaignSnapshot> campaigns(UUID city) {
    return store.inTransaction(
        connection -> {
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM campaigns WHERE attacker_city_id=? OR defender_city_id=? ORDER BY declared_at DESC LIMIT 100")) {
            statement.setObject(1, city);
            statement.setObject(2, city);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
          }
          List<CampaignSnapshot> values = new ArrayList<>();
          for (UUID id : ids) values.add(load(connection, id));
          return List.copyOf(values);
        });
  }

  @Override
  public AdvanceReport advanceDue(int maximum, Instant now) {
    int activated = 0;
    int resolving = 0;
    for (int index = 0; index < maximum; index++) {
      Advance value = store.inTransaction(connection -> advanceOne(connection, now));
      if (value == Advance.NONE) break;
      if (value == Advance.ACTIVATED) activated++;
      if (value == Advance.RESOLVING) resolving++;
    }
    return new AdvanceReport(activated, resolving);
  }

  @Override
  public WarPolicySnapshot policySnapshot(Instant now) {
    return store.inTransaction(
        connection -> {
          List<ActiveWar> wars = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id,attacker_city_id,defender_city_id FROM campaigns WHERE phase='ACTIVE'");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              UUID id = result.getObject(1, UUID.class);
              wars.add(
                  new ActiveWar(
                      id,
                      result.getObject(2, UUID.class),
                      result.getObject(3, UUID.class),
                      objectives(connection, id)));
            }
          }
          List<Membership> memberships = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT player_id,city_id FROM city_members");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              memberships.add(
                  new Membership(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
          }
          return new WarPolicySnapshot(List.copyOf(wars), List.copyOf(memberships), now);
        });
  }

  @Override
  public void recordCombat(UUID player, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO combat_activity(player_id,last_combat_at,afk,version) VALUES(?,?,false,0) ON CONFLICT(player_id) DO UPDATE SET last_combat_at=excluded.last_combat_at,afk=false,version=combat_activity.version+1")) {
            statement.setObject(1, player);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public ObjectiveTickReport tickObjectives(
      List<Presence> players, long progressUnits, Instant now) {
    return store.inTransaction(
        connection -> {
          java.util.Map<UUID, UUID> memberships = new java.util.HashMap<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT player_id,city_id FROM city_members");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              memberships.put(result.getObject(1, UUID.class), result.getObject(2, UUID.class));
          }
          List<ObjectiveTickRow> objectives = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT o.id,o.campaign_id,c.attacker_city_id,c.defender_city_id,o.world_id,(o.bounds->>'minX')::int,(o.bounds->>'minY')::int,(o.bounds->>'minZ')::int,(o.bounds->>'maxX')::int,(o.bounds->>'maxY')::int,(o.bounds->>'maxZ')::int,o.progress,o.target,o.minimum_participants FROM campaign_objectives o JOIN campaigns c ON c.id=o.campaign_id WHERE c.phase='ACTIVE' AND o.state IN ('ACTIVE','CONTESTED') FOR UPDATE OF o");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              objectives.add(
                  new ObjectiveTickRow(
                      result.getObject(1, UUID.class),
                      result.getObject(2, UUID.class),
                      result.getObject(3, UUID.class),
                      result.getObject(4, UUID.class),
                      result.getObject(5, UUID.class),
                      result.getInt(6),
                      result.getInt(7),
                      result.getInt(8),
                      result.getInt(9),
                      result.getInt(10),
                      result.getInt(11),
                      result.getLong(12),
                      result.getLong(13),
                      result.getInt(14)));
          }
          int contested = 0;
          int completed = 0;
          for (ObjectiveTickRow objective : objectives) {
            long attackers =
                players.stream()
                    .filter(Presence::eligible)
                    .filter(value -> inside(value, objective))
                    .filter(value -> objective.attacker.equals(memberships.get(value.player())))
                    .count();
            long defenders =
                players.stream()
                    .filter(Presence::eligible)
                    .filter(value -> inside(value, objective))
                    .filter(value -> objective.defender.equals(memberships.get(value.player())))
                    .count();
            long progress = objective.progress;
            String state = "ACTIVE";
            if (attackers >= objective.minimum && defenders == 0) {
              progress = Math.min(objective.target, Math.addExact(progress, progressUnits));
            } else if (attackers > 0 && defenders > 0) {
              state = "CONTESTED";
              contested++;
            } else if (defenders > 0) {
              progress = Math.max(0, progress - progressUnits);
            }
            if (progress >= objective.target) {
              state = "COMPLETED";
              completed++;
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE campaign_objectives SET progress=?,state=?,version=version+1 WHERE id=?")) {
              statement.setLong(1, progress);
              statement.setString(2, state);
              statement.setObject(3, objective.id);
              statement.executeUpdate();
            }
            if (state.equals("COMPLETED")) {
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "UPDATE campaigns SET attacker_score=attacker_score+?,version=version+1 WHERE id=?")) {
                statement.setLong(1, objective.target);
                statement.setObject(2, objective.campaign);
                statement.executeUpdate();
              }
              outbox(connection, objective.id, "CampaignObjectiveCompleted", now);
            }
          }
          return new ObjectiveTickReport(objectives.size(), contested, completed);
        });
  }

  private static boolean inside(Presence value, ObjectiveTickRow objective) {
    return value.world().equals(objective.world)
        && value.x() >= objective.minX
        && value.x() <= objective.maxX
        && value.y() >= objective.minY
        && value.y() <= objective.maxY
        && value.z() >= objective.minZ
        && value.z() <= objective.maxZ;
  }

  private CampaignSnapshot transition(
      UUID campaign, UUID actor, String expected, String next, String event, Instant now) {
    return store.inTransaction(
        connection -> {
          CampaignRow row = lockCampaign(connection, campaign);
          requireEitherRole(connection, row, actor, PEACE_ROLES);
          if (!row.phase.equals(expected))
            throw new DomainException("expected " + expected + " but campaign is " + row.phase);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE campaigns SET phase=?,version=version+1 WHERE id=?")) {
            statement.setString(1, next);
            statement.setObject(2, campaign);
            statement.executeUpdate();
          }
          outbox(connection, campaign, event, now);
          return load(connection, campaign);
        });
  }

  private static Advance advanceOne(Connection connection, Instant now) throws SQLException {
    CampaignRow row;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,attacker_city_id,defender_city_id,phase FROM campaigns WHERE (phase='PREPARATION' AND scheduled_active_at<=?) OR (phase='ACTIVE' AND maximum_ends_at<=?) ORDER BY coalesce(scheduled_active_at,maximum_ends_at),id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setTimestamp(2, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return Advance.NONE;
        row =
            new CampaignRow(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getObject(3, UUID.class),
                result.getString(4));
      }
    }
    if (row.phase.equals("PREPARATION")) {
      snapshotBaseline(connection, row.id, row.attacker, now);
      snapshotBaseline(connection, row.id, row.defender, now);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE campaigns SET phase='ACTIVE',active_at=?,baseline_finalized=true,version=version+1 WHERE id=?")) {
        statement.setTimestamp(1, Timestamp.from(now));
        statement.setObject(2, row.id);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE campaign_objectives SET state='ACTIVE',version=version+1 WHERE campaign_id=? AND state='AVAILABLE'")) {
        statement.setObject(1, row.id);
        statement.executeUpdate();
      }
      outbox(connection, row.id, "CampaignActivated", now);
      return Advance.ACTIVATED;
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE campaigns SET phase='RESOLUTION',resolution_reason='MAXIMUM_DURATION',version=version+1 WHERE id=?")) {
      statement.setObject(1, row.id);
      statement.executeUpdate();
    }
    outbox(connection, row.id, "CampaignMaximumDurationReached", now);
    return Advance.RESOLVING;
  }

  private static void snapshotBaseline(Connection connection, UUID campaign, UUID city, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO campaign_baselines(campaign_id,city_id,buildings,finalized_at) SELECT ?,?,coalesce(jsonb_agg(jsonb_build_object('id',id,'category',category,'bounds',bounds,'integrity',integrity)),'[]'::jsonb),? FROM city_buildings WHERE city_id=? ON CONFLICT DO NOTHING")) {
      statement.setObject(1, campaign);
      statement.setObject(2, city);
      statement.setTimestamp(3, Timestamp.from(now));
      statement.setObject(4, city);
      statement.executeUpdate();
    }
  }

  private static CampaignSnapshot byIdempotency(Connection connection, UUID key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM campaigns WHERE idempotency_key=?")) {
      statement.setObject(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? load(connection, result.getObject(1, UUID.class)) : null;
      }
    }
  }

  private static CampaignSnapshot load(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,attacker_city_id,defender_city_id,campaign_type,phase,declared_at,scheduled_active_at,active_at,maximum_ends_at,ended_at,attacker_score,defender_score,version FROM campaigns WHERE id=?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("campaign not found");
        return new CampaignSnapshot(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getObject(3, UUID.class),
            WarCampaign.Type.valueOf(result.getString(4)),
            WarCampaign.Phase.valueOf(result.getString(5)),
            instant(result, 6),
            instant(result, 7),
            instant(result, 8),
            instant(result, 9),
            instant(result, 10),
            result.getLong(11),
            result.getLong(12),
            objectives(connection, id),
            result.getLong(13));
      }
    }
  }

  private static List<ObjectiveSnapshot> objectives(Connection connection, UUID campaign)
      throws SQLException {
    List<ObjectiveSnapshot> values = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,objective_type,state,world_id,(bounds->>'minX')::int,(bounds->>'minY')::int,(bounds->>'minZ')::int,(bounds->>'maxX')::int,(bounds->>'maxY')::int,(bounds->>'maxZ')::int,progress,target,minimum_participants FROM campaign_objectives WHERE campaign_id=? ORDER BY id")) {
      statement.setObject(1, campaign);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next())
          values.add(
              new ObjectiveSnapshot(
                  result.getObject(1, UUID.class),
                  result.getString(2),
                  result.getString(3),
                  result.getObject(4, UUID.class),
                  result.getInt(5),
                  result.getInt(6),
                  result.getInt(7),
                  result.getInt(8),
                  result.getInt(9),
                  result.getInt(10),
                  result.getLong(11),
                  result.getLong(12),
                  result.getInt(13)));
      }
    }
    return List.copyOf(values);
  }

  private static CampaignRow lockCampaign(Connection connection, UUID campaign)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,attacker_city_id,defender_city_id,phase FROM campaigns WHERE id=? FOR UPDATE")) {
      statement.setObject(1, campaign);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("campaign not found");
        return new CampaignRow(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getObject(3, UUID.class),
            result.getString(4));
      }
    }
  }

  private static boolean activePair(Connection connection, UUID first, UUID second)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM campaigns WHERE ((attacker_city_id=? AND defender_city_id=?) OR (attacker_city_id=? AND defender_city_id=?)) AND phase IN ('DECLARED','PREPARATION','ACTIVE','CEASEFIRE','RESOLUTION')")) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      statement.setObject(3, second);
      statement.setObject(4, first);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void requireEitherRole(
      Connection connection, CampaignRow campaign, UUID actor, Set<String> roles)
      throws SQLException {
    if (hasRole(connection, campaign.attacker, actor, roles)
        || hasRole(connection, campaign.defender, actor, roles)) return;
    throw new DomainException("actor cannot manage this campaign");
  }

  private static void requireRole(Connection connection, UUID city, UUID actor, Set<String> roles)
      throws SQLException {
    if (!hasRole(connection, city, actor, roles))
      throw new DomainException("settlement role does not allow campaign action");
  }

  private static boolean hasRole(Connection connection, UUID city, UUID actor, Set<String> roles)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT role FROM city_members WHERE city_id=? AND player_id=? FOR SHARE")) {
      statement.setObject(1, city);
      statement.setObject(2, actor);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && roles.contains(result.getString(1));
      }
    }
  }

  private static void requireCity(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM cities WHERE id=?")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("defending city not found");
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

  private static void ledger(
      Connection connection,
      UUID account,
      UUID actor,
      long amount,
      long after,
      UUID campaign,
      UUID idempotency,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at) VALUES(?,?,?,'CAMPAIGN_DECLARATION',?,?,?,?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, account);
      statement.setObject(3, actor);
      statement.setLong(4, amount);
      statement.setLong(5, after);
      statement.setObject(6, campaign);
      statement.setObject(7, idempotency);
      statement.setTimestamp(8, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void audit(Connection connection, UUID actor, String action, UUID id, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log(id,actor_id,action,aggregate_type,aggregate_id,occurred_at) VALUES(?,?,?,'CAMPAIGN',?,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, actor);
      statement.setString(3, action);
      statement.setObject(4, id);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static void outbox(Connection connection, UUID id, String event, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'CAMPAIGN',?,?, '{}'::jsonb,?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, id);
      statement.setString(3, event);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static Instant instant(ResultSet result, int index) throws SQLException {
    Timestamp value = result.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }

  private static String bounds(ObjectiveSpec objective) {
    return "{\"minX\":"
        + objective.minX()
        + ",\"minY\":"
        + objective.minY()
        + ",\"minZ\":"
        + objective.minZ()
        + ",\"maxX\":"
        + objective.maxX()
        + ",\"maxY\":"
        + objective.maxY()
        + ",\"maxZ\":"
        + objective.maxZ()
        + "}";
  }

  private enum Advance {
    NONE,
    ACTIVATED,
    RESOLVING
  }

  private record CampaignRow(UUID id, UUID attacker, UUID defender, String phase) {}

  private record ObjectiveTickRow(
      UUID id,
      UUID campaign,
      UUID attacker,
      UUID defender,
      UUID world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      long progress,
      long target,
      int minimum) {}
}
