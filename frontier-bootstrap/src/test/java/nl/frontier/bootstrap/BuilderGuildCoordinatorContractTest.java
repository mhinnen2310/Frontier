package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

class BuilderGuildCoordinatorContractTest {
  @Test
  void controlledRepairListenerDelegatesThroughTransactionalBoundary() throws Exception {
    Method handler =
        BuilderGuildCoordinator.class.getDeclaredMethod("onManualRepair", BlockPlaceEvent.class);
    EventHandler annotation = handler.getAnnotation(EventHandler.class);
    assertNotNull(annotation);
    assertTrue(annotation.ignoreCancelled());
    assertEquals(EventPriority.HIGHEST, annotation.priority());

    Path source =
        repositoryRoot()
            .resolve(
                "frontier-bootstrap/src/main/java/nl/frontier/bootstrap/BuilderGuildCoordinator.java");
    String text = Files.readString(source);
    assertFalse(text.contains("prepareStatement"));
    assertFalse(text.contains("java.sql"));
    assertTrue(text.contains("guilds.completeManual"));
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("settings.gradle.kts")))
      current = current.getParent();
    if (current == null) throw new IllegalStateException("repository root not found");
    return current;
  }
}
