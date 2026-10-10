package integration.net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigInteger;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only PostgreSQL fixture with a strict loopback external-database option. */
public final class AccountPostgresIntegrationFixture {
  public static final String JDBC_URL_ENV = "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";

  private final PostgreSQLContainer<?> container;
  private final String externalJdbcUrl;
  private final boolean requireDurablePrimary;
  private boolean started;

  private static final BigInteger MAX_UINT64 =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  private static final BigInteger MAX_UINT32 =
      BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
  private static final int MAX_RELATION_LOCATOR_OCCURRENCES = 256;
  private static final int MAX_DISTINCT_RELATION_LOCATORS = 32;
  private static final int MAX_WAL_DUMP_OUTPUT_CHARS = 16_384;
  private static final int MAX_COMBINED_WAL_DUMP_OUTPUT_CHARS = MAX_WAL_DUMP_OUTPUT_CHARS * 3 + 256;
  private static final Pattern WAL_RELATION_LOCATOR =
      Pattern.compile("\\bblkref #\\d+: rel ([0-9]{1,10})/([0-9]{1,10})/([0-9]{1,10})\\b");
  private static final Pattern WAL_FAILURE_DETAIL =
      Pattern.compile(
          "(?:phase=(?:create|receipt-read) )?snapshot=[0-9:,]+ own_xid_if_assigned=([0-9]*)"
              + " finalization_xid=([1-9][0-9]{0,19}) receipt_xid=([0-9]*)"
              + " insert_fence=([0-9A-F]{1,8}/[0-9A-F]{1,8})"
              + " initial_flush=([0-9A-F]{1,8}/[0-9A-F]{1,8})"
              + " final_flush=([0-9A-F]{1,8}/[0-9A-F]{1,8})"
              + "(?: observed_db_ms=([1-9][0-9]{0,18}))?"
              + " unchanged_expiry_ms=([1-9][0-9]{0,18})");

