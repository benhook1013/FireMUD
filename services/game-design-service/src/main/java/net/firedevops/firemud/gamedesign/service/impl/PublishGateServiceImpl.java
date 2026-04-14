package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import org.springframework.stereotype.Service;

@Service
public class PublishGateServiceImpl implements PublishGateService {
  private static final List<PublishParticipantKey> FULL_VERSION_PARTICIPANTS =
      List.of(
          PublishParticipantKey.WORLD_MANAGEMENT,
          PublishParticipantKey.ENTITY_MANAGEMENT,
          PublishParticipantKey.GAME_LOGIC,
          PublishParticipantKey.AUTOMATION_SCRIPTING,
          PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE);

  private static final List<PublishParticipantKey> SCRIPT_PATCH_PARTICIPANTS =
      List.of(
          PublishParticipantKey.AUTOMATION_SCRIPTING,
          PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE);

  private final ControlPlaneDigestService controlPlaneDigestService;
  private final AutomationScriptingClient automationScriptingClient;

  public PublishGateServiceImpl(
      ControlPlaneDigestService controlPlaneDigestService,
      AutomationScriptingClient automationScriptingClient) {
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.automationScriptingClient = automationScriptingClient;
  }

  @Override
  public List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(
      VersionDto version) {
    Objects.requireNonNull(version, "version must not be null");
    return FULL_VERSION_PARTICIPANTS.stream()
        .map(participant -> observeFullVersionParticipant(version, participant))
        .toList();
  }

  @Override
  public List<PublishParticipantDigestDto> collectScriptPatchParticipantDigests(
      VersionDto version) {
    Objects.requireNonNull(version, "version must not be null");
    return SCRIPT_PATCH_PARTICIPANTS.stream()
        .map(participant -> observeScriptPatchParticipant(version, participant))
        .toList();
  }

  @Override
  public void assertGatePassed(
      VersionDto version, List<PublishParticipantDigestDto> participantDigests) {
    if (participantDigests.stream().anyMatch(digest -> !digest.succeeded())) {
      PublishParticipantDigestDto failed =
          participantDigests.stream()
              .filter(digest -> !digest.succeeded())
              .findFirst()
              .orElseThrow();
      throw new IllegalStateException(
          "publish gate failed for "
              + failed.participantKey()
              + ": "
              + (failed.errorMessage() == null ? failed.errorCode() : failed.errorMessage()));
    }
    String expectedScope =
        version.scriptOnly() ? version.scriptPatchVersion() : String.valueOf(version.id());
    participantDigests.forEach(
        digest -> {
          if (!expectedScope.equals(digest.scopeValue())) {
            throw new IllegalStateException(
                "publish gate failed: wrong scope from " + digest.participantKey());
          }
        });
    long distinctAppliedCommitIds =
        participantDigests.stream()
            .map(PublishParticipantDigestDto::appliedCommitId)
            .distinct()
            .count();
    if (distinctAppliedCommitIds != 1) {
      throw new IllegalStateException("publish gate failed: applied commit mismatch");
    }
  }

  private PublishParticipantDigestDto observeFullVersionParticipant(
      VersionDto version, PublishParticipantKey participantKey) {
    if (participantKey == PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE) {
      return toParticipantDigest(
          participantKey, controlPlaneDigestService.getDigestForVersion(version));
    }
    return failedObservation(
        participantKey,
        String.valueOf(version.id()),
        "UNIMPLEMENTED_DIGEST_PARTICIPANT",
        "required digest participant is not implemented for full-version publish");
  }

  private PublishParticipantDigestDto observeScriptPatchParticipant(
      VersionDto version, PublishParticipantKey participantKey) {
    return switch (participantKey) {
      case AUTOMATION_SCRIPTING ->
          automationScriptingClient.getDraftDesignDigestForScriptPatch(
              version.tenantId(), version.scriptPatchVersion());
      case GAME_DESIGN_CONTROL_PLANE ->
          toParticipantDigest(
              participantKey, controlPlaneDigestService.getDigestForScriptPatch(version));
      default ->
          failedObservation(
              participantKey,
              version.scriptPatchVersion(),
              "UNSUPPORTED_SCOPE",
              "participant is not part of the script-patch digest matrix");
    };
  }

  private PublishParticipantDigestDto toParticipantDigest(
      PublishParticipantKey participantKey, DesignControlPlaneDigestDto digest) {
    return new PublishParticipantDigestDto(
        participantKey.name(),
        digest.scopeValue(),
        digest.appliedCommitId(),
        digest.contentDigest(),
        digest.digestSchemaVersion(),
        null,
        null);
  }

  private PublishParticipantDigestDto failedObservation(
      PublishParticipantKey participantKey,
      String scopeValue,
      String errorCode,
      String errorMessage) {
    return new PublishParticipantDigestDto(
        participantKey.name(), scopeValue, null, null, null, errorCode, errorMessage);
  }
}
