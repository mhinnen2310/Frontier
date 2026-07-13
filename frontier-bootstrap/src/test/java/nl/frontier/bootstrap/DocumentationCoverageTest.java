package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import nl.frontier.api.FrontierUi;
import org.junit.jupiter.api.Test;

class DocumentationCoverageTest {
  private static final List<String> REQUIRED =
      List.of(
          "README.md",
          "ARCHITECTURE.md",
          "GAMEPLAY.md",
          "COMMANDS.md",
          "DIALOGS.md",
          "ADMIN.md",
          "PERMISSIONS.md",
          "DEVELOPER_API.md",
          "DATABASE.md",
          "CONFIGURATION.md",
          "UPGRADE.md");

  @Test
  void maintainedManualCoversScreensPermissionsConfigurationAndSchema() throws IOException {
    Path root = repositoryRoot();
    Path docs = root.resolve("docs");
    assertTrue(Files.isRegularFile(root.resolve("IMPLEMENTATION_STATUS.md")));
    String index = Files.readString(docs.resolve("README.md"));
    for (String required : REQUIRED) {
      assertTrue(Files.isRegularFile(docs.resolve(required)), "missing docs/" + required);
      if (!required.equals("README.md")) {
        assertTrue(index.contains(required), required);
      }
    }

    String dialogs = Files.readString(docs.resolve("DIALOGS.md"));
    for (FrontierUi.Screen screen : FrontierUi.Screen.values()) {
      assertTrue(dialogs.contains("`" + screen.name().toLowerCase() + "`"), screen.name());
    }

    String permissions = Files.readString(docs.resolve("PERMISSIONS.md"));
    for (String node :
        List.of("frontier.admin", "frontier.city.create", "frontier.protection.bypass")) {
      assertTrue(permissions.contains(node), node);
    }

    String configuration = Files.readString(docs.resolve("CONFIGURATION.md"));
    for (String module : ConfigRegistry.MODULES)
      assertTrue(configuration.contains("`" + module + ".yml`"), module);
    assertTrue(configuration.contains("config-version"));
    assertTrue(configuration.contains("config validate"));
    assertTrue(configuration.contains("redacted"));

    String database = Files.readString(docs.resolve("DATABASE.md"));
    assertTrue(database.contains("V1–V3"));
    assertTrue(database.contains("V30–V32"));
    assertTrue(database.contains("flyway_schema_history"));
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("repository root not found");
    }
    return current;
  }
}
