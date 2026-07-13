package nl.frontier.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Finds graph bridges and combines structural importance with live shipment usage. */
public final class CriticalPathAnalyzer {
  public Map<UUID, Integer> score(List<InfrastructureGateway.NetworkEdge> edges) {
    Map<UUID, List<Link>> graph = new HashMap<>();
    for (var edge : edges) {
      if (!edge.operational()) continue;
      graph
          .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
          .add(new Link(edge.id(), edge.to()));
      graph
          .computeIfAbsent(edge.to(), ignored -> new ArrayList<>())
          .add(new Link(edge.id(), edge.from()));
    }
    Set<UUID> bridges = new HashSet<>();
    Map<UUID, Integer> entered = new HashMap<>();
    Map<UUID, Integer> low = new HashMap<>();
    int[] time = {0};
    for (UUID node : graph.keySet())
      if (!entered.containsKey(node)) visit(node, null, graph, entered, low, bridges, time);

    Map<UUID, Integer> scores = new HashMap<>();
    for (var edge : edges) {
      int score = edge.importance() / 5 + Math.min(30, edge.activeShipments() * 10);
      if (bridges.contains(edge.id())) score += 50;
      scores.put(edge.id(), Math.min(100, score));
    }
    return Map.copyOf(scores);
  }

  private static void visit(
      UUID node,
      UUID parentEdge,
      Map<UUID, List<Link>> graph,
      Map<UUID, Integer> entered,
      Map<UUID, Integer> low,
      Set<UUID> bridges,
      int[] time) {
    int order = ++time[0];
    entered.put(node, order);
    low.put(node, order);
    for (Link link : graph.getOrDefault(node, List.of())) {
      if (link.edge.equals(parentEdge)) continue;
      Integer seen = entered.get(link.other);
      if (seen != null) {
        low.put(node, Math.min(low.get(node), seen));
        continue;
      }
      visit(link.other, link.edge, graph, entered, low, bridges, time);
      low.put(node, Math.min(low.get(node), low.get(link.other)));
      if (low.get(link.other) > entered.get(node)) bridges.add(link.edge);
    }
  }

  private record Link(UUID edge, UUID other) {}
}
