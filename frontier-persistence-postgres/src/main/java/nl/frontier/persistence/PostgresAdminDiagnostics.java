package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nl.frontier.api.AdminDiagnostics;
import nl.frontier.api.TransactionalStore;

public final class PostgresAdminDiagnostics implements AdminDiagnostics {
  private static final Map<String, String> TABLES =
      Map.of(
          "city", "cities",
          "war", "campaigns",
          "order", "repair_orders",
          "task", "repair_tasks",
          "worker", "workers",
          "shipment", "shipments",
          "kingdom", "kingdoms",
          "wonder", "world_wonders");
  private final TransactionalStore store;
  private static final Map<String, String> VIEWERS =
      Map.of(
          "settlement",
              "SELECT jsonb_build_object('city',to_jsonb(c),'treasury',(SELECT balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=c.id),'members',(SELECT count(*) FROM city_members WHERE city_id=c.id),'claims',(SELECT count(*) FROM city_claims WHERE city_id=c.id),'buildings',(SELECT count(*) FROM city_buildings WHERE city_id=c.id),'districts',(SELECT count(*) FROM city_districts WHERE city_id=c.id),'populationState',(SELECT to_jsonb(p) FROM city_population_state p WHERE p.city_id=c.id))::text FROM cities c WHERE c.id=?",
          "influence",
              "SELECT jsonb_build_object('city',c.id,'name',c.name,'states',(SELECT jsonb_object_agg(state,total) FROM (SELECT state,count(*) total FROM city_claims WHERE city_id=c.id GROUP BY state) s),'strongest',(SELECT jsonb_agg(to_jsonb(q)) FROM (SELECT world_id,chunk_x,chunk_z,state,influence,lead_cycles FROM city_claims WHERE city_id=c.id ORDER BY influence DESC LIMIT 25) q))::text FROM cities c WHERE c.id=?",
          "road",
              "SELECT jsonb_build_object('edge',to_jsonb(e),'from',to_jsonb(f),'to',to_jsonb(t))::text FROM road_edges e JOIN road_nodes f ON f.id=e.from_node JOIN road_nodes t ON t.id=e.to_node WHERE e.id=? UNION ALL SELECT jsonb_build_object('node',to_jsonb(n),'edges',(SELECT count(*) FROM road_edges WHERE from_node=n.id OR to_node=n.id))::text FROM road_nodes n WHERE n.id=?",
          "repair",
              "SELECT jsonb_build_object('order',to_jsonb(o),'taskStates',(SELECT jsonb_object_agg(status,total) FROM (SELECT status,count(*) total FROM repair_tasks WHERE repair_order_id=o.id GROUP BY status) s),'reservations',(SELECT count(*) FROM material_reservations WHERE repair_order_id=o.id),'packages',(SELECT count(*) FROM work_packages WHERE repair_order_id=o.id))::text FROM repair_orders o WHERE o.id=?",
          "campaign",
              "SELECT jsonb_build_object('campaign',to_jsonb(c),'objectives',(SELECT jsonb_agg(to_jsonb(o)) FROM campaign_objectives o WHERE o.campaign_id=c.id),'result',(SELECT to_jsonb(r) FROM campaign_results r WHERE r.campaign_id=c.id),'damage',(SELECT count(*) FROM damage_journal WHERE campaign_id=c.id))::text FROM campaigns c WHERE c.id=?",
          "worker",
              "SELECT jsonb_build_object('worker',to_jsonb(w),'city',c.name,'district',(SELECT d.name FROM district_workers dw JOIN city_districts d ON d.id=dw.district_id WHERE dw.worker_id=w.id),'housing',(SELECT b.id FROM city_buildings b WHERE b.id=w.housing_building))::text FROM workers w JOIN cities c ON c.id=w.city_id WHERE w.id=?",
          "economy",
              "SELECT jsonb_build_object('city',c.id,'name',c.name,'treasury',(SELECT balance_minor FROM accounts WHERE owner_type='CITY' AND owner_id=c.id),'warehouse',(SELECT to_jsonb(w) FROM warehouses w WHERE w.city_id=c.id AND w.status='ACTIVE' LIMIT 1),'stock',(SELECT jsonb_object_agg(s.commodity_key,s.available_quantity) FROM warehouses w JOIN warehouse_stock s ON s.warehouse_id=w.id WHERE w.city_id=c.id AND w.status='ACTIVE'),'openOrders',(SELECT count(*) FROM market_orders WHERE settlement_id=c.id AND status='OPEN'),'trades',(SELECT count(*) FROM trades t JOIN market_orders o ON o.id=t.buy_order_id WHERE o.settlement_id=c.id),'companies',(SELECT count(*) FROM companies WHERE city_id=c.id))::text FROM cities c WHERE c.id=?");

