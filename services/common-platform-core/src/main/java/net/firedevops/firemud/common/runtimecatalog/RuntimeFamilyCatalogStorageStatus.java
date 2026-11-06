package net.firedevops.firemud.common.runtimecatalog;

import io.grpc.Status;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

/** Shared catalog transport classification; messages and owner conflict types remain local. */
public final class RuntimeFamilyCatalogStorageStatus {
  private RuntimeFamilyCatalogStorageStatus() {}

  /**
   * The owner supplies the fallback for its persistence adapter without coupling this module to it.
   */
  public static Status classify(Throwable failure, Status unmappedStorageStatus) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        if (state.startsWith("23") || state.startsWith("P0") || state.equals("55000"))
          return Status.FAILED_PRECONDITION;
        if (state.equals("40001") || state.equals("40P01")) return Status.ABORTED;
        if (state.startsWith("08") || state.startsWith("57") || state.equals("55P03"))
          return Status.UNAVAILABLE;
        if (state.startsWith("22") || state.startsWith("42") || state.startsWith("XX"))
          return Status.INTERNAL;
      }
    }
    if (failure instanceof DataIntegrityViolationException) return Status.FAILED_PRECONDITION;
    if (failure instanceof TransientDataAccessException
        || failure instanceof DataAccessResourceFailureException) return Status.UNAVAILABLE;
    return java.util.Objects.requireNonNull(unmappedStorageStatus, "unmappedStorageStatus");
  }
}
