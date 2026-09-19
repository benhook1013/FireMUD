package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishGateService {
  List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId);

  List<PublishParticipantDigestDto> collectScriptPatchParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId);

  void assertGatePassed(VersionDto version, List<PublishParticipantDigestDto> participantDigests);
}
