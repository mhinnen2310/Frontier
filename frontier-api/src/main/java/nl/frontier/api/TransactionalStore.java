package nl.frontier.api;

import java.sql.Connection;
import java.sql.SQLException;

public interface TransactionalStore {
  <T> T inTransaction(SqlWork<T> work);

  @FunctionalInterface
  interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
