package nl.frontier.city;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SettlementDailySimulation {
  private final SettlementSimulationGateway gateway;
  private final UUID worker = UUID.randomUUID();

  public SettlementDailySimulation(SettlementSimulationGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway);
  }

  public CycleReport cycle(int limit, Instant now) {
    List<SettlementSimulationGateway.Snapshot> due =
        gateway.leaseDue(worker, limit, now, now.plus(Duration.ofMinutes(5)));
    int paid = 0;
    for (SettlementSimulationGateway.Snapshot snapshot : due) {
      try {
        Result result = calculate(snapshot);
        UUID key =
            UUID.nameUUIDFromBytes(
                (snapshot.city() + ":daily:" + LocalDate.ofInstant(now, ZoneOffset.UTC))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        gateway.apply(worker, snapshot, result, key, now.plus(Duration.ofDays(1)), now);
        if (result.maintenancePaid()) paid++;
      } finally {
        gateway.release(worker, snapshot.city());
      }
    }
    return new CycleReport(due.size(), paid);
  }

  public Result calculate(SettlementSimulationGateway.Snapshot snapshot) {
    TaxProfile tax = TaxProfile.valueOf(snapshot.taxProfile());
    long taxIncome = Math.multiplyExact(snapshot.population(), tax.centsPerCitizen);
    long baseMaintenance =
        Math.addExact(
            Math.addExact(snapshot.level().level() * 1_000L, snapshot.buildings() * 250L),
            Math.addExact(snapshot.workers() * 100L, snapshot.roadSegments() * 10L));
    long maintenance = baseMaintenance * (100L - snapshot.maintenanceBonus()) / 100L;
    long funds = Math.addExact(snapshot.treasuryMinor(), taxIncome);
    boolean paid = funds >= maintenance;
    long afterMaintenance = paid ? funds - maintenance : funds;
    boolean wagesPaid = afterMaintenance >= snapshot.workerWagesMinor();
    int requiredFood = Math.toIntExact(Math.max(0, (snapshot.population() + 3L) / 4L));
    long availableFoodUnits =
        Math.addExact(snapshot.wheatAvailable(), Math.multiplyExact(snapshot.breadAvailable(), 4));
    boolean foodSatisfied = availableFoodUnits >= requiredFood;
    long breadConsumed = Math.min(snapshot.breadAvailable(), requiredFood / 4L);
    long remainingFood = requiredFood - breadConsumed * 4L;
    long wheatConsumed = Math.min(snapshot.wheatAvailable(), remainingFood);
    remainingFood -= wheatConsumed;
    if (remainingFood > 0 && breadConsumed < snapshot.breadAvailable()) breadConsumed++;
    int prosperityDelta = paid ? tax.prosperityDelta : -5;
    if (!wagesPaid && snapshot.workers() > 0) prosperityDelta -= 3;
    if (!foodSatisfied && snapshot.population() > 0) prosperityDelta -= 5;
    int populationDelta =
        paid && snapshot.prosperity() >= 60
            ? Math.max(1, snapshot.population() / 100)
            : paid ? 0 : -Math.max(1, snapshot.population() / 50);
    if (paid && snapshot.housingBonus() > 0)
      populationDelta += Math.max(1, snapshot.population() / 100) * snapshot.housingBonus() / 20;
    if (!foodSatisfied && snapshot.population() > 0)
      populationDelta -= Math.max(1, snapshot.population() / 100);
    int civilizationDelta = paid ? 0 : -2;
    return new Result(
        taxIncome,
        maintenance,
        paid,
        snapshot.workerWagesMinor(),
        wagesPaid,
        requiredFood,
        wheatConsumed,
        breadConsumed,
        foodSatisfied,
        populationDelta,
        prosperityDelta,
        civilizationDelta);
  }

  public enum TaxProfile {
    LOW(25, 1),
    STANDARD(50, 0),
    HIGH(80, -1);
    private final long centsPerCitizen;
    private final int prosperityDelta;

    TaxProfile(long centsPerCitizen, int prosperityDelta) {
      this.centsPerCitizen = centsPerCitizen;
      this.prosperityDelta = prosperityDelta;
    }
  }

  public record Result(
      long taxIncomeMinor,
      long maintenanceMinor,
      boolean maintenancePaid,
      long workerWagesMinor,
      boolean wagesPaid,
      int requiredFoodUnits,
      long wheatConsumed,
      long breadConsumed,
      boolean foodSatisfied,
      int populationDelta,
      int prosperityDelta,
      int civilizationDelta) {}

  public record CycleReport(int settlements, int maintenancePaid) {}
}
