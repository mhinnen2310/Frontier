package nl.frontier.persistence;

import java.nio.charset.StandardCharsets;
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
import nl.frontier.warfare.CampaignOutcomeGateway;

public final class PostgresCampaignOutcomeGateway implements CampaignOutcomeGateway {
  private static final Set<String> ROLES = Set.of("MAYOR", "DIPLOMAT");
  private final TransactionalStore store;

  public PostgresCampaignOutcomeGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public CampaignResult apply(
      UUID campaign, UUID actor, Outcome outcome, long amountMinor, Instant now) {
    return store.inTransaction(
        connection -> {
          CampaignResult existing = existing(connection, campaign);
          if (existing != null) return existing;
          Campaign campaignRow = campaign(connection, campaign);
          requireRole(connection, campaignRow, actor);
          if (!campaignRow.phase.equals("RESOLUTION"))
            throw new DomainException("campaign must be in resolution");
          UUID winner =
              switch (outcome) {
                case LIBERATION, INDEPENDENCE -> campaignRow.defender;
                default ->
                    campaignRow.attackerScore >= campaignRow.defenderScore
                        ? campaignRow.attacker
                        : campaignRow.defender;
              };
          UUID loser =
              winner.equals(campaignRow.attacker) ? campaignRow.defender : campaignRow.attacker;
          if (campaignRow.attackerScore == campaignRow.defenderScore
              && Set.of(Outcome.OCCUPATION, Outcome.CONQUEST, Outcome.ANNEXATION).contains(outcome))
            throw new DomainException("territorial victory requires a non-tied campaign score");
          Transfer transfer = Transfer.NONE;
          switch (outcome) {
            case CONQUEST, ANNEXATION ->
                transfer = transferTerritory(connection, campaign, loser, winner, true, now);
            case TERRITORY_CONCESSION ->
                transfer = transferTerritory(connection, campaign, loser, winner, false, now);
            case OCCUPATION -> occupy(connection, campaign, winner, loser, now);
            case LIBERATION -> liberate(connection, loser, winner, now);
            case REPARATIONS ->
                transferMoney(connection, loser, winner, amountMinor, campaign, now);
            case TRIBUTE ->
                update(
                    connection,
                    "INSERT INTO campaign_tributes(id,campaign_id,payer_city,payee_city,amount_minor,status,next_due_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
                    UUID.randomUUID(),
                    campaign,
                    loser,
                    winner,
                    amountMinor,
                    now.plusSeconds(86_400));
            case INDEPENDENCE -> {
              liberate(connection, loser, winner, now);
              completeSecession(connection, campaign, winner, now);
              update(
                  connection,
                  "UPDATE campaign_tributes SET status='ENDED',version=version+1 WHERE (payer_city=? OR payee_city=?) AND status IN ('ACTIVE','OVERDUE')",
                  winner,
                  winner);
            }
            case CIVIL_WAR -> resolveSecession(connection, campaign, "CONTESTED", now);
            case KINGDOM_INTERVENTION ->
                recordKingdomIntervention(connection, campaignRow, campaign, now);
          }
          if (outcome == Outcome.ANNEXATION) deactivateCity(connection, loser, "ANNEXED", now);
          else if (outcome == Outcome.CONQUEST) occupy(connection, campaign, winner, loser, now);
          UUID result = UUID.randomUUID();
          String terms =
              "{\"amountMinor\":"
                  + amountMinor
                  + ",\"winnerScore\":"
                  + (winner.equals(campaignRow.attacker)
                      ? campaignRow.attackerScore
                      : campaignRow.defenderScore)
                  + "}";
          update(
              connection,
              "INSERT INTO campaign_results(id,campaign_id,outcome,winner_city,loser_city,amount_minor,claims_transferred,buildings_transferred,roads_transferred,workers_transferred,storage_transferred,terms,applied_by,applied_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
              result,
              campaign,
              outcome.name(),
              winner,
              loser,
              amountMinor,
              transfer.claims,
              transfer.buildings,
              transfer.roads,
              transfer.workers,
              transfer.storage,
              terms,
              actor,
              now);
          update(
              connection,
              "UPDATE campaigns SET phase='ENDED',ended_at=?,resolution_reason=?,version=version+1 WHERE id=?",
              now,
              outcome.name(),
              campaign);
          update(
              connection,
              "UPDATE campaign_objectives SET state=CASE WHEN state='COMPLETED' THEN state ELSE 'EXPIRED' END,version=version+1 WHERE campaign_id=?",
              campaign);
          history(connection, "CAMPAIGN_OUTCOME_" + outcome, campaign, terms, now);
          return result(connection, result);
        });
  }

