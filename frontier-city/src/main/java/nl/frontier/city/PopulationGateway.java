package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PopulationGateway {
  CycleReport cycle(int limit, Instant now);

  PopulationReport report(UUID city, UUID actor);

  List<WorkerProfile> workers(UUID city, UUID actor);

  record PopulationReport(
      UUID city,
      int population,
      int housingCapacity,
      int foodSecurity,
      int safety,
      int prosperity,
      int births,
      int deaths,
      int immigration,
      int emigration,
      Instant simulatedAt) {}

  record WorkerProfile(
      UUID id,
      String profession,
      int skill,
      int morale,
      int efficiency,
      long salaryMinor,
      long experience,
      String employment,
      UUID housing,
      int ageDays,
      int retirementAgeDays) {}

  record CycleReport(
      int settlements,
      int births,
      int deaths,
      int immigration,
      int emigration,
      int retiredWorkers) {}
}
