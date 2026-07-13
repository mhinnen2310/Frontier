package nl.frontier.city;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PopulationGateway {
  CycleReport cycle(int limit, Instant now);

  PopulationReport report(UUID city, UUID actor);

  List<WorkerProfile> workers(UUID city, UUID actor);

  WorkerProfile assignBuilding(UUID city, UUID actor, UUID worker, UUID building, Instant now);

  WorkerProfile clearBuilding(UUID city, UUID actor, UUID worker, Instant now);

  WorkerProfile setWage(UUID city, UUID actor, UUID worker, long wageMinor, Instant now);

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
      UUID assignedBuilding,
      UUID assignedDistrict,
      String status,
      UUID currentTask,
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
