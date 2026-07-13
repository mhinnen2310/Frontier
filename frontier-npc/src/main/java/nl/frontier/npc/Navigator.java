package nl.frontier.npc;

import static nl.frontier.domain.Position.BlockPos;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Version-sensitive navigation adapter; release default uses bounded waypoints. */
public interface Navigator {
  CompletableFuture<Result> navigate(UUID presentationEntity, BlockPos destination);

  enum Result {
    ARRIVED,
    BLOCKED,
    RETIRED,
    CANCELLED
  }
}
