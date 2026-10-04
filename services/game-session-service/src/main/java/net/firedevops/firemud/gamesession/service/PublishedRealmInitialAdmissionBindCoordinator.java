package net.firedevops.firemud.gamesession.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Explicit internal composition of one immutable published realm with the World initial-bind hold.
 */
@Service
@Lazy
public final class PublishedRealmInitialAdmissionBindCoordinator {
  private static final String DIGEST_DOMAIN = "firemud.published-realm-initial-admission-bind.v1";
  private static final String RUNNING = "RUNNING";

  private final PublishedRealmCatalogOwnerService publishedCatalogOwner;
  private final GameInstanceService gameInstanceService;
  private final InitialAdmissionBindOwnerService ownerService;
  private final WorldManagementClient worldManagementClient;

  public PublishedRealmInitialAdmissionBindCoordinator(
      PublishedRealmCatalogOwnerService publishedCatalogOwner,
      GameInstanceService gameInstanceService,
      InitialAdmissionBindOwnerService ownerService,
      WorldManagementClient worldManagementClient) {
    this.publishedCatalogOwner =
        Objects.requireNonNull(publishedCatalogOwner, "publishedCatalogOwner");
    this.gameInstanceService = Objects.requireNonNull(gameInstanceService, "gameInstanceService");
    this.ownerService = Objects.requireNonNull(ownerService, "ownerService");
    this.worldManagementClient =
        Objects.requireNonNull(worldManagementClient, "worldManagementClient");
  }

  /**
   * Runs only when explicitly invoked by an internal owner. Retries reuse the authored launch,
   * immutable published revision, request identity, World hold, and GS owner attempt.
   */
  public Result coordinate(PublishedRealmInitialAdmissionBindCommand command) {
    Objects.requireNonNull(command, "command");
    PublishedRealmCatalogSnapshot snapshot =
        publishedCatalogOwner.materializePublishedSnapshot(
            command.canonicalTenantId(), command.publishedVersionId());
    PublishedRealmCatalogEntry entry =
        snapshot.requireVisibleEntryForAdmission(command.worldSlug(), command.realmSlug());
    RealmEntryPolicy policy = entry.policyEvidence().policy();
    if (snapshot.policySetEvidence().versionId() != command.publishedVersionId()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || entry.namespaceResolution() != PublishedRealmCatalogEntry.NamespaceResolution.RESOLVED) {
      throw unresolved(
          "INITIAL_ADMISSION_PUBLISHED_POLICY_UNSUPPORTED: only an exact visible public SHARED PRESEEDED_ONLY entry with resolved namespace is admissible");
    }
    if (snapshot.sourceGameRowId() != snapshot.tenantId()) {
      throw unresolved(
          "INITIAL_ADMISSION_LAUNCH_TENANT_IDENTITY_UNAVAILABLE: existing authored launch accepts one numeric tenant ID for both the retained local GS key and the Game Design descriptor lookup; a source/local identity adapter is required");
    }

    RunOwnedInitialLaunchResult launch =
        gameInstanceService.startRunOwnedInitialLaunch(
            new StartSessionRequest(
                snapshot.tenantId(),
                command.gameTemplateId(),
                command.initialAdmissionRequestId(),
                command.ownerAccountId()));
    GameInstanceDto target = requireExactActiveLaunch(launch, command, snapshot);
    WorldInstanceLifecycleSnapshot lifecycle =
        requireExactWorldLifecycle(target, command, snapshot);
    if (launch.activeLifecycleEpoch() != lifecycle.getLifecycleEpoch()) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_LIFECYCLE_MISMATCH: ACTIVE epoch differs from authored launch proof");
    }
    long lifecycleEpoch = lifecycle.getLifecycleEpoch();
    String requestDigest = requestDigest(command, snapshot, entry, target, lifecycle);
    InitialAdmissionBindRequest request =
        new InitialAdmissionBindRequest(
            snapshot.tenantId(),
            command.worldSlug(),
            command.realmSlug(),
            command.initialAdmissionRequestId(),
            requestDigest,
            target.id(),
            command.publishedVersionId(),
            lifecycleEpoch,
            new InitialAdmissionBindRequest.PublishedCatalogBinding(
                snapshot.targetNamespace(),
                snapshot.canonicalTenantId(),
                snapshot.catalogRevision()),
            new InitialAdmissionBindRequest.LaunchEvidence(
                command.gameTemplateId(),
                lifecycle.getLaunchDescriptorId(),
                parsePositiveId(lifecycle.getReleaseBundleId(), "releaseBundleId"),
                lifecycle.getPublishedReleaseBundleRef(),
                lifecycle.getVersionStateEpoch()));
    var attempt = ownerService.beginIntent(request);
    requireExactAttempt(attempt, request, entry);

