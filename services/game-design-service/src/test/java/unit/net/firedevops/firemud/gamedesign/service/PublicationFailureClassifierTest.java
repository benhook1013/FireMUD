package unit.net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.gamedesign.service.PublicationFailureClassifier;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.Test;

class PublicationFailureClassifierTest {
  @Test
  void acceptsExactCanonicalParticipantTransientCodes() {
    assertTrue(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new PublishGateFailureException(
                net.firedevops.firemud.gamedesign.model.PublishGateFailureCode
                    .PARTICIPANT_UNAVAILABLE,
                "owner unavailable",
                "PARTICIPANT_UNAVAILABLE")));
    assertTrue(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new PublishGateFailureException(
                net.firedevops.firemud.gamedesign.model.PublishGateFailureCode
                    .PARTICIPANT_UNAVAILABLE,
                "owner timeout",
                "PARTICIPANT_TIMEOUT")));
  }

  @Test
  void ignoresFreeFormTimeoutMessageWhenParticipantCodeIsBlank() {
    assertFalse(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new PublishGateFailureException(
                net.firedevops.firemud.gamedesign.model.PublishGateFailureCode
                    .PARTICIPANT_UNAVAILABLE,
                "request timed out while validating scope",
                "")));
  }

  @Test
  void keepsSemanticParticipantFailureTerminal() {
    assertFalse(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new PublishGateFailureException(
                net.firedevops.firemud.gamedesign.model.PublishGateFailureCode
                    .PARTICIPANT_UNAVAILABLE,
                "unsupported scope",
                "UNSUPPORTED_SCOPE")));
  }

  @Test
  void acceptsOnlyUnavailableOrDeadlineGrpcStatuses() {
    assertTrue(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new StatusRuntimeException(Status.UNAVAILABLE)));
    assertTrue(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new StatusRuntimeException(Status.DEADLINE_EXCEEDED)));
    assertFalse(
        PublicationFailureClassifier.isRetryableParticipantDependencyFailure(
            new StatusRuntimeException(Status.INVALID_ARGUMENT)));
  }
}
