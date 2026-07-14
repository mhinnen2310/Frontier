package nl.frontier.city;

import java.util.ArrayList;
import java.util.List;
import nl.frontier.domain.DomainException;

/** Deterministic population rules. Persistence supplies an immutable factor snapshot per cycle. */
public record PopulationPolicy(
    int maximumDailyGrowth,
    int maximumDailyDecline,
    int settlementGraceDays,
    int foodShortageGraceDays,
    int collapseFloor) {
  public PopulationPolicy {
    if (maximumDailyGrowth < 1 || maximumDailyGrowth > 1_000)
      throw new DomainException("maximum daily population growth must be 1-1000");
    if (maximumDailyDecline < 1 || maximumDailyDecline > 1_000)
      throw new DomainException("maximum daily population decline must be 1-1000");
    if (settlementGraceDays < 0 || settlementGraceDays > 365)
      throw new DomainException("settlement population grace must be 0-365 days");
    if (foodShortageGraceDays < 0 || foodShortageGraceDays > 365)
      throw new DomainException("food shortage grace must be 0-365 days");
    if (collapseFloor < 0 || collapseFloor > 1_000)
      throw new DomainException("population collapse floor must be 0-1000");
  }

  public static PopulationPolicy defaults() {
    return new PopulationPolicy(5, 3, 3, 2, 1);
  }

  public Outcome evaluate(Factors factors) {
    List<String> reasons = reasons(factors);
    int freeHousing = Math.max(0, factors.housingCapacity() - factors.population());
    boolean healthyGrowth =
        factors.foodSecurity() >= 70
            && factors.safety() >= 50
            && factors.prosperity() >= 50
            && factors.employment() >= 50;
    int requestedBirths =
        factors.population() > 1 && freeHousing > 0 && healthyGrowth
            ? Math.max(1, factors.population() / 200)
            : 0;
    int requestedImmigration =
        freeHousing > requestedBirths
                && factors.foodSecurity() >= 50
                && factors.safety() >= 50
                && factors.prosperity() >= 55
                && factors.employment() >= 50
            ? Math.max(1, factors.prosperity() / 25)
            : 0;
    int growth =
        Math.min(Math.min(freeHousing, maximumDailyGrowth), requestedBirths + requestedImmigration);
    int births = Math.min(requestedBirths, growth);
    int immigration = growth - births;

    int requestedDeaths;
    if (factors.settlementGraceActive()) requestedDeaths = 0;
    else if (!factors.foodGraceActive() && factors.foodSecurity() < 25 && factors.population() > 0)
      requestedDeaths = Math.max(1, factors.population() / 100);
    else requestedDeaths = factors.population() / 1_000;
    boolean pressure =
        (!factors.foodGraceActive() && factors.foodSecurity() < 50)
            || factors.safety() < 40
            || factors.prosperity() < 30
            || factors.employment() < 30;
    int requestedEmigration =
        !factors.settlementGraceActive() && pressure && factors.population() > 0
            ? Math.max(1, factors.population() / 50)
            : 0;
    int availableBeforeFloor =
        Math.max(0, factors.population() + growth - Math.min(collapseFloor, factors.population()));
    int decline =
        Math.min(
            Math.min(maximumDailyDecline, availableBeforeFloor),
            requestedDeaths + requestedEmigration);
    int deaths = Math.min(requestedDeaths, decline);
    int emigration = decline - deaths;
    int population = factors.population() + growth - decline;
    return new Outcome(
        population,
        births,
        deaths,
        immigration,
        emigration,
        growth - decline,
        List.copyOf(reasons));
  }

  private static List<String> reasons(Factors factors) {
    List<String> reasons = new ArrayList<>();
    reasons.add(
        factors.housingCapacity() > factors.population()
            ? "+ Housing available"
            : "- Housing full");
    if (factors.foodSecurity() >= 70) reasons.add("+ Food surplus");
    else if (factors.foodSecurity() < 50)
      reasons.add(factors.foodGraceActive() ? "- Food shortage (grace active)" : "- Food shortage");
    else reasons.add("= Food stable");
    if (factors.employment() >= 60) reasons.add("+ Employment");
    else if (factors.employment() < 30) reasons.add("- Unemployment");
    else reasons.add("= Employment stable");
    if (factors.safety() >= 50) reasons.add("+ Safe settlement");
    else reasons.add("- Border conflict");
    if (factors.prosperity() >= 50) reasons.add("+ Prosperity");
    else reasons.add("- Low prosperity");
    if (factors.settlementGraceActive()) reasons.add("+ New settlement protection");
    return reasons;
  }

  public record Factors(
      int population,
      int housingCapacity,
      int foodSecurity,
      int safety,
      int prosperity,
      int employment,
      boolean settlementGraceActive,
      boolean foodGraceActive) {
    public Factors {
      if (population < 0 || housingCapacity < 0)
        throw new DomainException("negative population factor");
      requirePercent(foodSecurity, "food security");
      requirePercent(safety, "safety");
      requirePercent(prosperity, "prosperity");
      requirePercent(employment, "employment");
    }

    private static void requirePercent(int value, String name) {
      if (value < 0 || value > 100) throw new DomainException(name + " must be 0-100");
    }
  }

  public record Outcome(
      int population,
      int births,
      int deaths,
      int immigration,
      int emigration,
      int trend,
      List<String> reasons) {}
}
