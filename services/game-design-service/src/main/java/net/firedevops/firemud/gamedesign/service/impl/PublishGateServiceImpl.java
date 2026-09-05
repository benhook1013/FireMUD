package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import org.springframework.stereotype.Service;

@Service
public class PublishGateServiceImpl implements PublishGateService {
  private static final Map<String, Integer> SUPPORTED_DIGEST_SCHEMA_VERSIONS =
      Map.of(
          PublishParticipantKey.WORLD_MANAGEMENT.name(), 2,
          PublishParticipantKey.ENTITY_MANAGEMENT.name(), 1,
          PublishParticipantKey.GAME_LOGIC.name(), 1,
          PublishParticipantKey.AUTOMATION_SCRIPTING.name(), 4,
          PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE.name(), 1);
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
  private final WorldManagementClient worldManagementClient;
  private final EntityManagementClient entityManagementClient;
  private final GameLogicClient gameLogicClient;
  private final AutomationScriptingClient automationScriptingClient;

  public PublishGateServiceImpl(
      ControlPlaneDigestService controlPlaneDigestService,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      GameLogicClient gameLogicClient,
      AutomationScriptingClient automationScriptingClient) {
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
    this.gameLogicClient = gameLogicClient;
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
      throw new PublishGateFailureException(
          PublishGateFailureCode.PARTICIPANT_UNAVAILABLE,
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
            throw new PublishGateFailureException(
                PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH,
                "publish gate failed: wrong scope from " + digest.participantKey());
          }
          String participantKey = digest.participantKey();
          Integer supportedSchemaVersion =
              participantKey == null ? null : SUPPORTED_DIGEST_SCHEMA_VERSIONS.get(participantKey);
          if (digest.digestSchemaVersion() == null
              || !digest.digestSchemaVersion().equals(supportedSchemaVersion)) {
            throw new PublishGateFailureException(
                PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA,
                "publish gate failed: unsupported digest schema from " + digest.participantKey());
          }
          if (digest.contentDigest() == null || digest.contentDigest().isBlank()) {
            throw new PublishGateFailureException(
                PublishGateFailureCode.MISSING_CONTENT_DIGEST,
                "publish gate failed: missing content digest from " + digest.participantKey());
          }
          if (digest.appliedCommitId() == null || digest.appliedCommitId().isBlank()) {
            throw new PublishGateFailureException(
                PublishGateFailureCode.MISSING_APPLIED_COMMIT,
                "publish gate failed: missing applied commit from " + digest.participantKey());
          }
        });
    long distinctAppliedCommitIds =
        participantDigests.stream()
            .map(PublishParticipantDigestDto::appliedCommitId)
            .distinct()
            .count();
    if (distinctAppliedCommitIds != 1) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.APPLIED_COMMIT_MISMATCH,
          "publish gate failed: applied commit mismatch");
    }
  }

  private PublishParticipantDigestDto observeFullVersionParticipant(
      VersionDto version, PublishParticipantKey participantKey) {
    return switch (participantKey) {
      case WORLD_MANAGEMENT ->
          worldManagementClient.getDraftDesignDigestForVersion(version.tenantId(), version.id());
      case ENTITY_MANAGEMENT ->
          entityManagementClient.getDraftDesignDigestForVersion(version.tenantId(), version.id());
      case GAME_LOGIC ->
          gameLogicClient.getDraftDesignDigestForVersion(version.tenantId(), version.id());
      case AUTOMATION_SCRIPTING ->
          automationScriptingClient.getDraftDesignDigestForVersion(
              version.tenantId(), version.id());
      case GAME_DESIGN_CONTROL_PLANE ->
          toParticipantDigest(
              participantKey, controlPlaneDigestService.getDigestForVersion(version));
    };
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
