package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishGateService {
  List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId);

  /** Collects owner digests using the canonical binding read from the exact selected operation. */
  List<PublishParticipantDigestDto> collectSelectedFullVersionParticipantDigests(
      VersionDto version,
      PublicationDigestRequestBinding canonicalBinding,
      String publishWorkflowId);

  List<PublishParticipantDigestDto> collectScriptPatchParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId);

  void assertGatePassed(VersionDto version, List<PublishParticipantDigestDto> participantDigests);

  /**
   * Requires the selected full-publication digest schemas, including all six GD source families.
   */
  void assertSelectedGatePassed(
      VersionDto version, List<PublishParticipantDigestDto> participantDigests);
}
