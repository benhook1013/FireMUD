package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountPostgresIntegrationFixtureTest {
  private static final String V90_DETAIL =
      "phase=create snapshot=4847:4847: own_xid_if_assigned= finalization_xid=4846 receipt_xid="
          + " insert_fence=0/8754AB0 initial_flush=0/8754A70 final_flush=0/8754A70"
          + " observed_db_ms=1791484179743 unchanged_expiry_ms=1791484194098";
  private static final String V92_DETAIL =
      "snapshot=5581:5581: own_xid_if_assigned= finalization_xid=5579 receipt_xid=5580"
          + " insert_fence=0/9AEF178 initial_flush=0/9AEF138 final_flush=0/9AEF138"
          + " unchanged_expiry_ms=1791484201634";

  @Test
  void loggedWalFailureDetailsParseBeforeUnstartedFixtureRefusesCapture() throws Exception {
    var fixture = new AccountPostgresIntegrationFixture(true);
    String configuredUrl = System.getenv(AccountPostgresIntegrationFixture.JDBC_URL_ENV);
    String unavailable =
        configuredUrl == null || configuredUrl.isBlank()
            ? "WAL diagnostic unavailable: owned container is not running"
            : "WAL diagnostic unavailable: external loopback fixture has no owned container WAL access";
    for (String detail : List.of(V90_DETAIL, V92_DETAIL)) {
      assertThat(fixture.describeWalCoverageFailure(detail)).isEqualTo(unavailable);
    }
  }

  @Test
  void malformedOrInjectedWalFailureDetailsAreRejectedBeforeCapture() throws Exception {
    var fixture = new AccountPostgresIntegrationFixture(true);
    for (String detail :
        List.of(
            V90_DETAIL + "; touch /tmp/untrusted",
            V90_DETAIL.replace("0/8754AB0", "$(id)"),
            V90_DETAIL.replace("0/8754AB0", "0/100000000"),
            V90_DETAIL.replace("0/8754AB0", "0/-1"),
            V90_DETAIL.replace("phase=create", "phase=unknown"),
            V92_DETAIL.replace("receipt_xid=5580", "receipt_xid=-1"))) {
      assertThat(fixture.describeWalCoverageFailure(detail))
          .isEqualTo("WAL diagnostic unavailable: unsupported server DETAIL shape");
    }
    assertThat(fixture.describeWalCoverageFailure(null))
        .isEqualTo("WAL diagnostic unavailable: missing or oversized server DETAIL");
    assertThat(fixture.describeWalCoverageFailure("x".repeat(1025)))
        .isEqualTo("WAL diagnostic unavailable: missing or oversized server DETAIL");
  }

  @Test
  void invalidWalIdentitiesSnapshotsAndFenceOrderingAreRejectedBeforeCapture() throws Exception {
    var fixture = new AccountPostgresIntegrationFixture(true);
    for (String detail :
        List.of(
            V90_DETAIL.replace("finalization_xid=4846", "finalization_xid=18446744073709551616"),
            V92_DETAIL.replace("receipt_xid=5580", "receipt_xid=0"),
            V92_DETAIL.replace(
                "own_xid_if_assigned=", "own_xid_if_assigned=18446744073709551616"))) {
      assertThat(fixture.describeWalCoverageFailure(detail))
          .isEqualTo("WAL diagnostic unavailable: invalid XID");
    }
    for (String snapshot :
        List.of("5582:5581:", "0:5581:", "18446744073709551616:18446744073709551616:", "5581::")) {
      assertThat(fixture.describeWalCoverageFailure(V92_DETAIL.replace("5581:5581:", snapshot)))
          .isEqualTo("WAL diagnostic unavailable: invalid snapshot");
    }
    for (String snapshot : List.of("5581:5582:5580", "5581:5582:5582", "5581:5582:5581,")) {
      assertThat(fixture.describeWalCoverageFailure(V92_DETAIL.replace("5581:5581:", snapshot)))
          .isEqualTo("WAL diagnostic unavailable: invalid snapshot XID");
    }
    for (String detail :
        List.of(
            V90_DETAIL.replace("insert_fence=0/8754AB0", "insert_fence=0/0"),
            V90_DETAIL.replace("final_flush=0/8754A70", "final_flush=0/8754A60"),
            V90_DETAIL.replace("final_flush=0/8754A70", "final_flush=0/8754AB0"))) {
      assertThat(fixture.describeWalCoverageFailure(detail))
          .isEqualTo("WAL diagnostic unavailable: inconsistent WAL fences");
    }
  }

  @Test
  void overflowingWalFailureClockCountersCannotReachCapture() {
    var fixture = new AccountPostgresIntegrationFixture(true);
    for (String detail :
        List.of(
            V90_DETAIL.replace("1791484179743", "9223372036854775808"),
            V92_DETAIL.replace("1791484201634", "9223372036854775808"))) {
      assertThatThrownBy(() -> fixture.describeWalCoverageFailure(detail))
          .isInstanceOf(NumberFormatException.class);
    }
  }

  @Test
  void acceptsIpv4AndBracketedIpv6LoopbackUrls() {
    for (String jdbcUrl :
        List.of(
            "jdbc:postgresql://127.0.0.1:55432/postgres",
            "jdbc:postgresql://[::1]:55432/postgres")) {
      assertThat(AccountPostgresIntegrationFixture.validateLoopbackJdbcUrl(jdbcUrl))
          .isEqualTo(jdbcUrl);
    }
  }

  @Test
  void rejectsRemoteCredentialBearingAndOptionBearingUrls() {
    for (String jdbcUrl :
        List.of(
            "jdbc:postgresql://database.example.test:55432/postgres",
            "jdbc:postgresql://user:secret@127.0.0.1:55432/postgres",
            "jdbc:postgresql://127.0.0.1:55432/postgres?options=-csearch_path=public")) {
      assertThatThrownBy(() -> AccountPostgresIntegrationFixture.validateLoopbackJdbcUrl(jdbcUrl))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(AccountPostgresIntegrationFixture.JDBC_URL_ENV);
    }
  }

  @Test
  void invalidMissingOversizedAndLocatorFreeDumpsDoNotAccessDatabase() {
    DataSource dataSource = mock(DataSource.class);
    for (String dump :
        List.of(
            "no relation locator",
            "blkref #0: rel 4294967296/42/99",
            "blkref #0: rel 42/4294967296/99",
            "blkref #0: rel 42/99/4294967296")) {
      assertThat(
              AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(
                  dump, dataSource))
          .contains("inconclusive");
    }
    assertThat(
            AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(null, dataSource))
        .contains("unavailable");
    assertThat(
            AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(
                "x".repeat(49_409), dataSource))
        .contains("unavailable");
    verifyNoInteractions(dataSource);
  }

  @Test
  @SuppressFBWarnings(
      value = {"ODR_OPEN_DATABASE_RESOURCE", "OBL_UNSATISFIED_OBLIGATION"},
      justification =
          "The JDBC values in this test are Mockito mocks; the mapper under test owns and closes its connection, statement, and result set with try-with-resources.")
  void validLocatorUsesOneBoundedPreparedCurrentCatalogQuery() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    stubCatalogRow(
        rows, "firemud", "16384", "1663", "16384", "24600", "24610", "public", "account", "r", "0",
        "24600");

    String mapping =
        AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(
            "blkref #0: rel 1663/16384/24600", dataSource);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    assertThat(sql.getValue())
        .contains("pg_filenode_relation")
        .contains("pg_relation_filenode(c.oid)")
        .contains("pg_database")
        .contains("current_database()");
    verify(statement).setQueryTimeout(5);
    ArgumentCaptor<Integer> indexes = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
    verify(statement, org.mockito.Mockito.times(3)).setString(indexes.capture(), values.capture());
    assertThat(indexes.getAllValues()).containsExactly(1, 2, 3);
    assertThat(values.getAllValues()).containsExactly("1663", "16384", "24600");
    verify(dataSource).getConnection();
    verify(rows).close();
    verify(statement).close();
    verify(connection).close();
    assertThat(mapping)
        .contains("public.account")
        .contains("relation_oid=24610")
        .contains("current mapping only")
        .contains("reuse and causation not excluded")
        .contains("current database: firemud oid=16384");
  }

  @Test
  void crossDatabaseMissingAndChangedCatalogMappingsStayInconclusive() throws Exception {
    for (String[] row :
        List.of(
            new String[] {
              "firemud", "16384", "1663", "16385", "24576", null, null, null, null, null, null
            },
            new String[] {
              "firemud", "16384", "1663", "16384", "24576", null, null, null, null, null, null
            },
            new String[] {
              "firemud", "16384", "1663", "16384", "24576", "24600", "public", "account", "r", "0",
              "24601"
            })) {
      DataSource dataSource = catalogDataSource(row);
      String mapping =
          AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(
              "blkref #0: rel 1663/16384/24576", dataSource);
      assertThat(mapping).contains("inconclusive");
      assertThat(mapping)
          .containsAnyOf("different database", "no current mapping", "identity inconclusive");
    }
  }

  @Test
  void databaseLookupFailureReturnsBoundedUnavailableDiagnostic() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("sensitive connection detail"));

    String diagnostic =
        AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(
            "blkref #0: rel 1663/16384/24576", dataSource);

    assertThat(diagnostic).isEqualTo("WAL relation mapping unavailable: SQLException");
    assertThat(diagnostic).doesNotContain("sensitive connection detail");
  }

  @Test
  void occurrenceAndDistinctLimitsKeepCoverageExplicitlyIncomplete() throws Exception {
    String repeated = "blkref #0: rel 1663/16384/24576\n".repeat(257);
    assertThat(mapWithRows(repeated, 1))
        .contains("occurrence limit reached")
        .contains("mapping coverage is incomplete")
        .contains("omitted or invalid locators are inconclusive");

    StringBuilder manyDistinct = new StringBuilder();
    for (int index = 1; index <= 33; index++) {
      manyDistinct.append("blkref #0: rel 1663/16384/").append(24_000 + index).append('\n');
    }
    assertThat(mapWithRows(manyDistinct.toString(), 32))
        .contains("distinct-locator limit reached")
        .contains("mapping coverage is incomplete")
        .contains("omitted or invalid locators are inconclusive");
  }

  @SuppressFBWarnings(
      value = "OBL_UNSATISFIED_OBLIGATION",
      justification =
          "These JDBC values are Mockito mocks; the mapper under test owns and closes its connection, statement, and result set with try-with-resources.")
  private static String mapWithRows(String dump, int rowCount) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    String node = rowCount == 1 ? "24576" : "24001";
    stubCatalogRow(
        rows, "firemud", "16384", "1663", "16384", node, "24610", "public", "account", "r", "0",
        node);
    String diagnostic =
        AccountPostgresIntegrationFixture.mapWalRelationLocatorsAfterFailure(dump, dataSource);
    verify(statement, org.mockito.Mockito.times(rowCount * 3))
        .setString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    verify(rows).close();
    verify(statement).close();
    verify(connection).close();
    return diagnostic;
  }

  @SuppressFBWarnings(
      value = "OBL_UNSATISFIED_OBLIGATION",
      justification =
          "These JDBC values are Mockito mocks returned through a fake DataSource; the mapper under test owns and closes its connection, statement, and result set with try-with-resources.")
  private static DataSource catalogDataSource(String[] values) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    stubCatalogRow(rows, values);
    return dataSource;
  }

  private static void stubCatalogRow(ResultSet rows, String... values) throws Exception {
    for (int index = 0; index < values.length; index++) {
      when(rows.getString(index + 1)).thenReturn(values[index]);
    }
  }
}
