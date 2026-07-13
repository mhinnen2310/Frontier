package nl.frontier.bootstrap;

import static nl.frontier.domain.Position.BlockPos;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import nl.frontier.api.SchedulerFacade;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class PaperSchedulerFacade implements SchedulerFacade, AutoCloseable {
  private final Plugin plugin;
  private final Server server;
  private final ThreadPoolExecutor executor;
  private final AtomicLong asyncCompleted = new AtomicLong();
  private final AtomicLong asyncExecutionNanos = new AtomicLong();
  private final AtomicLong asyncQueueNanos = new AtomicLong();
  private final AtomicLong maxAsyncExecutionNanos = new AtomicLong();
  private final AtomicLong maxAsyncQueueNanos = new AtomicLong();
  private final AtomicLong regionCompleted = new AtomicLong();
  private final AtomicLong regionExecutionNanos = new AtomicLong();
  private final AtomicLong maxRegionExecutionNanos = new AtomicLong();
  private final Map<String, NamedCounter> named = new ConcurrentHashMap<>();

  public PaperSchedulerFacade(Plugin plugin, int asyncThreads) {
    this.plugin = Objects.requireNonNull(plugin);
    server = plugin.getServer();
    executor =
        new ThreadPoolExecutor(
            asyncThreads,
            asyncThreads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            Thread.ofPlatform().name("frontier-async-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Override
  public void at(BlockPos position, Runnable action) {
    World world = server.getWorld(position.world().value());
    if (world == null) throw new IllegalStateException("world is not loaded");
    server
        .getRegionScheduler()
        .execute(
            plugin,
            new Location(world, position.x(), position.y(), position.z()),
            measuredRegion(action));
  }

  @Override
  public void forEntity(UUID entityId, Runnable action, Runnable retired) {
    Entity entity = server.getEntity(entityId);
    if (entity == null) {
      retired.run();
      return;
    }
    entity.getScheduler().execute(plugin, measuredRegion(action), retired, 1L);
  }

  @Override
  public void global(Runnable action) {
    server.getGlobalRegionScheduler().execute(plugin, measuredRegion(action));
  }

  @Override
  public <T> CompletableFuture<T> async(Supplier<T> work) {
    long queuedAt = System.nanoTime();
    return CompletableFuture.supplyAsync(
        () -> {
          long started = System.nanoTime();
          long queued = started - queuedAt;
          asyncQueueNanos.addAndGet(queued);
          maxAsyncQueueNanos.accumulateAndGet(queued, Math::max);
          try {
            return work.get();
          } finally {
            long elapsed = System.nanoTime() - started;
            asyncExecutionNanos.addAndGet(elapsed);
            maxAsyncExecutionNanos.accumulateAndGet(elapsed, Math::max);
            asyncCompleted.incrementAndGet();
          }
        },
        executor);
  }

  @Override
  public <T> CompletableFuture<T> asyncNamed(String name, Supplier<T> work) {
    Objects.requireNonNull(name);
    return async(
        () -> {
          long started = System.nanoTime();
          try {
            return work.get();
          } finally {
            long elapsed = System.nanoTime() - started;
            NamedCounter counter = named.computeIfAbsent(name, ignored -> new NamedCounter());
            counter.total.addAndGet(elapsed);
            counter.maximum.accumulateAndGet(elapsed, Math::max);
            counter.count.incrementAndGet();
          }
        });
  }

  @Override
  public void later(Duration delay, Runnable action) {
    long ticks = Math.max(1, Math.divideExact(Math.addExact(delay.toMillis(), 49), 50));
    server
        .getGlobalRegionScheduler()
        .runDelayed(plugin, ignored -> measuredRegion(action).run(), ticks);
  }

  public SchedulerStats stats() {
    long async = asyncCompleted.get();
    long region = regionCompleted.get();
    return new SchedulerStats(
        executor.getPoolSize(),
        executor.getActiveCount(),
        executor.getQueue().size(),
        executor.getCompletedTaskCount(),
        async == 0 ? 0 : asyncExecutionNanos.get() / async,
        maxAsyncExecutionNanos.get(),
        async == 0 ? 0 : asyncQueueNanos.get() / async,
        maxAsyncQueueNanos.get(),
        region,
        region == 0 ? 0 : regionExecutionNanos.get() / region,
        maxRegionExecutionNanos.get());
  }

  public Map<String, TaskStats> namedStats() {
    java.util.TreeMap<String, TaskStats> values = new java.util.TreeMap<>();
    named.forEach(
        (name, counter) -> {
          long count = counter.count.get();
          values.put(
              name,
              new TaskStats(
                  count, count == 0 ? 0 : counter.total.get() / count, counter.maximum.get()));
        });
    return Map.copyOf(values);
  }

  private Runnable measuredRegion(Runnable action) {
    return () -> {
      long started = System.nanoTime();
      try {
        action.run();
      } finally {
        long elapsed = System.nanoTime() - started;
        regionExecutionNanos.addAndGet(elapsed);
        maxRegionExecutionNanos.accumulateAndGet(elapsed, Math::max);
        regionCompleted.incrementAndGet();
      }
    };
  }

  public record SchedulerStats(
      int poolSize,
      int activeAsync,
      int queuedAsync,
      long executorCompleted,
      long averageAsyncNanos,
      long maximumAsyncNanos,
      long averageQueueNanos,
      long maximumQueueNanos,
      long regionTasks,
      long averageRegionNanos,
      long maximumRegionNanos) {}

  public record TaskStats(long count, long averageNanos, long maximumNanos) {}

  private static final class NamedCounter {
    final AtomicLong count = new AtomicLong();
    final AtomicLong total = new AtomicLong();
    final AtomicLong maximum = new AtomicLong();
  }

  @Override
  public void close() {
    executor.close();
  }
}
