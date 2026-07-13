package nl.frontier.warfare;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface WarDamageGateway {
  Decision authorizeAndJournal(
      DamageAttempt attempt, Duration breachWindow, int baseCapacity, int maximumCapacity);

  void confirmApplied(UUID damage, String actualData, Instant now);

  void reject(UUID damage, String reason, Instant now);

  java.util.List<PendingMutation> pendingMutations(int maximum);

  record DamageAttempt(
      UUID campaign,
      UUID attacker,
      UUID defendingCity,
      UUID world,
      int x,
      int y,
      int z,
      String originalData,
      String damagedData,
      String cause,
      int baseCost,
      int activeDefenders,
      Instant now) {}

  record Decision(
      boolean allowed,
      String reason,
      int chargedPoints,
      int remainingPoints,
      UUID damage,
      boolean mutationRequired) {}

  record PendingMutation(
      UUID damage, UUID world, int x, int y, int z, String originalData, String damagedData) {}
}
