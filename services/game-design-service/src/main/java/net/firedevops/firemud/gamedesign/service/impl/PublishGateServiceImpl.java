package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
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
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationDigestReadService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public final class PublishGateServiceImpl implements PublishGateService {
  private static final Map<String, Integer> SUPPORTED_DIGEST_SCHEMA_VERSIONS =
      Map.of(
          PublishParticipantKey.WORLD_MANAGEMENT.name(), 3,
          PublishParticipantKey.ENTITY_MANAGEMENT.name(), 1,
          PublishParticipantKey.GAME_LOGIC.name(), 1,
          PublishParticipantKey.AUTOMATION_SCRIPTING.name(), 5,
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
  private final SelectedDraftGameLogicReceiptRepository selectedGameLogicReceiptRepository;
  private final SelectedDraftPublicationDigestReadService selectedPublicationDigestReader;
  private final String workloadNamespace;

  @Autowired
  public PublishGateServiceImpl(
      ControlPlaneDigestService controlPlaneDigestService,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      GameLogicClient gameLogicClient,
      AutomationScriptingClient automationScriptingClient,
      DSLContext dsl,
      PlatformTransactionManager transactions,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(
        controlPlaneDigestService,
        worldManagementClient,
        entityManagementClient,
        gameLogicClient,
        automationScriptingClient,
        new SelectedDraftGameLogicReceiptRepository(dsl),
        GrpcPeerIdentity.isValidNamespace(workloadNamespace)
            ? new SelectedDraftPublicationDigestReadService(dsl, transactions, workloadNamespace)
            : null,
        workloadNamespace);
  }

  PublishGateServiceImpl(
      ControlPlaneDigestService controlPlaneDigestService,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      GameLogicClient gameLogicClient,
      AutomationScriptingClient automationScriptingClient,
      SelectedDraftGameLogicReceiptRepository selectedGameLogicReceiptRepository) {
    this(
        controlPlaneDigestService,
        worldManagementClient,
        entityManagementClient,
        gameLogicClient,
        automationScriptingClient,
        selectedGameLogicReceiptRepository,
        null,
        null);
  }

  PublishGateServiceImpl(
      ControlPlaneDigestService controlPlaneDigestService,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      GameLogicClient gameLogicClient,
      AutomationScriptingClient automationScriptingClient,
      SelectedDraftGameLogicReceiptRepository selectedGameLogicReceiptRepository,
      SelectedDraftPublicationDigestReadService selectedPublicationDigestReader,
      String workloadNamespace) {
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
    this.gameLogicClient = gameLogicClient;
    this.automationScriptingClient = automationScriptingClient;
    this.selectedGameLogicReceiptRepository =
        Objects.requireNonNull(selectedGameLogicReceiptRepository);
    this.selectedPublicationDigestReader = selectedPublicationDigestReader;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(
      VersionDto version, String publishRequestId, String publishWorkflowId) {
    Objects.requireNonNull(version, "version must not be null");
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            version.tenantId(), String.valueOf(version.id()), publishRequestId);
    requireWorkflowIdentity(binding, publishWorkflowId);
    return collectFullVersionParticipantDigests(version, binding, false);
  }

  @Override
  public List<PublishParticipantDigestDto> collectSelectedFullVersionParticipantDigests(
      VersionDto version,
      PublicationDigestRequestBinding canonicalBinding,
      String publishWorkflowId) {
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(canonicalBinding, "canonicalBinding must not be null");
    if (version.scriptOnly()
        || version.id() == null
        || canonicalBinding.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION
        || !String.valueOf(version.id()).equals(canonicalBinding.versionId())) {
      throw new IllegalArgumentException(
          "selected publication requires the exact full-version Game Design row binding");
    }
    UUID canonicalTenant;
    try {
      canonicalTenant = UUID.fromString(canonicalBinding.tenantId());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "selected publication tenant must be its canonical UUID", malformed);
    }
    if (!canonicalTenant.toString().equals(canonicalBinding.tenantId())) {
      throw new IllegalArgumentException("selected publication tenant must be its canonical UUID");
    }
    requireWorkflowIdentity(canonicalBinding, publishWorkflowId);
    SelectedDraftPublicationDigestReadService.ReadResult selected = null;
    String failureCode = "PARTICIPANT_UNAVAILABLE";
    try {
      if (selectedPublicationDigestReader == null
          || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
        throw new IllegalStateException("Selected publication digest reader unavailable");
      }
      selected = selectedPublicationDigestReader.read(workloadNamespace, canonicalBinding);
      if (selected == null
          || !Arrays.equals(
              canonicalBinding.canonicalPreimage(), selected.requestBinding().canonicalPreimage())
          || !canonicalBinding.requestDigest().equals(selected.requestDigest())
          || !canonicalBinding
              .derivedWorkflowIdentity()
              .equals(selected.requestBinding().derivedWorkflowIdentity())) {
        throw new IllegalStateException("Selected publication digest request echo differs");
      }
    } catch (RuntimeException unavailable) {
      selected = null;
      failureCode =
          unavailable instanceof StatusRuntimeException grpcFailure
              ? grpcFailure.getStatus().getCode().name()
              : "FAILED_PRECONDITION";
      if (selectedPublicationDigestReader == null
          || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
        failureCode = "PARTICIPANT_UNAVAILABLE";
      }
    }
    var snapshot = selected;
    String snapshotFailureCode = failureCode;
    return FULL_VERSION_PARTICIPANTS.stream()
        .map(
            participant -> {
              if (participant == PublishParticipantKey.WORLD_MANAGEMENT
                  || participant == PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE) {
                if (snapshot == null) {
                  return failedObservation(
                      participant,
                      canonicalBinding.versionId(),
                      null,
                      snapshotFailureCode,
                      "Exact selected publication source evidence unavailable");
                }
                return participant == PublishParticipantKey.WORLD_MANAGEMENT
                    ? snapshot.worldManagementDigest()
                    : toParticipantDigest(participant, version, snapshot.gameDesignDigest());
              }
              return observeFullVersionParticipant(version, canonicalBinding, participant, true);
            })
        .toList();
  }

  private List<PublishParticipantDigestDto> collectFullVersionParticipantDigests(
      VersionDto version, PublicationDigestRequestBinding binding, boolean selectedPublication) {
    return FULL_VERSION_PARTICIPANTS.stream()
        .map(
            participant ->
                observeFullVersionParticipant(version, binding, participant, selectedPublication))
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
    assertGatePassed(version, participantDigests, false);
  }

  @Override
  public void assertSelectedGatePassed(
      VersionDto version, List<PublishParticipantDigestDto> participantDigests) {
    if (version.scriptOnly()) {
      throw new IllegalArgumentException("Selected publication requires a full version");
    }
    assertGatePassed(version, participantDigests, true);
  }

  private void assertGatePassed(
      VersionDto version,
      List<PublishParticipantDigestDto> participantDigests,
      boolean selectedPublication) {
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
          Integer supportedSchemaVersion;
          if (selectedPublication) {
            supportedSchemaVersion =
                AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                    participantKey,
                    AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
          } else {
            supportedSchemaVersion =
                participantKey == null
                    ? null
                    : SUPPORTED_DIGEST_SCHEMA_VERSIONS.get(participantKey);
          }
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
      PublishParticipantKey participantKey,
      boolean selectedPublication) {
    return switch (participantKey) {
      case WORLD_MANAGEMENT -> worldManagementClient.getDraftDesignDigestForVersion(binding);
      case ENTITY_MANAGEMENT -> entityManagementClient.getDraftDesignDigestForVersion(binding);
      case GAME_LOGIC ->
          selectedPublication
              ? observeSelectedGameLogicParticipant(binding)
              : gameLogicClient.getDraftDesignDigestForVersion(binding);
      case AUTOMATION_SCRIPTING ->
          automationScriptingClient.getDraftDesignDigestForVersion(binding);
      case GAME_DESIGN_CONTROL_PLANE ->
          toParticipantDigest(
              participantKey, version, controlPlaneDigestService.getDigestForVersion(version));
    };
  }

  private PublishParticipantDigestDto observeSelectedGameLogicParticipant(
      PublicationDigestRequestBinding binding) {
    Optional<SelectedDraftGameLogicReceipt> receipt;
    try {
      receipt = selectedGameLogicReceiptRepository.readForPublication(binding);
    } catch (org.jooq.exception.DataAccessException unavailable) {
      return failedObservation(
          PublishParticipantKey.GAME_LOGIC,
          binding.versionId(),
          null,
          "UNAVAILABLE",
          "immutable selected Game Logic receipt readback is temporarily unavailable");
    }
    if (receipt.isEmpty()) {
      return failedObservation(
          PublishParticipantKey.GAME_LOGIC,
          binding.versionId(),
          null,
          "PARTICIPANT_UNAVAILABLE",
          "immutable selected Game Logic receipt is not available yet");
    }
    return gameLogicClient.getDraftDesignDigestForVersion(binding, receipt.orElseThrow());
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
