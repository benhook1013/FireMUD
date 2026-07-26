package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V8__remote_followup_target_instance_effect_identity extends BaseJavaMigration {

  private static final String TARGET_INDEX =
      "idx_remote_followup_target_instance_region_epoch_effect";
  private static final String LEGACY_INDEX = "idx_remote_followup_target_region_epoch_effect";
  private static final String CREATE_TARGET_INDEX =
      """
      CREATE UNIQUE INDEX CONCURRENTLY idx_remote_followup_target_instance_region_epoch_effect
          ON remote_followup (
              tenant_id,
              target_game_instance_id,
              target_region_id,
              target_region_epoch,
              effect_key
          )
      """;
  private static final String INSPECT_INDEX =
      """
      SELECT
          i.indisvalid,
          i.indisunique,
          t.relname = 'remote_followup' AS expected_table,
          i.indpred IS NULL AS unfiltered,
          i.indexprs IS NULL AS no_expressions,
          i.indnkeyatts = 5 AS expected_key_count,
          i.indnatts = 5 AS expected_total_count,
          (
              SELECT array_agg(a.attname ORDER BY index_key.ordinality)
              FROM unnest(i.indkey::smallint[]) WITH ORDINALITY
                  AS index_key(attnum, ordinality)
              JOIN pg_catalog.pg_attribute a
                  ON a.attrelid = i.indrelid
                  AND a.attnum = index_key.attnum
          ) = ARRAY[
              'tenant_id',
              'target_game_instance_id',
              'target_region_id',
              'target_region_epoch',
              'effect_key'
          ]::name[] AS expected_columns
      FROM pg_catalog.pg_class index_class
      JOIN pg_catalog.pg_namespace n ON n.oid = index_class.relnamespace
      JOIN pg_catalog.pg_index i ON i.indexrelid = index_class.oid
      JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
      WHERE n.nspname = current_schema()
          AND index_class.relname = ?
      """;

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    IndexState indexState = inspectTargetIndex(connection);
    try (Statement statement = context.getConnection().createStatement()) {
      for (String sql : migrationStatements(indexState)) {
        statement.execute(sql);
      }
    }
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  private IndexState inspectTargetIndex(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSPECT_INDEX)) {
      statement.setString(1, TARGET_INDEX);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return IndexState.MISSING;
        }
        for (int column = 1; column <= 8; column++) {
          if (!resultSet.getBoolean(column)) {
            return IndexState.MISMATCHED;
          }
        }
        return IndexState.EXPECTED;
      }
    }
  }

  static List<String> migrationStatements(IndexState indexState) {
    List<String> statements = new ArrayList<>();
    if (indexState == IndexState.MISMATCHED) {
      statements.add("DROP INDEX CONCURRENTLY IF EXISTS " + TARGET_INDEX);
    }
    if (indexState != IndexState.EXPECTED) {
      statements.add(CREATE_TARGET_INDEX);
    }
    statements.add("DROP INDEX CONCURRENTLY IF EXISTS " + LEGACY_INDEX);
    return List.copyOf(statements);
  }

  enum IndexState {
    MISSING,
    EXPECTED,
    MISMATCHED
  }
}
