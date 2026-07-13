package nl.frontier.api;

import static nl.frontier.domain.Position.BlockPos;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface SchedulerFacade {
  void at(BlockPos position, Runnable action);

  void forEntity(UUID entityId, Runnable action, Runnable retired);

  void global(Runnable action);

  <T> CompletableFuture<T> async(Supplier<T> work);

  default <T> CompletableFuture<T> asyncNamed(String name, Supplier<T> work) {
    return async(work);
  }

  void later(Duration delay, Runnable action);
}
