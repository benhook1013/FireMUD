package net.firedevops.firemud.gamedesign.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
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
          PublishParticipantKey.ENTITY_MANAGEMENT.name(), 2,
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
      VersionDto version, String publishRequestId, String publishWorkflowId) {
    Objects.requireNonNull(version, "version must not be null");
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            version.tenantId(), String.valueOf(version.id()), publishRequestId);
    requireWorkflowIdentity(binding, publishWorkflowId);
    return FULL_VERSION_PARTICIPANTS.stream()
        .map(participant -> observeFullVersionParticipant(version, binding, participant))
        .toList();
  }

  @Override
  public List<PublishParticipantDigestDto> collectScriptPatchParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId) {
    Objects.requireNonNull(version, "version must not be null");
    if (!version.scriptOnly() || version.baseVersionId() == null) {
      throw new IllegalArgumentException("script-patch version must include baseVersionId");
    }
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch(
            version.tenantId(),
            String.valueOf(version.baseVersionId()),
            version.scriptPatchVersion(),
            publishRequestId);
    requireWorkflowIdentity(binding, publishWorkflowId);
    return SCRIPT_PATCH_PARTICIPANTS.stream()
        .map(participant -> observeScriptPatchParticipant(version, binding, participant))
        .toList();
  }

  @Override
  public void assertGatePassed(
      VersionDto version, List<PublishParticipantDigestDto> participantDigests) {
    List<PublishParticipantKey> expectedParticipants =
        version.scriptOnly() ? SCRIPT_PATCH_PARTICIPANTS : FULL_VERSION_PARTICIPANTS;
    List<String> expectedParticipantKeyList =
        expectedParticipants.stream().map(PublishParticipantKey::name).toList();
    Set<String> expectedParticipantKeys = new HashSet<>(expectedParticipantKeyList);
    List<String> actualParticipantKeys =
        participantDigests == null
            ? List.of()
            : participantDigests.stream()
                .map(digest -> digest == null ? null : digest.participantKey())
                .toList();
    Set<String> actualParticipantKeySet = new HashSet<>(actualParticipantKeys);
    if (actualParticipantKeys.size() != expectedParticipantKeys.size()
        || actualParticipantKeySet.size() != actualParticipantKeys.size()
        || !actualParticipantKeySet.equals(expectedParticipantKeys)) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.PARTICIPANT_SET_MISMATCH,
          "publish gate failed: expected participant keys "
              + expectedParticipantKeyList
              + " but received "
              + actualParticipantKeys);
    }
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
              + (failed.errorMessage() == null ? failed.errorCode() : failed.errorMessage()),
          failed.errorCode());
    }
    String expectedScope =
        version.scriptOnly() ? version.scriptPatchVersion() : String.valueOf(version.id());
    Long expectedBaseVersionId = version.scriptOnly() ? version.baseVersionId() : null;
    participantDigests.forEach(
        digest -> {
          if (!expectedScope.equals(digest.scopeValue())
              || !Objects.equals(expectedBaseVersionId, digest.baseVersionId())) {
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
      VersionDto version,
      PublicationDigestRequestBinding binding,
      PublishParticipantKey participantKey) {
    return switch (participantKey) {
      case WORLD_MANAGEMENT -> worldManagementClient.getDraftDesignDigestForVersion(binding);
      case ENTITY_MANAGEMENT -> entityManagementClient.getDraftDesignDigestForVersion(binding);
      case GAME_LOGIC -> gameLogicClient.getDraftDesignDigestForVersion(binding);
      case AUTOMATION_SCRIPTING ->
          automationScriptingClient.getDraftDesignDigestForVersion(binding);
      case GAME_DESIGN_CONTROL_PLANE ->
          toParticipantDigest(
              participantKey, version, controlPlaneDigestService.getDigestForVersion(version));
    };
  }

  private void requireWorkflowIdentity(
      PublicationDigestRequestBinding binding, String suppliedWorkflowIdentity) {
    if (!binding.derivedWorkflowIdentity().equals(suppliedWorkflowIdentity)) {
      throw new IllegalArgumentException("publishWorkflowId does not match publication binding");
    }
  }

  private PublishParticipantDigestDto observeScriptPatchParticipant(
      VersionDto version,
      PublicationDigestRequestBinding binding,
      PublishParticipantKey participantKey) {
    return switch (participantKey) {
      case AUTOMATION_SCRIPTING ->
          automationScriptingClient.getDraftDesignDigestForScriptPatch(binding);
      case GAME_DESIGN_CONTROL_PLANE ->
          toParticipantDigest(
              participantKey, version, controlPlaneDigestService.getDigestForScriptPatch(version));
      default ->
          failedObservation(
              participantKey,
              version.scriptPatchVersion(),
              version.baseVersionId(),
              "UNSUPPORTED_SCOPE",
              "participant is not part of the script-patch digest matrix");
    };
  }

  private PublishParticipantDigestDto toParticipantDigest(
      PublishParticipantKey participantKey,
      VersionDto version,
      DesignControlPlaneDigestDto digest) {
    return new PublishParticipantDigestDto(
        participantKey.name(),
        digest.scopeValue(),
        version.scriptOnly() ? version.baseVersionId() : null,
        digest.appliedCommitId(),
        digest.contentDigest(),
        digest.digestSchemaVersion(),
        null,
        null);
  }

  private PublishParticipantDigestDto failedObservation(
      PublishParticipantKey participantKey,
      String scopeValue,
      Long baseVersionId,
      String errorCode,
      String errorMessage) {
    return new PublishParticipantDigestDto(
        participantKey.name(),
        scopeValue,
        baseVersionId,
        null,
        null,
        null,
        errorCode,
        errorMessage);
  }
}
