package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V9__account_friend_links_reciprocal_lookup_index extends BaseJavaMigration {

  private static final String TABLE_HAS_ROWS =
      "SELECT EXISTS (SELECT 1 FROM account_friend_links LIMIT 1)";

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute(indexCreationStatement(tableHasRows(statement)));
    }
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  static String indexCreationStatement(boolean tableHasRows) {
    // Fresh bootstrap has no writes to protect and should not join PostgreSQL's
    // concurrent-index snapshot wait across every service starting in parallel.
    String concurrency = tableHasRows ? " CONCURRENTLY" : "";
    return """
        CREATE INDEX%s idx_account_friend_links_reciprocal_lookup
            ON account_friend_links(tenant_id, friend_account_id, account_id, status)
        """
        .formatted(concurrency);
  }

  private boolean tableHasRows(Statement statement) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery(TABLE_HAS_ROWS)) {
      if (!resultSet.next()) {
        throw new SQLException("Failed to inspect account_friend_links before index creation");
      }
      return resultSet.getBoolean(1);
    }
  }
}
