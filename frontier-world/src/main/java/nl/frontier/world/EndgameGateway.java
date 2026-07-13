package nl.frontier.world;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EndgameGateway {
  List<Definition> catalog();

  List<Ranking> rankings(int limit);

  List<HistoryEntry> worldHistory(int limit);

  List<String> unlocks(UUID kingdom);

  record Definition(
      String contentType,
      String key,
      String requiredEra,
      String commodity,
      long requirement,
      String prerequisite,
      String effect) {}

  record Ranking(
      int rank,
      UUID kingdom,
      String name,
      String era,
      long prestige,
      int cities,
      long population,
      int research,
      int wonders,
      int megaProjects,
      long score) {}

  record HistoryEntry(Instant occurredAt, String eventType, UUID aggregate, String payload) {}
}
