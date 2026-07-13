package nl.frontier.world;

import static nl.frontier.domain.Ids.SettlementId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Aggregated dirty-object simulation. It never scans chunks or materializes one NPC per citizen.
 */
public final class WorldSimulation {
  private final Queue<SettlementId> dirty = new ConcurrentLinkedQueue<>();

  public void markDirty(SettlementId settlement) {
    dirty.add(settlement);
  }

  public List<Result> cycle(
      Map<SettlementId, Snapshot> snapshots, Season season, int maximumPerCycle) {
    if (maximumPerCycle <= 0) throw new IllegalArgumentException("cycle limit must be positive");
    Map<SettlementId, Result> results = new HashMap<>();
    for (int index = 0; index < maximumPerCycle; index++) {
      SettlementId id = dirty.poll();
      if (id == null) break;
      Snapshot value = snapshots.get(id);
      if (value == null) continue;
      int shortage = Math.max(0, value.population() - value.foodUnits() * 4);
      int prosperityDelta = value.treasuryRunwayDays() > 7 ? 1 : -2;
      int populationDelta =
          shortage > 0 ? -Math.max(1, shortage / 25) : value.happiness() >= 60 ? 1 : 0;
      double production =
          switch (season) {
            case SPRING -> 1.10;
            case SUMMER -> 1.05;
            case AUTUMN -> 1.15;
            case WINTER -> 0.70;
          };
      results.put(id, new Result(id, populationDelta, prosperityDelta, production));
    }
    List<Result> ordered = new ArrayList<>(results.values());
    ordered.sort(Comparator.comparing(result -> result.settlement().value()));
    return List.copyOf(ordered);
  }

  public enum Season {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER
  }

  public record Snapshot(int population, int foodUnits, int happiness, int treasuryRunwayDays) {}

  public record Result(
      SettlementId settlement,
      int populationDelta,
      int prosperityDelta,
      double productionModifier) {}
}
