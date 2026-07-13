package nl.frontier.npc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable bounded worker scheduling; visible entities remain replaceable projections. */
public interface WorkerActivityGateway {
  CycleReport cycle(
      Set<PlayerObservation> observers,
      UUID scheduler,
      int maximum,
      Instant now,
      Instant leaseUntil);

  void arrived(UUID activity, UUID worker, UUID scheduler, Instant now);

  void failed(UUID activity, UUID worker, UUID scheduler, String reason, Instant now);

  record Activity(
      UUID id,
      UUID worker,
      UUID city,
      String type,
      UUID world,
      int x,
      int y,
      int z,
      UUID entity,
      String status) {}

  record CycleReport(int queued, int leased, int simulated, int recovered, List<Activity> visible) {
    public CycleReport {
      visible = List.copyOf(visible);
    }
  }
}
