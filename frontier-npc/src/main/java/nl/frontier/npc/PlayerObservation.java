package nl.frontier.npc;

import java.util.UUID;

/**
 * Immutable player position captured on the Paper scheduler before asynchronous projection work.
 */
public record PlayerObservation(UUID player, UUID world, int x, int y, int z) {}