  private static void completeSecession(
      Connection connection, UUID campaign, UUID city, Instant now) throws SQLException {
    update(
        connection,
        "DELETE FROM kingdom_members WHERE city_id=? AND kingdom_id=(SELECT kingdom_id FROM kingdom_secessions WHERE campaign_id=?)",
        city,
        campaign);
    resolveSecession(connection, campaign, "COMPLETED", now);
  }

  private static void resolveSecession(
      Connection connection, UUID campaign, String status, Instant now) throws SQLException {
    update(
        connection,
        "UPDATE kingdom_secessions SET status=?,resolved_at=? WHERE campaign_id=? AND status='CONTESTED'",
        status,
        now,
        campaign);
  }

  private static void recordKingdomIntervention(
      Connection connection, Campaign campaign, UUID campaignId, Instant now) throws SQLException {
    for (UUID city : List.of(campaign.attacker, campaign.defender)) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT kingdom_id FROM kingdom_members WHERE city_id=?")) {
        statement.setObject(1, city);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next()) {
            update(
                connection,
                "INSERT INTO kingdom_history(id,kingdom_id,event_type,payload,occurred_at) VALUES(?,?, 'KINGDOM_INTERVENTION',jsonb_build_object('campaign',?::text),?)",
                UUID.randomUUID(),
                result.getObject(1, UUID.class),
                campaignId,
                now);
          }
        }
      }
    }
  }

  @Override
  public TributeCycle cycleTributes(int limit, Instant now) {
    return store.inTransaction(
        connection -> {
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM campaign_tributes WHERE status IN ('ACTIVE','OVERDUE') AND next_due_at<=? ORDER BY next_due_at LIMIT ? FOR UPDATE SKIP LOCKED")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
          }
          int paid = 0;
          int overdue = 0;
          for (UUID id : ids) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "SELECT payer_city,payee_city,amount_minor,paid_cycles FROM campaign_tributes WHERE id=? FOR UPDATE")) {
              statement.setObject(1, id);
              try (ResultSet result = statement.executeQuery()) {
                result.next();
                UUID payer = result.getObject(1, UUID.class);
                UUID payee = result.getObject(2, UUID.class);
                long amount = result.getLong(3);
                int cycle = result.getInt(4) + 1;
                UUID payerAccount = cityAccount(connection, payer);
                UUID payeeAccount = cityAccount(connection, payee);
                long payerBalance = balance(connection, payerAccount);
                long payeeBalance = balance(connection, payeeAccount);
                if (payerBalance >= amount) {
                  money(connection, payerAccount, payeeAccount, amount);
                  UUID key =
                      UUID.nameUUIDFromBytes((id + ":" + cycle).getBytes(StandardCharsets.UTF_8));
                  ledger(connection, payerAccount, -amount, payerBalance - amount, id, key, now);
                  ledger(
                      connection,
                      payeeAccount,
                      amount,
                      payeeBalance + amount,
                      id,
                      UUID.nameUUIDFromBytes((key + ":payee").getBytes(StandardCharsets.UTF_8)),
                      now);
                  update(
                      connection,
                      "UPDATE campaign_tributes SET status='ACTIVE',paid_cycles=paid_cycles+1,next_due_at=?,version=version+1 WHERE id=?",
                      now.plusSeconds(86_400),
                      id);
                  paid++;
                } else {
                  update(
                      connection,
                      "UPDATE campaign_tributes SET status='OVERDUE',missed_cycles=missed_cycles+1,next_due_at=?,version=version+1 WHERE id=?",
                      now.plusSeconds(86_400),
                      id);
                  overdue++;
                }
              }
            }
          }
          return new TributeCycle(paid, overdue);
        });
  }

  private static Transfer transferTerritory(
      Connection connection, UUID campaign, UUID from, UUID to, boolean all, Instant now)
      throws SQLException {
    String scope = all ? "ALL" : "OBJECTIVES";
    int claims =
        all
            ? updateCount(
                connection,
                "UPDATE city_claims SET city_id=?,state=CASE WHEN state='CAPITAL' THEN 'INFLUENCED' ELSE state END,version=version+1 WHERE city_id=?",
                to,
                from)
            : updateCount(
                connection,
                "UPDATE city_claims cl SET city_id=?,state=CASE WHEN state='CAPITAL' THEN 'INFLUENCED' ELSE state END,version=cl.version+1 WHERE cl.city_id=? AND EXISTS(SELECT 1 FROM campaign_objectives o WHERE o.campaign_id=? AND o.state='COMPLETED' AND cl.world_id=o.world_id AND cl.chunk_x*16+8 BETWEEN (o.bounds->>'minX')::int AND (o.bounds->>'maxX')::int AND cl.chunk_z*16+8 BETWEEN (o.bounds->>'minZ')::int AND (o.bounds->>'maxZ')::int)",
                to,
                from,
                campaign);
    int buildings =
        updateCount(
            connection,
            "UPDATE city_buildings b SET city_id=?,district_key=NULL,version=b.version+1 WHERE b.city_id=? AND EXISTS(SELECT 1 FROM city_claims cl WHERE cl.city_id=? AND cl.world_id=(b.bounds->>'world')::uuid AND cl.chunk_x=floor((b.bounds->>'minX')::int/16.0)::int AND cl.chunk_z=floor((b.bounds->>'minZ')::int/16.0)::int)",
            to,
            from,
            to);
    int roads =
        updateCount(
            connection,
            "UPDATE road_nodes n SET city_id=? WHERE n.city_id=? AND EXISTS(SELECT 1 FROM city_claims cl WHERE cl.city_id=? AND cl.world_id=n.world_id AND cl.chunk_x=floor(n.x/16.0)::int AND cl.chunk_z=floor(n.z/16.0)::int)",
            to,
            from,
            to);
    update(
        connection,
        "UPDATE road_edges e SET owner_city=?,version=e.version+1 FROM road_nodes n WHERE n.id=e.from_node AND n.city_id=?",
        to,
        to);
    int workers =
        all
            ? updateCount(
                connection,
                "UPDATE workers SET city_id=?,version=version+1 WHERE city_id=?",
                to,
                from)
            : updateCount(
                connection,
                "UPDATE workers w SET city_id=?,version=w.version+1 FROM city_buildings b WHERE w.assigned_building=b.id AND w.city_id=? AND b.city_id=?",
                to,
                from,
                to);
    long storage = all ? transferStorage(connection, from, to) : 0;
    update(
        connection,
        "INSERT INTO territory_transfer_history(id,campaign_id,from_city,to_city,scope,claims,buildings,roads,workers,storage_units,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        campaign,
        from,
        to,
        scope,
        claims,
        buildings,
        roads,
        workers,
        storage,
        now);
    return new Transfer(claims, buildings, roads, workers, storage);
  }

  private static long transferStorage(Connection connection, UUID from, UUID to)
      throws SQLException {
    UUID source = activeWarehouse(connection, from);
    UUID target = activeWarehouse(connection, to);
    long units =
        scalar(
            connection,
            "SELECT coalesce(sum(available_quantity),0) FROM warehouse_stock WHERE warehouse_id=?",
            source);
    update(
        connection,
        "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) SELECT ?,commodity_key,available_quantity,0,0 FROM warehouse_stock WHERE warehouse_id=? ON CONFLICT(warehouse_id,commodity_key) DO UPDATE SET available_quantity=warehouse_stock.available_quantity+excluded.available_quantity,version=warehouse_stock.version+1",
        target,
        source);
    update(
        connection,
        "UPDATE warehouse_stock SET available_quantity=0,version=version+1 WHERE warehouse_id=?",
        source);
    update(
        connection,
        "UPDATE warehouses SET status='CONQUERED',version=version+1 WHERE id=?",
        source);
    return units;
  }

  private static void occupy(
      Connection connection, UUID campaign, UUID occupier, UUID city, Instant now)
      throws SQLException {
    update(
        connection,
        "INSERT INTO city_occupations(city_id,occupier_city,campaign_id,status,started_at) VALUES(?,?,?,'ACTIVE',?) ON CONFLICT(city_id) DO UPDATE SET occupier_city=excluded.occupier_city,campaign_id=excluded.campaign_id,status='ACTIVE',started_at=excluded.started_at,ended_at=NULL,version=city_occupations.version+1",
        city,
        occupier,
        campaign,
        now);
    update(
        connection,
        "UPDATE cities SET lifecycle_status='OCCUPIED',version=version+1 WHERE id=?",
        city);
  }

  private static void liberate(Connection connection, UUID first, UUID second, Instant now)
      throws SQLException {
    update(
        connection,
        "UPDATE city_occupations SET status='ENDED',ended_at=?,version=version+1 WHERE city_id IN (?,?) AND status='ACTIVE'",
        now,
        first,
        second);
    update(
        connection,
        "UPDATE cities SET lifecycle_status='ACTIVE',version=version+1 WHERE id IN (?,?) AND lifecycle_status='OCCUPIED'",
        first,
        second);
  }

  private static void deactivateCity(Connection connection, UUID city, String status, Instant now)
      throws SQLException {
    update(
        connection,
        "UPDATE cities SET lifecycle_status=?,abandoned_at=?,version=version+1 WHERE id=?",
        status,
        now,
        city);
    update(connection, "DELETE FROM dirty_settlements WHERE city_id=?", city);
    update(connection, "DELETE FROM city_simulation_state WHERE city_id=?", city);
    update(connection, "DELETE FROM city_world_simulation_state WHERE city_id=?", city);
  }

  private static void transferMoney(
      Connection connection, UUID payer, UUID payee, long amount, UUID reference, Instant now)
      throws SQLException {
    UUID payerAccount = cityAccount(connection, payer);
    UUID payeeAccount = cityAccount(connection, payee);
    long payerBalance = balance(connection, payerAccount);
    long payeeBalance = balance(connection, payeeAccount);
    if (payerBalance < amount) throw new DomainException("loser cannot pay campaign reparations");
    money(connection, payerAccount, payeeAccount, amount);
    UUID key =
        UUID.nameUUIDFromBytes((reference + ":reparations").getBytes(StandardCharsets.UTF_8));
    ledger(connection, payerAccount, -amount, payerBalance - amount, reference, key, now);
    ledger(
        connection,
        payeeAccount,
        amount,
        payeeBalance + amount,
        reference,
        UUID.nameUUIDFromBytes((key + ":winner").getBytes(StandardCharsets.UTF_8)),
        now);
  }

  private static Campaign campaign(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT attacker_city_id,defender_city_id,phase,attacker_score,defender_score FROM campaigns WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("campaign not found");
        return new Campaign(
            result.getObject(1, UUID.class),
            result.getObject(2, UUID.class),
            result.getString(3),
            result.getLong(4),
            result.getLong(5));
      }
    }
  }

  private static void requireRole(Connection c, Campaign campaign, UUID actor) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT role,city_id FROM city_members WHERE player_id=? AND city_id IN (?,?)")) {
      s.setObject(1, actor);
      s.setObject(2, campaign.attacker);
      s.setObject(3, campaign.defender);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next() || !ROLES.contains(r.getString(1)))
          throw new DomainException("campaign role cannot apply outcome");
      }
    }
  }

  private static CampaignResult existing(Connection c, UUID campaign) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT id FROM campaign_results WHERE campaign_id=?")) {
      s.setObject(1, campaign);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? result(c, r.getObject(1, UUID.class)) : null;
      }
    }
  }

  private static CampaignResult result(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,campaign_id,outcome,winner_city,loser_city,claims_transferred,buildings_transferred,roads_transferred,workers_transferred,storage_transferred,amount_minor,applied_at FROM campaign_results WHERE id=?")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("campaign result not found");
        return new CampaignResult(
            id,
            r.getObject(2, UUID.class),
            Outcome.valueOf(r.getString(3)),
            r.getObject(4, UUID.class),
            r.getObject(5, UUID.class),
            r.getInt(6),
            r.getInt(7),
            r.getInt(8),
            r.getInt(9),
            r.getLong(10),
            r.getLong(11),
            r.getTimestamp(12).toInstant());
      }
    }
  }

  private static UUID activeWarehouse(Connection c, UUID city) throws SQLException {
    return uuid(
        c, "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE", city);
  }

  private static UUID cityAccount(Connection c, UUID city) throws SQLException {
    return uuid(
        c, "SELECT id FROM accounts WHERE owner_type='CITY' AND owner_id=? FOR UPDATE", city);
  }

  private static UUID uuid(Connection c, String sql, Object... v) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, v);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("required campaign asset missing");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection c, UUID account) throws SQLException {
    return scalar(c, "SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE", account);
  }

  private static void money(Connection c, UUID from, UUID to, long amount) throws SQLException {
    if (updateCount(
            c,
            "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=? AND balance_minor>=?",
            amount,
            from,
            amount)
        != 1) throw new DomainException("campaign transfer underfunded");
    update(
        c,
        "UPDATE accounts SET balance_minor=balance_minor+?,version=version+1 WHERE id=?",
        amount,
        to);
  }

  private static void ledger(
      Connection c, UUID account, long amount, long after, UUID reference, UUID key, Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO ledger_entries(id,account_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,description) VALUES(? ,?,'CAMPAIGN_OUTCOME',?,?,?,?,?,'campaign result transfer')",
        UUID.randomUUID(),
        account,
        amount,
        after,
        reference,
        key,
        now);
  }

  private static void history(
      Connection c, String event, UUID aggregate, String payload, Instant now) throws SQLException {
    update(
        c,
        "INSERT INTO server_history(id,event_type,aggregate_id,payload,occurred_at) VALUES(?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        event,
        aggregate,
        payload,
        now);
  }

  private static long scalar(Connection c, String sql, Object... v) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, v);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static void update(Connection c, String sql, Object... v) throws SQLException {
    updateCount(c, sql, v);
  }

  private static int updateCount(Connection c, String sql, Object... v) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, v);
      return s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... v) throws SQLException {
    for (int i = 0; i < v.length; i++) {
      Object x = v[i];
      if (x instanceof Instant instant) s.setTimestamp(i + 1, Timestamp.from(instant));
      else s.setObject(i + 1, x);
    }
  }

  private record Campaign(
      UUID attacker, UUID defender, String phase, long attackerScore, long defenderScore) {}

  private record Transfer(int claims, int buildings, int roads, int workers, long storage) {
    static final Transfer NONE = new Transfer(0, 0, 0, 0, 0);
  }
}
