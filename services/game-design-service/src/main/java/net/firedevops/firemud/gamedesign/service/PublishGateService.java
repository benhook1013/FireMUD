package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishGateService {
  List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(VersionDto version);

  List<PublishParticipantDigestDto> collectScriptPatchParticipantDigests(VersionDto version);

  void assertGatePassed(VersionDto version, List<PublishParticipantDigestDto> participantDigests);
}
