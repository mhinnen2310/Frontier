package nl.frontier.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import nl.frontier.api.TransactionalStore;

public final class JdbcTransactionalStore implements TransactionalStore {
  private final DataSource dataSource;

  public JdbcTransactionalStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public <T> T inTransaction(SqlWork<T> work) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          T result = work.execute(connection);
          connection.commit();
          return result;
        } catch (SQLException | RuntimeException failure) {
          try {
            connection.rollback();
          } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
          }
          throw failure;
        }
      } catch (SQLException failure) {
        if (attempt < 3 && retryable(failure)) {
          Thread.onSpinWait();
          continue;
        }
        throw new PersistenceException("database transaction failed", failure);
      }
    }
    throw new IllegalStateException("unreachable transaction retry state");
  }

  private static boolean retryable(SQLException failure) {
    for (SQLException current = failure; current != null; current = current.getNextException()) {
      if ("40001".equals(current.getSQLState()) || "40P01".equals(current.getSQLState()))
        return true;
    }
    return false;
  }
}
