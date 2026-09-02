package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Builds the runtime-target admission uniqueness index without a table-wide write lock. */
public final class V9__unique_gameplay_admission_pointer_runtime_target extends BaseJavaMigration {

  static final String INDEX_NAME = "uq_gameplay_admission_pointer_runtime_target";
  private static final String DUPLICATE_PREFLIGHT =
      "SELECT tenant_id, game_instance_id FROM gameplay_admission_pointer "
          + "GROUP BY tenant_id, game_instance_id HAVING COUNT(*) > 1 LIMIT 1";
  private static final String INSPECT_INDEX =
      "SELECT i.indisvalid, i.indisunique, t.relname = 'gameplay_admission_pointer' "
          + "AS expected_table, i.indpred IS NULL AS unfiltered, i.indexprs IS NULL "
          + "AS no_expressions, i.indnkeyatts = 2 AS expected_key_count, "
          + "i.indnatts = 2 AS expected_total_count, "
          + "(SELECT array_agg(a.attname ORDER BY k.ordinality) "
          + "FROM unnest(i.indkey::smallint[]) WITH ORDINALITY AS k(attnum, ordinality) "
          + "JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) "
          + "= ARRAY['tenant_id', 'game_instance_id']::name[] AS expected_columns "
          + "FROM pg_catalog.pg_class index_class "
          + "JOIN pg_catalog.pg_namespace n ON n.oid = index_class.relnamespace "
          + "JOIN pg_catalog.pg_index i ON i.indexrelid = index_class.oid "
          + "JOIN pg_catalog.pg_class t ON t.oid = i.indrelid "
          + "WHERE n.nspname = current_schema() AND index_class.relname = ?";

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    ensureNoDuplicates(connection);
    IndexState state = inspectIndex(connection);
    if (state == IndexState.CONFLICTING_OBJECT) {
      throw new IllegalStateException(
          "Cannot create admission-pointer uniqueness index: an object with the expected index name is owned by another table");
    }
    try (Statement statement = connection.createStatement()) {
      for (String sql : statements(state)) {
        statement.execute(sql);
      }
    }
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  private static void ensureNoDuplicates(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery(DUPLICATE_PREFLIGHT)) {
      if (results.next()) {
        throw new IllegalStateException(
            "Cannot create admission-pointer uniqueness index while duplicate runtime target exists"
                + " for tenantId="
                + results.getLong(1)
                + " gameInstanceId="
                + results.getLong(2));
      }
    }
  }

  private static IndexState inspectIndex(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSPECT_INDEX)) {
      statement.setString(1, INDEX_NAME);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return IndexState.MISSING;
        }
        if (!results.getBoolean(3)) {
          return IndexState.CONFLICTING_OBJECT;
        }
        for (int column = 1; column <= 8; column++) {
          if (!results.getBoolean(column)) {
            return IndexState.INVALID;
          }
        }
        return IndexState.EXPECTED;
      }
    }
  }

  static List<String> statements(IndexState state) {
    if (state == IndexState.CONFLICTING_OBJECT) {
      throw new IllegalStateException(
          "Cannot create admission-pointer uniqueness index: an object with the expected index name is owned by another table");
    }
    if (state == IndexState.EXPECTED) {
      return List.of();
    }
    if (state == IndexState.INVALID) {
      return List.of("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX_NAME, createIndexStatement());
    }
    return List.of(createIndexStatement());
  }

  private static String createIndexStatement() {
    return "CREATE UNIQUE INDEX CONCURRENTLY "
        + INDEX_NAME
        + " ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id)";
  }

  enum IndexState {
    MISSING,
    EXPECTED,
    INVALID,
    CONFLICTING_OBJECT
  }
}
