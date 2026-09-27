package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;

public interface PublishAttemptService {
  /**
   * Runs the script-patch operation with Spring {@code REQUIRES_NEW} semantics, committing its
   * transaction independently of any caller transaction.
   */
  <T> T executeScriptPatchTransaction(Supplier<T> operation);

  /**
   * Runs the full-version operation with Spring {@code REQUIRES_NEW} semantics, committing its
   * transaction independently of any caller transaction.
   */
  <T> T executeFullVersionTransaction(Supplier<T> operation);

  final class ScriptPatchTransactionException extends RuntimeException {
    public ScriptPatchTransactionException(RuntimeException cause) {
      super("script-patch transaction operation failed", cause);
    }

    public RuntimeException causeException() {
      return (RuntimeException) getCause();
    }
  }

  final class FullVersionTransactionException extends RuntimeException {
    public FullVersionTransactionException(RuntimeException cause) {
      super("full-version transaction operation failed", cause);
    }

    public RuntimeException causeException() {
      return (RuntimeException) getCause();
    }
  }

  void createFullVersionAttempt(VersionDto version, String publishWorkflowId, String requestDigest);

  void createScriptPatchAttempt(
      VersionDto version, String publishWorkflowId, Long baseVersionId, String requestDigest);

  void recordScriptPatchParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void markScriptPatchSucceeded(String publishWorkflowId);

  void markScriptPatchFailed(String publishWorkflowId, String failureCode, String failureMessage);

  Optional<PublishAttempt> findByPublishWorkflowId(String publishWorkflowId);

  void recordFullVersionParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void markFullVersionSucceeded(String publishWorkflowId);

  void markFullVersionFailed(String publishWorkflowId, String failureCode, String failureMessage);
}
