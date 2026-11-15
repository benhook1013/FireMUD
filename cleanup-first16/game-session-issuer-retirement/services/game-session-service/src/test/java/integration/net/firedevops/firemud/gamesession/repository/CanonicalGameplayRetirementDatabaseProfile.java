package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.testcontainers.containers.PostgreSQLContainer;

/** Opt-in server-side attribution for the single physical-retirement proof case. */
final class CanonicalGameplayRetirementDatabaseProfile implements AutoCloseable {
  static final String ENVIRONMENT_VARIABLE = "FIREMUD_RETIREMENT_DB_PROFILE";
  static final String JOIN_ORDER_CANDIDATE_ENVIRONMENT_VARIABLE =
      "FIREMUD_RETIREMENT_JOIN_ORDER_CANDIDATE";

  private static final String COST_PROFILE_ENVIRONMENT_VARIABLE =
      CanonicalGameplayIssuerRetirementCostProbe.ENVIRONMENT_VARIABLE;
  private static final String FIXTURE_APPLICATION_NAME = "firemud_retirement_fixture";
  private static final String SAMPLER_APPLICATION_NAME = "firemud_retirement_db_sampler";
  private static final long SAMPLE_PERIOD_MILLIS = 200L;
  private static final int DIAGNOSTIC_QUERY_TIMEOUT_SECONDS = 5;
  private static final String GUARD_FUNCTION_NAME = "require_first_new_abort_source_complete";
  private static final String GUARD_SELECT_MARKER =
      "FROM game_session_canonical_binding_transition transition";
  private static final String GUARD_FAILURE_MARKER =
      "RAISE EXCEPTION 'First-NEW Account abort";

  private static final CanonicalGameplayRetirementDatabaseProfile DISABLED =
      new CanonicalGameplayRetirementDatabaseProfile();

  private final Connection connection;
  private final Snapshot before;
  private final String unavailableReason;
  private final Map<String, Long> waitSamples = new ConcurrentSkipListMap<>();
  private volatile boolean sampling;
  private volatile boolean samplerFailed;
  private volatile long sampleCount;
  private volatile long emptyActiveSamples;
  private long completedCycles;
  private Thread sampler;
  private boolean closed;
  private DSLContext candidateFixtureDsl;
  private String candidateProconfigBefore;
  private String candidateProconfigAfter;
  private String previousJoinCollapseLimit;
  private boolean candidateSettingAttempted;
  private boolean candidateSettingInstalled;
  private String candidateMode = "baseline";

  private CanonicalGameplayRetirementDatabaseProfile() {
    connection = null;
    before = null;
    unavailableReason = "disabled";
  }

  private CanonicalGameplayRetirementDatabaseProfile(
      Connection connection, Snapshot before, String unavailableReason) {
    this.connection = connection;
    this.before = before;
    this.unavailableReason = unavailableReason;
  }

  static boolean isEnabled() {
    return "1".equals(System.getenv(ENVIRONMENT_VARIABLE))
        && "1".equals(System.getenv(COST_PROFILE_ENVIRONMENT_VARIABLE));
  }

  static boolean isJoinOrderCandidateRequested() {
    return "1".equals(System.getenv(JOIN_ORDER_CANDIDATE_ENVIRONMENT_VARIABLE));
  }

  static CanonicalGameplayRetirementDatabaseProfile start(PostgreSQLContainer<?> postgres) {
    if (!isEnabled()) return DISABLED;
    Connection connection = null;
    try {
      Properties properties = new Properties();
      properties.setProperty("user", postgres.getUsername());
      properties.setProperty("password", postgres.getPassword());
      properties.setProperty("ApplicationName", SAMPLER_APPLICATION_NAME);
      properties.setProperty("connectTimeout", "5");
      properties.setProperty("socketTimeout", "5");
      connection = DriverManager.getConnection(postgres.getJdbcUrl(), properties);
      connection.setAutoCommit(true);
      Snapshot before = Snapshot.read(connection);
      var profile =
          new CanonicalGameplayRetirementDatabaseProfile(connection, before, null);
      profile.startSampling();
      return profile;
    } catch (SQLException unavailable) {
      closeQuietly(connection);
      return new CanonicalGameplayRetirementDatabaseProfile(null, null, "snapshot-unavailable");
    }
  }

  private void startSampling() {
    sampling = true;
    sampler = new Thread(this::sampleWaitEvents, "retirement-db-wait-sampler");
    sampler.setDaemon(true);
    sampler.start();
  }

