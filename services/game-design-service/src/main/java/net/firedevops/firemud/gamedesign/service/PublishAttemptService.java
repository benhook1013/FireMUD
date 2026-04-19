package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishType;

public interface PublishAttemptService {
  void createAttempt(VersionDto version, PublishType publishType, String publishWorkflowId);

  void recordParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests);

  void markSucceeded(String publishWorkflowId);

  void markFailed(String publishWorkflowId, String failureCode, String failureMessage);
}
