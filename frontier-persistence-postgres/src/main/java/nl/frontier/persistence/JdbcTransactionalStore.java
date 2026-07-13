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
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (SQLException failure) {
      throw new PersistenceException("database transaction failed", failure);
    }
  }
}
