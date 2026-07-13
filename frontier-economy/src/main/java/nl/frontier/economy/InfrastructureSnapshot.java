package nl.frontier.economy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable world data captured on Paper region schedulers and safe for asynchronous analysis. */
public record InfrastructureSnapshot(
    UUID world,
    InfrastructureGateway.Point from,
    InfrastructureGateway.Point to,
    List<Cell> cells,
    Set<Column> voidColumns,
    int plannedColumns) {
  public InfrastructureSnapshot {
    cells = List.copyOf(cells);
    voidColumns = Set.copyOf(voidColumns);
    if (plannedColumns < 1) throw new IllegalArgumentException("plannedColumns must be positive");
  }

  public record Cell(
      int x, int y, int z, int quality, boolean bridge, boolean tunnel, boolean gate) {
    public Cell {
      if (quality < 1 || quality > 100)
        throw new IllegalArgumentException("cell quality must be 1-100");
    }
  }

  public record Column(int x, int z) {}
}
