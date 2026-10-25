package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.worldmanagement.client.GameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Outcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.RequestSelector;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manually composed, unregistered World owner for immutable first-player placement and readback.
 */
public final class CanonicalInitialPlayerLocationService {
  private static final String RAW_SHA256_PREFIX = "sha256:";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String trustedNamespace;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final WorldCanonicalInitialAdmissionHoldRepository holdRepository;
  private final WorldCanonicalInitialPlayerLocationRepository locationRepository;
  private final GameSessionCanonicalInitialAdmissionOwnerClient gameSessionOwner;
  private final EntityReceiptReader entityReceiptReader;

  public CanonicalInitialPlayerLocationService(
      String trustedNamespace,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInitialAdmissionHoldRepository holdRepository,
      WorldCanonicalInitialPlayerLocationRepository locationRepository,
      GameSessionCanonicalInitialAdmissionOwnerClient gameSessionOwner,
      EntityReceiptReader entityReceiptReader) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.trustedNamespace = trustedNamespace;
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    this.locationRepository = Objects.requireNonNull(locationRepository, "locationRepository");
    this.gameSessionOwner = Objects.requireNonNull(gameSessionOwner, "gameSessionOwner");
    this.entityReceiptReader = Objects.requireNonNull(entityReceiptReader, "entityReceiptReader");
  }

  /** Places only a new operation; exact retries return the immutable retained V1019 result. */
  public Result place(CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    Objects.requireNonNull(selection, "selection");
    requireNoAmbientTransaction("World initial-player placement");
    RequestSelector selector = requestSelector(selection);
    Optional<Result> retained =
        locationRepository.readByOperationId(selection.placementOperationId(), selector);
    if (retained.isPresent()) {
      requireExactEntityReceipt(selection);
      return retained.orElseThrow();
    }

    WorldCanonicalInstanceAssociation association = requireAssociation(selection);
    requireExactEntityReceipt(selection);
    HoldIdentity holdIdentity = holdIdentity(selection, association);
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        lifecycleRequest(selection, association);

    // Remote owner reads stay outside every World SQL transaction. A placement requires the
    // retained World COMMITTED hold and the actual authenticated GS COMMITTED terminal; Account
    // settlement remains an independent contract and is not an additional placement gate.
    Optional<WorldCanonicalInitialAdmissionHoldState> maybeHold =
        holdRepository.readState(holdIdentity, lifecycleRequest);
    WorldCanonicalInitialAdmissionHoldState holdState =
        maybeHold.orElseThrow(
            () -> failedPrecondition("Exact retained World initial-admission hold is unavailable"));
    requireCommittedActiveHold(selection, holdIdentity, holdState);

    GameSessionCanonicalInitialAdmissionOwnerProof terminal = gameSessionOwner.read(holdIdentity);
    requireCommittedTerminal(selection, holdIdentity, terminal);

    WorldCanonicalInstanceLifecycleEvidence activeLifecycle = holdState.lifecycleEvidence();
    WorldCanonicalInitialPlayerLocationRepository.Request worldRequest =
        worldRequest(selection, activeLifecycle, terminal);
    Result stored;
    try {
      stored = locationRepository.store(worldRequest);
    } catch (
        WorldCanonicalInitialPlayerLocationRepository.OperationBindingConflictException conflict) {
      // A concurrent exact first writer may have committed while this caller gathered sources.
      // Reconcile only from the original retained bytes and the same caller selectors.
      return locationRepository
          .readByOperationId(selection.placementOperationId(), selector)
          .orElseThrow(() -> conflict);
    }
    if (!stored.retained() || stored.outcome() == Outcome.UNREGISTERED) {
      throw failedPrecondition(
          "World did not retain the placement because its ACTIVE lifecycle or owner bindings changed");
    }
    return stored;
  }

  /**
   * Reads the retained placement and immutable Entity receipt, not current lifecycle or occupancy.
   */
  public Optional<Result> read(CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    Objects.requireNonNull(selection, "selection");
    requireNoAmbientTransaction("World initial-player location read");
    RequestSelector selector = requestSelector(selection);
    Optional<Result> retained =
        locationRepository.readByOperationId(selection.placementOperationId(), selector);
    if (retained.isEmpty()) return Optional.empty();
    requireExactEntityReceipt(selection);
    return retained;
  }

  private ReadPreseededActorAssignmentReceiptResponse requireExactEntityReceipt(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    // This is Entity's immutable historical assignment receipt, not current Account authority or
    // gameplay admission. Game Session must freshly revalidate the full route and policy before
    // PLAY.
    ReadPreseededActorAssignmentReceiptRequest request = entityRequest(selection);
    ReadPreseededActorAssignmentReceiptResponse response = entityReceiptReader.read(request);
    if (response == null
        || response.getSerializedSize() > 16 * 1024
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || !response.getPlacementOperationUuid().equals(selection.placementOperationId().toString())
        || !response.getAssignmentUuid().equals(selection.assignmentId().toString())
        || !response.getCanonicalAccountUuid().equals(selection.canonicalAccountId().toString())
        || !response.getCharacterUuid().equals(selection.characterId().toString())
        || !response.getIntentDigest().equals(selection.assignmentIntentDigest())
        || !response.hasExpectedTarget()
        || !response.getExpectedTarget().equals(selection.expectedTarget())
        || !response.getExpectedTarget().getUnknownFields().asMap().isEmpty()
        || !response.hasCorePayload()
        || !response.getCorePayload().getUnknownFields().asMap().isEmpty()
        || response.getCorePayload().getActorKind()
            != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER
        || !validDisplayName(response.getCorePayload().getDisplayName())
        || response.getStatus()
            != PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED) {
      throw failedPrecondition("Entity returned no exact committed assignment receipt");
    }
    return response;
  }

  private WorldCanonicalInstanceAssociation requireAssociation(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    WorldCanonicalInstanceAssociation association =
        associationRepository
            .readOwnerAssociation(selection.canonicalGameInstanceId())
            .orElseThrow(
                () -> failedPrecondition("Exact canonical World association is unavailable"));
    WorldCanonicalInstanceAssociation.CanonicalIdentity identity = association.identity();
    if (association.isRunOwnedFixture()
        || !trustedNamespace.equals(identity.targetNamespace())
        || !identity.publicProduction()
        || !identity.canonicalTenantId().equals(selection.canonicalTenantId())
        || !identity.canonicalRealmId().equals(selection.realmId())
        || !identity.worldSlug().equals(selection.worldSlug())
        || !identity.playableStateNamespaceId().equals(selection.playableStateNamespaceId())
        || !identity.playableStateScope().equals(selection.playableStateScope())
        || !identity.canonicalGameInstanceId().equals(selection.canonicalGameInstanceId())
        || identity.catalogRevision() != selection.catalogRevision()
        || !association.canonicalVersionId().equals(selection.canonicalVersionId())
        || !association
            .worldPrepareFields()
            .publishedReleaseBundleRef()
            .equals(selection.publishedReleaseBundleRef())) {
      throw failedPrecondition(
          "Entity assignment target differs from the exact retained canonical World association");
    }
    return association;
  }

  private HoldIdentity holdIdentity(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      WorldCanonicalInstanceAssociation association) {
    Request request =
        new Request(
            trustedNamespace,
            selection.canonicalTenantId(),
            selection.worldSlug(),
            selection.realmId(),
            selection.playableStateNamespaceId(),
            selection.playableStateScope(),
            selection.canonicalGameInstanceId(),
            selection.canonicalVersionId(),
            selection.activeLifecycleEpoch(),
            selection.initialAdmissionRequestId(),
            selection.initialAdmissionRequestDigest(),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            selection.catalogRevision(),
            null);
    if (!request
        .canonicalGameInstanceId()
        .equals(association.identity().canonicalGameInstanceId())) {
      throw failedPrecondition("Initial-admission hold target differs from its World association");
    }
    return new HoldIdentity(
        request, selection.initialAdmissionHoldId(), selection.initialAdmissionHoldFence());
  }

  private WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    var descriptor = association.completeLaunchBinding().descriptor();
    var release = association.completeLaunchBinding().evidence().releaseAttestation();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        lifecycleReadRequestId(selection.placementOperationId()),
        trustedNamespace,
        identity.canonicalTenantId(),
        identity.worldSlug(),
        identity.canonicalGameInstanceId(),
        identity.playableStateNamespaceId(),
        identity.playableStateScope(),
        identity.publicProduction(),
        identity.controlPlaneRequestId(),
        association.canonicalVersionId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private void requireCommittedActiveHold(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      HoldIdentity expectedIdentity,
      WorldCanonicalInitialAdmissionHoldState state) {
    if (!expectedIdentity.equals(state.holdIdentity())
        || state.holdStatus() != HoldStatus.COMMITTED
        || !"ACTIVE".equals(state.lifecycleEvidence().lifecycleStatus())
        || state.lifecycleEvidence().lifecycleEpoch() != selection.activeLifecycleEpoch()
        || !state
            .lifecycleEvidence()
            .request()
            .canonicalGameInstanceId()
            .equals(selection.canonicalGameInstanceId())
        || !state
            .lifecycleEvidence()
            .request()
            .canonicalTenantId()
            .equals(selection.canonicalTenantId())
        || !state
            .lifecycleEvidence()
            .request()
            .canonicalVersionId()
            .equals(selection.canonicalVersionId())
        || !state
            .lifecycleEvidence()
            .request()
            .playableStateNamespaceId()
            .equals(selection.playableStateNamespaceId())
        || !state
            .lifecycleEvidence()
            .startLocation()
            .tenantId()
            .equals(selection.canonicalTenantId())
        || !state
            .lifecycleEvidence()
            .startLocation()
            .versionId()
            .equals(selection.canonicalVersionId())) {
      throw failedPrecondition(
          "Initial placement requires the exact retained COMMITTED hold and current ACTIVE lifecycle epoch");
    }
  }

  private void requireCommittedTerminal(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    if (proof == null
        || !expectedIdentity.equals(proof.holdIdentity())
        || proof.outcome() != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
        || proof.committedPointerVersion() == null
        || proof.committedPointerVersion() != selection.committedPointerVersion()
        || proof.auditEventId() == null
        || proof.auditEventId() != selection.auditEventId()
        || !selection.terminalProofDigest().equals(proof.proofDigest())
        || proof.positiveDurableAbort()
        || proof.terminalAt() == null) {
      throw failedPrecondition(
          "Game Session did not return the exact authenticated COMMITTED pointer and audit terminal");
    }
  }

  private WorldCanonicalInitialPlayerLocationRepository.Request worldRequest(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection,
      WorldCanonicalInstanceLifecycleEvidence activeLifecycle,
      GameSessionCanonicalInitialAdmissionOwnerProof terminal) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("schema", "world-canonical-initial-player-location-request/v1");
    request.put("operationId", selection.placementOperationId().toString());
    request.put("canonicalTenantId", selection.canonicalTenantId().toString());
    request.put("playableStateNamespaceId", selection.playableStateNamespaceId().toString());
    request.put("canonicalGameInstanceId", selection.canonicalGameInstanceId().toString());
    request.put("canonicalAccountId", selection.canonicalAccountId().toString());
    request.put("characterId", selection.characterId().toString());
    request.put("entityAssignmentOperationId", selection.assignmentId().toString());
    request.put("entityAssignmentDigest", selection.assignmentIntentDigest());
    request.put("realmId", selection.realmId().toString());
    request.put("worldSlug", selection.worldSlug());
    request.put("playableStateScope", selection.playableStateScope());
    request.put("initialAdmissionHoldId", selection.initialAdmissionHoldId().toString());
    request.put("initialAdmissionHoldFence", selection.initialAdmissionHoldFence().toString());
    request.put("initialAdmissionOrigin", "NO_PRIOR_POINTER");
    request.put("initialAdmissionRequestId", selection.initialAdmissionRequestId());
    request.put("initialAdmissionRequestDigest", selection.initialAdmissionRequestDigest());
    request.put("catalogRevision", Long.toString(selection.catalogRevision()));
    request.put("initialAdmissionOwnerProofId", selection.initialAdmissionRequestId());
    request.put(
        "initialAdmissionOwnerProofDigest",
        terminal.proofDigest().substring(RAW_SHA256_PREFIX.length()));
    request.put("pointerAuditId", Long.toString(terminal.auditEventId()));
    request.put("pointerVersion", Long.toString(terminal.committedPointerVersion()));
    request.put("activeLifecycleEvidence", lifecycleJson(activeLifecycle));
    byte[] requestBytes = canonicalJsonBytes(request);
    byte[] lifecycleBytes = activeLifecycle.canonicalBytes();
    return new WorldCanonicalInitialPlayerLocationRepository.Request(
        selection.placementOperationId(),
        prefixedDigest(requestBytes),
        requestBytes,
        lifecycleBytes);
  }

  private static RequestSelector requestSelector(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    return new RequestSelector(
        selection.placementOperationId(),
        selection.canonicalTenantId(),
        selection.playableStateNamespaceId(),
        selection.canonicalGameInstanceId(),
        selection.canonicalAccountId(),
        selection.characterId(),
        selection.assignmentId(),
        selection.assignmentIntentDigest(),
        selection.realmId(),
        selection.worldSlug(),
        selection.playableStateScope(),
        selection.canonicalVersionId(),
        selection.initialAdmissionHoldId(),
        selection.initialAdmissionHoldFence(),
        selection.initialAdmissionRequestId(),
        selection.initialAdmissionRequestDigest(),
        selection.activeLifecycleEpoch(),
        selection.catalogRevision(),
        selection.initialAdmissionRequestId(),
        selection.terminalProofDigest().substring(RAW_SHA256_PREFIX.length()),
        Long.toString(selection.auditEventId()),
        selection.committedPointerVersion());
  }

  private static ReadPreseededActorAssignmentReceiptRequest entityRequest(
      CanonicalInitialPlayerLocationGrpcCodec.Selection selection) {
    return ReadPreseededActorAssignmentReceiptRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(selection.placementOperationId().toString())
        .setAssignmentUuid(selection.assignmentId().toString())
        .setCanonicalAccountUuid(selection.canonicalAccountId().toString())
        .setCharacterUuid(selection.characterId().toString())
        .setIntentDigest(selection.assignmentIntentDigest())
        .setExpectedTarget(selection.expectedTarget())
        .build();
  }

  private static tools.jackson.databind.JsonNode lifecycleJson(
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    try {
      return JSON.readTree(new String(lifecycle.canonicalBytes(), StandardCharsets.UTF_8));
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalStateException("World lifecycle evidence cannot be encoded", invalid);
    }
  }

  private static byte[] canonicalJsonBytes(Map<String, Object> request) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(request));
    } catch (IOException invalid) {
      throw new IllegalStateException("World initial-location request cannot be encoded", invalid);
    }
  }

  private static String prefixedDigest(byte[] value) {
    try {
      return RAW_SHA256_PREFIX
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static UUID lifecycleReadRequestId(UUID operationId) {
    byte[] name =
        ("firemud-world-initial-location-lifecycle-read/v1/" + operationId)
            .getBytes(StandardCharsets.US_ASCII);
    return UUID.nameUUIDFromBytes(name);
  }

  private static boolean validDisplayName(String value) {
    return value != null
        && !value.isBlank()
        && value.equals(value.trim())
        && value.codePointCount(0, value.length()) <= 100
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static io.grpc.StatusRuntimeException failedPrecondition(String message) {
    return io.grpc.Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException();
  }

  private static void requireNoAmbientTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(operation + " requires independent World owner operations");
    }
  }

  @FunctionalInterface
  public interface EntityReceiptReader {
    ReadPreseededActorAssignmentReceiptResponse read(
        ReadPreseededActorAssignmentReceiptRequest request);
  }
}
