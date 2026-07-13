package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import nl.frontier.api.DialogScreenCatalog;
import nl.frontier.api.FrontierUi;
import org.junit.jupiter.api.Test;

final class SecurityBoundaryTest {
  @Test
  void commandLimiterAlsoProtectsMenuOnlyTraffic() {
    CommandRateLimiter limiter = new CommandRateLimiter(2, Duration.ofSeconds(1));
    UUID player = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertTrue(limiter.allow(player, now));
    assertTrue(limiter.allow(player, now));
    assertFalse(limiter.allow(player, now));
    assertTrue(limiter.allow(player, now.plusSeconds(1)));
  }

  @Test
  void permissionsAndDialogMutationsStayInsideFrontierBoundary() throws IOException {
    String plugin = resource("plugin.yml");
    assertTrue(plugin.contains("frontier.admin:"));
    assertTrue(plugin.contains("frontier.protection.bypass:"));
    assertTrue(plugin.contains("default: op"));
    for (FrontierUi.Screen screen : FrontierUi.Screen.values())
      for (DialogScreenCatalog.Action action : DialogScreenCatalog.actions(screen)) {
        assertTrue(action.command().startsWith("frontier "));
        if (action.mutation()) assertFalse(action.command().startsWith("frontier admin "));
      }
  }

  private static String resource(String name) throws IOException {
    try (var input = SecurityBoundaryTest.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) throw new IOException("missing test resource " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
