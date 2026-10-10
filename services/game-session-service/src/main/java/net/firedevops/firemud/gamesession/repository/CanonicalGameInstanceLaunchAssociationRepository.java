package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.InvalidProtocolBufferException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.Resolved;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Explicit Game Session owner persistence for the exact launch-to-instance binding. */
public final class CanonicalGameInstanceLaunchAssociationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ASSOCIATION_TABLE = "game_session_canonical_instance_launch";

  private final DSLContext dsl;
  private final String workloadNamespace;

  public CanonicalGameInstanceLaunchAssociationRepository(
      DSLContext dsl, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Captures an existing STARTING owner row and its exact launch binding in the same writable READ
   * COMMITTED transaction. This method does not allocate or create a runtime row.
   */
  public CanonicalGameInstanceLaunchAssociation capture(
      FreshGameSessionTenantAssociation tenantAssociation,
      long gameInstanceId,
      CompleteLaunchBindingEvidence launchBindingEvidence) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(tenantAssociation, "tenantAssociation");
    requirePositive(gameInstanceId, "gameInstanceId");
    ValidatedBinding binding = validateBinding(launchBindingEvidence);
    if (!workloadNamespace.equals(binding.descriptor().targetNamespace())) {
      throw new IllegalArgumentException("Launch binding targets another Game Session namespace");
    }

    RuntimeTenantIdentityEvidence source = tenantAssociation.sourceEvidence();
    requireFreshSourceIdentity(tenantAssociation, source);
    if (!binding.descriptor().canonicalTenantId().equals(source.canonicalTenantId())) {
      throw new IllegalArgumentException("Launch binding does not match its fresh tenant owner");
    }

    lockRequest(binding.descriptor().controlPlaneRequestId());
    FreshTenantOwnerMapping mapping = requireFreshTenantMapping(tenantAssociation);
    GameInstanceRow runtime =
        requireLockedRuntime(tenantAssociation.legacyGameSessionTenantId(), gameInstanceId);
    RealmOwnerProjection realm = requirePublicSharedRealm(binding.descriptor());
    CanonicalGameInstanceLaunchAssociation existing =
        findByRequest(binding.descriptor().controlPlaneRequestId(), true)
            .map(this::toAssociation)
            .orElse(null);
    if (existing != null) {
      requireExactRetry(
          existing, tenantAssociation, gameInstanceId, runtime, binding.evidence(), mapping, realm);
      return existing;
    }

    requireStartingRuntime(runtime, binding.evidence());
    String evidenceJson = serializeEvidence(binding.evidence());
    RuntimeTenantIdentityEvidence sourceEvidence = tenantAssociation.sourceEvidence();
    dsl.execute(
        "INSERT INTO "
            + ASSOCIATION_TABLE
            + " (target_namespace, control_plane_request_id, game_session_tenant_id, "
            + "canonical_tenant_id, tenant_association_operation_id, tenant_association_request_id, "
            + "tenant_source_schema_version, tenant_source_target_namespace, "
            + "tenant_source_request_id, tenant_source_canonical_tenant_id, "
            + "tenant_source_game_row_id, tenant_source_game_tenant_key, "
            + "tenant_source_provenance_kind, tenant_association_kind, game_instance_id, "
            + "game_instance_uuid, canonical_realm_id, world_slug, playable_state_namespace_id, playable_state_scope, "
            + "public_production, captured_starting_row_version, game_template_id, "
            + "launch_descriptor_id, version_id, release_bundle_id, generation_config_revision, "
            + "version_state_epoch, complete_launch_binding_evidence) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND', ?, ?, ?, ?, ?, "
            + "'SHARED', TRUE, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
        workloadNamespace,
        binding.descriptor().controlPlaneRequestId(),
        tenantAssociation.legacyGameSessionTenantId(),
        sourceEvidence.canonicalTenantId(),
        mapping.operationId(),
        sourceEvidence.requestId(),
        sourceEvidence.schemaVersion(),
        sourceEvidence.targetNamespace(),
        sourceEvidence.requestId(),
        sourceEvidence.canonicalTenantId(),
        sourceEvidence.sourceGameRowId(),
        sourceEvidence.sourceGameTenantKey(),
        sourceEvidence.provenanceKind(),
        gameInstanceId,
        runtime.gameInstanceUuid(),
        realm.realmId(),
        binding.descriptor().worldSlug(),
        realm.playableStateNamespaceId(),
        runtime.rowVersion(),
        binding.descriptor().gameTemplateId(),
        binding.descriptor().launchDescriptorId(),
        binding.descriptor().versionId(),
        binding.descriptor().releaseBundleId(),
        binding.descriptor().generationConfigRevision(),
        binding.descriptor().versionStateEpoch(),
        evidenceJson);

    CanonicalGameInstanceLaunchAssociation inserted =
        findByRequest(binding.descriptor().controlPlaneRequestId(), true)
            .map(this::toAssociation)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical launch association insert readback is missing"));
    requireExactRetry(
        inserted, tenantAssociation, gameInstanceId, runtime, binding.evidence(), mapping, realm);
    return inserted;
  }

  /** Serializes a STARTING-row producer on the exact namespace/request key used by capture. */
  public void lockRequestForStartingGameInstance(String controlPlaneRequestId) {
    requireWritableReadCommittedOwnerTransaction();
    requireRequestId(controlPlaneRequestId);
    lockRequest(controlPlaneRequestId);
  }

  /**
   * Reads one committed immutable owner association plus the transient current GameInstance row.
   */
  public Optional<CanonicalGameInstanceLaunchAssociation> read(String controlPlaneRequestId) {
    requireOutsideTransaction();
    requireRequestId(controlPlaneRequestId);
    return findByRequest(controlPlaneRequestId, false).map(this::toAssociation);
  }

  /**
   * Reads a STARTING association only when its original pending StartSession owner attempt and both
   * immutable evidence pins are still present and exact. This returns read evidence, never a claim
   * or continuation capability.
   */
  public Optional<OriginalStartSessionAssociation> readOriginalStartSessionAssociation(
      net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence
              .Request
          selector) {
    requireOutsideTransaction();
    Objects.requireNonNull(selector, "selector");
    if (!workloadNamespace.equals(selector.targetNamespace())) {
      throw new IllegalArgumentException(
          "Canonical launch association selector targets another Game Session namespace");
    }
    requireRequestId(selector.controlPlaneRequestId());
    Record row = findOriginalStartSessionAssociation(selector);
    if (row == null) {
      return Optional.empty();
    }
    try {
      OriginalStartSessionAssociation result = decodeOriginalStartSessionAssociation(row);
      requireExactOriginalStartSessionSelector(selector, result.association());
      return Optional.of(result);
    } catch (LaunchAssociationOwnerReadMismatchException mismatch) {
      throw mismatch;
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(
          "Persisted original StartSession association evidence is malformed", malformed);
    }
  }

  private Record findOriginalStartSessionAssociation(
      net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence
              .Request
          selector) {
    return dsl.fetchOne(
        "SELECT association.*, current.status AS current_status, "
            + "current.row_version AS current_row_version, "
            + "current.game_instance_uuid AS current_game_instance_uuid, "
            + "current.owner_account_uuid AS current_owner_account_uuid, "
            + "current.game_template_id AS current_game_template_id, "
            + "current.launch_descriptor_id AS current_launch_descriptor_id, "
            + "current.version_id AS current_version_id, "
            + "current.release_bundle_id AS current_release_bundle_id, "
            + "current.generation_config_revision AS current_generation_config_revision, "
            + "current.version_state_epoch AS current_version_state_epoch, "
            + "current.run_owned_start_request_id AS current_run_owned_start_request_id, "
            + "current.run_owned_start_published_release_bundle_ref "
            + "AS current_run_owned_start_published_release_bundle_ref, "
            + "attempt.canonical_tenant_id AS owner_canonical_tenant_id, "
            + "attempt.owner_attempt_id AS owner_attempt_id, "
            + "attempt.owner_fence AS owner_fence, "
            + "attempt.post_authorization_execution_tuple AS owner_tuple, "
            + "attempt.mutation_digest AS owner_mutation_digest, "
            + "attempt.authorization_reference_fingerprint AS owner_authorization_fingerprint, "
            + "attempt.account_redemption_projection AS owner_projection, "
            + "association_pin.canonical_tenant_id AS pin_canonical_tenant_id, "
            + "association_pin.owner_attempt_id AS pin_owner_attempt_id, "
            + "association_pin.owner_fence AS pin_owner_fence, "
            + "association_pin.post_authorization_execution_tuple AS pin_owner_tuple, "
            + "association_pin.post_authorization_tuple_digest AS pin_tuple_digest, "
            + "association_pin.account_redemption_projection_digest AS pin_projection_digest, "
            + "association_pin.association_request_wire AS pin_association_request_wire, "
            + "association_pin.association_response_wire AS pin_association_response_wire, "
            + "association_pin.association_request_digest AS pin_association_request_digest, "
            + "association_pin.association_response_digest AS pin_association_response_digest, "
            + "association_pin.template_id AS pin_template_id, "
            + "association_pin.canonical_version_id AS pin_canonical_version_id, "
            + "association_pin.selected_commit_id AS pin_selected_commit_id, "
            + "association_pin.publish_workflow_id AS pin_publish_workflow_id, "
            + "association_pin.publication_selection_digest AS pin_publication_selection_digest, "
            + "association_pin.association_digest AS pin_association_digest, "
            + "association_pin.world_intake_request_id AS pin_world_intake_request_id, "
            + "association_pin.world_operation_id AS pin_world_operation_id, "
            + "association_pin.source_operation_id AS pin_source_operation_id, "
            + "association_pin.source_evidence_digest AS pin_source_evidence_digest, "
            + "descriptor_pin.canonical_tenant_id AS descriptor_canonical_tenant_id, "
            + "descriptor_pin.owner_attempt_id AS descriptor_owner_attempt_id, "
            + "descriptor_pin.owner_fence AS descriptor_owner_fence, "
            + "descriptor_pin.association_request_digest AS descriptor_association_request_digest, "
            + "descriptor_pin.association_response_digest AS descriptor_association_response_digest, "
            + "descriptor_pin.descriptor_request_wire AS descriptor_request_wire, "
            + "descriptor_pin.descriptor_response_wire AS descriptor_response_wire, "
            + "descriptor_pin.descriptor_request_digest AS descriptor_request_digest, "
            + "descriptor_pin.descriptor_response_digest AS descriptor_response_digest "
            + "FROM "
            + ASSOCIATION_TABLE
            + " association JOIN game_instances current "
            + "ON current.id = association.game_instance_id "
            + "AND current.tenant_id = association.game_session_tenant_id "
            + "JOIN game_session_canonical_realm_catalog realm "
            + "ON realm.target_namespace = association.target_namespace "
            + "AND realm.realm_id = association.canonical_realm_id "
            + "AND realm.canonical_tenant_id = association.canonical_tenant_id "
            + "AND realm.world_slug = association.world_slug "
            + "AND realm.playable_state_namespace_id = association.playable_state_namespace_id "
            + "AND realm.visible = TRUE AND realm.public_production = TRUE "
            + "AND realm.state_scope = 'SHARED' "
            + "JOIN game_session_start_session_operator_attempt attempt "
            + "ON attempt.target_namespace = association.target_namespace "
            + "AND attempt.control_plane_request_id = association.control_plane_request_id "
            + "AND attempt.canonical_tenant_id = association.canonical_tenant_id "
            + "AND attempt.phase_state = 'OWNER_EXECUTION_PENDING' "
            + "AND attempt.account_redemption_projection IS NOT NULL "
            + "AND attempt.lease_expires_at > clock_timestamp() "
            + "JOIN game_session_start_session_template_association_pin association_pin "
            + "ON association_pin.target_namespace = attempt.target_namespace "
            + "AND association_pin.control_plane_request_id = attempt.control_plane_request_id "
            + "AND association_pin.canonical_tenant_id = attempt.canonical_tenant_id "
            + "AND association_pin.owner_attempt_id = attempt.owner_attempt_id "
            + "AND association_pin.owner_fence = attempt.owner_fence "
            + "AND association_pin.post_authorization_execution_tuple "
            + "= attempt.post_authorization_execution_tuple "
            + "JOIN game_session_start_session_launch_descriptor_pin descriptor_pin "
            + "ON descriptor_pin.target_namespace = attempt.target_namespace "
            + "AND descriptor_pin.control_plane_request_id = attempt.control_plane_request_id "
            + "AND descriptor_pin.canonical_tenant_id = attempt.canonical_tenant_id "
            + "AND descriptor_pin.owner_attempt_id = attempt.owner_attempt_id "
            + "AND descriptor_pin.owner_fence = attempt.owner_fence "
            + "AND descriptor_pin.association_request_digest "
            + "= association_pin.association_request_digest "
            + "AND descriptor_pin.association_response_digest "
            + "= association_pin.association_response_digest "
            + "WHERE association.target_namespace = ? "
            + "AND association.control_plane_request_id = ? "
            + "AND association.canonical_tenant_id = ? "
            + "AND association.game_instance_uuid = ? "
            + "AND current.status = 'STARTING'",
        workloadNamespace,
        selector.controlPlaneRequestId(),
        selector.canonicalTenantId(),
        selector.gameInstanceUuid());
  }

  private OriginalStartSessionAssociation decodeOriginalStartSessionAssociation(Record row) {
    UUID ownerAttemptId = row.get("owner_attempt_id", UUID.class);
    Long ownerFenceValue = row.get("owner_fence", Long.class);
    if (ownerAttemptId == null
        || NIL_UUID.equals(ownerAttemptId)
        || ownerFenceValue == null
        || ownerFenceValue <= 0) {
      throw new IllegalStateException("Original StartSession owner identity is malformed");
    }
    long ownerFence = ownerFenceValue;
    UUID canonicalTenantId = row.get("owner_canonical_tenant_id", UUID.class);
    byte[] ownerTupleBytes = requiredBytes(row, "owner_tuple");
    byte[] ownerProjectionBytes = requiredBytes(row, "owner_projection");
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(ownerTupleBytes);
    if (!Arrays.equals(tuple.canonicalBytes(), ownerTupleBytes)
        || !tuple.controlPlaneRequestId().equals(row.get("control_plane_request_id", String.class))
        || !workloadNamespace.equals(
            tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !canonicalTenantId.equals(tuple.preAuthorizationTuple().action().scope().tenantId())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .target()
            .ownerAccountId()
            .equals(row.get("current_owner_account_uuid", UUID.class))
        || !canonicalTenantId.equals(row.get("canonical_tenant_id", UUID.class))
        || !tuple.mutationDigest().equals(row.get("owner_mutation_digest", String.class))
        || !tuple
            .authorizationReferenceFingerprint()
            .equals(row.get("owner_authorization_fingerprint", String.class))) {
      throw new IllegalStateException(
          "Original StartSession owner tuple differs from its retained association");
    }
    StartSessionAuthorityEvidenceBundle authority =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    long issuanceFence;
    try {
      issuanceFence = Long.parseLong(tuple.issuanceFence());
    } catch (NumberFormatException invalid) {
      throw new IllegalStateException("Original StartSession issuance fence is malformed", invalid);
    }
    GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection
        expectedProjection =
            new GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection(
                tuple.authorizationReferenceFingerprint(),
                tuple.authorityEvidenceBundleBytes(),
                authority.issuanceOperationId(),
                issuanceFence);
    if (!Arrays.equals(expectedProjection.canonicalBytes(), ownerProjectionBytes)) {
      throw new IllegalStateException(
          "Original StartSession Account projection differs from its canonical tuple");
    }

    byte[] associationTupleBytes = requiredBytes(row, "pin_owner_tuple");
    byte[] associationRequestWire = requiredBytes(row, "pin_association_request_wire");
    byte[] associationResponseWire = requiredBytes(row, "pin_association_response_wire");
    if (!Arrays.equals(ownerTupleBytes, associationTupleBytes)
        || !ownerAttemptId.equals(row.get("pin_owner_attempt_id", UUID.class))
        || ownerFence != row.get("pin_owner_fence", Long.class)
        || !canonicalTenantId.equals(row.get("pin_canonical_tenant_id", UUID.class))
        || !sha256(associationTupleBytes).equals(row.get("pin_tuple_digest", String.class))
        || !sha256(ownerProjectionBytes).equals(row.get("pin_projection_digest", String.class))
        || !sha256(associationRequestWire)
            .equals(row.get("pin_association_request_digest", String.class))
        || !sha256(associationResponseWire)
            .equals(row.get("pin_association_response_digest", String.class))) {
      throw new IllegalStateException(
          "Original StartSession association pin differs from its owner attempt");
    }

    Result associationPin =
        decodeAssociationPin(
            row,
            tuple,
            ownerAttemptId,
            ownerFence,
            associationRequestWire,
            associationResponseWire);
    byte[] descriptorRequestWire = requiredBytes(row, "descriptor_request_wire");
    byte[] descriptorResponseWire = requiredBytes(row, "descriptor_response_wire");
    if (!canonicalTenantId.equals(row.get("descriptor_canonical_tenant_id", UUID.class))
        || !ownerAttemptId.equals(row.get("descriptor_owner_attempt_id", UUID.class))
        || ownerFence != row.get("descriptor_owner_fence", Long.class)
        || !row.get("pin_association_request_digest", String.class)
            .equals(row.get("descriptor_association_request_digest", String.class))
        || !row.get("pin_association_response_digest", String.class)
            .equals(row.get("descriptor_association_response_digest", String.class))
        || !sha256(descriptorRequestWire).equals(row.get("descriptor_request_digest", String.class))
        || !sha256(descriptorResponseWire)
            .equals(row.get("descriptor_response_digest", String.class))) {
      throw new IllegalStateException(
          "Original StartSession descriptor pin differs from its association pin");
    }

    Resolved descriptorPin =
        decodeDescriptorPin(associationPin, descriptorRequestWire, descriptorResponseWire);
    CanonicalGameInstanceLaunchAssociation association = toOriginalAssociation(row);
    if (!(descriptorPin.outcome() instanceof DescriptorOutcome descriptorOutcome)
        || !descriptorOutcome.descriptor().equals(association.launchBindingEvidence().descriptor())
        || !associationPin.association().canonicalTenantId().equals(canonicalTenantId)
        || associationPin.association().templateId()
            != tuple.preAuthorizationTuple().action().target().gameTemplateId()
        || !associationPin.association().worldSlug().equals(association.worldSlug())
        || !associationPin
            .association()
            .sourceOperationId()
            .equals(
                association.launchBindingEvidence().descriptor().authoredWorldSourceOperationId())
        || !associationPin
            .association()
            .sourceEvidenceDigest()
            .equals(
                association
                    .launchBindingEvidence()
                    .descriptor()
                    .authoredWorldSourceEvidenceDigest())) {
      throw new IllegalStateException(
          "Original StartSession descriptor or association pin differs from the STARTING binding");
    }
    return new OriginalStartSessionAssociation(association, tuple, ownerAttemptId, ownerFence);
  }

  private static Result decodeAssociationPin(
      Record row,
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID ownerAttemptId,
      long ownerFence,
      byte[] requestWire,
      byte[] responseWire) {
    try {
      ReadStartSessionTemplateAssociationRequest requestMessage =
          ReadStartSessionTemplateAssociationRequest.parseFrom(requestWire);
      Request request = StartSessionTemplateAssociationReadGrpcCodec.fromRequest(requestMessage);
      if (!(request.selection() instanceof InitialConfigured)
          || !request
              .targetNamespace()
              .equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
          || !request.ownerAttemptId().equals(ownerAttemptId)
          || request.ownerFence() != ownerFence
          || !Arrays.equals(request.canonicalPostAuthorizationTuple(), tuple.canonicalBytes())
          || !Arrays.equals(
              StartSessionTemplateAssociationReadGrpcCodec.toRequest(request).toByteArray(),
              requestWire)) {
        throw new IllegalStateException("Original association request pin is not canonical");
      }
      ReadStartSessionTemplateAssociationResponse responseMessage =
          ReadStartSessionTemplateAssociationResponse.parseFrom(responseWire);
      Result result =
          StartSessionTemplateAssociationReadGrpcCodec.fromResponse(request, responseMessage);
      if (!Arrays.equals(
              StartSessionTemplateAssociationReadGrpcCodec.toResponse(result).toByteArray(),
              responseWire)
          || !row.get("pin_canonical_tenant_id", UUID.class)
              .equals(result.association().canonicalTenantId())
          || row.get("pin_template_id", Long.class) != result.association().templateId()
          || !row.get("pin_canonical_version_id", UUID.class)
              .equals(result.association().canonicalVersionId())
          || !row.get("pin_selected_commit_id", UUID.class)
              .equals(result.association().selectedCommitId())
          || !row.get("pin_publish_workflow_id", String.class)
              .equals(result.association().publishWorkflowId())
          || !row.get("pin_publication_selection_digest", String.class)
              .equals(result.association().publicationSelectionDigest())
          || !row.get("pin_association_digest", String.class)
              .equals(result.association().associationDigest())
          || !row.get("pin_world_intake_request_id", UUID.class)
              .equals(result.association().intakeRequestId())
          || !row.get("pin_world_operation_id", UUID.class)
              .equals(result.association().worldOperationId())
          || !row.get("pin_source_operation_id", UUID.class)
              .equals(result.association().sourceOperationId())
          || !row.get("pin_source_evidence_digest", String.class)
              .equals(result.association().sourceEvidenceDigest())) {
        throw new IllegalStateException("Retained association pin columns or wire changed");
      }
      return result;
    } catch (InvalidProtocolBufferException | IllegalArgumentException malformed) {
      throw new IllegalStateException("Retained association pin wire is malformed", malformed);
    }
  }

  private static Resolved decodeDescriptorPin(
      Result associationPin, byte[] requestWire, byte[] responseWire) {
    try {
      Request first = associationPin.request();
      var pinned = associationPin.association();
      Request expected =
          new Request(
              first.schemaVersion(),
              first.targetNamespace(),
              first.readRequestId(),
              first.canonicalPostAuthorizationTuple(),
              first.ownerAttemptId(),
              first.ownerFence(),
              new ExactReplay(
                  pinned.canonicalVersionId(),
                  pinned.selectedCommitId(),
                  pinned.publishWorkflowId(),
                  pinned.associationDigest()));
      ReadStartSessionTemplateAssociationRequest requestMessage =
          ReadStartSessionTemplateAssociationRequest.parseFrom(requestWire);
      Request request = StartSessionLaunchDescriptorGrpcCodec.fromRequest(requestMessage);
      if (!expected.equals(request)
          || !Arrays.equals(
              StartSessionLaunchDescriptorGrpcCodec.toRequest(request).toByteArray(),
              requestWire)) {
        throw new IllegalStateException(
            "Retained descriptor request is not the exact association replay");
      }
      ResolveStartSessionLaunchDescriptorResponse responseMessage =
          ResolveStartSessionLaunchDescriptorResponse.parseFrom(responseWire);
      Resolved result =
          StartSessionLaunchDescriptorGrpcCodec.fromResponse(expected, responseMessage);
      if (!(result.outcome() instanceof DescriptorOutcome)
          || !Arrays.equals(
              StartSessionLaunchDescriptorGrpcCodec.toResponse(
                      expected, result.associationRead(), result.outcome())
                  .toByteArray(),
              responseWire)) {
        throw new IllegalStateException(
            "Retained descriptor response is not canonical exact evidence");
      }
      return result;
    } catch (InvalidProtocolBufferException | IllegalArgumentException malformed) {
      throw new IllegalStateException("Retained descriptor pin wire is malformed", malformed);
    }
  }

  private CanonicalGameInstanceLaunchAssociation toOriginalAssociation(Record row) {
    try {
      CompleteLaunchBindingEvidence evidence =
          JSON.readValue(
              row.get("complete_launch_binding_evidence", JSONB.class).data(),
              CompleteLaunchBindingEvidence.class);
      ValidatedBinding binding = validateBinding(evidence);
      UUID canonicalTenantId = row.get("canonical_tenant_id", UUID.class);
      UUID associationOperationId = row.get("tenant_association_operation_id", UUID.class);
      UUID gameInstanceUuid = row.get("game_instance_uuid", UUID.class);
      UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
      RuntimeTenantIdentityEvidence source = sourceFromRow(row);
      requireFreshSourceIdentity(
          new FreshGameSessionTenantAssociation(
              associationOperationId, row.get("game_session_tenant_id", Long.class), source),
          source);
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      if (!workloadNamespace.equals(row.get("tenant_source_target_namespace", String.class))
          || source.schemaVersion() != row.get("tenant_source_schema_version", Integer.class)
          || !source.requestId().equals(row.get("tenant_association_request_id", UUID.class))
          || !source.requestId().equals(row.get("tenant_source_request_id", UUID.class))
          || !canonicalTenantId.equals(row.get("tenant_source_canonical_tenant_id", UUID.class))
          || !"FRESH_SOURCE_BOUND".equals(row.get("tenant_association_kind", String.class))) {
        throw new IllegalStateException(
            "Persisted fresh tenant source fields differ from their retained owner identity");
      }
      GameInstanceRow current =
          new GameInstanceRow(
              row.get("game_instance_id", Long.class),
              row.get("game_session_tenant_id", Long.class),
              row.get("current_game_instance_uuid", UUID.class),
              row.get("current_status", String.class),
              row.get("current_row_version", Long.class),
              row.get("current_game_template_id", Long.class),
              row.get("current_launch_descriptor_id", String.class),
              row.get("current_version_id", Long.class),
              row.get("current_release_bundle_id", Long.class),
              row.get("current_generation_config_revision", String.class),
              row.get("current_version_state_epoch", Long.class),
              row.get("current_run_owned_start_request_id", String.class),
              row.get("current_run_owned_start_published_release_bundle_ref", String.class));
      requireRuntimeBinding(current, evidence);
      if (!workloadNamespace.equals(row.get("target_namespace", String.class))
          || !canonicalTenantId.equals(source.canonicalTenantId())
          || !"FRESH_SOURCE_BOUND".equals(row.get("tenant_association_kind", String.class))
          || !binding.descriptor().targetNamespace().equals(workloadNamespace)
          || !binding
              .descriptor()
              .controlPlaneRequestId()
              .equals(row.get("control_plane_request_id", String.class))
          || !canonicalTenantId.equals(binding.descriptor().canonicalTenantId())
          || !row.get("world_slug", String.class).equals(binding.descriptor().worldSlug())
          || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))
          || !row.get("launch_descriptor_id", String.class)
              .equals(binding.descriptor().launchDescriptorId())
          || row.get("game_template_id", Long.class) != binding.descriptor().gameTemplateId()
          || row.get("version_id", Long.class) != binding.descriptor().versionId()
          || row.get("release_bundle_id", Long.class) != binding.descriptor().releaseBundleId()
          || !row.get("generation_config_revision", String.class)
              .equals(binding.descriptor().generationConfigRevision())
          || row.get("version_state_epoch", Long.class) != binding.descriptor().versionStateEpoch()
          || !gameInstanceUuid.equals(current.gameInstanceUuid())
          || !"STARTING".equals(current.status())
          || current.rowVersion() < row.get("captured_starting_row_version", Long.class)
          || !RealmEntryPolicy.StateScope.SHARED
              .name()
              .equals(row.get("playable_state_scope", String.class))
          || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))) {
        throw new IllegalStateException(
            "Persisted STARTING association contradicts its exact runtime binding");
      }
      return new CanonicalGameInstanceLaunchAssociation(
          workloadNamespace,
          row.get("game_session_tenant_id", Long.class),
          associationOperationId,
          canonicalTenantId,
          gameInstanceUuid,
          row.get("world_slug", String.class),
          namespaceId,
          RealmEntryPolicy.StateScope.SHARED,
          true,
          binding.descriptor().controlPlaneRequestId(),
          binding.descriptor().launchDescriptorId(),
          row.get("captured_starting_row_version", Long.class),
          evidence,
          CurrentGameInstanceStatus.STARTING,
          current.rowVersion());
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException illegalState) {
        throw illegalState;
      }
      throw new IllegalStateException(
          "Persisted original STARTING association is malformed", exception);
    }
  }

  private static void requireExactOriginalStartSessionSelector(
      net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence
              .Request
          selector,
      CanonicalGameInstanceLaunchAssociation association) {
    var descriptor = association.launchBindingEvidence().descriptor();
    if (!selector.targetNamespace().equals(association.targetNamespace())
        || !selector.canonicalTenantId().equals(association.canonicalTenantId())
        || !selector.worldSlug().equals(association.worldSlug())
        || !selector.gameInstanceUuid().equals(association.gameInstanceUuid())
        || !selector.controlPlaneRequestId().equals(association.controlPlaneRequestId())
        || !selector.launchDescriptorId().equals(association.launchDescriptorId())
        || !selector.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !selector.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !selector
            .expectedReleaseAttestationEvidenceDigest()
            .equals(association.launchBindingEvidence().releaseAttestation().evidenceDigest())) {
      throw new LaunchAssociationOwnerReadMismatchException(
          "Canonical launch association differs from its exact original StartSession selector");
    }
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Persisted owner association is missing " + field);
    }
    return value.clone();
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Locks the exact canonical launch association and its current Game Session row. */
  public CanonicalInitialAdmissionLaunchTarget lockForInitialAdmission(
      String targetNamespace, UUID canonicalTenantId, UUID realmId, UUID canonicalGameInstanceId) {
    return lockForInitialAdmission(
        targetNamespace, canonicalTenantId, realmId, canonicalGameInstanceId, true);
  }

  /** Locks the immutable launch owner and current row for an owner-local admission transaction. */
  public CanonicalInitialAdmissionLaunchTarget lockForInitialAdmission(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID realmId,
      UUID canonicalGameInstanceId,
      boolean requireRunning) {
    requireWritableReadCommittedOwnerTransaction();
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Initial admission targetNamespace does not match this Game Session owner");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    Record row =
        findForInitialAdmission(
            targetNamespace, canonicalTenantId, realmId, canonicalGameInstanceId, true);
    if (row == null) {
      throw new IllegalStateException(
          "Exact canonical launch association and current runtime row are unavailable");
    }
    CanonicalGameInstanceLaunchAssociation association = toAssociation(row);
    if ((requireRunning
            && association.currentGameInstanceStatus() != CurrentGameInstanceStatus.RUNNING)
        || !association.targetNamespace().equals(targetNamespace)
        || !association.canonicalTenantId().equals(canonicalTenantId)
        || !association.gameInstanceUuid().equals(canonicalGameInstanceId)) {
      throw new IllegalStateException(
          "Initial admission requires the exact canonical launch target in RUNNING state");
    }
    return new CanonicalInitialAdmissionLaunchTarget(
        association,
        row.get("canonical_realm_id", UUID.class),
        row.get("current_game_instance_id", Long.class),
        row.get("current_version_id", Long.class));
  }

  /** Reads the exact launch association and current runtime row after owner commit. */
  public Optional<CanonicalInitialAdmissionLaunchTarget> readForInitialAdmission(
      String targetNamespace, UUID canonicalTenantId, UUID realmId, UUID canonicalGameInstanceId) {
    requireOutsideTransaction();
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Initial admission targetNamespace does not match this Game Session owner");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(realmId, "realmId");
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    Record row =
        findForInitialAdmission(
            targetNamespace, canonicalTenantId, realmId, canonicalGameInstanceId, false);
    if (row == null) {
      return Optional.empty();
    }
    CanonicalGameInstanceLaunchAssociation association = toAssociation(row);
    return Optional.of(
        new CanonicalInitialAdmissionLaunchTarget(
            association,
            row.get("canonical_realm_id", UUID.class),
            row.get("current_game_instance_id", Long.class),
            row.get("current_version_id", Long.class)));
  }

  private Record findForInitialAdmission(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID realmId,
      UUID canonicalGameInstanceId,
      boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT association.*, current.id AS current_game_instance_id, "
            + "current.status AS current_status, current.row_version AS current_row_version, "
            + "current.game_instance_uuid AS current_game_instance_uuid, "
            + "current.game_template_id AS current_game_template_id, "
            + "current.launch_descriptor_id AS current_launch_descriptor_id, "
            + "current.version_id AS current_version_id, "
            + "current.release_bundle_id AS current_release_bundle_id, "
            + "current.generation_config_revision AS current_generation_config_revision, "
            + "current.version_state_epoch AS current_version_state_epoch, "
            + "current.run_owned_start_request_id AS current_run_owned_start_request_id, "
            + "current.run_owned_start_published_release_bundle_ref "
            + "AS current_run_owned_start_published_release_bundle_ref "
            + "FROM "
            + ASSOCIATION_TABLE
            + " association JOIN game_instances current "
            + "ON current.id = association.game_instance_id "
            + "AND current.tenant_id = association.game_session_tenant_id "
            + "JOIN game_session_canonical_realm_catalog realm "
            + "ON realm.target_namespace = association.target_namespace "
            + "AND realm.realm_id = association.canonical_realm_id "
            + "AND realm.canonical_tenant_id = association.canonical_tenant_id "
            + "AND realm.world_slug = association.world_slug "
            + "AND realm.playable_state_namespace_id = association.playable_state_namespace_id "
            + "AND realm.visible = TRUE AND realm.public_production = TRUE "
            + "AND realm.state_scope = 'SHARED' "
            + "WHERE association.target_namespace = ? "
            + "AND association.canonical_tenant_id = ? "
            + "AND association.canonical_realm_id = ? "
            + "AND association.game_instance_uuid = ?"
            + (forUpdate ? " FOR UPDATE OF association, current" : ""),
        targetNamespace,
        canonicalTenantId,
        realmId,
        canonicalGameInstanceId);
  }

  private void lockRequest(String controlPlaneRequestId) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))",
        workloadNamespace,
        controlPlaneRequestId);
  }

  private GameInstanceRow requireLockedRuntime(long tenantId, long gameInstanceId) {
    Record row =
        dsl.fetchOne(
            "SELECT id, tenant_id, game_instance_uuid, status, row_version, game_template_id, "
                + "launch_descriptor_id, version_id, release_bundle_id, generation_config_revision, "
                + "version_state_epoch, run_owned_start_request_id, "
                + "run_owned_start_published_release_bundle_ref FROM game_instances "
                + "WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId,
            gameInstanceId);
    if (row == null) {
      throw new IllegalStateException("Game Session owner instance row is missing in tenant scope");
    }
    return toRuntime(row, "status", "row_version");
  }

  private RealmOwnerProjection requirePublicSharedRealm(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Record row =
        dsl.fetchOne(
            "SELECT realm_id, playable_state_namespace_id, state_scope, public_production, visible "
                + "FROM game_session_canonical_realm_catalog WHERE target_namespace = ? "
                + "AND canonical_tenant_id = ? AND world_slug = ? AND public_production = TRUE "
                + "AND visible = TRUE",
            workloadNamespace,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug());
    if (row == null
        || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))
        || !Boolean.TRUE.equals(row.get("visible", Boolean.class))
        || !RealmEntryPolicy.StateScope.SHARED
            .name()
            .equals(row.get("state_scope", String.class))) {
      throw new IllegalStateException(
          "Canonical launch association requires the owner's visible public SHARED realm");
    }
    UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
    UUID realmId = row.get("realm_id", UUID.class);
    requireNonNil(namespaceId, "playableStateNamespaceId");
    requireNonNil(realmId, "canonicalRealmId");
    return new RealmOwnerProjection(realmId, namespaceId);
  }

  private FreshTenantOwnerMapping requireFreshTenantMapping(
      FreshGameSessionTenantAssociation expected) {
    RuntimeTenantIdentityEvidence source = expected.sourceEvidence();
    Record row =
        dsl.fetchOne(
            "SELECT association.association_operation_id, association.association_request_id, "
                + "association.source_schema_version, association.source_target_namespace, "
                + "association.source_request_id, association.source_canonical_tenant_id, "
                + "association.canonical_tenant_id, association.legacy_game_session_tenant_id, "
                + "association.source_game_row_id, association.source_game_tenant_key, "
                + "association.provenance_kind, association.association_kind, "
                + "claim.association_operation_id AS claim_operation_id, "
                + "claim.association_request_id AS claim_request_id, "
                + "claim.association_kind AS claim_kind, claim.reservation_kind AS claim_reservation_kind, "
                + "reservation.reservation_kind "
                + "FROM game_session_fresh_tenant_association association "
                + "JOIN game_session_tenant_canonical_claim claim ON "
                + "claim.target_namespace = association.target_namespace "
                + "AND claim.canonical_tenant_id = association.canonical_tenant_id "
                + "AND claim.legacy_game_session_tenant_id = association.legacy_game_session_tenant_id "
                + "AND claim.association_operation_id = association.association_operation_id "
                + "AND claim.association_kind = association.association_kind "
                + "JOIN game_session_tenant_scope_reservation reservation ON "
                + "reservation.game_session_tenant_id = association.legacy_game_session_tenant_id "
                + "WHERE association.target_namespace = ? AND association.association_operation_id = ?",
            workloadNamespace,
            expected.associationOperationId());
    if (row == null) {
      throw new IllegalStateException(
          "Persisted fresh Game Session tenant association is unavailable");
    }
    UUID operationId = row.get("association_operation_id", UUID.class);
    long localTenantId = row.get("legacy_game_session_tenant_id", Long.class);
    if (!expected.associationOperationId().equals(operationId)
        || localTenantId != expected.legacyGameSessionTenantId()
        || !source.requestId().equals(row.get("association_request_id", UUID.class))
        || !source.requestId().equals(row.get("source_request_id", UUID.class))
        || source.schemaVersion() != row.get("source_schema_version", Integer.class)
        || !source.targetNamespace().equals(row.get("source_target_namespace", String.class))
        || !source.canonicalTenantId().equals(row.get("source_canonical_tenant_id", UUID.class))
        || !source.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || source.sourceGameRowId() != row.get("source_game_row_id", Long.class)
        || !source.sourceGameTenantKey().equals(row.get("source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(row.get("provenance_kind", String.class))
        || !operationId.equals(row.get("claim_operation_id", UUID.class))
        || !source.requestId().equals(row.get("claim_request_id", UUID.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("association_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("claim_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("reservation_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("claim_reservation_kind", String.class))) {
      throw new IllegalStateException(
          "Persisted fresh tenant source identity does not match its owner");
    }
    return new FreshTenantOwnerMapping(operationId);
  }

  private Optional<Record> findByRequest(String controlPlaneRequestId, boolean forUpdate) {
    String sql =
        "SELECT association.*, current.status AS current_status, "
            + "current.row_version AS current_row_version, current.game_instance_uuid AS "
            + "current_game_instance_uuid, current.game_template_id AS current_game_template_id, "
            + "current.launch_descriptor_id AS current_launch_descriptor_id, "
            + "current.version_id AS current_version_id, current.release_bundle_id AS "
            + "current_release_bundle_id, current.generation_config_revision AS "
            + "current_generation_config_revision, current.version_state_epoch AS "
            + "current_version_state_epoch, current.run_owned_start_request_id AS "
            + "current_run_owned_start_request_id, current.run_owned_start_published_release_bundle_ref "
            + "AS current_run_owned_start_published_release_bundle_ref "
            + "FROM "
            + ASSOCIATION_TABLE
            + " association JOIN game_instances current "
            + "ON current.id = association.game_instance_id "
            + "AND current.tenant_id = association.game_session_tenant_id "
            + "JOIN game_session_canonical_realm_catalog realm "
            + "ON realm.target_namespace = association.target_namespace "
            + "AND realm.realm_id = association.canonical_realm_id "
            + "AND realm.canonical_tenant_id = association.canonical_tenant_id "
            + "AND realm.world_slug = association.world_slug "
            + "AND realm.playable_state_namespace_id = association.playable_state_namespace_id "
            + "AND realm.visible = TRUE AND realm.public_production = TRUE "
            + "AND realm.state_scope = 'SHARED' "
            + "WHERE association.target_namespace = ? AND association.control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE OF association, current" : "");
    return Optional.ofNullable(dsl.fetchOne(sql, workloadNamespace, controlPlaneRequestId));
  }

  private CanonicalGameInstanceLaunchAssociation toAssociation(Record row) {
    try {
      CompleteLaunchBindingEvidence evidence =
          JSON.readValue(
              row.get("complete_launch_binding_evidence", JSONB.class).data(),
              CompleteLaunchBindingEvidence.class);
      ValidatedBinding binding = validateBinding(evidence);
      UUID canonicalTenantId = row.get("canonical_tenant_id", UUID.class);
      UUID associationOperationId = row.get("tenant_association_operation_id", UUID.class);
      UUID gameInstanceUuid = row.get("game_instance_uuid", UUID.class);
      UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
      Boolean publicProduction = row.get("public_production", Boolean.class);
      if (!workloadNamespace.equals(row.get("target_namespace", String.class))
          || !"FRESH_SOURCE_BOUND".equals(row.get("tenant_association_kind", String.class))
          || !binding.descriptor().targetNamespace().equals(workloadNamespace)
          || !binding
              .descriptor()
              .controlPlaneRequestId()
              .equals(row.get("control_plane_request_id", String.class))
          || !canonicalTenantId.equals(binding.descriptor().canonicalTenantId())
          || !row.get("world_slug", String.class).equals(binding.descriptor().worldSlug())
          || !row.get("launch_descriptor_id", String.class)
              .equals(binding.descriptor().launchDescriptorId())
          || row.get("game_template_id", Long.class) != binding.descriptor().gameTemplateId()
          || row.get("version_id", Long.class) != binding.descriptor().versionId()
          || row.get("release_bundle_id", Long.class) != binding.descriptor().releaseBundleId()
          || !row.get("generation_config_revision", String.class)
              .equals(binding.descriptor().generationConfigRevision())
          || row.get("version_state_epoch", Long.class) != binding.descriptor().versionStateEpoch()
          || !Boolean.TRUE.equals(publicProduction)) {
        throw new IllegalStateException(
            "Persisted launch association contradicts its typed binding");
      }
      RuntimeTenantIdentityEvidence source = sourceFromRow(row);
      FreshGameSessionTenantAssociation mapping =
          new FreshGameSessionTenantAssociation(
              associationOperationId, row.get("game_session_tenant_id", Long.class), source);
      requireFreshTenantMapping(mapping);
      GameInstanceRow current =
          new GameInstanceRow(
              row.get("game_instance_id", Long.class),
              row.get("game_session_tenant_id", Long.class),
              row.get("current_game_instance_uuid", UUID.class),
              row.get("current_status", String.class),
              row.get("current_row_version", Long.class),
              row.get("current_game_template_id", Long.class),
              row.get("current_launch_descriptor_id", String.class),
              row.get("current_version_id", Long.class),
              row.get("current_release_bundle_id", Long.class),
              row.get("current_generation_config_revision", String.class),
              row.get("current_version_state_epoch", Long.class),
              row.get("current_run_owned_start_request_id", String.class),
              row.get("current_run_owned_start_published_release_bundle_ref", String.class));
      requireRuntimeBinding(current, evidence);
      if (!gameInstanceUuid.equals(current.gameInstanceUuid())
          || current.rowVersion() < row.get("captured_starting_row_version", Long.class)
          || !RealmEntryPolicy.StateScope.SHARED
              .name()
              .equals(row.get("playable_state_scope", String.class))) {
        throw new IllegalStateException(
            "Current Game Session row contradicts its launch association");
      }
      return new CanonicalGameInstanceLaunchAssociation(
          workloadNamespace,
          row.get("game_session_tenant_id", Long.class),
          associationOperationId,
          canonicalTenantId,
          gameInstanceUuid,
          row.get("world_slug", String.class),
          namespaceId,
          RealmEntryPolicy.StateScope.SHARED,
          true,
          binding.descriptor().controlPlaneRequestId(),
          binding.descriptor().launchDescriptorId(),
          row.get("captured_starting_row_version", Long.class),
          evidence,
          CurrentGameInstanceStatus.valueOf(current.status()),
          current.rowVersion());
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException illegalState) {
        throw illegalState;
      }
      throw new IllegalStateException(
          "Persisted canonical launch association is malformed", exception);
    }
  }

  private static RuntimeTenantIdentityEvidence sourceFromRow(Record row) {
    return new RuntimeTenantIdentityEvidence(
        row.get("tenant_source_schema_version", Integer.class),
        row.get("tenant_source_target_namespace", String.class),
        row.get("tenant_source_request_id", UUID.class),
        row.get("tenant_source_canonical_tenant_id", UUID.class),
        row.get("tenant_source_game_row_id", Long.class),
        row.get("tenant_source_game_tenant_key", String.class),
        row.get("tenant_source_provenance_kind", String.class));
  }

  private void requireExactRetry(
      CanonicalGameInstanceLaunchAssociation stored,
      FreshGameSessionTenantAssociation expectedTenant,
      long expectedGameInstanceId,
      GameInstanceRow current,
      CompleteLaunchBindingEvidence expectedEvidence,
      FreshTenantOwnerMapping mapping,
      RealmOwnerProjection realm) {
    if (stored.gameSessionTenantId() != expectedTenant.legacyGameSessionTenantId()
        || !stored.tenantAssociationOperationId().equals(mapping.operationId())
        || !stored.canonicalTenantId().equals(expectedTenant.sourceEvidence().canonicalTenantId())
        || !stored.playableStateNamespaceId().equals(realm.playableStateNamespaceId())
        || !stored.launchBindingEvidence().equals(expectedEvidence)
        || !stored.gameInstanceUuid().equals(current.gameInstanceUuid())
        || stored.currentRowVersion() > current.rowVersion()
        || stored.capturedStartingRowVersion() > current.rowVersion()) {
      throw new CanonicalGameInstanceLaunchAssociationConflictException(
          "Launch association request was reused with a changed tenant, instance, or source binding");
    }
    Record row =
        dsl.fetchOne(
            "SELECT game_instance_id FROM "
                + ASSOCIATION_TABLE
                + " WHERE target_namespace = ? AND control_plane_request_id = ?",
            workloadNamespace,
            stored.controlPlaneRequestId());
    if (row == null || row.get("game_instance_id", Long.class) != expectedGameInstanceId) {
      throw new CanonicalGameInstanceLaunchAssociationConflictException(
          "Launch association request was reused for another Game Session instance");
    }
    requireRuntimeBinding(current, expectedEvidence);
  }

  private static void requireStartingRuntime(
      GameInstanceRow runtime, CompleteLaunchBindingEvidence evidence) {
    requireRuntimeBinding(runtime, evidence);
    if (!"STARTING".equals(runtime.status())
        || !evidence.descriptor().controlPlaneRequestId().equals(runtime.runOwnedStartRequestId())
        || !evidence
            .descriptor()
            .publishedReleaseBundleRef()
            .equals(runtime.runOwnedStartPublishedReleaseBundleRef())) {
      throw new IllegalStateException(
          "Launch association capture requires the exact persisted run-owned STARTING instance");
    }
  }

  private static void requireRuntimeBinding(
      GameInstanceRow runtime, CompleteLaunchBindingEvidence evidence) {
    var descriptor = evidence.descriptor();
    if (runtime.id() <= 0
        || runtime.tenantId() <= 0
        || runtime.gameInstanceUuid() == null
        || NIL_UUID.equals(runtime.gameInstanceUuid())
        || runtime.rowVersion() < 0
        || runtime.gameTemplateId() == null
        || runtime.gameTemplateId() != descriptor.gameTemplateId()
        || !Objects.equals(runtime.launchDescriptorId(), descriptor.launchDescriptorId())
        || runtime.versionId() == null
        || runtime.versionId() != descriptor.versionId()
        || runtime.releaseBundleId() == null
        || runtime.releaseBundleId() != descriptor.releaseBundleId()
        || runtime.versionStateEpoch() == null
        || runtime.versionStateEpoch() != descriptor.versionStateEpoch()
        || !Objects.equals(
            runtime.generationConfigRevision(), descriptor.generationConfigRevision())
        || !Objects.equals(runtime.runOwnedStartRequestId(), descriptor.controlPlaneRequestId())
        || !Objects.equals(
            runtime.runOwnedStartPublishedReleaseBundleRef(),
            descriptor.publishedReleaseBundleRef())) {
      throw new IllegalStateException(
          "Game Session instance does not match the complete launch binding");
    }
    try {
      CurrentGameInstanceStatus.valueOf(runtime.status());
    } catch (RuntimeException invalidStatus) {
      throw new IllegalStateException("Game Session instance status is unavailable", invalidStatus);
    }
  }

  private static GameInstanceRow toRuntime(
      Record row, String statusColumn, String rowVersionColumn) {
    return toRuntime(
        row,
        statusColumn,
        rowVersionColumn,
        "game_instance_uuid",
        "game_template_id",
        "launch_descriptor_id",
        "version_id",
        "release_bundle_id",
        "generation_config_revision",
        "version_state_epoch",
        "run_owned_start_request_id",
        "run_owned_start_published_release_bundle_ref");
  }

  private static GameInstanceRow toRuntime(
      Record row,
      String statusColumn,
      String rowVersionColumn,
      String uuidColumn,
      String templateColumn,
      String descriptorColumn,
      String versionColumn,
      String bundleColumn,
      String configColumn,
      String epochColumn,
      String requestColumn,
      String publishedRefColumn) {
    return new GameInstanceRow(
        row.get("id", Long.class),
        row.get("tenant_id", Long.class),
        row.get(uuidColumn, UUID.class),
        row.get(statusColumn, String.class),
        row.get(rowVersionColumn, Long.class),
        row.get(templateColumn, Long.class),
        row.get(descriptorColumn, String.class),
        row.get(versionColumn, Long.class),
        row.get(bundleColumn, Long.class),
        row.get(configColumn, String.class),
        row.get(epochColumn, Long.class),
        row.get(requestColumn, String.class),
        row.get(publishedRefColumn, String.class));
  }

  private static ValidatedBinding validateBinding(CompleteLaunchBindingEvidence evidence) {
    Objects.requireNonNull(evidence, "launchBindingEvidence");
    var descriptor = evidence.descriptor();
    descriptor.requireValid();
    evidence.releaseAttestation().requireValid(descriptor);
    requireRequestId(descriptor.controlPlaneRequestId());
    return new ValidatedBinding(evidence);
  }

  private void requireFreshSourceIdentity(
      FreshGameSessionTenantAssociation association, RuntimeTenantIdentityEvidence evidence) {
    if (evidence.schemaVersion() != 1
        || !workloadNamespace.equals(evidence.targetNamespace())
        || evidence.requestId() == null
        || NIL_UUID.equals(evidence.requestId())
        || evidence.canonicalTenantId() == null
        || NIL_UUID.equals(evidence.canonicalTenantId())
        || association.legacyGameSessionTenantId() <= 0
        || !"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
      throw new IllegalArgumentException(
          "Only exact fresh NEW_GAME_ROW owner identity is supported");
    }
  }

  private static String serializeEvidence(CompleteLaunchBindingEvidence evidence) {
    try {
      return JSON.writeValueAsString(evidence);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Failed to persist complete launch binding evidence", exception);
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical launch association capture requires a writable owner transaction");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equals(settings.get("transaction_isolation", String.class))
        || !"off".equals(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical launch association capture requires writable READ COMMITTED isolation");
    }
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical launch association read requires a committed owner read");
    }
  }

  private static void requireRequestId(String value) {
    Objects.requireNonNull(value, "controlPlaneRequestId");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("controlPlaneRequestId must contain 1 to 128 characters");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private record ValidatedBinding(CompleteLaunchBindingEvidence evidence) {
    private net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence
        descriptor() {
      return evidence.descriptor();
    }
  }

  private record FreshTenantOwnerMapping(UUID operationId) {}

  private record RealmOwnerProjection(UUID realmId, UUID playableStateNamespaceId) {}

  private record GameInstanceRow(
      long id,
      long tenantId,
      UUID gameInstanceUuid,
      String status,
      long rowVersion,
      Long gameTemplateId,
      String launchDescriptorId,
      Long versionId,
      Long releaseBundleId,
      String generationConfigRevision,
      Long versionStateEpoch,
      String runOwnedStartRequestId,
      String runOwnedStartPublishedReleaseBundleRef) {}

  /** Owner-derived association readback; deliberately not an AttemptClaim or continuation token. */
  public record OriginalStartSessionAssociation(
      CanonicalGameInstanceLaunchAssociation association,
      StartSessionPostAuthorizationExecutionTuple postAuthorizationExecutionTuple,
      UUID ownerAttemptId,
      long ownerFence) {
    public OriginalStartSessionAssociation {
      Objects.requireNonNull(association, "association");
      Objects.requireNonNull(postAuthorizationExecutionTuple, "postAuthorizationExecutionTuple");
      requireNonNil(ownerAttemptId, "ownerAttemptId");
      requirePositive(ownerFence, "ownerFence");
      var scope = postAuthorizationExecutionTuple.preAuthorizationTuple().action().scope();
      if (!association.targetNamespace().equals(scope.targetNamespace())
          || !association.canonicalTenantId().equals(scope.tenantId())
          || !association
              .controlPlaneRequestId()
              .equals(postAuthorizationExecutionTuple.controlPlaneRequestId())) {
        throw new IllegalArgumentException(
            "Original StartSession tuple differs from its retained association scope");
      }
    }
  }

  public static final class LaunchAssociationOwnerReadMismatchException extends RuntimeException {
    public LaunchAssociationOwnerReadMismatchException(String message) {
      super(message);
    }
  }

  public static final class CanonicalGameInstanceLaunchAssociationConflictException
      extends RuntimeException {
    public CanonicalGameInstanceLaunchAssociationConflictException(String message) {
      super(message);
    }
  }
}
