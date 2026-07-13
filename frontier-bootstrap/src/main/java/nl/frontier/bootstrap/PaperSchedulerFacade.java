package nl.frontier.bootstrap;

import static nl.frontier.domain.Position.BlockPos;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private final ExecutorService executor;

  public PaperSchedulerFacade(Plugin plugin, int asyncThreads) {
    this.plugin = Objects.requireNonNull(plugin);
    server = plugin.getServer();
    executor =
        Executors.newFixedThreadPool(
            asyncThreads, Thread.ofPlatform().name("frontier-async-", 0).factory());
  }

  @Override
  public void at(BlockPos position, Runnable action) {
    World world = server.getWorld(position.world().value());
    if (world == null) throw new IllegalStateException("world is not loaded");
    server
        .getRegionScheduler()
        .execute(plugin, new Location(world, position.x(), position.y(), position.z()), action);
  }

  @Override
  public void forEntity(UUID entityId, Runnable action, Runnable retired) {
    Entity entity = server.getEntity(entityId);
    if (entity == null) {
      retired.run();
      return;
    }
    entity.getScheduler().execute(plugin, action, retired, 1L);
  }

  @Override
  public void global(Runnable action) {
    server.getGlobalRegionScheduler().execute(plugin, action);
  }

  @Override
  public <T> CompletableFuture<T> async(Supplier<T> work) {
    return CompletableFuture.supplyAsync(work, executor);
  }

  @Override
  public void later(Duration delay, Runnable action) {
    long ticks = Math.max(1, Math.divideExact(Math.addExact(delay.toMillis(), 49), 50));
    server.getGlobalRegionScheduler().runDelayed(plugin, ignored -> action.run(), ticks);
  }

  @Override
  public void close() {
    executor.close();
  }
}
