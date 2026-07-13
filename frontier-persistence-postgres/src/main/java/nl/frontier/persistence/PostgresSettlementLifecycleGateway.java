package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.city.SettlementLifecycleGateway;
import nl.frontier.domain.DomainException;

public final class PostgresSettlementLifecycleGateway implements SettlementLifecycleGateway {
  private final TransactionalStore store;

  public PostgresSettlementLifecycleGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public void validateCore(CoreLocation core) {
    store.inTransaction(
        connection -> {
          requireCoreDistance(connection, core);
          return null;
        });
  }

  @Override
  public FoundingReservation reserveFounding(
      UUID player, long feeMinor, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          if (scalar(connection, "SELECT count(*) FROM city_members WHERE player_id=?", player) > 0)
            throw new DomainException("you already belong to a settlement");
          UUID account = account(connection, player);
          long balance = balance(connection, account);
          if (balance < feeMinor) throw new DomainException("founding fee requires 2500 cents");
          UUID reservation = UUID.randomUUID();
          update(
              connection,
              "UPDATE accounts SET balance_minor=balance_minor-?,version=version+1 WHERE id=?",
              feeMinor,
              account);
          update(
              connection,
              "INSERT INTO settlement_founding_reservations(id,player_id,fee_minor,status,created_at,expires_at) VALUES(?,?,?,'RESERVED',?,?)",
              reservation,
              player,
              feeMinor,
              now,
              expiresAt);
          ledger(
              connection,
              account,
              player,
              -feeMinor,
              balance - feeMinor,
              reservation,
              "FOUNDING_FEE",
              now);
          return new FoundingReservation(reservation, player, feeMinor, "RESERVED", expiresAt);
        });
  }

  @Override
  public void completeFounding(
      UUID reservation,
      UUID city,
      UUID founder,
      CoreLocation core,
      String charter,
      int minimumFounders,
      Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT player_id,status,expires_at,fee_minor FROM settlement_founding_reservations WHERE id=? FOR UPDATE")) {
            statement.setObject(1, reservation);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("founding reservation not found");
              if (!founder.equals(result.getObject(1, UUID.class)))
                throw new DomainException("founding reservation belongs to another player");
              if (!result.getString(2).equals("RESERVED")) return null;
              if (result.getTimestamp(3).toInstant().isBefore(now))
                throw new DomainException("founding reservation expired");
            }
          }
          requireMayor(connection, city, founder);
          requireCoreDistance(connection, core);
          update(
              connection,
              "INSERT INTO settlement_cores(city_id,world_id,x,y,z,status,placed_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
              city,
              core.world(),
              core.x(),
              core.y(),
              core.z(),
              now);
          update(
              connection,
              "INSERT INTO settlement_charters(city_id,charter_text,founding_fee_minor,minimum_founders,ratified_at) SELECT ?,?,?,?,? FROM settlement_founding_reservations WHERE id=?",
              city,
              charter,
              2_500,
              minimumFounders,
              now,
              reservation);
          update(
              connection,
              "INSERT INTO settlement_founders(city_id,player_id,founder_order,accepted_at) VALUES(?,?,1,?)",
              city,
              founder,
              now);
          if (scalar(connection, "SELECT count(*) FROM settlement_founders WHERE city_id=?", city)
              < minimumFounders) throw new DomainException("minimum founders have not accepted");
          update(
              connection,
              "INSERT INTO settlement_member_activity(city_id,player_id,last_active_at) VALUES(?,?,?) ON CONFLICT(city_id,player_id) DO UPDATE SET last_active_at=excluded.last_active_at",
              city,
              founder,
              now);
          update(
              connection,
              "UPDATE settlement_founding_reservations SET status='COMPLETED',city_id=?,version=version+1 WHERE id=?",
              city,
              reservation);
          update(
              connection,
              "UPDATE cities SET lifecycle_status='ACTIVE',last_active_at=?,version=version+1 WHERE id=?",
              now,
              city);
          history(
              connection,
              city,
              "FOUNDED",
              founder,
              "{\"core\":\"" + core.x() + "," + core.y() + "," + core.z() + "\"}",
              now);
          return null;
        });
  }

  @Override
  public void cancelFounding(UUID reservation, UUID player, Instant now) {
    store.inTransaction(
        connection -> {
          refundReservation(connection, reservation, player, now);
          return null;
        });
  }

  @Override
  public void touch(UUID player, Instant now) {
    store.inTransaction(
        connection -> {
          update(
              connection,
              "INSERT INTO settlement_member_activity(city_id,player_id,last_active_at) SELECT city_id,player_id,? FROM city_members WHERE player_id=? ON CONFLICT(city_id,player_id) DO UPDATE SET last_active_at=excluded.last_active_at",
              now,
              player);
          update(
              connection,
              "UPDATE cities SET last_active_at=? FROM city_members m WHERE m.city_id=cities.id AND m.player_id=?",
              now,
              player);
          return null;
        });
  }

  @Override
  public LifecycleSnapshot transfer(UUID city, UUID actor, UUID successor, Instant now) {
    return store.inTransaction(
        connection -> transfer(connection, city, actor, successor, "OWNERSHIP_TRANSFERRED", now));
  }

  @Override
  public LifecycleSnapshot succeed(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID owner = owner(connection, city, true);
          requireMember(connection, city, actor);
          Instant active = memberActivity(connection, city, owner);
          if (active.plusSeconds(7L * 86_400L).isAfter(now))
            throw new DomainException("mayor must be inactive for seven days");
          return transfer(connection, city, owner, actor, "MAYOR_SUCCEEDED", now);
        });
  }

  @Override
  public LifecycleSnapshot abandon(UUID city, UUID actor, Instant now) {
    return store.inTransaction(connection -> ruin(connection, city, actor, "ABANDONED", now));
  }

  @Override
  public LifecycleSnapshot disband(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          if (scalar(
                  connection,
                  "SELECT count(*) FROM campaigns WHERE (attacker_city_id=? OR defender_city_id=?) AND phase<>'ENDED'",
                  city,
                  city)
              > 0) throw new DomainException("cannot disband during an active campaign");
          return ruin(connection, city, actor, "DISBANDED", now);
        });
  }

  @Override
  public LifecycleSnapshot recoverRuins(UUID city, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, city, actor);
          LifecycleSnapshot before = snapshot(connection, city);
          if (!before.status().equals("RUINS"))
            throw new DomainException("settlement is not ruins");
          if (before.ruinsUntil() == null || before.ruinsUntil().isBefore(now))
            throw new DomainException("ruins recovery window expired");
          update(
              connection,
              "UPDATE city_claims c SET city_id=r.city_id,state=r.previous_state,influence=CASE WHEN r.previous_state='CAPITAL' THEN 100 ELSE 25 END,version=c.version+1 FROM settlement_ruin_claims r WHERE r.city_id=? AND c.world_id=r.world_id AND c.chunk_x=r.chunk_x AND c.chunk_z=r.chunk_z AND c.city_id IS NULL AND c.state='WILDERNESS'",
              city);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM city_claims WHERE city_id=? AND state='CAPITAL'",
                  city)
              == 0) throw new DomainException("settlement capital claim is no longer recoverable");
          update(
              connection,
              "UPDATE cities SET lifecycle_status='ACTIVE',abandoned_at=NULL,ruins_until=NULL,last_active_at=?,version=version+1 WHERE id=?",
              now,
              city);
          update(
              connection,
              "UPDATE settlement_cores SET status='ACTIVE',version=version+1 WHERE city_id=?",
              city);
          update(
              connection,
              "INSERT INTO dirty_settlements(city_id,reason,enqueued_at) VALUES(?,'RUINS_RECOVERED',?) ON CONFLICT(city_id) DO UPDATE SET reason=excluded.reason,enqueued_at=excluded.enqueued_at",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_simulation_state(city_id,next_cycle_at) VALUES(?,?) ON CONFLICT(city_id) DO NOTHING",
              city,
              now);
          update(
              connection,
              "INSERT INTO city_world_simulation_state(city_id,region_key,next_cycle_at) SELECT c.city_id,c.world_id::text||':'||floor(c.chunk_x/32.0)::int||':'||floor(c.chunk_z/32.0)::int,? FROM city_claims c WHERE c.city_id=? AND c.state='CAPITAL' LIMIT 1 ON CONFLICT(city_id) DO NOTHING",
              now,
              city);
          history(connection, city, "RUINS_RECOVERED", actor, "{}", now);
          return snapshot(connection, city);
        });
  }

  @Override
  public MergeProposal proposeMerge(
      UUID source, UUID actor, UUID target, Instant now, Instant expiresAt) {
    return store.inTransaction(
        connection -> {
          requireMayor(connection, source, actor);
          owner(connection, target, false);
          UUID proposal = UUID.randomUUID();
          update(
              connection,
              "INSERT INTO settlement_merge_proposals(id,source_city,target_city,proposed_by,status,created_at,expires_at) VALUES(?,?,?,?,'PROPOSED',?,?)",
              proposal,
              source,
              target,
              actor,
              now,
              expiresAt);
          history(
              connection, source, "MERGE_PROPOSED", actor, "{\"target\":\"" + target + "\"}", now);
          return new MergeProposal(proposal, source, target, "PROPOSED", expiresAt);
        });
  }

  @Override
  public LifecycleSnapshot acceptMerge(UUID proposal, UUID actor, Instant now) {
    return store.inTransaction(
        connection -> {
          UUID source;
          UUID target;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT source_city,target_city,status,expires_at FROM settlement_merge_proposals WHERE id=? FOR UPDATE")) {
            statement.setObject(1, proposal);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new DomainException("merge proposal not found");
              source = result.getObject(1, UUID.class);
              target = result.getObject(2, UUID.class);
              if (!result.getString(3).equals("PROPOSED")
                  || result.getTimestamp(4).toInstant().isBefore(now))
                throw new DomainException("merge proposal is no longer active");
            }
          }
          requireMayor(connection, target, actor);
          if (scalar(
                  connection,
                  "SELECT count(*) FROM campaigns WHERE (attacker_city_id IN (?,?) OR defender_city_id IN (?,?)) AND phase<>'ENDED'",
                  source,
                  target,
                  source,
                  target)
              > 0) throw new DomainException("cannot merge during an active campaign");
          if (scalar(
                  connection,
                  "SELECT count(*) FROM kingdom_members WHERE city_id IN (?,?)",
                  source,
                  target)
              > 0) throw new DomainException("leave kingdoms before merging settlements");
          List<UUID> sourceMembers =
              ids(
                  connection,
                  "SELECT player_id FROM city_members WHERE city_id=? ORDER BY joined_at",
                  source);
          UUID sourceAccount = cityAccount(connection, source);
          UUID targetAccount = cityAccount(connection, target);
          long sourceBalance = balance(connection, sourceAccount);
          long targetBalance = balance(connection, targetAccount);
          update(
              connection,
              "UPDATE accounts SET balance_minor=0,version=version+1 WHERE id=?",
              sourceAccount);
          update(
              connection,
              "UPDATE accounts SET balance_minor=?,version=version+1 WHERE id=?",
              Math.addExact(sourceBalance, targetBalance),
              targetAccount);
          update(
              connection,
              "UPDATE city_claims SET city_id=?,state=CASE WHEN state='CAPITAL' THEN 'INFLUENCED' ELSE state END WHERE city_id=?",
              target,
              source);
          update(
              connection,
              "UPDATE city_buildings SET city_id=?,district_key=NULL WHERE city_id=?",
              target,
              source);
          update(connection, "UPDATE workers SET city_id=? WHERE city_id=?", target, source);
          UUID sourceWarehouse = warehouse(connection, source);
          UUID targetWarehouse = warehouse(connection, target);
          update(
              connection,
              "INSERT INTO warehouse_stock(warehouse_id,commodity_key,available_quantity,reserved_quantity,version) SELECT ?,commodity_key,available_quantity,0,0 FROM warehouse_stock WHERE warehouse_id=? ON CONFLICT(warehouse_id,commodity_key) DO UPDATE SET available_quantity=warehouse_stock.available_quantity+excluded.available_quantity,version=warehouse_stock.version+1",
              targetWarehouse,
              sourceWarehouse);
          update(
              connection,
              "UPDATE warehouse_stock SET available_quantity=0,version=version+1 WHERE warehouse_id=?",
              sourceWarehouse);
          update(
              connection,
              "UPDATE warehouses SET capacity=capacity+(SELECT capacity FROM warehouses WHERE id=?),version=version+1 WHERE id=?",
              sourceWarehouse,
              targetWarehouse);
          update(
              connection,
              "UPDATE warehouses SET status='MERGED',version=version+1 WHERE id=?",
              sourceWarehouse);
          update(connection, "UPDATE road_nodes SET city_id=? WHERE city_id=?", target, source);
          update(connection, "UPDATE city_districts SET city_id=? WHERE city_id=?", target, source);
          update(connection, "DELETE FROM city_members WHERE city_id=?", source);
          for (UUID member : sourceMembers)
            update(
                connection,
                "INSERT INTO city_members(city_id,player_id,role,joined_at) VALUES(?,?,'CITIZEN',?) ON CONFLICT(player_id) DO NOTHING",
                target,
                member,
                now);
          update(
              connection,
              "UPDATE cities SET lifecycle_status='MERGED',abandoned_at=?,ruins_until=?,version=version+1 WHERE id=?",
              now,
              now.plusSeconds(30L * 86_400L),
              source);
          update(
              connection,
              "UPDATE settlement_cores SET status='MERGED',version=version+1 WHERE city_id=?",
              source);
          update(connection, "DELETE FROM dirty_settlements WHERE city_id=?", source);
          update(connection, "DELETE FROM city_simulation_state WHERE city_id=?", source);
          update(connection, "DELETE FROM city_world_simulation_state WHERE city_id=?", source);
          update(
              connection,
              "UPDATE settlement_merge_proposals SET status='ACCEPTED',accepted_by=?,version=version+1 WHERE id=?",
              actor,
              proposal);
          history(connection, source, "MERGED", actor, "{\"target\":\"" + target + "\"}", now);
          history(
              connection, target, "MERGE_ACCEPTED", actor, "{\"source\":\"" + source + "\"}", now);
          return snapshot(connection, target);
        });
  }

  @Override
  public RecoveryReport recoverInactive(Instant inactiveBefore, Instant now, int limit) {
    return store.inTransaction(
        connection -> {
          int refunded = 0, successions = 0, abandoned = 0;
          List<UUID> reservations =
              ids(
                  connection,
                  "SELECT id FROM settlement_founding_reservations WHERE status='RESERVED' AND expires_at<? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  now,
                  limit);
          for (UUID id : reservations) {
            refundReservation(connection, id, null, now);
            refunded++;
          }
          List<UUID> cities =
              ids(
                  connection,
                  "SELECT id FROM cities WHERE lifecycle_status='ACTIVE' AND last_active_at<? ORDER BY last_active_at LIMIT ? FOR UPDATE SKIP LOCKED",
                  inactiveBefore,
                  limit);
          for (UUID city : cities) {
            UUID current = owner(connection, city, false);
            UUID successor = activeSuccessor(connection, city, current, inactiveBefore);
            if (successor != null) {
              transfer(connection, city, current, successor, "AUTOMATIC_SUCCESSION", now);
              successions++;
            } else {
              ruin(connection, city, current, "INACTIVE_ABANDONMENT", now);
              abandoned++;
            }
          }
          return new RecoveryReport(abandoned, successions, refunded);
        });
  }

  @Override
  public List<HistoryEntry> history(UUID city, UUID actor, int limit) {
    return store.inTransaction(
        connection -> {
          requireMember(connection, city, actor);
          List<HistoryEntry> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT event_type,actor_id,payload::text,occurred_at FROM settlement_lifecycle_history WHERE city_id=? ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setObject(1, city);
            statement.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new HistoryEntry(
                        result.getString(1),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getTimestamp(4).toInstant()));
            }
          }
          return List.copyOf(values);
        });
  }

  private static LifecycleSnapshot transfer(
      Connection c, UUID city, UUID actor, UUID successor, String event, Instant now)
      throws SQLException {
    UUID owner = owner(c, city, true);
    if (!owner.equals(actor)) throw new DomainException("only the mayor can transfer ownership");
    requireMember(c, city, successor);
    update(
        c, "UPDATE city_members SET role='CITIZEN' WHERE city_id=? AND player_id=?", city, owner);
    update(
        c, "UPDATE city_members SET role='MAYOR' WHERE city_id=? AND player_id=?", city, successor);
    update(
        c,
        "UPDATE cities SET owner_id=?,last_active_at=?,version=version+1 WHERE id=?",
        successor,
        now,
        city);
    history(c, city, event, actor, "{\"successor\":\"" + successor + "\"}", now);
    return snapshot(c, city);
  }

  private static void requireCoreDistance(Connection connection, CoreLocation core)
      throws SQLException {
    if (scalar(
            connection,
            "SELECT count(*) FROM settlement_cores WHERE world_id=? AND ((x-?::int)::bigint*(x-?::int)::bigint+(z-?::int)::bigint*(z-?::int)::bigint)<?",
            core.world(),
            core.x(),
            core.x(),
            core.z(),
            core.z(),
            128L * 128L)
        > 0) throw new DomainException("settlement core must be at least 128 blocks away");
  }

  private static LifecycleSnapshot ruin(
      Connection c, UUID city, UUID actor, String event, Instant now) throws SQLException {
    requireMayor(c, city, actor);
    Instant until = now.plusSeconds(30L * 86_400L);
    update(
        c,
        "INSERT INTO settlement_ruin_claims(city_id,world_id,chunk_x,chunk_z,previous_state,abandoned_at) SELECT city_id,world_id,chunk_x,chunk_z,state,? FROM city_claims WHERE city_id=? ON CONFLICT(city_id,world_id,chunk_x,chunk_z) DO UPDATE SET previous_state=excluded.previous_state,abandoned_at=excluded.abandoned_at",
        now,
        city);
    update(
        c,
        "UPDATE cities SET lifecycle_status='RUINS',abandoned_at=?,ruins_until=?,version=version+1 WHERE id=?",
        now,
        until,
        city);
    update(
        c,
        "UPDATE city_claims SET city_id=NULL,state='WILDERNESS',influence=0,version=version+1 WHERE city_id=?",
        city);
    update(
        c,
        "UPDATE city_buildings SET status=CASE WHEN status='DESTROYED' THEN status ELSE 'DISABLED' END,version=version+1 WHERE city_id=?",
        city);
    update(c, "UPDATE settlement_cores SET status='RUINS',version=version+1 WHERE city_id=?", city);
    update(c, "DELETE FROM dirty_settlements WHERE city_id=?", city);
    update(c, "DELETE FROM city_simulation_state WHERE city_id=?", city);
    update(c, "DELETE FROM city_world_simulation_state WHERE city_id=?", city);
    history(c, city, event, actor, "{\"ruinsUntil\":\"" + until + "\"}", now);
    return snapshot(c, city);
  }

  private static void refundReservation(Connection c, UUID id, UUID player, Instant now)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT player_id,fee_minor,status FROM settlement_founding_reservations WHERE id=? FOR UPDATE")) {
      s.setObject(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("founding reservation not found");
        UUID owner = r.getObject(1, UUID.class);
        if (player != null && !player.equals(owner))
          throw new DomainException("founding reservation belongs to another player");
        if (!r.getString(3).equals("RESERVED")) return;
        long fee = r.getLong(2), before = balance(c, account(c, owner));
        UUID account = account(c, owner);
        update(
            c,
            "UPDATE accounts SET balance_minor=balance_minor+?,version=version+1 WHERE id=?",
            fee,
            account);
        ledger(c, account, owner, fee, before + fee, id, "FOUNDING_REFUND", now);
        update(
            c,
            "UPDATE settlement_founding_reservations SET status='REFUNDED',version=version+1 WHERE id=?",
            id);
      }
    }
  }

  private static UUID activeSuccessor(Connection c, UUID city, UUID owner, Instant cutoff)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT m.player_id FROM city_members m JOIN settlement_member_activity a ON a.city_id=m.city_id AND a.player_id=m.player_id WHERE m.city_id=? AND m.player_id<>? AND a.last_active_at>=? ORDER BY CASE m.role WHEN 'ARCHITECT' THEN 0 WHEN 'TREASURER' THEN 1 ELSE 2 END,m.joined_at LIMIT 1")) {
      s.setObject(1, city);
      s.setObject(2, owner);
      s.setTimestamp(3, Timestamp.from(cutoff));
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getObject(1, UUID.class) : null;
      }
    }
  }

  private static Instant memberActivity(Connection c, UUID city, UUID player) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT last_active_at FROM settlement_member_activity WHERE city_id=? AND player_id=?")) {
      s.setObject(1, city);
      s.setObject(2, player);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("member activity missing");
        return r.getTimestamp(1).toInstant();
      }
    }
  }

  private static LifecycleSnapshot snapshot(Connection c, UUID city) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id,owner_id,lifecycle_status,abandoned_at,ruins_until FROM cities WHERE id=?")) {
      s.setObject(1, city);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("settlement not found");
        return new LifecycleSnapshot(
            city,
            r.getObject(2, UUID.class),
            r.getString(3),
            r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(),
            r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant());
      }
    }
  }

  private static UUID owner(Connection c, UUID city, boolean lock) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT owner_id FROM cities WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
      s.setObject(1, city);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("settlement not found");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static void requireMayor(Connection c, UUID city, UUID actor) throws SQLException {
    if (!owner(c, city, false).equals(actor))
      throw new DomainException("only the mayor can change settlement lifecycle");
  }

  private static void requireMember(Connection c, UUID city, UUID actor) throws SQLException {
    if (scalar(c, "SELECT count(*) FROM city_members WHERE city_id=? AND player_id=?", city, actor)
        != 1) throw new DomainException("not a settlement member");
  }

  private static UUID account(Connection c, UUID player) throws SQLException {
    update(
        c,
        "INSERT INTO accounts(id,owner_type,owner_id,balance_minor) VALUES(?,'PLAYER',?,0) ON CONFLICT(owner_type,owner_id) DO NOTHING",
        UUID.randomUUID(),
        player);
    return accountByOwner(c, "PLAYER", player);
  }

  private static UUID warehouse(Connection connection, UUID city) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM warehouses WHERE city_id=? AND status='ACTIVE' FOR UPDATE")) {
      statement.setObject(1, city);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new DomainException("active settlement warehouse missing");
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static UUID cityAccount(Connection c, UUID city) throws SQLException {
    return accountByOwner(c, "CITY", city);
  }

  private static UUID accountByOwner(Connection c, String type, UUID owner) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id FROM accounts WHERE owner_type=? AND owner_id=? FOR UPDATE")) {
      s.setString(1, type);
      s.setObject(2, owner);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("account missing");
        return r.getObject(1, UUID.class);
      }
    }
  }

  private static long balance(Connection c, UUID account) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT balance_minor FROM accounts WHERE id=? FOR UPDATE")) {
      s.setObject(1, account);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new DomainException("account missing");
        return r.getLong(1);
      }
    }
  }

  private static void ledger(
      Connection c,
      UUID account,
      UUID actor,
      long amount,
      long after,
      UUID key,
      String type,
      Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO ledger_entries(id,account_id,actor_id,entry_type,amount_minor,balance_after_minor,reference_id,idempotency_key,occurred_at,description) VALUES(?,?,?,?,?,?,?,?,?,'settlement founding lifecycle')",
        UUID.randomUUID(),
        account,
        actor,
        type,
        amount,
        after,
        key,
        UUID.nameUUIDFromBytes(
            (key + ":" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        now);
  }

  private static void history(
      Connection c, UUID city, String event, UUID actor, String payload, Instant now)
      throws SQLException {
    update(
        c,
        "INSERT INTO settlement_lifecycle_history(id,city_id,event_type,actor_id,payload,occurred_at) VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        city,
        event,
        actor,
        payload,
        now);
  }

  private static List<UUID> ids(Connection c, String sql, Object... args) throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) ids.add(r.getObject(1, UUID.class));
      }
    }
    return ids;
  }

  private static long scalar(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static void update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      Object v = args[i];
      if (v instanceof Instant instant) s.setTimestamp(i + 1, Timestamp.from(instant));
      else s.setObject(i + 1, v);
    }
  }
}
