package net.firedevops.firemud.gamedesign.service;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.Locale;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;

/** Classifies only proven participant dependency interruptions as retryable. */
public final class PublicationFailureClassifier {
  private PublicationFailureClassifier() {}

  /**
   * Returns {@code true} only for a transport-level unavailable/deadline status or an explicitly
   * preserved participant-unavailable/timeout result. Semantic gate failures remain terminal.
   */
  public static boolean isRetryableParticipantDependencyFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PublishGateFailureException gateFailure) {
        if (gateFailure.failureCode() != PublishGateFailureCode.PARTICIPANT_UNAVAILABLE) {
          return false;
        }
        return isExplicitTransientParticipantResult(gateFailure.participantFailureCode());
      }
      if (current instanceof StatusRuntimeException statusFailure) {
        return isRetryableGrpcStatus(statusFailure.getStatus().getCode());
      }
      if (current instanceof StatusException statusFailure) {
        return isRetryableGrpcStatus(statusFailure.getStatus().getCode());
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isRetryableGrpcStatus(Status.Code statusCode) {
    return statusCode == Status.Code.UNAVAILABLE || statusCode == Status.Code.DEADLINE_EXCEEDED;
  }

  private static boolean isExplicitTransientParticipantResult(String participantFailureCode) {
    String code = normalize(participantFailureCode);
    if (code.equals("UNAVAILABLE")
        || code.equals("DEADLINE_EXCEEDED")
        || code.equals("PARTICIPANT_UNAVAILABLE")
        || code.equals("PARTICIPANT_TIMEOUT")
        || code.equals("TIMEOUT")) {
      return true;
    }
    return false;
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }
}