  /**
   * Failure-only observation; WAL capture and locator lookup use the run-owned fixture database.
   */
  public String describeWalCoverageFailure(String detail) throws Exception {
    if (detail == null || detail.length() > 1024) {
      return "WAL diagnostic unavailable: missing or oversized server DETAIL";
    }
    var fields = WAL_FAILURE_DETAIL.matcher(detail);
    if (!fields.matches()) return "WAL diagnostic unavailable: unsupported server DETAIL shape";
    String[] snapshot =
        detail
            .substring(detail.indexOf("snapshot=") + 9, detail.indexOf(" own_xid_if_assigned="))
            .split(":", -1);
    if (snapshot.length != 3
        || !validXid(snapshot[0])
        || !validXid(snapshot[1])
        || new BigInteger(snapshot[0]).compareTo(new BigInteger(snapshot[1])) > 0) {
      return "WAL diagnostic unavailable: invalid snapshot";
    }
    if (!snapshot[2].isEmpty()) {
      for (String xid : snapshot[2].split(",", -1)) {
        if (!validXid(xid)
            || new BigInteger(xid).compareTo(new BigInteger(snapshot[0])) < 0
            || new BigInteger(xid).compareTo(new BigInteger(snapshot[1])) >= 0) {
          return "WAL diagnostic unavailable: invalid snapshot XID";
        }
      }
    }
    for (int group : new int[] {1, 2, 3}) {
      String xid = fields.group(group);
      if (!xid.isEmpty() && !validXid(xid)) {
        return "WAL diagnostic unavailable: invalid XID";
      }
    }
    for (int group : new int[] {7, 8}) {
      if (fields.group(group) != null) Long.parseLong(fields.group(group));
    }
    BigInteger insert = parseLsn(fields.group(4));
    BigInteger initialFlush = parseLsn(fields.group(5));
    BigInteger finalFlush = parseLsn(fields.group(6));
    if (insert.signum() <= 0
        || initialFlush.compareTo(finalFlush) > 0
        || finalFlush.compareTo(insert) >= 0) {
      return "WAL diagnostic unavailable: inconsistent WAL fences";
    }
    if (container == null) {
      return "WAL diagnostic unavailable: external loopback fixture has no owned container WAL access";
    }
    if (!started) return "WAL diagnostic unavailable: owned container is not running";

    // Search only the preceding 1 MiB. Missing/recycled records or a COMMIT outside this window
    // remain explicitly inconclusive. This observes WAL files after failure, not flush durability.
    String start =
        formatLsn(finalFlush.subtract(BigInteger.valueOf(1_048_576)).max(BigInteger.ZERO));
    StringBuilder result = new StringBuilder("WAL failure DETAIL: ").append(detail);
    StringBuilder walOutput = new StringBuilder();
    result.append("\nUnflushed interval (up to 256 records):\n");
    appendWalDump(result, walOutput, dumpWal(fields.group(6), fields.group(4), null));
    for (int group : new int[] {2, 3}) {
      String xid = fields.group(group);
      if (xid.isEmpty()) continue;
      // pg_waldump filters 32-bit WAL XIDs, whereas the server DETAIL retains full xid8 identity.
      String walXid =
          new BigInteger(xid).and(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)).toString();
      result
          .append("\nXID8 ")
          .append(xid)
          .append(" (WAL xid ")
          .append(walXid)
          .append(") records in bounded lookback ")
          .append(start)
          .append("..")
          .append(fields.group(4))
          .append(":\n");
      appendWalDump(result, walOutput, dumpWal(start, fields.group(4), walXid));
    }
    result
        .append('\n')
        .append(mapWalRelationLocatorsAfterFailure(walOutput.toString(), dataSource()));
    return result
        .append(
            "\nLimits: each dump <=6s, <=16KiB, <=256 matching records; output may be truncated. "
                + "Missing COMMIT is inconclusive. WAL file presence is not a flush observation; "
                + "compare records with the original logged flush fence. pg_waldump reports record start "
                + "and length, not an independently measured COMMIT end/flush bound.")
        .toString();
  }

  private static void appendWalDump(StringBuilder result, StringBuilder walOutput, String dump) {
    result.append(dump);
    walOutput.append(dump).append('\n');
  }

  /**
   * Maps bounded WAL relation locators against the current catalog after failure-only WAL capture.
   * Results are current observations only; they do not establish historical identity, backend, or
   * causation for a record.
   */
  public static String mapWalRelationLocatorsAfterFailure(
      String walOutput, DataSource sameDatabase) {
    if (walOutput == null || walOutput.length() > MAX_COMBINED_WAL_DUMP_OUTPUT_CHARS) {
      return "WAL relation mapping unavailable: missing or oversized bounded dump output";
    }

    Map<String, RelationLocator> locators = new LinkedHashMap<>();
    Matcher matcher = WAL_RELATION_LOCATOR.matcher(walOutput);
    int occurrences = 0;
    int invalidLocators = 0;
    boolean occurrenceLimitReached = false;
    boolean distinctLimitReached = false;
    while (matcher.find()) {
      if (++occurrences > MAX_RELATION_LOCATOR_OCCURRENCES) {
        occurrenceLimitReached = true;
        break;
      }
      String tablespace = matcher.group(1);
      String database = matcher.group(2);
      String relfilenode = matcher.group(3);
      if (!validOid(tablespace) || !validOid(database) || !validOid(relfilenode)) {
        invalidLocators++;
        continue;
      }
      String key = tablespace + "/" + database + "/" + relfilenode;
      if (!locators.containsKey(key)) {
        if (locators.size() == MAX_DISTINCT_RELATION_LOCATORS) {
          distinctLimitReached = true;
          continue;
        }
        locators.put(key, new RelationLocator(tablespace, database, relfilenode));
      }
    }
    if (locators.isEmpty()) {
      return "WAL relation mapping inconclusive: no valid relation locators in captured records"
          + locatorLimitNote(occurrenceLimitReached, distinctLimitReached, invalidLocators);
    }

    StringBuilder mapping =
        new StringBuilder("WAL relation mappings (post-failure current catalog only):");
    try (Connection connection = sameDatabase.getConnection()) {
      String currentDatabase = null;
      String currentDatabaseOid = null;
      StringBuilder values = new StringBuilder();
      for (int index = 0; index < locators.size(); index++) {
        if (index > 0) values.append(',');
        values.append("(?::oid, ?::oid, ?::oid)");
      }
      String sql =
          "WITH locator_input(tablespace_oid, database_oid, relfilenode) AS (VALUES "
              + values
              + "), current_database_row AS ("
              + "SELECT oid FROM pg_database WHERE datname = current_database()"
              + "), resolved AS ("
              + "SELECT l.*, d.oid AS current_database_oid, "
              + "CASE WHEN l.database_oid = d.oid "
              + "THEN pg_filenode_relation(l.tablespace_oid, l.relfilenode) END AS relation_oid "
              + "FROM locator_input l CROSS JOIN current_database_row d) "
              + "SELECT current_database(), r.current_database_oid::text, "
              + "r.tablespace_oid::text, r.database_oid::text, r.relfilenode::text, "
              + "r.relation_oid::oid::text, n.nspname, c.relname, c.relkind::text, "
              + "c.reltablespace::text, pg_relation_filenode(c.oid)::text "
              + "FROM resolved r LEFT JOIN pg_class c ON c.oid = r.relation_oid "
              + "LEFT JOIN pg_namespace n ON n.oid = c.relnamespace "
              + "ORDER BY r.tablespace_oid, r.database_oid, r.relfilenode";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(5);
        int parameter = 1;
        for (RelationLocator locator : locators.values()) {
          statement.setString(parameter++, locator.tablespaceOid());
          statement.setString(parameter++, locator.databaseOid());
          statement.setString(parameter++, locator.relfilenode());
        }
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            currentDatabase = rows.getString(1);
            currentDatabaseOid = rows.getString(2);
            String locator = rows.getString(3) + "/" + rows.getString(4) + "/" + rows.getString(5);
            String relationOid = rows.getString(6);
            String schema = rows.getString(7);
            String relation = rows.getString(8);
            String relationKind = rows.getString(9);
            String relationTablespace = rows.getString(10);
            String currentRelfilenode = rows.getString(11);
            mapping.append("\n  ").append(locator).append(" -> ");
            if (!rows.getString(4).equals(currentDatabaseOid)) {
              mapping.append("different database; not looked up (inconclusive)");
            } else if (relationOid == null || schema == null || relation == null) {
              mapping.append("no current mapping (missing/reused locator; inconclusive)");
            } else if (!rows.getString(5).equals(currentRelfilenode)) {
              mapping
                  .append("catalog mapping changed during lookup to ")
                  .append(schema)
                  .append('.')
                  .append(relation)
                  .append("; locator identity inconclusive");
            } else {
              mapping
                  .append(schema)
                  .append('.')
                  .append(relation)
                  .append(" relation_oid=")
                  .append(relationOid)
                  .append(" relkind=")
                  .append(relationKind)
                  .append(" reltablespace=")
                  .append(relationTablespace)
                  .append(" (current mapping only; reuse and causation not excluded)");
            }
          }
        }
      }
      if (currentDatabase == null || currentDatabaseOid == null) {
        return "WAL relation mapping inconclusive: current database catalog identity unavailable";
      }
      mapping
          .append("\n  current database: ")
          .append(currentDatabase)
          .append(" oid=")
          .append(currentDatabaseOid);
    } catch (Exception lookupFailure) {
      return "WAL relation mapping unavailable: " + lookupFailure.getClass().getSimpleName();
    }
    return mapping
        .append(locatorLimitNote(occurrenceLimitReached, distinctLimitReached, invalidLocators))
        .toString();
  }

  private static String locatorLimitNote(
      boolean occurrenceLimitReached, boolean distinctLimitReached, int invalidLocators) {
    if (!occurrenceLimitReached && !distinctLimitReached && invalidLocators == 0) return "";
    return "\n  mapping coverage is incomplete:"
        + (occurrenceLimitReached ? " occurrence limit reached;" : "")
        + (distinctLimitReached ? " distinct-locator limit reached;" : "")
        + (invalidLocators == 0 ? "" : " invalid locator count=" + invalidLocators + ";")
        + " omitted or invalid locators are inconclusive";
  }

  private static boolean validOid(String oid) {
    if (!oid.matches("[0-9]{1,10}")) return false;
    BigInteger value = new BigInteger(oid);
    return value.compareTo(MAX_UINT32) <= 0;
  }

  private record RelationLocator(String tablespaceOid, String databaseOid, String relfilenode) {}

  private String dumpWal(String start, String end, String xid) throws Exception {
    // The shell program is constant; validated values travel only as positional arguments.
    var arguments =
        new java.util.ArrayList<String>(
            java.util.List.of(
                "sh",
                "-c",
                "set -o pipefail; timeout -k 1 5 pg_waldump \"$@\" 2>&1 | head -c 16384",
                "wal-diagnostic",
                "-p",
                "/var/lib/postgresql/data/pg_wal",
                "-s",
                start,
                "-e",
                end,
                "-n",
                "256"));
    if (xid != null) {
      arguments.add("-x");
      arguments.add(xid);
    }
    var output = container.execInContainer(arguments.toArray(String[]::new));
    return "pipeline_exit=" + output.getExitCode() + "\n" + output.getStdout() + output.getStderr();
  }

  private static BigInteger parseLsn(String lsn) {
    String[] halves = lsn.split("/");
    return new BigInteger(halves[0], 16).shiftLeft(32).add(new BigInteger(halves[1], 16));
  }

  private static boolean validXid(String xid) {
    return xid.matches("[1-9][0-9]{0,19}") && new BigInteger(xid).compareTo(MAX_UINT64) <= 0;
  }

  private static String formatLsn(BigInteger lsn) {
    return lsn.shiftRight(32).toString(16).toUpperCase(java.util.Locale.ROOT)
        + "/"
        + lsn.and(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE))
            .toString(16)
            .toUpperCase(java.util.Locale.ROOT);
  }

  public AccountPostgresIntegrationFixture() {
    this(false);
  }

  public AccountPostgresIntegrationFixture(boolean requireDurablePrimary) {
    this.requireDurablePrimary = requireDurablePrimary;
    String configuredUrl = System.getenv(JDBC_URL_ENV);
    if (configuredUrl != null && !configuredUrl.isBlank()) {
      externalJdbcUrl = validateLoopbackJdbcUrl(configuredUrl);
      container = null;
    } else {
      externalJdbcUrl = null;
      container =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withCommand(postgresCommand(requireDurablePrimary));
    }
  }

  public void start() {
    if (container == null) {
      if (requireDurablePrimary) {
        verifyDurablePrimary();
      }
      return;
    }
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; set " + JDBC_URL_ENV + " to an isolated loopback PostgreSQL URL");
    container.start();
    started = true;
    if (requireDurablePrimary) {
      try {
        verifyDurablePrimary();
      } catch (RuntimeException unsuitable) {
        stop();
        throw unsuitable;
      }
    }
  }

  public void stop() {
    if (started) {
      container.stop();
      started = false;
    }
  }

  public DriverManagerDataSource dataSource() {
    return dataSource(null);
  }

  public DriverManagerDataSource dataSource(String schema) {
    String url;
    String username;
    String password;
    if (container == null) {
      url = externalJdbcUrl;
      username = "postgres";
      password = "";
    } else {
      if (!started) {
        throw new IllegalStateException("The PostgreSQL test fixture has not started");
      }
      url = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
    }
    if (schema != null) {
      if (!schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalArgumentException("Generated test PostgreSQL schema name is invalid");
      }
      url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("connectTimeout", "10");
    connectionProperties.setProperty("socketTimeout", "60");
    if (container == null) {
      // The explicit loopback option is carried through an SSH tunnel, whose remote endpoint
      // is a Unix-domain PostgreSQL socket. Avoid PgJDBC's optional SSL/GSS negotiation against
      // that local forwarding endpoint; Docker-backed fixture behavior remains unchanged.
      connectionProperties.setProperty("sslmode", "disable");
      connectionProperties.setProperty("gssEncMode", "disable");
    }
    dataSource.setConnectionProperties(connectionProperties);
    return dataSource;
  }

  /** Reads and validates the live PostgreSQL settings required by durable commit confirmation. */
  public DurablePrimarySettings verifyDurablePrimary() {
    DurablePrimarySettings settings =
        new JdbcTemplate(dataSource())
            .queryForObject(
                "SELECT pg_is_in_recovery(), current_setting('fsync'), "
                    + "current_setting('synchronous_commit')",
                (result, rowNumber) ->
                    new DurablePrimarySettings(
                        result.getBoolean(1), result.getString(2), result.getString(3)));
    if (settings == null) {
      throw new IllegalStateException("Durable PostgreSQL primary settings are unavailable");
    }
    validateDurablePrimarySettings(
        settings.inRecovery(), settings.fsync(), settings.synchronousCommit());
    return settings;
  }

  static String[] postgresCommand(boolean requireDurablePrimary) {
    if (requireDurablePrimary) {
      return new String[] {"postgres", "-c", "fsync=on", "-c", "synchronous_commit=on"};
    }
    return new String[] {"postgres", "-c", "fsync=off"};
  }

  static void validateDurablePrimarySettings(
      boolean inRecovery, String fsync, String synchronousCommit) {
    if (inRecovery || !"on".equals(fsync) || !"on".equals(synchronousCommit)) {
      throw new IllegalStateException(
          "Durable PostgreSQL primary required (pg_is_in_recovery=false, fsync=on, "
              + "synchronous_commit=on); observed pg_is_in_recovery="
              + inRecovery
              + ", fsync="
              + fsync
              + ", synchronous_commit="
              + synchronousCommit);
    }
  }

  public record DurablePrimarySettings(
      boolean inRecovery, String fsync, String synchronousCommit) {}

  static String validateLoopbackJdbcUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) {
        throw new IllegalArgumentException();
      }
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          JDBC_URL_ENV + " must use a credential-free loopback PostgreSQL URL without options");
    }
    String host = uri.getHost();
    boolean loopbackHost = "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    if (!"postgresql".equals(uri.getScheme())
        || !loopbackHost
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !"/postgres".equals(uri.getRawPath())) {
      throw new IllegalArgumentException(
          JDBC_URL_ENV + " must use a credential-free loopback PostgreSQL URL without options");
    }
    return jdbcUrl;
  }
}
