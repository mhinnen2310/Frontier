package nl.frontier.repair;

public record BuilderGuildPolicy(
    int levelOffset,
    int maximumTier,
    long capacityUnitsPerTier,
    int teamsPerTier,
    int baseWorkersPerTeam,
    int maximumDeliveryUnits,
    int maximumBoostPerAction,
    int dailyBoostLimit,
    int assistSessionSeconds,
    int maximumAssistTasks) {
  public BuilderGuildPolicy {
    if (levelOffset < 0
        || maximumTier < 1
        || capacityUnitsPerTier < 1
        || teamsPerTier < 1
        || baseWorkersPerTeam < 1
        || maximumDeliveryUnits < 1
        || maximumBoostPerAction < 1
        || dailyBoostLimit < maximumBoostPerAction
        || assistSessionSeconds < 1
        || maximumAssistTasks < 1)
      throw new IllegalArgumentException("invalid Builder Guild policy");
  }

  public static BuilderGuildPolicy defaults() {
    return new BuilderGuildPolicy(2, 3, 10_000, 1, 2, 2_304, 25, 100, 600, 64);
  }
}
