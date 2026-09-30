package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.automationscripting.v1.AutomationAdmissionMode;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionTimedOutException;

final class AutomationControlPlaneSupport {
  private static final String REPLAY_UNAVAILABLE_MESSAGE =
      "Replay service temporarily unavailable; retry with the same control_plane_request_id";
  private static final String REPLAY_INTERNAL_MESSAGE = "Replay failed due to an internal error";

  private AutomationControlPlaneSupport() {}

  static ErrorDetail authorizationError(AdminAuthorizationException ex) {
    return ErrorDetail.newBuilder()
        .setCode("PERMISSION_DENIED")
        .setMessage(ex.getMessage())
        .build();
  }

  static ErrorDetail invalidArgument(String message) {
    return ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(message).build();
  }

  static ErrorDetail failedPrecondition(String message) {
    return ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(message).build();
  }

  static ErrorDetail notFound(String method, String reason) {
    return ErrorDetail.newBuilder()
        .setCode("NOT_FOUND")
        .setMessage(method + " failed: " + reason)
        .build();
  }

  static ErrorDetail replayRuntimeError(Throwable failure) {
    if (isRetryableReplayFailure(failure)) {
      return ErrorDetail.newBuilder()
          .setCode("UNAVAILABLE")
          .setMessage(REPLAY_UNAVAILABLE_MESSAGE)
          .build();
    }
    return ErrorDetail.newBuilder().setCode("INTERNAL").setMessage(REPLAY_INTERNAL_MESSAGE).build();
  }

  private static boolean isRetryableReplayFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof StatusRuntimeException statusFailure
          && isRetryableGrpcStatus(statusFailure.getStatus().getCode())) {
        return true;
      }
      if (current instanceof StatusException statusFailure
          && isRetryableGrpcStatus(statusFailure.getStatus().getCode())) {
        return true;
      }
      if (current instanceof SQLTransientException
          || current instanceof SQLRecoverableException
          || current instanceof TransientDataAccessException
          || current instanceof RecoverableDataAccessException
          || current instanceof ConcurrencyFailureException
          || current instanceof TransactionTimedOutException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      if (current instanceof SQLException sqlException && isRetryableSqlState(sqlException)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isRetryableGrpcStatus(Status.Code statusCode) {
    return statusCode == Status.Code.UNAVAILABLE || statusCode == Status.Code.DEADLINE_EXCEEDED;
  }

  static boolean isRetryableSqlState(SQLException sqlException) {
    String sqlState = sqlException.getSQLState();
    return sqlState != null
        && (sqlState.startsWith("08")
            || sqlState.equals("40001")
            || sqlState.equals("40P01")
            || sqlState.equals("57014")
            || sqlState.equals("55P03"));
  }

  static AutomationAdmissionMode toProtoMode(String mode) {
    return switch (mode) {
      case "NORMAL" -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_NORMAL;
      case "PAUSED_FOR_ROLLBACK" ->
          AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK;
      default -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_UNSPECIFIED;
    };
  }

  static TriggerMode toTriggerMode(String triggerMode) {
    return switch (triggerMode) {
      case "TRIGGER_MODE_CATCH_UP" -> TriggerMode.TRIGGER_MODE_CATCH_UP;
      case "TRIGGER_MODE_NORMAL" -> TriggerMode.TRIGGER_MODE_NORMAL;
      default -> TriggerMode.TRIGGER_MODE_UNSPECIFIED;
    };
  }

  static String requireMode(AutomationAdmissionMode mode) {
    return switch (mode) {
      case AUTOMATION_ADMISSION_MODE_NORMAL -> "NORMAL";
      case AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK -> "PAUSED_FOR_ROLLBACK";
      case UNRECOGNIZED, AUTOMATION_ADMISSION_MODE_UNSPECIFIED ->
          throw new IllegalArgumentException("mode is required");
    };
  }

  static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (normalize(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> "";
    };
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  static String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
}
