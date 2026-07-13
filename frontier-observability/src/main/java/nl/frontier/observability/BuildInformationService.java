package nl.frontier.observability;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.IntSupplier;

public final class BuildInformationService {
  private final Metadata metadata;
  private final IntSupplier schemaVersion;
  private final Map<String, String> modules;

  public BuildInformationService(
      Metadata metadata, IntSupplier schemaVersion, Map<String, String> modules) {
    this.metadata = Objects.requireNonNull(metadata);
    this.schemaVersion = Objects.requireNonNull(schemaVersion);
    this.modules = Map.copyOf(new LinkedHashMap<>(modules));
  }

  public List<String> report() {
    List<String> rows = new ArrayList<>();
    rows.add("Frontier build " + metadata.version());
    rows.add("gitCommit=" + metadata.gitCommit());
    rows.add("buildTime=" + metadata.buildTime());
    rows.add("java=" + Runtime.version());
    rows.add("paperTarget=" + metadata.paperTarget());
    rows.add("databaseSchema=V" + schemaVersion.getAsInt());
    modules.forEach((module, status) -> rows.add("module." + module + "=" + status));
    return List.copyOf(rows);
  }

  public static Metadata load(ClassLoader loader) {
    Properties properties = new Properties();
    try (InputStream input = loader.getResourceAsStream("frontier-build.properties")) {
      if (input == null) throw new IllegalStateException("frontier-build.properties is missing");
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("could not read Frontier build metadata", failure);
    }
    return new Metadata(
        required(properties, "version"),
        required(properties, "git-commit"),
        required(properties, "build-time"),
        required(properties, "paper-target"));
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank() || value.startsWith("${")) {
      throw new IllegalStateException("invalid build metadata: " + key);
    }
    return value;
  }

  public record Metadata(String version, String gitCommit, String buildTime, String paperTarget) {
    public Metadata {
      Objects.requireNonNull(version);
      Objects.requireNonNull(gitCommit);
      Objects.requireNonNull(buildTime);
      Objects.requireNonNull(paperTarget);
    }
  }
}
