package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

/** Narrow SQL failure classification for the standalone canonical preparation owner adapters. */
final class WorldPreparationStorageFailureStatus {
  private WorldPreparationStorageFailureStatus() {}

  static Status.Code from(Throwable failure) {
    Objects.requireNonNull(failure, "failure");
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        Status.Code sqlStatus = fromSqlState(sqlException.getSQLState());
        if (sqlStatus != null) return sqlStatus;
      }
    }

    if (failure instanceof DataIntegrityViolationException) {
      return Status.Code.FAILED_PRECONDITION;
    }
    if (failure instanceof DataAccessResourceFailureException
        || failure instanceof TransientDataAccessException
        || failure instanceof org.jooq.exception.DataAccessException) {
      return Status.Code.UNAVAILABLE;
    }
    return Status.Code.INTERNAL;
  }

  private static Status.Code fromSqlState(String sqlState) {
    if (sqlState == null || sqlState.length() < 2) return null;
    if (sqlState.startsWith("23") || sqlState.startsWith("P0") || "55000".equals(sqlState)) {
      return Status.Code.FAILED_PRECONDITION;
    }
    if ("40001".equals(sqlState) || "40P01".equals(sqlState)) {
      return Status.Code.ABORTED;
    }
    if (sqlState.startsWith("08") || sqlState.startsWith("57") || "55P03".equals(sqlState)) {
      return Status.Code.UNAVAILABLE;
    }
    if (sqlState.startsWith("22") || sqlState.startsWith("42") || sqlState.startsWith("XX")) {
      return Status.Code.INTERNAL;
    }
    return null;
  }
}
