package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.model.PublishType;

public interface PublishAttemptService {
  <T> T executeScriptPatchTransaction(Supplier<T> operation);

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

  void createAttempt(VersionDto version, PublishType publishType, String publishWorkflowId);

  void createFullVersionAttempt(VersionDto version, String publishWorkflowId, String requestDigest);

  void createScriptPatchAttempt(
      VersionDto version, String publishWorkflowId, Long baseVersionId, String requestDigest);

  void recordScriptPatchParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void markScriptPatchSucceeded(String publishWorkflowId);

  void markScriptPatchFailed(String publishWorkflowId, String failureCode, String failureMessage);

  Optional<PublishAttempt> findByPublishWorkflowId(String publishWorkflowId);

  void recordParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void recordFullVersionParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void markSucceeded(String publishWorkflowId);

  void markFullVersionSucceeded(String publishWorkflowId);

  void markFailed(String publishWorkflowId, String failureCode, String failureMessage);

  void markFullVersionFailed(String publishWorkflowId, String failureCode, String failureMessage);
}
