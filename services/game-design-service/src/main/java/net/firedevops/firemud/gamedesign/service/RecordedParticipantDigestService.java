package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.model.PublishType;

public interface RecordedParticipantDigestService {
  void assertMatchesRecordedDigests(
      String tenantId,
      PublishType publishType,
      List<PublishParticipantDigestDto> participantDigests);

  void recordVerifiedDigests(
      String tenantId,
      PublishType publishType,
      String publishWorkflowId,
      List<PublishParticipantDigestDto> participantDigests);
}
