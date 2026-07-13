package nl.frontier.domain;

import static nl.frontier.domain.Ids.WorldId;

import java.util.Objects;

public final class Position {
  private Position() {}

  public record ChunkPos(WorldId world, int x, int z) {
    public ChunkPos {
      Objects.requireNonNull(world);
    }

    public long packed() {
      return ((long) x << 32) | (z & 0xffffffffL);
    }
  }

  public record BlockPos(WorldId world, int x, int y, int z) {
    public BlockPos {
      Objects.requireNonNull(world);
    }

    public ChunkPos chunk() {
      return new ChunkPos(world, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }
  }
}
