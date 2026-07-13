package nl.frontier.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import nl.frontier.api.SchedulerFacade;
import nl.frontier.domain.Ids.WorldId;
import nl.frontier.domain.Position.BlockPos;
import nl.frontier.economy.InfrastructureGateway;
import nl.frontier.economy.InfrastructureSnapshot;
import nl.frontier.economy.InfrastructureValidationPolicy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/** Captures immutable route cells on the owning region scheduler for every touched chunk. */
final class PaperInfrastructureSurveyor {
  private static final Set<Material> WATER = Set.of(Material.WATER, Material.BUBBLE_COLUMN);
  private final InfrastructureValidationPolicy policy;

  PaperInfrastructureSurveyor(InfrastructureValidationPolicy policy) {
    this.policy = policy;
  }

  void snapshot(
      InfrastructureGateway.Context context,
      SchedulerFacade schedulers,
      Consumer<InfrastructureSnapshot> success,
      Consumer<Throwable> failure) {
    List<PlannedColumn> columns;
    try {
      columns = plan(context);
    } catch (RuntimeException error) {
      failure.accept(error);
      return;
    }
    Map<Chunk, List<PlannedColumn>> chunks = new LinkedHashMap<>();
    for (PlannedColumn column : columns)
      chunks
          .computeIfAbsent(
              new Chunk(Math.floorDiv(column.x, 16), Math.floorDiv(column.z, 16)),
              ignored -> new ArrayList<>())
          .add(column);
    var cells = new ConcurrentLinkedQueue<InfrastructureSnapshot.Cell>();
    Set<InfrastructureSnapshot.Column> voids = ConcurrentHashMap.newKeySet();
    AtomicInteger remaining = new AtomicInteger(chunks.size());
    AtomicBoolean completed = new AtomicBoolean();
    try {
      for (List<PlannedColumn> chunkColumns : chunks.values()) {
        PlannedColumn anchor = chunkColumns.getFirst();
        schedulers.at(
            new BlockPos(new WorldId(context.from().world()), anchor.x, anchor.expectedY, anchor.z),
            () -> {
              try {
                World world = Bukkit.getWorld(context.from().world());
                if (world == null)
                  throw new IllegalStateException("infrastructure world is not loaded");
                for (PlannedColumn column : chunkColumns) capture(world, column, cells, voids);
                if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true))
                  success.accept(
                      new InfrastructureSnapshot(
                          context.from().world(),
                          context.from(),
                          context.to(),
                          List.copyOf(cells),
                          Set.copyOf(voids),
                          columns.size()));
              } catch (RuntimeException error) {
                if (completed.compareAndSet(false, true)) failure.accept(error);
              }
            });
      }
    } catch (RuntimeException error) {
      if (completed.compareAndSet(false, true)) failure.accept(error);
    }
  }

  List<PlannedColumn> plan(InfrastructureGateway.Context context) {
    var from = context.from();
    var to = context.to();
    int deltaX = to.x() - from.x();
    int deltaZ = to.z() - from.z();
    int routeSamples = Math.max(Math.abs(deltaX), Math.abs(deltaZ)) + 1;
    if (routeSamples - 1 > policy.maximumLength())
      throw new IllegalArgumentException(
          "infrastructure survey exceeds " + policy.maximumLength() + " blocks");
    Map<ColumnKey, PlannedColumn> unique = new LinkedHashMap<>();
    for (int index = 0; index < routeSamples; index++) {
      double fraction = routeSamples == 1 ? 0 : (double) index / (routeSamples - 1);
      int centerX = (int) Math.round(from.x() + deltaX * fraction);
      int centerZ = (int) Math.round(from.z() + deltaZ * fraction);
      int expectedY = (int) Math.round(from.y() + (to.y() - from.y()) * fraction);
      for (int x = centerX - policy.corridorRadius(); x <= centerX + policy.corridorRadius(); x++) {
        for (int z = centerZ - policy.corridorRadius();
            z <= centerZ + policy.corridorRadius();
            z++) {
          ColumnKey key = new ColumnKey(x, z);
          unique.putIfAbsent(key, new PlannedColumn(x, expectedY, z));
          if (unique.size() > policy.maximumSnapshotColumns())
            throw new IllegalArgumentException("infrastructure snapshot exceeds configured bound");
        }
      }
    }
    return List.copyOf(unique.values());
  }

  private void capture(
      World world,
      PlannedColumn column,
      ConcurrentLinkedQueue<InfrastructureSnapshot.Cell> cells,
      Set<InfrastructureSnapshot.Column> voids) {
    int surfaceY = Integer.MIN_VALUE;
    int quality = 0;
    for (int offset = 0; offset <= policy.verticalTolerance(); offset++) {
      int above = column.expectedY + offset;
      int aboveQuality =
          policy.quality(world.getBlockAt(column.x, above, column.z).getType().name());
      if (aboveQuality > 0) {
        surfaceY = above;
        quality = aboveQuality;
        break;
      }
      if (offset == 0) continue;
      int below = column.expectedY - offset;
      int belowQuality =
          policy.quality(world.getBlockAt(column.x, below, column.z).getType().name());
      if (belowQuality > 0) {
        surfaceY = below;
        quality = belowQuality;
        break;
      }
    }
    if (surfaceY == Integer.MIN_VALUE) {
      Material below = world.getBlockAt(column.x, column.expectedY - 1, column.z).getType();
      if (below.isAir() || WATER.contains(below))
        voids.add(new InfrastructureSnapshot.Column(column.x, column.z));
      return;
    }
    Material below = world.getBlockAt(column.x, surfaceY - 1, column.z).getType();
    boolean bridge = below.isAir() || WATER.contains(below);
    boolean tunnel = world.getBlockAt(column.x, surfaceY + 2, column.z).getType().isSolid();
    boolean gate = false;
    for (int y = surfaceY; y <= surfaceY + 2; y++)
      gate |= policy.gate(world.getBlockAt(column.x, y, column.z).getType().name());
    cells.add(
        new InfrastructureSnapshot.Cell(
            column.x, surfaceY, column.z, quality, bridge, tunnel, gate));
  }

  record PlannedColumn(int x, int expectedY, int z) {}

  private record ColumnKey(int x, int z) {}

  private record Chunk(int x, int z) {}
}
