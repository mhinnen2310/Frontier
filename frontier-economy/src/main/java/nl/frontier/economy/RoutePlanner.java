package nl.frontier.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Deterministic Dijkstra planner over registered, usable road edges. */
public final class RoutePlanner {
  public Route plan(UUID origin, UUID destination, List<Edge> edges) {
    Map<UUID, List<Edge>> graph = new HashMap<>();
    for (Edge edge : edges) {
      if (edge.integrity() < 10 || edge.capacity() <= 0) continue;
      graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
      graph.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.reverse());
    }
    Map<UUID, Double> distance = new HashMap<>();
    Map<UUID, UUID> previous = new HashMap<>();
    PriorityQueue<Step> queue =
        new PriorityQueue<>(Comparator.comparingDouble(Step::distance).thenComparing(Step::node));
    distance.put(origin, 0.0);
    queue.add(new Step(origin, 0.0));
    Set<UUID> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      Step current = queue.remove();
      if (!visited.add(current.node())) continue;
      if (current.node().equals(destination)) break;
      for (Edge edge : graph.getOrDefault(current.node(), List.of())) {
        double cost = edge.distance() * (100.0 / edge.integrity());
        double candidate = current.distance() + cost;
        if (candidate < distance.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
          distance.put(edge.to(), candidate);
          previous.put(edge.to(), current.node());
          queue.add(new Step(edge.to(), candidate));
        }
      }
    }
    if (!distance.containsKey(destination)) return Route.unreachable();
    List<UUID> path = new ArrayList<>();
    UUID cursor = destination;
    while (cursor != null) {
      path.add(cursor);
      cursor = previous.get(cursor);
    }
    java.util.Collections.reverse(path);
    return new Route(List.copyOf(path), distance.get(destination), true);
  }

  public record Edge(UUID from, UUID to, double distance, long capacity, int integrity) {
    public Edge {
      if (distance <= 0 || capacity < 0 || integrity < 0 || integrity > 100)
        throw new IllegalArgumentException("invalid road edge");
    }

    Edge reverse() {
      return new Edge(to, from, distance, capacity, integrity);
    }
  }

  public record Route(List<UUID> nodes, double weightedDistance, boolean reachable) {
    static Route unreachable() {
      return new Route(List.of(), Double.POSITIVE_INFINITY, false);
    }
  }

  private record Step(UUID node, double distance) {}
}