    AcquireInitialAdmissionBindHoldResponse acquire =
        worldManagementClient.acquireInitialAdmissionBindHold(
            snapshot.tenantId(),
            target.id(),
            command.publishedVersionId(),
            lifecycleEpoch,
            command.initialAdmissionRequestId(),
            requestDigest,
            entry.realmId(),
            entry.requirePlayableStateNamespaceId(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            snapshot.catalogRevision());
    if (acquire == null || acquire.hasError() || !acquire.hasHold()) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_HOLD_UNRESOLVED: World did not return the exact hold");
    }
    InitialAdmissionBindHold hold = acquire.getHold();
    InitialAdmissionBindHoldBinding binding =
        requireExactHold(hold, request, entry, snapshot.catalogRevision());

    if (hold.getStatus()
            == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_COMMITTED
        || hold.getStatus()
            == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED) {
      return terminalResult(ownerService.read(binding), binding, attempt);
    }
    if (!isRetryable(hold.getStatus())) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_HOLD_UNRESOLVED: World returned an unknown hold state");
    }

    InitialAdmissionBindAttempt attached = ownerService.attachHold(binding);
    requireExactAttempt(attached, request, entry);
    InitialAdmissionBindOwnerProof proof =
        attached.status()
                == net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status
                    .PENDING
            ? ownerService.commit(binding)
            : ownerService.read(binding);
    return terminalResult(proof, binding, attached);
  }

  private static GameInstanceDto requireExactActiveLaunch(
      RunOwnedInitialLaunchResult launch,
      PublishedRealmInitialAdmissionBindCommand command,
      PublishedRealmCatalogSnapshot snapshot) {
    if (launch == null || launch.gameInstance() == null || launch.activeLifecycleEpoch() <= 0) {
      throw unresolved("INITIAL_ADMISSION_LAUNCH_UNRESOLVED: authored launch did not prove ACTIVE");
    }
    GameInstanceDto target = launch.gameInstance();
    if (target.id() == null
        || target.id() <= 0
        || !Objects.equals(target.tenantId(), snapshot.tenantId())
        || !Objects.equals(target.gameTemplateId(), command.gameTemplateId())
        || !Objects.equals(target.ownerAccountId(), command.ownerAccountId())
        || !Objects.equals(target.versionId(), command.publishedVersionId())
        || target.launchDescriptorId() == null
        || target.launchDescriptorId().isBlank()
        || target.releaseBundleId() == null
        || target.releaseBundleId() <= 0
        || target.versionStateEpoch() == null
        || target.versionStateEpoch() <= 0
        || !RUNNING.equals(target.status())
        || snapshot.policySetEvidence().versionId() != target.versionId()) {
      throw unresolved(
          "INITIAL_ADMISSION_LAUNCH_MISMATCH: authored launch does not match the local tenant, requested template, or exact published version");
    }
    return target;
  }

  private WorldInstanceLifecycleSnapshot requireExactWorldLifecycle(
      GameInstanceDto target,
      PublishedRealmInitialAdmissionBindCommand command,
      PublishedRealmCatalogSnapshot snapshot) {
    final GetWorldInstanceLifecycleResponse response;
    try {
      response = worldManagementClient.getWorldInstanceLifecycle(snapshot.tenantId(), target.id());
    } catch (RuntimeException exception) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_LIFECYCLE_UNAVAILABLE: exact launch readback failed");
    }
    if (response == null || response.hasError() || !response.hasWorldInstance()) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_LIFECYCLE_INVALID: exact launch readback is absent");
    }
    WorldInstanceLifecycleSnapshot lifecycle = response.getWorldInstance();
    if (parsePositiveId(lifecycle.getTenantId(), "tenantId") != snapshot.tenantId()
        || parsePositiveId(lifecycle.getGameInstanceId(), "gameInstanceId") != target.id()
        || parsePositiveId(lifecycle.getGameTemplateId(), "gameTemplateId")
            != command.gameTemplateId()
        || !command.initialAdmissionRequestId().equals(lifecycle.getControlPlaneRequestId())
        || !target.launchDescriptorId().equals(lifecycle.getLaunchDescriptorId())
        || parsePositiveId(lifecycle.getVersionId(), "versionId") != target.versionId()
        || parsePositiveId(lifecycle.getReleaseBundleId(), "releaseBundleId")
            != target.releaseBundleId()
        || lifecycle.getVersionStateEpoch() != target.versionStateEpoch()
        || lifecycle.getLifecycleEpoch() <= 0
        || lifecycle.getStatus()
            != WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE
        || lifecycle.getPublishedReleaseBundleRef() == null
        || lifecycle.getPublishedReleaseBundleRef().isBlank()) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_LIFECYCLE_MISMATCH: World template, descriptor, version, release, or ACTIVE epoch differs from authored launch readback");
    }
    return lifecycle;
  }

  private static String requestDigest(
      PublishedRealmInitialAdmissionBindCommand command,
      PublishedRealmCatalogSnapshot snapshot,
      PublishedRealmCatalogEntry entry,
      GameInstanceDto target,
      WorldInstanceLifecycleSnapshot lifecycle) {
    var evidence = entry.policyEvidence();
    var policySet = snapshot.policySetEvidence();
    return sha256(
        DIGEST_DOMAIN,
        command.initialAdmissionRequestId(),
        snapshot.targetNamespace(),
        Long.toString(snapshot.tenantId()),
        snapshot.canonicalTenantId().toString(),
        Long.toString(snapshot.sourceGameRowId()),
        snapshot.sourceGameTenantKey(),
        snapshot.tenantIdentityProvenanceKind(),
        Long.toString(snapshot.catalogRevision()),
        Long.toString(policySet.versionId()),
        Integer.toString(policySet.versionNumber()),
        policySet.releaseBundleIdentity(),
        policySet.publishWorkflowId(),
        policySet.manifestHash(),
        policySet.policySetDigest(),
        evidence.policyId().toString(),
        evidence.policyDigest(),
        entry.realmId().toString(),
        entry.requirePlayableStateNamespaceId().toString(),
        evidence.policy().stateScope().name(),
        evidence.policy().entryPolicy().name(),
        evidence.policy().worldSlug(),
        evidence.policy().realmSlug(),
        Long.toString(command.gameTemplateId()),
        Long.toString(target.id()),
        target.launchDescriptorId(),
        Long.toString(target.versionId()),
        Long.toString(target.releaseBundleId()),
        lifecycle.getPublishedReleaseBundleRef(),
        Long.toString(target.versionStateEpoch()),
        Long.toString(lifecycle.getLifecycleEpoch()));
  }

  private static String sha256(String... fields) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        for (String field : fields) {
          byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
          output.writeInt(encoded.length);
          output.write(encoded);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "Initial admission request digest could not be computed", exception);
    }
  }

  private static void requireExactAttempt(
      net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt attempt,
      InitialAdmissionBindRequest request,
      PublishedRealmCatalogEntry entry) {
    if (attempt == null
        || attempt.attemptId() == null
        || !"V14_PUBLISHED".equals(attempt.catalogSourceKind())
        || attempt.tenantId() != request.tenantId()
        || !request.initialAdmissionRequestId().equals(attempt.initialAdmissionRequestId())
        || !request.requestDigest().equals(attempt.requestDigest())
        || !entry.realmId().equals(attempt.realmId())
        || !entry.requirePlayableStateNamespaceId().equals(attempt.playableStateNamespaceId())
        || !"SHARED".equals(attempt.playableStateScope())
        || !attempt.expectedNoPriorPointer()
        || attempt.catalogRevision() != request.publishedCatalog().catalogRevision()
        || attempt.gameInstanceId() != request.gameInstanceId()
        || attempt.versionId() != request.versionId()
        || attempt.activeLifecycleEpoch() != request.activeLifecycleEpoch()
        || !Objects.equals(
            attempt.publishedTargetNamespace(), request.publishedCatalog().targetNamespace())
        || !Objects.equals(
            attempt.canonicalTenantId(), request.publishedCatalog().canonicalTenantId())
        || !Objects.equals(attempt.gameTemplateId(), request.launchEvidence().gameTemplateId())
        || !Objects.equals(
            attempt.launchDescriptorId(), request.launchEvidence().launchDescriptorId())
        || !Objects.equals(attempt.releaseBundleId(), request.launchEvidence().releaseBundleId())
        || !Objects.equals(
            attempt.publishedReleaseBundleRef(),
            request.launchEvidence().publishedReleaseBundleRef())
        || !Objects.equals(
            attempt.versionStateEpoch(), request.launchEvidence().versionStateEpoch())) {
      throw unresolved("INITIAL_ADMISSION_ATTEMPT_READBACK_MISMATCH: durable intent changed");
    }
  }

  private static InitialAdmissionBindHoldBinding requireExactHold(
      InitialAdmissionBindHold hold,
      InitialAdmissionBindRequest request,
      PublishedRealmCatalogEntry entry,
      long catalogRevision) {
    if (!isCanonicalUuid(hold.getHoldId())
        || !isCanonicalUuid(hold.getHoldFence())
        || !Long.toString(request.tenantId()).equals(hold.getTenantId())
        || !entry.realmId().toString().equals(hold.getRealmUuid())
        || !entry.playableStateNamespaceId().toString().equals(hold.getPlayableStateNamespaceUuid())
        || hold.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !Long.toString(request.gameInstanceId()).equals(hold.getGameInstanceId())
        || !Long.toString(request.versionId()).equals(hold.getVersionId())
        || hold.getActiveLifecycleEpoch() != request.activeLifecycleEpoch()
        || !request.initialAdmissionRequestId().equals(hold.getInitialAdmissionRequestId())
        || !request.requestDigest().equals(hold.getRequestDigest())
        || !hold.getExpectedNoPriorPointer()
        || hold.getExpectedCatalogRevision() != catalogRevision
        || hold.getStatus()
            == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_UNSPECIFIED) {
      throw unresolved(
          "INITIAL_ADMISSION_WORLD_HOLD_MISMATCH: World returned a different hold tuple");
    }
    return new InitialAdmissionBindHoldBinding(
        hold.getHoldId(),
        hold.getHoldFence(),
        request.tenantId(),
        hold.getRealmUuid(),
        hold.getPlayableStateNamespaceUuid(),
        "SHARED",
        request.gameInstanceId(),
        request.versionId(),
        request.activeLifecycleEpoch(),
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        true,
        catalogRevision);
  }

  private Result terminalResult(
      InitialAdmissionBindOwnerProof proof,
      InitialAdmissionBindHoldBinding binding,
      net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt attempt) {
    if (proof == null
        || !binding.holdId().equals(proof.holdId())
        || !binding.holdFence().equals(proof.holdFence())
        || binding.tenantId() != proof.tenantId()
        || !binding.realmUuid().equals(proof.realmUuid())
        || !binding.playableStateNamespaceUuid().equals(proof.playableStateNamespaceUuid())
        || !binding.playableStateScope().equals(proof.playableStateScope())
        || binding.gameInstanceId() != proof.gameInstanceId()
        || binding.versionId() != proof.versionId()
        || binding.activeLifecycleEpoch() != proof.activeLifecycleEpoch()
        || !binding.initialAdmissionRequestId().equals(proof.initialAdmissionRequestId())
        || !binding.requestDigest().equals(proof.requestDigest())
        || !proof.expectedNoPriorPointer()
        || binding.expectedCatalogRevision() != proof.expectedCatalogRevision()) {
      throw unresolved("INITIAL_ADMISSION_OWNER_PROOF_MISMATCH: owner readback differs from hold");
    }
    if (proof.outcome() == Outcome.COMMITTED
        && Objects.equals(proof.ownerProofId(), attempt.attemptId().toString())
        && proof.pointerAuditId() != null
        && proof.pointerVersion() == 1L
        && proof.requestDigest().equals(proof.pointerAuditRequestDigest())) {
      return new Result(TerminalOutcome.COMMITTED, proof);
    }
    if (proof.outcome() == Outcome.ABORTED
        && proof.futureCommitPrevented()
        && Objects.equals(proof.ownerProofId(), attempt.attemptId().toString())
        && proof.pointerAuditId() == null) {
      return new Result(TerminalOutcome.ABORTED, proof);
    }
    throw unresolved(
        "INITIAL_ADMISSION_OWNER_PROOF_UNRESOLVED: exact terminal owner proof is absent");
  }

  private static boolean isRetryable(InitialAdmissionBindHoldStatus status) {
    return status == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING
        || status
            == InitialAdmissionBindHoldStatus
                .INITIAL_ADMISSION_BIND_HOLD_STATUS_RECONCILIATION_REQUIRED;
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return value != null && java.util.UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static long parsePositiveId(String value, String name) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed > 0 && Long.toString(parsed).equals(value)) {
        return parsed;
      }
    } catch (RuntimeException ignored) {
      // Report only the stable owner failure code below.
    }
    throw unresolved("INITIAL_ADMISSION_WORLD_LIFECYCLE_INVALID: " + name + " is malformed");
  }

  private static IllegalStateException unresolved(String message) {
    return new IllegalStateException(message);
  }

  public enum TerminalOutcome {
    COMMITTED,
    ABORTED
  }

  /** A verified GS owner outcome; World still owns asynchronous hold finalization. */
  public record Result(TerminalOutcome outcome, InitialAdmissionBindOwnerProof ownerProof) {}
}
