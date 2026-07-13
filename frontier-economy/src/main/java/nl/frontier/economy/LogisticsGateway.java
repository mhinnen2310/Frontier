package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LogisticsGateway {
  RoadNode registerNode(
      UUID city, UUID actor, UUID world, int x, int y, int z, String type, Instant now);

  RoadEdge connect(UUID city, UUID actor, UUID from, UUID to, long capacity, Instant now);

  Shipment createShipment(
      UUID city,
      UUID actor,
      UUID originWarehouse,
      UUID destinationWarehouse,
      UUID originNode,
      UUID destinationNode,
      String commodity,
      long quantity,
      String carrier,
      long declaredValue,
      UUID idempotency,
      Instant now);

  List<Shipment> shipments(UUID city);

  CycleReport cycle(int maximumShipments, Instant now);

  record RoadNode(
      UUID id, UUID city, UUID world, int x, int y, int z, String type, int integrity) {}

  record RoadEdge(UUID id, UUID from, UUID to, double distance, long capacity, int integrity) {}

  record Shipment(
      UUID id,
      UUID origin,
      UUID destination,
      String commodity,
      long quantity,
      String carrier,
      String status,
      Instant departedAt,
      Instant expectedArrivalAt,
      Instant arrivedAt) {}

  record CycleReport(int visited, int delivered, int waitingRoute) {}
}
