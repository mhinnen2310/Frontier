package nl.frontier.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.frontier.api.TransactionalStore;
import nl.frontier.world.EndgameGateway;

public final class PostgresEndgameGateway implements EndgameGateway {
  private final TransactionalStore store;

  public PostgresEndgameGateway(TransactionalStore store) {
    this.store = store;
  }

  @Override
  public List<Definition> catalog() {
    return store.inTransaction(
        connection -> {
          List<Definition> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT 'RESEARCH',project_key,required_era,NULL,required_points,prerequisite_key,effect::text FROM endgame_research_definitions WHERE enabled UNION ALL SELECT 'WONDER',wonder_key,required_era,commodity_key,required_units,NULL,effect::text FROM endgame_wonder_definitions WHERE enabled UNION ALL SELECT 'MEGA_PROJECT',project_key,required_era,commodity_key,required_units,NULL,effect::text FROM endgame_mega_definitions WHERE enabled ORDER BY 1,2");
              ResultSet result = statement.executeQuery()) {
            while (result.next())
              values.add(
                  new Definition(
                      result.getString(1),
                      result.getString(2),
                      result.getString(3),
                      result.getString(4),
                      result.getLong(5),
                      result.getString(6),
                      result.getString(7)));
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<Ranking> rankings(int limit) {
    return store.inTransaction(
        connection -> {
          List<Ranking> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "WITH scores AS (SELECT k.id,k.name,k.era,k.prestige,count(DISTINCT km.city_id)::int cities,coalesce(sum(c.population),0) population,(SELECT count(*) FROM research_projects r WHERE r.kingdom_id=k.id AND r.status='COMPLETED')::int research,(SELECT count(*) FROM world_wonders w WHERE w.kingdom_id=k.id AND w.status='COMPLETED')::int wonders,(SELECT count(*) FROM mega_projects m WHERE m.kingdom_id=k.id AND m.status='COMPLETED')::int mega,k.prestige+coalesce(sum(c.population),0)+(SELECT count(*)*100 FROM research_projects r WHERE r.kingdom_id=k.id AND r.status='COMPLETED')+(SELECT count(*)*500 FROM world_wonders w WHERE w.kingdom_id=k.id AND w.status='COMPLETED')+(SELECT count(*)*150 FROM mega_projects m WHERE m.kingdom_id=k.id AND m.status='COMPLETED')+CASE k.era WHEN 'GOLDEN_AGE' THEN 4000 WHEN 'KINGDOM' THEN 3000 WHEN 'INDUSTRIAL' THEN 2000 WHEN 'EXPANSION' THEN 1000 ELSE 0 END score FROM kingdoms k LEFT JOIN kingdom_members km ON km.kingdom_id=k.id LEFT JOIN cities c ON c.id=km.city_id GROUP BY k.id) SELECT row_number() OVER(ORDER BY score DESC,name)::int,id,name,era,prestige,cities,population,research,wonders,mega,score FROM scores ORDER BY score DESC,name LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new Ranking(
                        result.getInt(1),
                        result.getObject(2, UUID.class),
                        result.getString(3),
                        result.getString(4),
                        result.getLong(5),
                        result.getInt(6),
                        result.getLong(7),
                        result.getInt(8),
                        result.getInt(9),
                        result.getInt(10),
                        result.getLong(11)));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<HistoryEntry> worldHistory(int limit) {
    return store.inTransaction(
        connection -> {
          List<HistoryEntry> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT occurred_at,event_type,aggregate_id,payload::text FROM server_history ORDER BY occurred_at DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next())
                values.add(
                    new HistoryEntry(
                        result.getTimestamp(1).toInstant(),
                        result.getString(2),
                        result.getObject(3, UUID.class),
                        result.getString(4)));
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public List<String> unlocks(UUID kingdom) {
    return store.inTransaction(
        connection -> {
          List<String> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT content_type||':'||content_key||' '||effect::text FROM kingdom_unlocks WHERE kingdom_id=? ORDER BY unlocked_at")) {
            statement.setObject(1, kingdom);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(result.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }
}
