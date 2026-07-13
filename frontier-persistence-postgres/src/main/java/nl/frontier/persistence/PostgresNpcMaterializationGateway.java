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

public final class PostgresNpcMaterializationGateway implements NpcMaterializationGateway {
  private final TransactionalStore store;

  public PostgresNpcMaterializationGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public List<Candidate> candidates(Set<UUID> onlinePlayers, int maximumPerSettlement) {
    if (onlinePlayers.isEmpty() || maximumPerSettlement <= 0) return List.of();
    return store.inTransaction(
        connection -> {
          String placeholders =
              onlinePlayers.stream().map(ignored -> "?").collect(Collectors.joining(","));
          String sql =
              "WITH active_cities AS (SELECT DISTINCT city_id FROM city_members WHERE player_id IN ("
                  + placeholders
                  + ")), ranked AS (SELECT w.*,row_number() OVER(PARTITION BY w.city_id ORDER BY CASE w.profession WHEN 'BUILDER' THEN 0 WHEN 'GUARD' THEN 1 WHEN 'MERCHANT' THEN 2 ELSE 3 END,w.id) rn FROM workers w JOIN active_cities a ON a.city_id=w.city_id WHERE w.state<>'DESPAWNED') SELECT r.id,r.city_id,r.profession,r.skill,c.world_id,c.chunk_x,c.chunk_z,r.presentation_entity_id FROM ranked r JOIN city_claims c ON c.city_id=r.city_id AND c.state='CAPITAL' WHERE r.rn<=? ORDER BY r.city_id,r.rn";
          List<Candidate> values = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID player : onlinePlayers) statement.setObject(index++, player);
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
  public List<Binding> retirements(Set<UUID> onlinePlayers) {
    return store.inTransaction(
        connection -> {
          List<Binding> values = new ArrayList<>();
          String sql;
          if (onlinePlayers.isEmpty()) {
            sql =
                "SELECT id,presentation_entity_id FROM workers WHERE presentation_entity_id IS NOT NULL";
          } else {
            String placeholders =
                onlinePlayers.stream().map(ignored -> "?").collect(Collectors.joining(","));
            sql =
                "SELECT w.id,w.presentation_entity_id FROM workers w WHERE w.presentation_entity_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM city_members m WHERE m.city_id=w.city_id AND m.player_id IN ("
                    + placeholders
                    + "))";
          }
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID player : onlinePlayers) statement.setObject(index++, player);
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
