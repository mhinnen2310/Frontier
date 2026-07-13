package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.frontier.api.TransactionalStore;
import nl.frontier.domain.DomainException;
import nl.frontier.npc.NpcMaterializationGateway;
import nl.frontier.npc.PlayerObservation;

public final class PostgresNpcMaterializationGateway implements NpcMaterializationGateway {
  private final TransactionalStore store;

  public PostgresNpcMaterializationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public List<Candidate> candidates(Set<PlayerObservation> observers, int maximumPerSettlement) {
    if (observers.isEmpty() || maximumPerSettlement <= 0) return List.of();
    return store.inTransaction(
        connection -> {
          String observerRows =
              observers.stream().map(ignored -> "(?,?,?,?)").collect(Collectors.joining(","));
          String sql =
              "WITH observers(player_id,world_id,x,z) AS (VALUES "
                  + observerRows
                  + "), active_cities AS (SELECT DISTINCT c.id city_id FROM cities c JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL' JOIN observers o ON o.world_id=cl.world_id AND abs(cl.chunk_x*16+8-o.x)<=128 AND abs(cl.chunk_z*16+8-o.z)<=128 WHERE c.lifecycle_status='ACTIVE'), ranked AS (SELECT w.*,row_number() OVER(PARTITION BY w.city_id ORDER BY CASE w.profession WHEN 'BUILDER' THEN 0 WHEN 'GUARD' THEN 1 WHEN 'CLERK' THEN 2 WHEN 'MERCHANT' THEN 3 ELSE 4 END,w.id) rn FROM workers w JOIN active_cities a ON a.city_id=w.city_id WHERE w.state<>'UNAVAILABLE') SELECT r.id,r.city_id,r.profession,r.skill,CASE WHEN r.profession='BUILDER' AND b.id IS NOT NULL THEN (b.bounds->>'world')::uuid ELSE c.world_id END,CASE WHEN r.profession='BUILDER' AND b.id IS NOT NULL THEN ((b.bounds->>'minX')::int+(b.bounds->>'maxX')::int)/2 ELSE c.chunk_x*16+8 END,CASE WHEN r.profession='BUILDER' AND b.id IS NOT NULL THEN ((b.bounds->>'minZ')::int+(b.bounds->>'maxZ')::int)/2 ELSE c.chunk_z*16+8 END,r.presentation_entity_id FROM ranked r JOIN city_claims c ON c.city_id=r.city_id AND c.state='CAPITAL' LEFT JOIN city_buildings b ON b.id=r.assigned_building AND b.status IN ('ACTIVE','DAMAGED') WHERE r.rn<=? ORDER BY r.city_id,r.rn";
          List<Candidate> values = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (PlayerObservation observer : observers) {
              statement.setObject(index++, observer.player());
              statement.setObject(index++, observer.world());
              statement.setInt(index++, observer.x());
              statement.setInt(index++, observer.z());
            }
            statement.setInt(index, maximumPerSettlement);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                int chunkX = result.getInt(6);
                int chunkZ = result.getInt(7);
                values.add(
                    new Candidate(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getInt(4),
                        result.getObject(5, UUID.class),
                        chunkX * 16 + 8,
                        64,
                        chunkZ * 16 + 8,
                        result.getObject(8, UUID.class)));
              }
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<Binding> retirements(Set<PlayerObservation> observers) {
    return store.inTransaction(
        connection -> {
          List<Binding> values = new ArrayList<>();
          String sql;
          if (observers.isEmpty()) {
            sql =
                "SELECT id,presentation_entity_id FROM workers WHERE presentation_entity_id IS NOT NULL";
          } else {
            String observerRows =
                observers.stream().map(ignored -> "(?,?,?,?)").collect(Collectors.joining(","));
            sql =
                "WITH observers(player_id,world_id,x,z) AS (VALUES "
                    + observerRows
                    + ") SELECT w.id,w.presentation_entity_id FROM workers w WHERE w.presentation_entity_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM city_claims cl JOIN observers o ON o.world_id=cl.world_id AND abs(cl.chunk_x*16+8-o.x)<=128 AND abs(cl.chunk_z*16+8-o.z)<=128 WHERE cl.city_id=w.city_id AND cl.state='CAPITAL')";
          }
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (PlayerObservation observer : observers) {
              statement.setObject(index++, observer.player());
              statement.setObject(index++, observer.world());
              statement.setInt(index++, observer.x());
              statement.setInt(index++, observer.z());
            }
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new Binding(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public void bind(UUID worker, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE workers SET presentation_entity_id=?,presentation_bound_at=?,version=version+1 WHERE id=? AND (presentation_entity_id IS NULL OR presentation_entity_id=?)")) {
            statement.setObject(1, entity);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, worker);
            statement.setObject(4, entity);
            if (statement.executeUpdate() != 1)
              throw new DomainException("worker already has another presentation");
          }
          return null;
        });
  }

  @Override
  public void unbind(UUID worker, UUID entity, Instant now) {
    store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE workers SET presentation_entity_id=NULL,presentation_bound_at=NULL,version=version+1 WHERE id=? AND presentation_entity_id=?")) {
            statement.setObject(1, worker);
            statement.setObject(2, entity);
            statement.executeUpdate();
          }
          return null;
        });
  }
}
