package nl.frontier.npc;

import java.util.ArrayList;
import java.util.List;
import nl.frontier.domain.DomainException;

/** Pure settlement ambient-life budget and day/night schedule. */
public record AmbientLifePolicy(
    int maximumTotalPresentations,
    int maximumCitizens,
    int maximumMarketScenes,
    int maximumGuards,
    int maximumRepairScenes) {
  public AmbientLifePolicy {
    if (maximumTotalPresentations < 1 || maximumTotalPresentations > 500)
      throw new DomainException("ambient total presentation budget must be 1-500");
    requireBound(maximumCitizens, 100, "ambient citizens");
    requireBound(maximumMarketScenes, 20, "ambient market scenes");
    requireBound(maximumGuards, 20, "ambient guards");
    requireBound(maximumRepairScenes, 20, "ambient repair scenes");
  }

  public List<SceneSpec> scenes(Inputs input) {
    int available = Math.max(0, maximumTotalPresentations - input.workerPresentations());
    List<SceneSpec> values = new ArrayList<>();
    List<String> shortages = shortages(input);
    if (!shortages.isEmpty())
      add(values, available, SceneType.SHORTAGE, 1, "Shortage • " + String.join(", ", shortages));
    if (input.activeEvent() != null)
      add(
          values,
          available,
          SceneType.TOWN_HALL_EVENT,
          1,
          "Town Hall • " + title(input.activeEvent()));
    int repairs =
        input.hasBuilderGuild() && input.activeRepairs() > 0
            ? Math.min(maximumRepairScenes, 1 + input.activeRepairs() / 5)
            : 0;
    add(values, available, SceneType.REPAIR, repairs, "Builders • Repair activity");
    int guards = input.hasBarracks() ? Math.min(maximumGuards, input.daytime() ? 1 : 2) : 0;
    add(
        values,
        available,
        SceneType.GUARD,
        guards,
        input.daytime() ? "Guard patrol" : "Night watch");
    int markets =
        input.daytime() && input.hasMarket() && input.openMarketOrders() > 0
            ? Math.min(maximumMarketScenes, 1 + input.openMarketOrders() / 10)
            : 0;
    add(values, available, SceneType.MARKET, markets, "Market • Trading");
    int citizens =
        input.population() == 0
            ? 0
            : Math.min(maximumCitizens, input.daytime() ? Math.max(1, input.population() / 10) : 1);
    add(
        values,
        available,
        SceneType.CITIZEN,
        citizens,
        input.daytime() ? "Frontier citizen" : "Resident • Heading home");
    return List.copyOf(values);
  }

  public String announcement(String settlementName, Inputs input) {
    List<String> parts = new ArrayList<>();
    if (input.activeEvent() != null) parts.add("Town Hall event: " + title(input.activeEvent()));
    List<String> shortages = shortages(input);
    if (!shortages.isEmpty()) parts.add("shortage: " + String.join(", ", shortages));
    if (input.populationTrend() != 0)
      parts.add("population " + (input.populationTrend() > 0 ? "+" : "") + input.populationTrend());
    return parts.isEmpty() ? null : settlementName + " • " + String.join(" • ", parts);
  }

  private static List<String> shortages(Inputs input) {
    List<String> values = new ArrayList<>();
    if (input.foodSecurity() < 30) values.add("food");
    if (input.housingCapacity() < input.population()) values.add("housing");
    if (input.employment() < 30) values.add("jobs");
    return values;
  }

  private static void add(
      List<SceneSpec> values, int budget, SceneType type, int requested, String label) {
    int count = Math.min(requested, Math.max(0, budget - values.size()));
    for (int slot = 0; slot < count; slot++) values.add(new SceneSpec(type, slot, label));
  }

  private static String title(String value) {
    String normalized = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
  }

  private static void requireBound(int value, int maximum, String name) {
    if (value < 0 || value > maximum) throw new DomainException(name + " must be 0-" + maximum);
  }

  public enum SceneType {
    CITIZEN,
    GUARD,
    MARKET,
    REPAIR,
    TOWN_HALL_EVENT,
    SHORTAGE
  }

  public record SceneSpec(SceneType type, int slot, String label) {}

  public record Inputs(
      int population,
      int housingCapacity,
      int foodSecurity,
      int employment,
      int populationTrend,
      boolean daytime,
      int openMarketOrders,
      int activeRepairs,
      String activeEvent,
      boolean hasMarket,
      boolean hasBarracks,
      boolean hasBuilderGuild,
      int workerPresentations) {
    public Inputs {
      if (population < 0
          || housingCapacity < 0
          || openMarketOrders < 0
          || activeRepairs < 0
          || workerPresentations < 0) throw new DomainException("negative ambient input");
      requirePercent(foodSecurity, "ambient food security");
      requirePercent(employment, "ambient employment");
    }

    private static void requirePercent(int value, String name) {
      if (value < 0 || value > 100) throw new DomainException(name + " must be 0-100");
    }
  }
}
