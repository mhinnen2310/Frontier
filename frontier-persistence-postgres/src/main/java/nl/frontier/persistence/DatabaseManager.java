package nl.frontier.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class DatabaseManager implements AutoCloseable {
  private final HikariDataSource dataSource;

  public DatabaseManager(Configuration configuration) {
    Objects.requireNonNull(configuration);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(configuration.jdbcUrl());
    // Paper plugins have isolated classloaders; explicit loading avoids DriverManager's
    // system-classloader discovery path ignoring the shaded JDBC service provider.
    config.setDriverClassName("org.postgresql.Driver");
    config.setUsername(configuration.username());
    config.setPassword(configuration.password());
    config.setMaximumPoolSize(configuration.maximumPoolSize());
    config.setMinimumIdle(Math.min(2, configuration.maximumPoolSize()));
    config.setConnectionTimeout(configuration.connectionTimeout().toMillis());
    config.setPoolName("Frontier-Postgres");
    config.setAutoCommit(true);
    dataSource = new HikariDataSource(config);
  }

  public void migrate() {
    migrate("classpath:db/migration");
  }

  public void migrate(String location) {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(DatabaseManager.class.getClassLoader());
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .locations(location)
          .sqlMigrationPrefix("V")
          .sqlMigrationSeparator("__")
          .sqlMigrationSuffixes(".sql")
          .encoding("UTF-8")
          .validateMigrationNaming(true)
          .load()
          .migrate();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public PoolStats poolStats() {
    var pool = dataSource.getHikariPoolMXBean();
    return new PoolStats(
        pool.getActiveConnections(),
        pool.getIdleConnections(),
        pool.getTotalConnections(),
        pool.getThreadsAwaitingConnection());
  }

  @Override
  public void close() {
    dataSource.close();
  }

  public record Configuration(
      String jdbcUrl,
      String username,
      String password,
      int maximumPoolSize,
      Duration connectionTimeout) {
    public Configuration {
      if (jdbcUrl.isBlank() || username.isBlank() || maximumPoolSize < 2)
        throw new IllegalArgumentException("invalid database configuration");
    }
  }

  public record PoolStats(int active, int idle, int total, int awaiting) {}
}