  void cycleCompleted() {
    if (before != null && !closed) completedCycles++;
  }

  boolean isActive() {
    return before != null && !closed;
  }

  void installJoinOrderCandidate(DSLContext fixtureDsl) {
    if (!isJoinOrderCandidateRequested()) {
      throw new IllegalStateException("retirement join-order candidate flag is not set");
    }
    if (!isEnabled() || !isActive()) {
      throw new IllegalStateException("retirement join-order candidate requires both profile flags");
    }

    candidateFixtureDsl = fixtureDsl;
    try {
      DSLContext diagnosticDsl = diagnosticContext(fixtureDsl);
      candidateProconfigBefore = readGuardFunctionProconfig(diagnosticDsl);
      previousJoinCollapseLimit = readJoinCollapseLimit(diagnosticDsl);
      if (previousJoinCollapseLimit != null && !previousJoinCollapseLimit.matches("-?\\d+")) {
        throw new IllegalStateException("existing join_collapse_limit is not an integer setting");
      }

      String schema = diagnosticDsl.fetchOne("SELECT current_schema()").get(0, String.class);
      candidateSettingAttempted = true;
      diagnosticDsl.execute(
          "ALTER FUNCTION "
              + quoteIdentifier(schema)
              + "."
              + quoteIdentifier(GUARD_FUNCTION_NAME)
              + "() SET join_collapse_limit = 1");
      candidateProconfigAfter = readGuardFunctionProconfig(diagnosticDsl);
      String installedValue = readJoinCollapseLimit(diagnosticDsl);
      if (!"1".equals(installedValue)) {
        throw new IllegalStateException("join_collapse_limit candidate setting did not persist");
      }
      candidateSettingInstalled = true;
      candidateMode = "join-collapse-limit-1";
      System.out.println(
          "FIREMUD_RETIREMENT_JOIN_ORDER_CANDIDATE v=1 status=installed mode="
              + candidateMode
              + " proconfig_before="
              + candidateProconfigBefore
              + " proconfig_after="
              + candidateProconfigAfter);
    } catch (RuntimeException unavailable) {
      candidateMode = "installation-failed";
      System.out.println(
          "FIREMUD_RETIREMENT_JOIN_ORDER_CANDIDATE v=1 status=failed reason="
              + unavailable.getClass().getSimpleName()
              + " proconfig_before="
              + candidateProconfigBefore
              + " proconfig_after="
              + candidateProconfigAfter);
      throw new IllegalStateException(
          "retirement join-order candidate installation failed: "
              + unavailable.getClass().getSimpleName());
    }
  }

  void captureGuardSelectPlan(
      DSLContext dsl, UUID transitionId, boolean initialAbortTransition, String phase) {
    if (!isActive()) return;
    try {
      DSLContext diagnosticDsl = diagnosticContext(dsl);
      String functionDefinition =
          diagnosticDsl.fetchOne(
              "SELECT pg_get_functiondef(f.oid)"
                  + " FROM pg_proc f"
                  + " JOIN pg_namespace n ON n.oid = f.pronamespace"
                  + " WHERE f.proname = ? AND f.prorettype = 'trigger'::regtype"
                  + " AND f.pronargs = 0 AND n.nspname = current_schema()",
              GUARD_FUNCTION_NAME).get(0, String.class);
      if (functionDefinition == null) {
        emitGuardPlan(phase, initialAbortTransition, "function-source-unavailable", "none", "none");
        return;
      }

      String exactSelect = extractGuardSelect(functionDefinition);
      String parameterizedSelect =
          exactSelect
              .replaceAll("\\baffected_transition_id\\b", "?")
              .replaceAll("\\binitial_abort_transition\\b", "?");
      if (count(exactSelect, "affected_transition_id") != 1
          || count(exactSelect, "initial_abort_transition") != 1) {
        emitGuardPlan(phase, initialAbortTransition, "unexpected-parameter-shape", "none", "none");
        return;
      }

      String plan =
          candidateSettingInstalled
              ? diagnosticDsl.transactionResult(
                  configuration -> {
                    DSLContext transactionDsl = DSL.using(configuration);
                    transactionDsl.execute("SET LOCAL join_collapse_limit = 1");
                    return fetchGuardPlan(
                        transactionDsl,
                        parameterizedSelect,
                        transitionId,
                        initialAbortTransition);
                  })
              : fetchGuardPlan(
                  diagnosticDsl, parameterizedSelect, transitionId, initialAbortTransition);
      if (plan == null) {
        emitGuardPlan(
            phase,
            initialAbortTransition,
            "plan-unavailable",
            sha256(exactSelect),
            "none");
        return;
      }
      String safePlan = plan.replace(transitionId.toString(), "<fixture-transition-id>");
      emitGuardPlan(phase, initialAbortTransition, "ok", sha256(exactSelect), safePlan);
    } catch (RuntimeException unavailable) {
      emitGuardPlan(
          phase,
          initialAbortTransition,
          "unavailable-" + unavailable.getClass().getSimpleName(),
          "none",
          "none");
    }
  }