  public PostgresAdminDiagnostics(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public Snapshot snapshot() {
    return store.inTransaction(
        connection -> {
          Map<String, Long> counts = new LinkedHashMap<>();
          for (Map.Entry<String, String> entry : TABLES.entrySet()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT count(*) FROM " + entry.getValue());
                ResultSet result = statement.executeQuery()) {
              result.next();
              counts.put(entry.getKey(), result.getLong(1));
            }
          }
          long lag;
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT coalesce(extract(epoch from (now()-min(occurred_at)))::bigint,0) FROM outbox_events WHERE published_at IS NULL");
              ResultSet result = statement.executeQuery()) {
            result.next();
            lag = Math.max(0, result.getLong(1));
          }
          return new Snapshot(Map.copyOf(counts), lag);
        });
  }

  @Override
  public List<String> inspect(String aggregateType, UUID aggregateId) {
    String table = TABLES.get(aggregateType.toLowerCase(Locale.ROOT));
    if (table == null) throw new IllegalArgumentException("unknown inspect type");
    return store.inTransaction(
        connection -> {
          List<String> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT row_to_json(value)::text FROM " + table + " value WHERE id=?")) {
            statement.setObject(1, aggregateId);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(result.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<String> audit(int limit) {
    return store.inTransaction(
        connection -> {
          List<String> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT occurred_at||' '||action||' '||aggregate_type||' '||coalesce(aggregate_id::text,'-')||' actor='||coalesce(actor_id::text,'system')||' reason='||coalesce(reason,'-') FROM audit_log ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setInt(1, Math.max(1, Math.min(limit, 100)));
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(result.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<String> viewer(String viewType, UUID aggregateId) {
    String sql = VIEWERS.get(viewType.toLowerCase(Locale.ROOT));
    if (sql == null)
      throw new IllegalArgumentException(
          "viewer type must be settlement, influence, road, repair, campaign, worker or economy");
    return store.inTransaction(
        connection -> {
          List<String> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= countParameters(sql); index++)
              statement.setObject(index, aggregateId);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) rows.add(result.getString(1));
            }
          }
          return List.copyOf(rows);
        });
  }

  @Override
  public List<String> heatmap(UUID world, int centerChunkX, int centerChunkZ, int radius) {
    int bounded = Math.max(1, Math.min(radius, 12));
    return store.inTransaction(
        connection -> {
          Map<String, ClaimCell> cells = new HashMap<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT chunk_x,chunk_z,state,influence,city_id FROM city_claims WHERE world_id=? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ?")) {
            statement.setObject(1, world);
            statement.setInt(2, centerChunkX - bounded);
            statement.setInt(3, centerChunkX + bounded);
            statement.setInt(4, centerChunkZ - bounded);
            statement.setInt(5, centerChunkZ + bounded);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                cells.put(
                    result.getInt(1) + ":" + result.getInt(2),
                    new ClaimCell(
                        result.getString(3), result.getInt(4), result.getObject(5, UUID.class)));
            }
          }
          List<String> rows = new ArrayList<>();
          rows.add("Claim heatmap A=capital C=controlled X=contested i=influenced .=wilderness");
          for (int z = centerChunkZ - bounded; z <= centerChunkZ + bounded; z++) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%6d ", z));
            for (int x = centerChunkX - bounded; x <= centerChunkX + bounded; x++)
              row.append(symbol(cells.get(x + ":" + z)));
            rows.add(row.toString());
          }
          cells.values().stream()
              .filter(cell -> cell.owner != null)
              .map(cell -> cell.owner)
              .distinct()
              .sorted()
              .forEach(owner -> rows.add("owner " + owner));
          return List.copyOf(rows);
        });
  }

  @Override
  public List<String> chunkOwnership(UUID world, int chunkX, int chunkZ) {
    return store.inTransaction(
        connection -> {
          List<String> rows = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT jsonb_build_object('world',world_id,'chunkX',chunk_x,'chunkZ',chunk_z,'city',city_id,'cityName',(SELECT name FROM cities WHERE id=city_id),'state',state,'influence',influence,'leadCycles',lead_cycles,'version',version)::text FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=?")) {
            statement.setObject(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            try (ResultSet result = statement.executeQuery()) {
              if (result.next()) rows.add(result.getString(1));
              else rows.add("{\"state\":\"WILDERNESS\"}");
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT 'activeCampaign='||id||' phase='||phase FROM campaigns WHERE phase IN ('PREPARATION','ACTIVE','CEASEFIRE','RESOLUTION') AND (attacker_city_id=(SELECT city_id FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=?) OR defender_city_id=(SELECT city_id FROM city_claims WHERE world_id=? AND chunk_x=? AND chunk_z=?)) ORDER BY declared_at")) {
            statement.setObject(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setObject(4, world);
            statement.setInt(5, chunkX);
            statement.setInt(6, chunkZ);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) rows.add(result.getString(1));
            }
          }
          return List.copyOf(rows);
        });
  }

  @Override
  public Map<String, Long> liveMetrics() {
    return store.inTransaction(
        connection -> {
          Map<String, Long> values = new LinkedHashMap<>();
          values.put(
              "activeCampaigns",
              scalar(
                  connection,
                  "SELECT count(*) FROM campaigns WHERE phase IN ('PREPARATION','ACTIVE','CEASEFIRE','RESOLUTION')"));
          values.put(
              "pendingRepairs",
              scalar(
                  connection,
                  "SELECT count(*) FROM repair_tasks WHERE status IN ('PENDING','LEASED','READY','PLACING')"));
          values.put(
              "activeCaravans",
              scalar(connection, "SELECT count(*) FROM caravans WHERE state<>'DESPAWNED'"));
          values.put(
              "openOrders",
              scalar(connection, "SELECT count(*) FROM market_orders WHERE status='OPEN'"));
          values.put(
              "activeWorkers",
              scalar(
                  connection,
                  "SELECT count(*) FROM workers WHERE state='AVAILABLE' OR employment_status='EMPLOYED'"));
          values.put(
              "loadedClaims",
              scalar(connection, "SELECT count(*) FROM city_claims WHERE state<>'WILDERNESS'"));
          values.put(
              "pendingOutbox",
              scalar(connection, "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"));
          values.put(
              "databaseSessions",
              scalar(
                  connection,
                  "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"));
          return Map.copyOf(values);
        });
  }

  @Override
  public List<String> securityAudit() {
    return store.inTransaction(
        connection -> {
          List<String> rows = new ArrayList<>();
          securityCheck(
              connection,
              rows,
              "nonNegativeAccounts",
              "SELECT count(*) FROM accounts WHERE balance_minor<0");
          securityCheck(
              connection,
              rows,
              "stockInvariant",
              "SELECT count(*) FROM warehouse_stock WHERE available_quantity<0 OR reserved_quantity<0");
          securityCheck(
              connection,
              rows,
              "campaignPairUniqueness",
              "SELECT count(*) FROM (SELECT least(attacker_city_id::text,defender_city_id::text),greatest(attacker_city_id::text,defender_city_id::text),count(*) FROM campaigns WHERE phase IN ('DECLARED','PREPARATION','ACTIVE','CEASEFIRE','RESOLUTION') GROUP BY 1,2 HAVING count(*)>1)s");
          securityCheck(
              connection,
              rows,
              "dynamicEventReplay",
              "SELECT count(*) FROM (SELECT payload->>'sourceKey',count(*) FROM world_events WHERE payload ?? 'sourceKey' AND state IN ('SCHEDULED','ANNOUNCED','ACTIVE') GROUP BY 1 HAVING count(*)>1)s");
          securityCheck(
              connection,
              rows,
              "ledgerReplayKeys",
              "SELECT count(*) FROM (SELECT idempotency_key,count(*) FROM ledger_entries GROUP BY idempotency_key HAVING count(*)>1)s");
          securityCheck(
              connection,
              rows,
              "damageCoordinateUniqueness",
              "SELECT count(*) FROM (SELECT campaign_id,world_id,x,y,z,count(*) FROM damage_journal GROUP BY 1,2,3,4,5 HAVING count(*)>1)s");
          securityCheck(
              connection,
              rows,
              "staleAuthorizedDamage",
              "SELECT count(*) FROM damage_journal WHERE mutation_state='AUTHORIZED' AND authorized_at<now()-interval '10 minutes'");
          securityCheck(
              connection,
              rows,
              "overConsumedRepairMaterials",
              "SELECT count(*) FROM material_reservations WHERE consumed_quantity>reserved_quantity");
          securityCheck(
              connection,
              rows,
              "expiredActiveReservations",
              "SELECT count(*) FROM stock_reservations WHERE status='ACTIVE' AND expires_at<now()-interval '1 hour'");
          long missingIndexes =
              scalar(
                  connection,
                  "SELECT count(*) FROM (VALUES('uq_dynamic_event_source_open'),('uq_active_campaign_pair'),('idx_damage_reconcile'),('ledger_entries_idempotency_key_key')) required(name) WHERE NOT EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=required.name)");
          rows.add(
              (missingIndexes == 0 ? "PASS " : "FAIL ")
                  + "requiredSecurityIndexes="
                  + missingIndexes);
          rows.add(
              rows.stream().noneMatch(value -> value.startsWith("FAIL"))
                  ? "PASS securityAudit"
                  : "FAIL securityAudit");
          return List.copyOf(rows);
        });
  }

  private static void securityCheck(
      java.sql.Connection connection, List<String> rows, String name, String sql)
      throws java.sql.SQLException {
    long findings = scalar(connection, sql);
    rows.add((findings == 0 ? "PASS " : "FAIL ") + name + "=" + findings);
  }

  private static long scalar(java.sql.Connection connection, String sql)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private static int countParameters(String sql) {
    return (int) sql.chars().filter(value -> value == '?').count();
  }

  private static char symbol(ClaimCell cell) {
    if (cell == null) return '.';
    return switch (cell.state) {
      case "CAPITAL" -> 'A';
      case "CONTROLLED" -> 'C';
      case "CONTESTED" -> 'X';
      default -> cell.influence > 0 ? 'i' : '.';
    };
  }

  private record ClaimCell(String state, int influence, UUID owner) {}
}
