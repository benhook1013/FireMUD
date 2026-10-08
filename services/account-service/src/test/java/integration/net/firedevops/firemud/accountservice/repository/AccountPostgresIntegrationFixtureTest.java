package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