  private static DSLContext diagnosticContext(DSLContext fixtureDsl) {
    var settings = (org.jooq.conf.Settings) fixtureDsl.configuration().settings().clone();
    settings.withQueryTimeout(DIAGNOSTIC_QUERY_TIMEOUT_SECONDS);
    return DSL.using(fixtureDsl.configuration().derive(settings));
  }

  private static String readGuardFunctionProconfig(DSLContext dsl) {
    var row =
        dsl.fetchOne(
            "SELECT f.proconfig::text FROM pg_proc f"
                + " JOIN pg_namespace n ON n.oid = f.pronamespace"
                + " WHERE f.proname = ? AND f.prorettype = 'trigger'::regtype"
                + " AND f.pronargs = 0 AND n.nspname = current_schema()",
            GUARD_FUNCTION_NAME);
    if (row == null) throw new IllegalStateException("guard function config unavailable");
    return row.get(0, String.class);
  }

  private static String readJoinCollapseLimit(DSLContext dsl) {
    var row =
        dsl.fetchOne(
            "SELECT (SELECT split_part(setting, '=', 2)"
                + " FROM unnest(COALESCE(f.proconfig, ARRAY[]::text[])) AS config(setting)"
                + " WHERE split_part(setting, '=', 1) = 'join_collapse_limit')"
                + " FROM pg_proc f JOIN pg_namespace n ON n.oid = f.pronamespace"
                + " WHERE f.proname = ? AND f.prorettype = 'trigger'::regtype"
                + " AND f.pronargs = 0 AND n.nspname = current_schema()",
            GUARD_FUNCTION_NAME);
    if (row == null) throw new IllegalStateException("guard function setting unavailable");
    return row.get(0, String.class);
  }

