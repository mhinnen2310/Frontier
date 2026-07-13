package nl.frontier.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HarborGateway {
  HarborSnapshot bootstrap(UUID world, int chunkX, int chunkZ, Instant now);

  TutorialSnapshot onboard(UUID player, Instant now);

  List<StarterJob> jobs(UUID player, Instant now);

  JobReceipt completeJob(UUID player, UUID job, Instant now);

  HarborSnapshot refresh(Instant now);

  record HarborSnapshot(
      UUID city,
      String name,
      long budgetRemainingMinor,
      Instant budgetResetsAt,
      int openJobs,
      int openBuyOrders,
      int openSellOrders) {}

  record TutorialSnapshot(UUID player, String stage, boolean firstVisit, long balanceMinor) {}

  record StarterJob(
      UUID id,
      String jobType,
      String description,
      long rewardMinor,
      String status,
      Instant expiresAt) {}

  record JobReceipt(
      UUID job, long rewardMinor, long playerBalanceMinor, long harborBudgetRemainingMinor) {}
}
