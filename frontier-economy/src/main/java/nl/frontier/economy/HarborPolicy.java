package nl.frontier.economy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import nl.frontier.domain.DomainException;

public record HarborPolicy(
    long dailyBudgetMinor,
    long maximumDailySourceMinor,
    long maximumPlayerRewardPerDayMinor,
    long initialCapitalMinor,
    Set<String> allowedCommodities,
    Map<String, Long> initialStock,
    List<StarterJobDefinition> starterJobs,
    List<MarketOffer> buyOrders,
    List<MarketOffer> sellOrders) {
  private static final Set<String> SAFE_STARTER_COMMODITIES =
      Set.of(
          "minecraft:bread",
          "minecraft:wheat",
          "minecraft:oak_log",
          "minecraft:cobblestone",
          "minecraft:iron_ingot");

  public HarborPolicy {
    if (dailyBudgetMinor <= 0
        || maximumDailySourceMinor <= 0
        || maximumPlayerRewardPerDayMinor <= 0
        || initialCapitalMinor <= 0)
      throw new DomainException("Harbor money caps must be positive");
    if (maximumDailySourceMinor > dailyBudgetMinor)
      throw new DomainException("Harbor daily source cannot exceed its daily budget");
    allowedCommodities = Set.copyOf(allowedCommodities);
    initialStock = Map.copyOf(initialStock);
    starterJobs = List.copyOf(starterJobs);
    buyOrders = List.copyOf(buyOrders);
    sellOrders = List.copyOf(sellOrders);
    if (allowedCommodities.isEmpty() || !SAFE_STARTER_COMMODITIES.containsAll(allowedCommodities))
      throw new DomainException("Harbor contains a non-starter commodity");
    if (starterJobs.isEmpty() || buyOrders.isEmpty() || sellOrders.isEmpty())
      throw new DomainException("Harbor requires jobs and both market sides");
    validateCommodities(allowedCommodities, initialStock.keySet());
    validateCommodities(
        allowedCommodities, buyOrders.stream().map(MarketOffer::commodity).toList());
    validateCommodities(
        allowedCommodities, sellOrders.stream().map(MarketOffer::commodity).toList());
    long rewardTotal = starterJobs.stream().mapToLong(StarterJobDefinition::rewardMinor).sum();
    if (rewardTotal > maximumPlayerRewardPerDayMinor)
      throw new DomainException("Harbor starter jobs exceed the per-player daily cap");
    Map<String, Long> buyPrices =
        buyOrders.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MarketOffer::commodity, MarketOffer::unitPriceMinor, Math::max));
    for (MarketOffer sell : sellOrders) {
      Long buyPrice = buyPrices.get(sell.commodity());
      if (buyPrice != null && buyPrice >= sell.unitPriceMinor())
        throw new DomainException("Harbor market spread permits arbitrage: " + sell.commodity());
    }
  }

  public static HarborPolicy defaults() {
    return new HarborPolicy(
        250_000,
        250_000,
        2_250,
        10_000_000,
        SAFE_STARTER_COMMODITIES,
        Map.of(
            "minecraft:bread", 10_000L,
            "minecraft:wheat", 20_000L,
            "minecraft:oak_log", 10_000L,
            "minecraft:cobblestone", 20_000L,
            "minecraft:iron_ingot", 2_000L),
        List.of(
            new StarterJobDefinition("DOCK_HELP", "Help unload supplies at the docks", 750),
            new StarterJobDefinition(
                "COURIER", "Deliver Harbor notices to the settlement board", 900),
            new StarterJobDefinition("CLEANUP", "Clear storm debris around Frontier Harbor", 600)),
        List.of(
            new MarketOffer("minecraft:cobblestone", 512, 8),
            new MarketOffer("minecraft:wheat", 256, 12),
            new MarketOffer("minecraft:oak_log", 128, 20)),
        List.of(
            new MarketOffer("minecraft:bread", 128, 150),
            new MarketOffer("minecraft:oak_log", 256, 90),
            new MarketOffer("minecraft:iron_ingot", 64, 450)));
  }

  private static void validateCommodities(Set<String> allowed, Iterable<String> commodities) {
    Set<String> unexpected = new HashSet<>();
    commodities.forEach(
        commodity -> {
          if (!allowed.contains(commodity)) unexpected.add(commodity);
        });
    if (!unexpected.isEmpty())
      throw new DomainException("Harbor commodity is not allowed: " + unexpected);
  }

  public record StarterJobDefinition(String type, String description, long rewardMinor) {
    public StarterJobDefinition {
      Objects.requireNonNull(type);
      Objects.requireNonNull(description);
      if (type.isBlank() || description.isBlank() || rewardMinor <= 0)
        throw new DomainException("invalid Harbor starter job");
    }
  }

  public record MarketOffer(String commodity, long quantity, long unitPriceMinor) {
    public MarketOffer {
      Objects.requireNonNull(commodity);
      if (commodity.isBlank() || quantity <= 0 || unitPriceMinor <= 0)
        throw new DomainException("invalid Harbor market offer");
    }
  }
}
