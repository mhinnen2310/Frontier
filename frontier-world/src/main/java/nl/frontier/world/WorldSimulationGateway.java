package nl.frontier.world;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorldSimulationGateway {
  CycleReport cycle(int maximumCities, Instant now);

  WorldSimulation.Season season(Instant now);

  List<RegionSnapshot> regions();

  List<EventSnapshot> events(boolean activeOnly);

  record RegionSnapshot(
      UUID id,
      String key,
      int population,
      double prosperity,
      double stability,
      double tradeActivity,
      double roadIntegrity,
      String season,
      long version) {}

  record EventSnapshot(
      UUID id,
      UUID region,
      WorldEvent.Category category,
      String key,
      WorldEvent.State state,
      long progress,
      long target,
      Instant stateAt) {}

  record CycleReport(
      int cities, int migrations, int infrastructureAged, int eventsCreated, int eventsAdvanced) {}
}
