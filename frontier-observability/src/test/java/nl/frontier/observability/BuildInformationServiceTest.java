package nl.frontier.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class BuildInformationServiceTest {
  @Test
  void reportsBuildRuntimeSchemaAndEveryModuleInStableOrder() {
    var modules = new LinkedHashMap<String, String>();
    modules.put("city", "ACTIVE");
    modules.put("testkit", "BUILD_ONLY");
    var service =
        new BuildInformationService(
            new BuildInformationService.Metadata(
                "1.1.0-RC1", "abc123", "2026-07-13T20:00:00Z", "Paper 26.2 build 60"),
            () -> 31,
            modules);

    var report = service.report();
    assertEquals("Frontier build 1.1.0-RC1", report.getFirst());
    assertTrue(report.contains("gitCommit=abc123"));
    assertTrue(report.contains("databaseSchema=V31"));
    assertTrue(report.contains("module.city=ACTIVE"));
    assertTrue(report.contains("module.testkit=BUILD_ONLY"));
  }
}
