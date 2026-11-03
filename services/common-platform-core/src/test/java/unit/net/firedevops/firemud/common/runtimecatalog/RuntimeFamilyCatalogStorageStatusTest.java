package net.firedevops.firemud.common.runtimecatalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

class RuntimeFamilyCatalogStorageStatusTest {
  @Test
  void sqlStatesOverrideAdapterFallbackWithoutLeakingDatabaseMessages() {
    for (String state : new String[] {"23514", "P0001", "55000"})
      check(state, Status.FAILED_PRECONDITION);
    for (String state : new String[] {"40001", "40P01"}) check(state, Status.ABORTED);
    for (String state : new String[] {"08006", "57014", "55P03"}) check(state, Status.UNAVAILABLE);
    for (String state : new String[] {"22000", "42P01", "XX000"}) check(state, Status.INTERNAL);
  }

  @Test
  void springAndUnknownAdapterFailuresPreserveTheExistingContract() {
    assertThat(classify(new DataIntegrityViolationException("private")))
        .isEqualTo(Status.FAILED_PRECONDITION);
    assertThat(classify(new QueryTimeoutException("private"))).isEqualTo(Status.UNAVAILABLE);
    assertThat(classify(new DataAccessResourceFailureException("private")))
        .isEqualTo(Status.UNAVAILABLE);
    assertThat(classify(new IllegalStateException("private"))).isEqualTo(Status.INTERNAL);
    assertThat(
            RuntimeFamilyCatalogStorageStatus.classify(new RuntimeException(), Status.UNAVAILABLE))
        .isEqualTo(Status.UNAVAILABLE);
    assertThat(classify(new RuntimeException(new SQLException("private", (String) null))))
        .isEqualTo(Status.INTERNAL);
  }

  private static Status classify(Throwable failure) {
    return RuntimeFamilyCatalogStorageStatus.classify(failure, Status.INTERNAL);
  }

  private static void check(String state, Status expected) {
    assertThat(
            RuntimeFamilyCatalogStorageStatus.classify(
                new RuntimeException(new SQLException("private", state)), Status.UNAVAILABLE))
        .isEqualTo(expected);
  }
}
