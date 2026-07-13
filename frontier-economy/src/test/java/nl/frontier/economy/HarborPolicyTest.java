package nl.frontier.economy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import nl.frontier.domain.DomainException;
import org.junit.jupiter.api.Test;

class HarborPolicyTest {
  @Test
  void rejectsHighTierCommoditiesRewardInflationAndArbitrage() {
    assertThrows(
        DomainException.class, () -> policy(Set.of("minecraft:diamond"), 1_000, 2_000, 10, 20));
    assertThrows(
        DomainException.class, () -> policy(Set.of("minecraft:oak_log"), 1_500, 2_000, 10, 20));
    assertThrows(
        DomainException.class, () -> policy(Set.of("minecraft:oak_log"), 1_000, 500, 20, 20));
  }

  private static HarborPolicy policy(
      Set<String> commodities, long dailySource, long playerCap, long buyPrice, long sellPrice) {
    return new HarborPolicy(
        1_000,
        dailySource,
        playerCap,
        10_000,
        commodities,
        Map.of(commodities.iterator().next(), 10L),
        List.of(new HarborPolicy.StarterJobDefinition("JOB", "Starter work", 600)),
        List.of(new HarborPolicy.MarketOffer(commodities.iterator().next(), 2, buyPrice)),
        List.of(new HarborPolicy.MarketOffer(commodities.iterator().next(), 2, sellPrice)));
  }
}
