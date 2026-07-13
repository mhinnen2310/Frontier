package nl.frontier.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure bounded route analysis. It never accesses a live Minecraft world. */
public final class InfrastructurePathAnalyzer {
  private static final int[][] NEIGHBORS = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};
  private final InfrastructureValidationPolicy policy;

  public InfrastructurePathAnalyzer(InfrastructureValidationPolicy policy) {
    this.policy = policy;
  }

  public InfrastructureSurvey analyze(InfrastructureSnapshot snapshot) {
    if (snapshot.plannedColumns() > policy.maximumSnapshotColumns())
      throw new IllegalArgumentException("infrastructure snapshot exceeds configured bound");
    Map<Key, InfrastructureSnapshot.Cell> cells = new HashMap<>();
    for (var cell : snapshot.cells()) cells.put(new Key(cell.x(), cell.z()), cell);
    List<InfrastructureSnapshot.Cell> starts = near(cells.values(), snapshot.from());
    Set<Key> goals = new HashSet<>();
    for (var cell : near(cells.values(), snapshot.to())) goals.add(new Key(cell.x(), cell.z()));
    List<InfrastructureSnapshot.Cell> path = shortest(cells, starts, goals);
    if (path.isEmpty()) return disconnected(snapshot, cells);

    int minimumWidth = Integer.MAX_VALUE;
    int quality = 0;
    int bridge = 0;
    int tunnel = 0;
    int gate = 0;
    double maximumSlope = 0;
    for (int index = 0; index < path.size(); index++) {
      var cell = path.get(index);
      quality += cell.quality();
      if (cell.bridge()) bridge++;
      if (cell.tunnel()) tunnel++;
      if (cell.gate()) gate++;
      minimumWidth = Math.min(minimumWidth, width(path, index, cells));
      if (index > 0) {
        var previous = path.get(index - 1);
        double horizontal = Math.hypot(cell.x() - previous.x(), cell.z() - previous.z());
        maximumSlope = Math.max(maximumSlope, Math.abs(cell.y() - previous.y()) / horizontal);
      }
    }
    Bounds bounds = bounds(path);
    return new InfrastructureSurvey(
        path.size(),
        path.size(),
        minimumWidth == Integer.MAX_VALUE ? 0 : minimumWidth,
        bridge,
        tunnel,
        quality / path.size(),
        maximumSlope,
        0,
        0,
        true,
        gate,
        bounds.minX,
        bounds.minY,
        bounds.minZ,
        bounds.maxX,
        bounds.maxY,
        bounds.maxZ,
        java.util.stream.IntStream.range(0, path.size())
            .mapToObj(
                index -> {
                  var cell = path.get(index);
                  return new InfrastructureSurvey.RoutePoint(
                      index,
                      cell.x(),
                      cell.y(),
                      cell.z(),
                      "minecraft:" + cell.material().toLowerCase(java.util.Locale.ROOT));
                })
            .toList());
  }

  private List<InfrastructureSnapshot.Cell> shortest(
      Map<Key, InfrastructureSnapshot.Cell> cells,
      List<InfrastructureSnapshot.Cell> starts,
      Set<Key> goals) {
    if (starts.isEmpty() || goals.isEmpty()) return List.of();
    ArrayDeque<Key> queue = new ArrayDeque<>();
    Map<Key, Key> previous = new HashMap<>();
    Set<Key> visited = new HashSet<>();
    starts.stream()
        .sorted(
            Comparator.comparingInt(InfrastructureSnapshot.Cell::x)
                .thenComparingInt(InfrastructureSnapshot.Cell::z))
        .forEach(
            cell -> {
              Key key = new Key(cell.x(), cell.z());
              if (visited.add(key)) queue.add(key);
            });
    Key reached = null;
    while (!queue.isEmpty() && visited.size() <= policy.maximumSnapshotColumns()) {
      Key current = queue.removeFirst();
      if (goals.contains(current)) {
        reached = current;
        break;
      }
      var currentCell = cells.get(current);
      for (int[] offset : NEIGHBORS) {
        Key next = new Key(current.x + offset[0], current.z + offset[1]);
        var nextCell = cells.get(next);
        if (nextCell == null
            || Math.abs(nextCell.y() - currentCell.y()) > Math.ceil(policy.maximumSlope())
            || !visited.add(next)) continue;
        previous.put(next, current);
        queue.addLast(next);
      }
    }
    if (reached == null) return List.of();
    ArrayList<InfrastructureSnapshot.Cell> reversed = new ArrayList<>();
    for (Key cursor = reached; cursor != null; cursor = previous.get(cursor))
      reversed.add(cells.get(cursor));
    java.util.Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  private List<InfrastructureSnapshot.Cell> near(
      java.util.Collection<InfrastructureSnapshot.Cell> cells, InfrastructureGateway.Point point) {
    List<InfrastructureSnapshot.Cell> exact =
        cells.stream()
            .filter(
                cell ->
                    cell.x() == point.x()
                        && cell.z() == point.z()
                        && Math.abs(cell.y() - point.y()) <= policy.verticalTolerance())
            .toList();
    if (!exact.isEmpty()) return exact;
    return cells.stream()
        .filter(
            cell ->
                Math.max(Math.abs(cell.x() - point.x()), Math.abs(cell.z() - point.z())) <= 1
                    && Math.abs(cell.y() - point.y()) <= policy.verticalTolerance())
        .toList();
  }

  private InfrastructureSurvey disconnected(
      InfrastructureSnapshot snapshot, Map<Key, InfrastructureSnapshot.Cell> cells) {
    int samples =
        Math.max(
                Math.abs(snapshot.to().x() - snapshot.from().x()),
                Math.abs(snapshot.to().z() - snapshot.from().z()))
            + 1;
    int connected = 0;
    int quality = 0;
    int broken = 0;
    int destroyed = 0;
    for (int index = 0; index < samples; index++) {
      double fraction = samples == 1 ? 0 : (double) index / (samples - 1);
      int x =
          (int)
              Math.round(
                  snapshot.from().x() + (snapshot.to().x() - snapshot.from().x()) * fraction);
      int z =
          (int)
              Math.round(
                  snapshot.from().z() + (snapshot.to().z() - snapshot.from().z()) * fraction);
      var cell = cells.get(new Key(x, z));
      if (cell == null) {
        broken++;
        if (snapshot.voidColumns().contains(new InfrastructureSnapshot.Column(x, z))) destroyed++;
      } else {
        connected++;
        quality += cell.quality();
      }
    }
    return new InfrastructureSurvey(
        samples,
        connected,
        0,
        0,
        0,
        connected == 0 ? 0 : quality / connected,
        0,
        broken,
        destroyed,
        false,
        0,
        Math.min(snapshot.from().x(), snapshot.to().x()),
        Math.min(snapshot.from().y(), snapshot.to().y()),
        Math.min(snapshot.from().z(), snapshot.to().z()),
        Math.max(snapshot.from().x(), snapshot.to().x()),
        Math.max(snapshot.from().y(), snapshot.to().y()),
        Math.max(snapshot.from().z(), snapshot.to().z()));
  }

  private static int width(
      List<InfrastructureSnapshot.Cell> path,
      int index,
      Map<Key, InfrastructureSnapshot.Cell> cells) {
    var current = path.get(index);
    var other = index + 1 < path.size() ? path.get(index + 1) : path.get(Math.max(0, index - 1));
    int dx = Integer.signum(other.x() - current.x());
    int dz = Integer.signum(other.z() - current.z());
    int perpendicularX = -dz;
    int perpendicularZ = dx;
    int width = 1;
    for (int direction : new int[] {-1, 1}) {
      for (int distance = 1; distance <= 16; distance++) {
        var candidate =
            cells.get(
                new Key(
                    current.x() + perpendicularX * direction * distance,
                    current.z() + perpendicularZ * direction * distance));
        if (candidate == null || Math.abs(candidate.y() - current.y()) > 1) break;
        width++;
      }
    }
    return width;
  }

  private static Bounds bounds(List<InfrastructureSnapshot.Cell> path) {
    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    for (var cell : path) {
      minX = Math.min(minX, cell.x());
      minY = Math.min(minY, cell.y());
      minZ = Math.min(minZ, cell.z());
      maxX = Math.max(maxX, cell.x());
      maxY = Math.max(maxY, cell.y());
      maxZ = Math.max(maxZ, cell.z());
    }
    return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private record Key(int x, int z) {}

  private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}
