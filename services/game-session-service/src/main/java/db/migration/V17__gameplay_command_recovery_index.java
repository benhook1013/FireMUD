package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Builds the accepted-but-unmaterialized command recovery index outside a transaction. */
public final class V17__gameplay_command_recovery_index extends BaseJavaMigration {

  static final String INDEX_NAME = "idx_gameplay_command_recovery_accepted_unstaged";
  private static final String EXPECTED_PREDICATE = "execution_outcome='accepted'andstaged_atisnull";
  private static final String INSPECT_INDEX =
      "SELECT c.relkind = 'i' AS is_index, "
          + "t.relname = 'gameplay_command' AS expected_table, "
          + "i.indisvalid AS is_valid, i.indisunique AS is_unique, "
          + "i.indpred IS NOT NULL AS is_partial, "
          + "i.indexprs IS NULL AS no_expressions, i.indnkeyatts = 2 AS expected_key_count, "
          + "i.indnatts = 2 AS expected_total_count, "
          + "(SELECT array_agg(a.attname ORDER BY k.ordinality) "
          + "FROM unnest(i.indkey::smallint[]) WITH ORDINALITY AS k(attnum, ordinality) "
          + "JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) "
          + "= ARRAY['accepted_at', 'id']::name[] AS expected_columns, "
          + "pg_get_expr(i.indpred, i.indrelid) AS predicate "
          + "FROM pg_catalog.pg_class c "
          + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
          + "LEFT JOIN pg_catalog.pg_index i ON i.indexrelid = c.oid "
          + "LEFT JOIN pg_catalog.pg_class t ON t.oid = i.indrelid "
          + "WHERE n.nspname = current_schema() AND c.relname = ?";

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    IndexState state = inspectIndex(connection);
    if (state == IndexState.CONFLICTING_OBJECT) {
      throw new IllegalStateException(
          "Cannot create gameplay-command recovery index: an object with the expected index name "
              + "is owned by another table or is not an index");
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

  private static IndexState inspectIndex(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSPECT_INDEX)) {
      statement.setString(1, INDEX_NAME);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return IndexState.MISSING;
        }
        return classifyCatalogRow(
            results.getBoolean("is_index"),
            results.getBoolean("expected_table"),
            results.getBoolean("is_valid"),
            results.getBoolean("is_unique"),
            results.getBoolean("is_partial"),
            results.getBoolean("no_expressions"),
            results.getBoolean("expected_key_count"),
            results.getBoolean("expected_total_count"),
            results.getBoolean("expected_columns"),
            results.getString("predicate"));
      }
    }
  }

  static IndexState classifyCatalogRow(
      boolean isIndex,
      boolean expectedTable,
      boolean isValid,
      boolean isUnique,
      boolean isPartial,
      boolean noExpressions,
      boolean expectedKeyCount,
      boolean expectedTotalCount,
      boolean expectedColumns,
      String predicate) {
    if (!isIndex || !expectedTable) {
      return IndexState.CONFLICTING_OBJECT;
    }
    if (!isValid
        || isUnique
        || !isPartial
        || !noExpressions
        || !expectedKeyCount
        || !expectedTotalCount
        || !expectedColumns) {
      return IndexState.INVALID;
    }
    return isExpectedPredicate(predicate) ? IndexState.EXPECTED : IndexState.INVALID;
  }

  private static boolean isExpectedPredicate(String predicate) {
    if (predicate == null) {
      return false;
    }
    String normalized =
        predicate
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "")
            .replace("::text", "")
            .replace("::character varying", "")
            .replace("::varchar", "")
            .replace("(", "")
            .replace(")", "");
    return EXPECTED_PREDICATE.equals(normalized);
  }

  static List<String> statements(IndexState state) {
    if (state == IndexState.CONFLICTING_OBJECT) {
      throw new IllegalStateException(
          "Cannot create gameplay-command recovery index: an object with the expected index name "
              + "is owned by another table or is not an index");
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
    return "CREATE INDEX CONCURRENTLY "
        + INDEX_NAME
        + " ON gameplay_command USING btree (accepted_at, id) "
        + "WHERE execution_outcome = 'ACCEPTED' AND staged_at IS NULL";
  }

  enum IndexState {
    MISSING,
    EXPECTED,
    INVALID,
    CONFLICTING_OBJECT
  }
}
