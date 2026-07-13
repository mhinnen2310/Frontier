package nl.frontier.world;

import java.util.Optional;

public final class WorldEventPolicy {
  public Optional<EventChoice> select(Conditions value) {
    if (value.roadIntegrity() < 25)
      return choice("DISASTER", WorldEvent.Category.NATURAL, "INFRASTRUCTURE");
    if (value.weather().equals("STORM") && value.weatherSeverity() >= 60)
      return choice("FLOOD", WorldEvent.Category.NATURAL, "BRIDGES");
    if (value.population() >= 100 && value.stability() < 45)
      return choice("PLAGUE", WorldEvent.Category.NATURAL, "HEALTH");
    if (value.stability() < 40)
      return choice("BANDIT_RAID", WorldEvent.Category.MILITARY, "SECURITY");
    if ((value.weather().equals("HEATWAVE") || value.weather().equals("FROST"))
        && value.weatherSeverity() >= 50)
      return choice("HARVEST_FAILURE", WorldEvent.Category.NATURAL, "FOOD");
    if (value.prosperity() >= 70 && value.tradeActivity() > 0)
      return choice("TRADE_FAIR", WorldEvent.Category.ECONOMIC, "TRADE");
    if (value.prosperity() >= 65 && value.stability() >= 60 && value.population() > 0)
      return choice("MIGRATION_WAVE", WorldEvent.Category.SOCIAL, "HOUSING");
    if (value.season() == WorldSimulation.Season.AUTUMN && value.population() > 0)
      return choice("HARVEST_FESTIVAL", WorldEvent.Category.SOCIAL, "PROSPERITY");
    return Optional.empty();
  }

  private static Optional<EventChoice> choice(
      String key, WorldEvent.Category category, String response) {
    return Optional.of(new EventChoice(key, category, response));
  }

  public record Conditions(
      int population,
      double prosperity,
      double stability,
      double tradeActivity,
      double roadIntegrity,
      WorldSimulation.Season season,
      String weather,
      int weatherSeverity) {}

  public record EventChoice(String key, WorldEvent.Category category, String response) {}
}
