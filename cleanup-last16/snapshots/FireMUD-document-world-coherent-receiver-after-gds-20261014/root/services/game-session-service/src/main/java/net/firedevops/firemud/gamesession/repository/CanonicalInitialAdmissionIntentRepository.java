package net.firedevops.firemud.gamesession.repository;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.SourceBinding;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable Game Session intent persisted before an initial-admission request reaches World. */
public final class CanonicalInitialAdmissionIntentRepository {
  private static final String INTENT_TABLE = "game_session_canonical_initial_admission_intent";
  private static final int REQUEST_BYTES_LIMIT = 16_384;
  private static final int HOLD_IDENTITY_BYTES_LIMIT = 65_536;

  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;
  private final GameSessionCanonicalInstanceLaunchRepository fixtureLaunchRepository;

  public CanonicalInitialAdmissionIntentRepository(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository) {
    this(dsl, catalogRepository, launchRepository, null);
  }

  public CanonicalInitialAdmissionIntentRepository(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameSessionCanonicalInstanceLaunchRepository fixtureLaunchRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.launchRepository = Objects.requireNonNull(launchRepository, "launchRepository");
    this.fixtureLaunchRepository = fixtureLaunchRepository;
  }

  /** Locks both Game Session owner sources and commits exact World request bytes before acquire. */
  public CanonicalInitialAdmissionIntentSnapshot reserve(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    Objects.requireNonNull(holdRequest, "holdRequest");
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    requireAuthority(holdRequest, AuthorityKind.HUMAN);
    return reserveInternal(null, holdRequest, lifecycleRequest);
  }