  private static String quoteIdentifier(String identifier) {
    if (identifier == null) throw new IllegalStateException("fixture schema unavailable");
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private void restoreJoinOrderCandidate() {
    if (!candidateSettingAttempted) return;
    DSLContext diagnosticDsl = diagnosticContext(candidateFixtureDsl);
    String schema = diagnosticDsl.fetchOne("SELECT current_schema()").get(0, String.class);
    String functionName =
        quoteIdentifier(schema) + "." + quoteIdentifier(GUARD_FUNCTION_NAME) + "()";
    String restoreSql =
        "ALTER FUNCTION "
            + functionName
            + (previousJoinCollapseLimit == null
                ? " RESET join_collapse_limit"
                : " SET join_collapse_limit = " + previousJoinCollapseLimit);
    diagnosticDsl.execute(restoreSql);
    String restoredValue = readJoinCollapseLimit(diagnosticDsl);
    String restoredProconfig = readGuardFunctionProconfig(diagnosticDsl);
    boolean restored =
        java.util.Objects.equals(previousJoinCollapseLimit, restoredValue)
            && java.util.Objects.equals(candidateProconfigBefore, restoredProconfig);
    System.out.println(
        "FIREMUD_RETIREMENT_JOIN_ORDER_CANDIDATE v=1 status="
            + (restored ? "restored" : "restore-mismatch")
            + " mode="
            + candidateMode
            + " proconfig_before="
            + candidateProconfigBefore
            + " proconfig_candidate="
            + candidateProconfigAfter
            + " proconfig_after_restore="
            + restoredProconfig);
    if (!restored) {
      throw new IllegalStateException("retirement join-order candidate did not restore original setting");
    }
    candidateSettingAttempted = false;
    candidateSettingInstalled = false;
  }

  private static String fetchGuardPlan(
      DSLContext dsl, String parameterizedSelect, UUID transitionId, boolean initialAbortTransition) {
    var row =
        dsl.fetchOne(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, SETTINGS) SELECT NOT EXISTS ("
                + parameterizedSelect
                + ")",
            transitionId,
            initialAbortTransition);
    return row == null ? null : row.get(0, String.class);
  }

  private static String extractGuardSelect(String functionDefinition) {
    int marker = functionDefinition.indexOf(GUARD_SELECT_MARKER);
    int failure = functionDefinition.indexOf(GUARD_FAILURE_MARKER, marker);
    int select = functionDefinition.lastIndexOf("SELECT 1", marker);
    int end = functionDefinition.lastIndexOf(") THEN", failure);
    if (marker < 0 || failure < 0 || select < 0 || end <= select) {
      throw new IllegalStateException("guard-select-source-shape-unavailable");
    }
    String exactSelect = functionDefinition.substring(select, end).trim();
    if (!exactSelect.startsWith("SELECT 1")
        || count(exactSelect, "affected_transition_id") != 1
        || count(exactSelect, "initial_abort_transition") != 1) {
      throw new IllegalStateException("guard-select-parameter-shape-unavailable");
    }
    return exactSelect;
  }

  private static int count(String source, String value) {
    int count = 0;
    int from = 0;
    while ((from = source.indexOf(value, from)) >= 0) {
      count++;
      from += value.length();
    }
    return count;
  }

  private static String sha256(String source) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private void emitGuardPlan(
      String phase,
      boolean initialAbortTransition,
      String status,
      String querySha256,
      String planJson) {
    System.out.println(
        "FIREMUD_RETIREMENT_GUARD_PLAN v=1 phase="
            + phase
            + " branch="
            + (initialAbortTransition ? "initial-account-abort" : "release-state")
            + " candidate_mode="
            + candidateMode
            + " bindings=affected_transition_id:<fixture-transition-id>,initial_abort_transition:"
            + initialAbortTransition
            + " status="
            + status
            + " query_sha256="
            + querySha256
            + " query_timeout_s="
            + DIAGNOSTIC_QUERY_TIMEOUT_SECONDS
            + " plan_json="
            + planJson);
  }

  private void sampleWaitEvents() {
    String sql =
        "SELECT COALESCE(wait_event_type, 'none'), COALESCE(wait_event, 'none'), count(*)"
            + " FROM pg_stat_activity WHERE application_name = ? AND backend_type = 'client backend'"
            + " AND state = 'active' GROUP BY 1, 2 ORDER BY 1, 2";
    while (sampling) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, FIXTURE_APPLICATION_NAME);
        try (ResultSet rows = statement.executeQuery()) {
          long activeRows = 0L;
          while (rows.next()) {
            String key = rows.getString(1) + "/" + rows.getString(2);
            long count = rows.getLong(3);
            waitSamples.merge(key, count, Long::sum);
            activeRows += count;
          }
          sampleCount++;
          if (activeRows == 0L) emptyActiveSamples++;
        }
      } catch (SQLException unavailable) {
        samplerFailed = true;
        sampling = false;
        return;
      }
      try {
        Thread.sleep(SAMPLE_PERIOD_MILLIS);
      } catch (InterruptedException stopped) {
        if (sampling) samplerFailed = true;
        return;
      }
    }
  }

  @Override
  public void close() {
    if (this == DISABLED || closed) return;
    RuntimeException restoreFailure = null;
    try {
      restoreJoinOrderCandidate();
    } catch (RuntimeException unavailable) {
      System.out.println(
          "FIREMUD_RETIREMENT_JOIN_ORDER_CANDIDATE v=1 status=restore-failed reason="
              + unavailable.getClass().getSimpleName());
      restoreFailure =
          new IllegalStateException(
              "retirement join-order candidate restore failed: "
                  + unavailable.getClass().getSimpleName());
    }
    closed = true;
    Snapshot after = null;
    boolean samplerStopped = stopSampling();
    if (samplerStopped && connection != null) {
      try {
        after = Snapshot.read(connection);
      } catch (SQLException unavailable) {
        // Diagnostics must not turn a passing owner proof into a test failure.
      }
    }
    closeQuietly(connection);
    try {
      System.out.println(summary(after, samplerStopped));
    } catch (RuntimeException ignored) {
      // Diagnostic output must not replace a test failure.
    }
    if (restoreFailure != null) throw restoreFailure;
  }

  private boolean stopSampling() {
    sampling = false;
    if (sampler == null) return true;
    sampler.interrupt();
    try {
      sampler.join(5_000L);
      return !sampler.isAlive();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private String summary(Snapshot after, boolean samplerStopped) {
    if (before == null || after == null) {
      return "FIREMUD_RETIREMENT_DB_PROFILE v=1 phase=repeat-window status="
          + (unavailableReason == null ? "final-snapshot-unavailable" : unavailableReason)
          + " candidate_mode="
          + candidateMode
          + " sampler_failed="
          + samplerFailed
          + " sampler_stopped="
          + samplerStopped;
    }
    List<FunctionDelta> functions = before.functionDeltas(after);
    functions.sort(Comparator.comparingDouble(FunctionDelta::totalMillis).reversed());
    StringBuilder functionSummary = new StringBuilder();
    int displayed = 0;
    for (FunctionDelta function : functions) {
      if (displayed == 16) break;
      if (displayed > 0) functionSummary.append(';');
      functionSummary
          .append(function.name())
          .append("{calls=")
          .append(function.calls())
          .append(",self_ms=")
          .append(format(function.selfMillis()))
          .append(",total_ms=")
          .append(format(function.totalMillis()))
          .append('}');
      displayed++;
    }
    if (displayed == 0) functionSummary.append("none");

    return "FIREMUD_RETIREMENT_DB_PROFILE v=1 phase=repeat-window status=ok"
        + " candidate_mode="
        + candidateMode
        + " settings_observed="
        + before.settings
        + " wait_sample_ms="
        + SAMPLE_PERIOD_MILLIS
        + " cycles_completed="
        + completedCycles
        + " wait_samples="
        + sampleCount
        + " empty_active_samples="
        + emptyActiveSamples
        + " wait_events="
        + waitSamples
        + " sampler_failed="
        + samplerFailed
        + " sampler_stopped="
        + samplerStopped
        + " wal_delta="
        + "{counts="
        + after.formatCounterDeltas(before, "wal.")
        + ",times_ms="
        + after.formatTimingDeltas(before, "wal.")
        + "}"
        + " database_delta="
        + "{counts="
        + after.formatCounterDeltas(before, "database.")
        + ",times_ms="
        + after.formatTimingDeltas(before, "database.")
        + "}"
        + " user_function_count="
        + functions.size()
        + " user_function_self_total_ms="
        + format(functions.stream().mapToDouble(FunctionDelta::selfMillis).sum())
        + " user_function_delta_ms=self,total_inclusive_not_summed_top16:{"
        + functionSummary
        + "} observer_overhead=activity_query_each_sample_and_stats_snapshots"
        + " limits=wait_events_are_samples_not_complete_wait_time;database_counters_include_sampler_and_snapshot_queries;"
        + "only_active_fixture_backends_are_sampled;commit_residual_does_not_isolate_network_or_non_io_server_work";
  }

  private static String format(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) return;
    try {
      connection.close();
    } catch (SQLException ignored) {
      // Preserve the owner proof result.
    }
  }

  private record FunctionMetric(String name, long calls, double totalMillis, double selfMillis) {}

  private record FunctionDelta(String name, long calls, double totalMillis, double selfMillis) {}

  private static final class Snapshot {
    private final Map<String, Long> counters;
    private final Map<String, Double> timings;
    private final Map<String, FunctionMetric> functions;
    private final String settings;

    private Snapshot(
        Map<String, Long> counters,
        Map<String, Double> timings,
        Map<String, FunctionMetric> functions, String settings) {
      this.settings = settings;
      this.counters = counters;
      this.timings = timings;
      this.functions = functions;
    }

    private static Snapshot read(Connection connection) throws SQLException {
      try (var clear = connection.createStatement()) {
        clear.execute("SELECT pg_stat_clear_snapshot()");
      }
      String settings;
      try (var statement = connection.createStatement();
          var row = statement.executeQuery(
              "SELECT current_setting('track_functions'), current_setting('track_io_timing'), "
                  + "current_setting('track_wal_io_timing')")) {
        row.next();
        settings = "track_functions:" + row.getString(1) + ",track_io_timing:"
            + row.getString(2) + ",track_wal_io_timing:" + row.getString(3);
      }
      Map<String, Long> counters = new TreeMap<>();
      Map<String, Double> timings = new TreeMap<>();
      try (var statement = connection.createStatement();
          ResultSet row = statement.executeQuery(
              "SELECT wal_records, wal_fpi, wal_bytes, wal_buffers_full FROM pg_stat_wal")) {
        row.next();
        readLongs(
            row,
            counters,
            "wal.records",
            "wal.fpi",
            "wal.bytes",
            "wal.buffers_full");
      }
      try (var statement = connection.createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT COALESCE(SUM(writes), 0) AS wal_writes,"
                      + " COALESCE(SUM(fsyncs), 0) AS wal_fsyncs,"
                      + " COALESCE(SUM(write_time), 0.0) AS wal_write_time_ms,"
                      + " COALESCE(SUM(fsync_time), 0.0) AS wal_fsync_time_ms"
                      + " FROM pg_stat_io WHERE object = 'wal'")) {
        row.next();
        readLongs(row, counters, "wal.writes", "wal.fsyncs");
        readDoubles(
            row,
            timings,
            new String[] {"wal.write_time_ms", "wal.fsync_time_ms"},
            new String[] {"wal_write_time_ms", "wal_fsync_time_ms"});
      }
      try (var statement = connection.createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT xact_commit, xact_rollback, blks_read, blks_hit, temp_files, temp_bytes,"
                      + " deadlocks, blk_read_time, blk_write_time FROM pg_stat_database"
                      + " WHERE datname = current_database()")) {
        row.next();
        readLongs(
            row,
            counters,
            "database.commits",
            "database.rollbacks",
            "database.blocks_read",
            "database.blocks_hit",
            "database.temp_files",
            "database.temp_bytes",
            "database.deadlocks");
        readDoubles(
            row,
            timings,
            new String[] {"database.block_read_time_ms", "database.block_write_time_ms"},
            new String[] {"blk_read_time", "blk_write_time"});
      }
      Map<String, FunctionMetric> functions = new TreeMap<>();
      try (var statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT funcid::text, schemaname, funcname, calls, total_time, self_time"
                      + " FROM pg_stat_user_functions WHERE schemaname NOT IN ('pg_catalog', 'information_schema')"
                      + " AND schemaname NOT LIKE 'pg_toast%'")) {
        while (rows.next()) {
          String id = rows.getString(1);
          String name = rows.getString(2) + "." + rows.getString(3) + "#" + id;
          functions.put(
              id,
              new FunctionMetric(name, rows.getLong(4), rows.getDouble(5), rows.getDouble(6)));
        }
      }
      return new Snapshot(counters, timings, functions, settings);
    }

    private static void readLongs(ResultSet row, Map<String, Long> target, String... keys)
        throws SQLException {
      for (int index = 0; index < keys.length; index++) {
        target.put(keys[index], row.getLong(index + 1));
      }
    }

    private static void readDoubles(
        ResultSet row, Map<String, Double> target, String[] keys, String[] columns)
        throws SQLException {
      for (int index = 0; index < keys.length; index++) {
        target.put(keys[index], row.getDouble(columns[index]));
      }
    }

    private List<FunctionDelta> functionDeltas(Snapshot after) {
      List<FunctionDelta> deltas = new ArrayList<>();
      after.functions.forEach(
          (id, current) -> {
            FunctionMetric prior = functions.get(id);
            if (prior == null) prior = new FunctionMetric(current.name(), 0L, 0.0, 0.0);
            long calls = current.calls() - prior.calls();
            double total = current.totalMillis() - prior.totalMillis();
            double self = current.selfMillis() - prior.selfMillis();
            if (calls > 0L || total > 0.0 || self > 0.0) {
              deltas.add(new FunctionDelta(current.name(), calls, total, self));
            }
          });
      return deltas;
    }

    private String formatCounterDeltas(Snapshot before, String prefix) {
      StringBuilder result = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, Long> entry : counters.entrySet()) {
        if (!entry.getKey().startsWith(prefix)) continue;
        if (!first) result.append(',');
        result
            .append(entry.getKey().substring(prefix.length()))
            .append(':')
            .append(entry.getValue() - before.counters.getOrDefault(entry.getKey(), 0L));
        first = false;
      }
      return result.append('}').toString();
    }

    private String formatTimingDeltas(Snapshot before, String prefix) {
      StringBuilder result = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, Double> entry : timings.entrySet()) {
        String key = entry.getKey();
        if (!key.startsWith(prefix)) continue;
        if (!first) result.append(',');
        result
            .append(key.substring(prefix.length()))
            .append(':')
            .append(format(entry.getValue() - before.timings.getOrDefault(key, 0.0)));
        first = false;
      }
      return result.append('}').toString();
    }
  }
}