  /** Persists a fixture intent only after verified V48 launch owners are locked again. */
  public CanonicalInitialAdmissionIntentSnapshot reserveFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      Request holdRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    Objects.requireNonNull(evidence, "evidence").requireVerifiedFixture();
    Objects.requireNonNull(holdRequest, "holdRequest");
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    requireAuthority(holdRequest, AuthorityKind.RUN_OWNED_FIXTURE);
    return reserveInternal(evidence, holdRequest, lifecycleRequest);
  }

  private CanonicalInitialAdmissionIntentSnapshot reserveInternal(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      Request holdRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    requireIntentRequestId(holdRequest.initialAdmissionRequestId());
    byte[] holdBytes = boundedRequestBytes(holdRequest.canonicalRequestBytes(), "hold request");
    byte[] lifecycleBytes =
        boundedRequestBytes(lifecycleRequest.canonicalBytes(), "lifecycle request");
    requireWritableReadCommittedOwnerTransaction();

    LockedSources sources = lockSources(holdRequest, evidence);
    CanonicalInitialAdmissionIntentSnapshot.requireExactBinding(
        holdRequest, lifecycleRequest, sources.binding());

    Record existing =
        findIntent(holdRequest.targetNamespace(), holdRequest.initialAdmissionRequestId(), true);
    if (existing != null) {
      requireExplicitIntentAuthority(existing, holdRequest);
      CanonicalInitialAdmissionIntentSnapshot snapshot = decodeSnapshot(existing);
      requireExactRetry(snapshot, holdBytes, lifecycleBytes, sources.binding());
      return snapshot;
    }

    if (findPendingRealmIntent(holdRequest, true) != null) {
      throw conflict("Another nonterminal initial-admission intent already owns this realm");
    }

    try {
      insertIntent(holdRequest, holdBytes, lifecycleBytes, sources.binding());
    } catch (DataAccessException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw conflict("A competing initial-admission intent already owns this request or realm");
      }
      throw failure;
    }
    Record inserted =
        findIntent(holdRequest.targetNamespace(), holdRequest.initialAdmissionRequestId(), true);
    if (inserted == null) {
      throw new IllegalStateException(
          "Committed initial-admission intent insert readback is missing");
    }
    CanonicalInitialAdmissionIntentSnapshot snapshot = decodeSnapshot(inserted);
    requireExactRetry(snapshot, holdBytes, lifecycleBytes, sources.binding());
    return snapshot;
  }

  /** Attaches only World-issued acquisition identity to an already committed exact intent. */
  public CanonicalInitialAdmissionIntentSnapshot attach(
      Request exactHoldRequest, HoldIdentity holdIdentity) {
    Objects.requireNonNull(exactHoldRequest, "exactHoldRequest");
    requireAuthority(exactHoldRequest, AuthorityKind.HUMAN);
    return attachInternal(null, exactHoldRequest, holdIdentity);
  }

  /** Attaches the World-issued fixture hold identity after re-locking fixture source owners. */
  public CanonicalInitialAdmissionIntentSnapshot attachFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      Request exactHoldRequest,
      HoldIdentity holdIdentity) {
    Objects.requireNonNull(evidence, "evidence").requireVerifiedFixture();
    Objects.requireNonNull(exactHoldRequest, "exactHoldRequest");
    requireAuthority(exactHoldRequest, AuthorityKind.RUN_OWNED_FIXTURE);
    return attachInternal(evidence, exactHoldRequest, holdIdentity);
  }

  private CanonicalInitialAdmissionIntentSnapshot attachInternal(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      Request exactHoldRequest,
      HoldIdentity holdIdentity) {
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    requireIntentRequestId(exactHoldRequest.initialAdmissionRequestId());
    byte[] holdBytes =
        boundedRequestBytes(exactHoldRequest.canonicalRequestBytes(), "hold request");
    byte[] identityBytes = holdIdentity.canonicalBytes();
    if (identityBytes.length == 0 || identityBytes.length > HOLD_IDENTITY_BYTES_LIMIT) {
      throw new IllegalArgumentException(
          "World hold identity exceeds the immutable V28 byte limit");
    }
    if (!Arrays.equals(holdBytes, holdIdentity.canonicalRequestBytes())) {
      throw conflict("World hold identity does not echo the exact requested hold bytes");
    }
    requireWritableReadCommittedOwnerTransaction();

    LockedSources sources = lockSources(exactHoldRequest, evidence);
    Record existing =
        findIntent(
            exactHoldRequest.targetNamespace(), exactHoldRequest.initialAdmissionRequestId(), true);
    if (existing == null) {
      throw conflict("Initial-admission intent must commit before World hold identity attachment");
    }
    requireExplicitIntentAuthority(existing, exactHoldRequest);

    CanonicalInitialAdmissionIntentSnapshot snapshot = decodeSnapshot(existing);
    CanonicalInitialAdmissionIntentSnapshot.requireExactBinding(
        snapshot.holdRequest(), snapshot.lifecycleRequest(), sources.binding());
    requireExactRetry(
        snapshot, holdBytes, snapshot.lifecycleRequest().canonicalBytes(), sources.binding());
    if (!Arrays.equals(
        snapshot.holdRequest().canonicalRequestBytes(), holdIdentity.canonicalRequestBytes())) {
      throw conflict(
          "World hold identity request differs from the retained initial-admission intent");
    }

    if (snapshot.state() != IntentState.PENDING_HOLD) {
      if (snapshot.holdIdentity() != null
          && Arrays.equals(snapshot.holdIdentity().canonicalBytes(), identityBytes)) {
        return snapshot;
      }
      throw conflict(
          "Attached or terminal initial-admission intent cannot accept a changed hold identity");
    }

    int updated =
        dsl.execute(
            "UPDATE "
                + INTENT_TABLE
                + " SET hold_identity_bytes = ?, state = 'HOLD_ATTACHED', "
                + "updated_at = clock_timestamp() "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ? "
                + "AND state = 'PENDING_HOLD' AND hold_identity_bytes IS NULL",
            identityBytes,
            exactHoldRequest.targetNamespace(),
            exactHoldRequest.initialAdmissionRequestId());
    if (updated != 1) {
      throw conflict("Initial-admission intent changed before hold identity attachment");
    }
    Record attached =
        findIntent(
            exactHoldRequest.targetNamespace(), exactHoldRequest.initialAdmissionRequestId(), true);
    if (attached == null) {
      throw new IllegalStateException("Attached initial-admission intent readback is missing");
    }
    CanonicalInitialAdmissionIntentSnapshot result = decodeSnapshot(attached);
    if (result.state() != IntentState.HOLD_ATTACHED
        || result.holdIdentity() == null
        || !Arrays.equals(result.holdIdentity().canonicalBytes(), identityBytes)) {
      throw new IllegalStateException(
          "Attached initial-admission intent readback changed identity");
    }
    return result;
  }

  /** Returns one committed immutable intent readback, without asserting live World hold state. */
  public Optional<CanonicalInitialAdmissionIntentSnapshot> read(
      String targetNamespace, String initialAdmissionRequestId) {
    requireSelector(targetNamespace, initialAdmissionRequestId);
    requireCommittedRead();
    Record row = findIntent(targetNamespace, initialAdmissionRequestId, false);
    return row == null ? Optional.empty() : Optional.of(decodeSnapshot(row));
  }

  private LockedSources lockSources(
      Request request, RunOwnedInitialAdmissionLaunchEvidence evidence) {
    if (request.authorityKind() == AuthorityKind.RUN_OWNED_FIXTURE) {
      if (evidence == null || fixtureLaunchRepository == null) {
        throw conflict("Verified fixture owner evidence is required for fixture intent storage");
      }
      evidence.requireVerifiedFixture();
      var launch = fixtureLaunchRepository.lockVerifiedFixtureLaunchInOwnerTransaction(evidence);
      var target = CanonicalInitialAdmissionLaunchTarget.verifiedFixture(evidence, launch);
      requireFixtureRequestMatches(request, target, launch);
      return new LockedSources(fixtureSourceBinding(evidence, launch));
    }
    if (evidence != null) {
      throw conflict("Human initial-admission intent cannot consume fixture evidence");
    }
    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository.lockInitialAdmissionTarget(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.expectedCatalogRevision());
    CanonicalInitialAdmissionLaunchTarget launch =
        launchRepository.lockForInitialAdmission(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.canonicalGameInstanceId());
    SourceBinding source = sourceBinding(catalog, launch);
    return new LockedSources(source);
  }

  private static SourceBinding sourceBinding(
      CanonicalRealmCatalogSnapshot catalog, CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    var descriptor = association.launchBindingEvidence().descriptor();
    var release = association.launchBindingEvidence().releaseAttestation();
    return new SourceBinding(
        catalog.targetNamespace(),
        catalog.tenantId(),
        catalog.worldSlug(),
        catalog.realmId(),
        catalog.playableStateNamespaceId(),
        catalog.stateScope(),
        catalog.visible(),
        catalog.publicProduction(),
        catalog.catalogRevision(),
        catalog.creationRequestId(),
        catalog.requestDigest(),
        catalog.receiptDigest(),
        association.tenantAssociationOperationId(),
        association.gameSessionTenantId(),
        association.gameInstanceUuid(),
        launch.gameInstanceId(),
        launch.canonicalVersionId(),
        launch.runtimeVersionId(),
        association.controlPlaneRequestId(),
        association.launchDescriptorId(),
        association.capturedStartingRowVersion(),
        association.currentRowVersion(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private static SourceBinding fixtureSourceBinding(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch launch) {
    var catalog = launch.catalog().snapshot();
    var association = launch.catalog().association();
    var descriptor = fixtureDescriptor(evidence);
    return new SourceBinding(
        catalog.targetNamespace(),
        catalog.tenantId(),
        catalog.worldSlug(),
        catalog.realmId(),
        catalog.playableStateNamespaceId(),
        catalog.stateScope(),
        catalog.visible(),
        catalog.publicProduction(),
        catalog.catalogRevision(),
        catalog.creationRequestId(),
        catalog.requestDigest(),
        catalog.receiptDigest(),
        association.associationOperationId(),
        association.gameSessionTenantId(),
        launch.gameInstanceUuid(),
        launch.gameInstanceId(),
        UUID.fromString(evidence.publishedReleaseBundle().getCanonicalVersionId()),
        evidence.launchDescriptor().getVersionId(),
        evidence.fixtureCapability().operationId(),
        evidence.launchDescriptor().getLaunchDescriptorId(),
        0L,
        launch.rowVersion(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        null,
        AuthorityKind.RUN_OWNED_FIXTURE,
        evidence.fixturePolicySeal().evidenceDigest(),
        Boolean.FALSE);
  }

  private static AuthoredWorldLaunchDescriptorEvidence fixtureDescriptor(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    evidence.requireVerifiedFixture();
    var source = evidence.sourceEvidence();
    var capability = evidence.fixtureCapability();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            source.targetNamespace(),
            capability.operationId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            capability.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
        request, evidence.launchDescriptor());
  }

  private static void requireFixtureRequestMatches(
      Request request,
      CanonicalInitialAdmissionLaunchTarget target,
      GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch launch) {
    var catalog = launch.catalog().snapshot();
    if (!request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.worldSlug().equals(catalog.worldSlug())
        || !request.realmId().equals(catalog.realmId())
        || !request.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !request.playableStateScope().equals(catalog.stateScope())
        || request.expectedCatalogRevision() != catalog.catalogRevision()
        || !request.canonicalGameInstanceId().equals(launch.gameInstanceUuid())
        || !request.canonicalVersionId().equals(target.canonicalVersionId())
        || request.authorityKind() != target.authorityKind()
        || !Objects.equals(request.fixtureProvenanceDigest(), target.fixtureProvenanceDigest())
        || !Boolean.FALSE.equals(target.requiresCharacterSelection())) {
      throw conflict("Fixture World request differs from the locked verified launch owners");
    }
  }

  private void insertIntent(
      Request request, byte[] holdBytes, byte[] lifecycleBytes, SourceBinding source) {
    dsl.execute(
        "INSERT INTO "
            + INTENT_TABLE
            + " (target_namespace, initial_admission_request_id, request_digest, "
            + "canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id, "
            + "playable_state_scope, catalog_visible, catalog_public_production, catalog_revision, "
            + "catalog_creation_request_id, catalog_request_digest, catalog_receipt_digest, "
            + "tenant_association_operation_id, game_session_tenant_id, canonical_game_instance_id, "
            + "game_instance_id, canonical_version_id, runtime_version_id, control_plane_request_id, "
            + "launch_descriptor_id, captured_starting_row_version, current_row_version, "
            + "descriptor_request_digest, descriptor_result_digest, release_attestation_digest, "
            + "source_authority_kind, source_fixture_provenance_digest, source_requires_character_selection, "
            + "canonical_hold_request_bytes, canonical_lifecycle_request_bytes, hold_identity_bytes, "
            + "state, created_at, updated_at, terminal_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, clock_timestamp(), clock_timestamp(), NULL)",
        request.targetNamespace(),
        request.initialAdmissionRequestId(),
        request.initialAdmissionRequestDigest(),
        source.canonicalTenantId(),
        source.worldSlug(),
        source.realmId(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.catalogVisible(),
        source.catalogPublicProduction(),
        source.catalogRevision(),
        source.catalogCreationRequestId(),
        source.catalogRequestDigest(),
        source.catalogReceiptDigest(),
        source.tenantAssociationOperationId(),
        source.gameSessionTenantId(),
        source.canonicalGameInstanceId(),
        source.gameInstanceId(),
        source.canonicalVersionId(),
        source.runtimeVersionId(),
        source.controlPlaneRequestId(),
        source.launchDescriptorId(),
        source.capturedStartingRowVersion(),
        source.currentRowVersion(),
        source.descriptorRequestDigest(),
        source.descriptorResultDigest(),
        source.releaseAttestationDigest(),
        source.authorityKind().name(),
        source.fixtureProvenanceDigest(),
        source.requiresCharacterSelection(),
        holdBytes,
        lifecycleBytes,
        IntentState.PENDING_HOLD.name());
  }

  private Record findIntent(String targetNamespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + INTENT_TABLE
            + " WHERE target_namespace = ? AND initial_admission_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        targetNamespace,
        requestId);
  }

  private Record findPendingRealmIntent(Request request, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + INTENT_TABLE
            + " WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? "
            + "AND state IN ('PENDING_HOLD', 'HOLD_ATTACHED') LIMIT 1"
            + (forUpdate ? " FOR UPDATE" : ""),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.realmId());
  }

  private CanonicalInitialAdmissionIntentSnapshot decodeSnapshot(Record row) {
    try {
      String targetNamespace = required(row, "target_namespace", String.class);
      String requestId = required(row, "initial_admission_request_id", String.class);
      String requestDigest = required(row, "request_digest", String.class);
      byte[] holdBytes = required(row, "canonical_hold_request_bytes", byte[].class).clone();
      byte[] lifecycleBytes =
          required(row, "canonical_lifecycle_request_bytes", byte[].class).clone();
      Request holdRequest = Request.fromStored(holdBytes);
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
          WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(lifecycleBytes);
      if (!targetNamespace.equals(holdRequest.targetNamespace())
          || !requestId.equals(holdRequest.initialAdmissionRequestId())
          || !requestDigest.equals(holdRequest.initialAdmissionRequestDigest())) {
        throw new IllegalArgumentException(
            "Persisted request keys or digest differ from canonical bytes");
      }
      byte[] identityBytes = row.get("hold_identity_bytes", byte[].class);
      HoldIdentity identity =
          identityBytes == null ? null : HoldIdentity.fromStored(identityBytes.clone());
      SourceBinding source = sourceBindingFromRow(row);
      IntentState state = IntentState.valueOf(required(row, "state", String.class));
      OffsetDateTime terminalAtValue = row.get("terminal_at", OffsetDateTime.class);
      CanonicalInitialAdmissionIntentSnapshot snapshot =
          new CanonicalInitialAdmissionIntentSnapshot(
              holdRequest,
              lifecycleRequest,
              state,
              identity,
              source,
              required(row, "created_at", OffsetDateTime.class).toInstant(),
              required(row, "updated_at", OffsetDateTime.class).toInstant(),
              terminalAtValue == null ? null : terminalAtValue.toInstant());
      if (holdBytes.length > REQUEST_BYTES_LIMIT
          || lifecycleBytes.length > REQUEST_BYTES_LIMIT
          || (identityBytes != null && identityBytes.length > HOLD_IDENTITY_BYTES_LIMIT)) {
        throw new IllegalArgumentException("Persisted canonical request bytes exceed V28 limits");
      }
      return snapshot;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalStateException stateFailure
          && stateFailure.getMessage() != null
          && stateFailure.getMessage().startsWith("Persisted canonical initial-admission intent")) {
        throw stateFailure;
      }
      throw new IllegalStateException(
          "Persisted canonical initial-admission intent is malformed", malformed);
    }
  }

  private static SourceBinding sourceBindingFromRow(Record row) {
    Request retainedRequest =
        Request.fromStored(required(row, "canonical_hold_request_bytes", byte[].class));
    String storedAuthority = row.get("source_authority_kind", String.class);
    AuthorityKind authorityKind =
        storedAuthority == null
            ? retainedRequest.authorityKind()
            : AuthorityKind.valueOf(storedAuthority);
    String fixtureDigest = row.get("source_fixture_provenance_digest", String.class);
    Boolean requiresCharacterSelection =
        row.get("source_requires_character_selection", Boolean.class);
    if (authorityKind != retainedRequest.authorityKind()
        || !Objects.equals(fixtureDigest, retainedRequest.fixtureProvenanceDigest())
        || (storedAuthority == null && authorityKind != AuthorityKind.HUMAN)) {
      throw new IllegalStateException(
          "Persisted initial-admission intent source discriminator differs from its canonical request");
    }
    return new SourceBinding(
        required(row, "target_namespace", String.class),
        required(row, "canonical_tenant_id", UUID.class),
        required(row, "world_slug", String.class),
        required(row, "realm_id", UUID.class),
        required(row, "playable_state_namespace_id", UUID.class),
        required(row, "playable_state_scope", String.class),
        required(row, "catalog_visible", Boolean.class),
        required(row, "catalog_public_production", Boolean.class),
        required(row, "catalog_revision", Long.class),
        required(row, "catalog_creation_request_id", UUID.class),
        required(row, "catalog_request_digest", String.class),
        required(row, "catalog_receipt_digest", String.class),
        required(row, "tenant_association_operation_id", UUID.class),
        required(row, "game_session_tenant_id", Long.class),
        required(row, "canonical_game_instance_id", UUID.class),
        required(row, "game_instance_id", Long.class),
        required(row, "canonical_version_id", UUID.class),
        required(row, "runtime_version_id", Long.class),
        required(row, "control_plane_request_id", String.class),
        required(row, "launch_descriptor_id", String.class),
        required(row, "captured_starting_row_version", Long.class),
        required(row, "current_row_version", Long.class),
        required(row, "descriptor_request_digest", String.class),
        required(row, "descriptor_result_digest", String.class),
        row.get("release_attestation_digest", String.class),
        authorityKind,
        fixtureDigest,
        requiresCharacterSelection);
  }

  private static void requireExplicitIntentAuthority(Record row, Request request) {
    String kind = row.get("source_authority_kind", String.class);
    String digest = row.get("source_fixture_provenance_digest", String.class);
    Boolean selection = row.get("source_requires_character_selection", Boolean.class);
    if (kind == null
        || !request.authorityKind().name().equals(kind)
        || !Objects.equals(request.fixtureProvenanceDigest(), digest)
        || (request.authorityKind() == AuthorityKind.HUMAN && selection != null)
        || (request.authorityKind() == AuthorityKind.RUN_OWNED_FIXTURE
            && !Boolean.FALSE.equals(selection))) {
      throw conflict(
          "Retained initial-admission intent lacks the exact explicit authority for a new hold transition");
    }
  }

  private static void requireExactRetry(
      CanonicalInitialAdmissionIntentSnapshot snapshot,
      byte[] expectedHoldBytes,
      byte[] expectedLifecycleBytes,
      SourceBinding currentSource) {
    if (!Arrays.equals(snapshot.holdRequest().canonicalRequestBytes(), expectedHoldBytes)
        || !Arrays.equals(snapshot.lifecycleRequest().canonicalBytes(), expectedLifecycleBytes)
        || !matchesRetainedSourceBinding(snapshot.sourceBinding(), currentSource)) {
      throw conflict(
          "Initial-admission retry differs from its retained request or locked owner source binding");
    }
  }

  /** Checks immutable source equality while allowing only a monotonic live runtime-row advance. */
  public static boolean matchesRetainedSourceBinding(
      SourceBinding retained, SourceBinding current) {
    Objects.requireNonNull(retained, "retained");
    Objects.requireNonNull(current, "current");
    return current.currentRowVersion() >= retained.currentRowVersion()
        && current.capturedStartingRowVersion() == retained.capturedStartingRowVersion()
        && retained.equals(withCurrentRowVersion(current, retained.currentRowVersion()));
  }

  private static SourceBinding withCurrentRowVersion(SourceBinding source, long rowVersion) {
    return new SourceBinding(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.worldSlug(),
        source.realmId(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.catalogVisible(),
        source.catalogPublicProduction(),
        source.catalogRevision(),
        source.catalogCreationRequestId(),
        source.catalogRequestDigest(),
        source.catalogReceiptDigest(),
        source.tenantAssociationOperationId(),
        source.gameSessionTenantId(),
        source.canonicalGameInstanceId(),
        source.gameInstanceId(),
        source.canonicalVersionId(),
        source.runtimeVersionId(),
        source.controlPlaneRequestId(),
        source.launchDescriptorId(),
        source.capturedStartingRowVersion(),
        rowVersion,
        source.descriptorRequestDigest(),
        source.descriptorResultDigest(),
        source.releaseAttestationDigest(),
        source.authorityKind(),
        source.fixtureProvenanceDigest(),
        source.requiresCharacterSelection());
  }

  private static byte[] boundedRequestBytes(byte[] bytes, String label) {
    Objects.requireNonNull(bytes, label + " bytes");
    if (bytes.length == 0 || bytes.length > REQUEST_BYTES_LIMIT) {
      throw new IllegalArgumentException(
          "Canonical " + label + " exceeds the immutable V28 byte limit");
    }
    return bytes.clone();
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical initial-admission intent mutation requires a writable owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Canonical initial-admission intent DSL connection must join the owner transaction");
          }
          if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Canonical initial-admission intent mutation requires READ COMMITTED isolation");
          }
          return null;
        });
    Record readOnly = dsl.fetchOne("SHOW transaction_read_only");
    if (readOnly == null || !"off".equalsIgnoreCase(readOnly.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Canonical initial-admission intent mutation requires a writable transaction");
    }
  }

  private static void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical initial-admission intent read requires a committed owner read");
    }
  }

  private static void requireSelector(String targetNamespace, String requestId) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be a canonical workload namespace");
    }
    requireIntentRequestId(requestId);
  }

  private static void requireIntentRequestId(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 120
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Initial-admission request ID must fit the immutable V28 120-character intent column");
    }
  }

  private static void requireAuthority(Request request, AuthorityKind expected) {
    if (request.authorityKind() != expected) {
      throw conflict(
          "Initial-admission intent authority must use the matching typed owner method");
    }
  }

  private static <T> T required(Record row, String column, Class<T> type) {
    T value = row.get(column, type);
    if (value == null) {
      throw new IllegalStateException(
          "Persisted canonical initial-admission intent is missing " + column);
    }
    return value;
  }

  private static CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException
      conflict(String message) {
    return new CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException(
        message);
  }

  private record LockedSources(SourceBinding binding) {}
}
