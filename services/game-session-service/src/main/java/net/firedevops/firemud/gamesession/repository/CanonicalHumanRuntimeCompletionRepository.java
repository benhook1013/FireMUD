package net.firedevops.firemud.gamesession.repository;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered owner-local completion of one protected Human STARTING runtime. */
public final class CanonicalHumanRuntimeCompletionRepository {
  private static final String TABLE = "game_session_canonical_human_runtime_completion";
  private final DSLContext dsl;
  private final String workloadNamespace;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;

  public CanonicalHumanRuntimeCompletionRepository(DSLContext dsl, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Human completion workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.launchRepository =
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, workloadNamespace);
  }

  /**
   * Completes only the exact protected STARTING row. It creates no admission pointer, terminal
   * attempt, Account settlement, or World hold release.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Completion completeProtected(
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionEvidence protection,
      CanonicalInitialAdmissionWorldProof worldProof) {
    Objects.requireNonNull(request, "request");
    requireWorkloadNamespace(request.targetNamespace());
    Objects.requireNonNull(protection, "protection");
    Objects.requireNonNull(worldProof, "worldProof");
    worldProof.requireMatches(request);
    requireWritableReadCommittedTransaction();
    lockTarget(request);

    Record retained = find(request.targetNamespace(), request.initialAdmissionRequestId(), true);
    if (retained != null) {
      return requireExactReplay(retained, request, protection);
    }

    Record attempt =
        dsl.fetchOne(
            "SELECT status FROM game_session_canonical_initial_admission_attempt "
                + "WHERE target_namespace=? AND initial_admission_request_id=? FOR UPDATE",
            request.targetNamespace(),
            request.initialAdmissionRequestId());
    if (attempt == null || !"PENDING".equals(attempt.get("status", String.class))) {
      throw conflict(
          "Human runtime completion requires the exact nonterminal protected reservation");
    }

    CanonicalInitialAdmissionLaunchTarget launch =
        launchRepository.lockStartingForHumanRuntimeCompletion(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.canonicalGameInstanceId());
    requireExactLaunch(request, launch);
    var protectionRepository = new AccountInitialAdmissionProtectionBindingRepository(dsl);
    protectionRepository.requireExactHumanStartingCompletionSource(request, protection);

    CanonicalGameInstanceLaunchAssociation association = launch.association();
    launchRepository.requireOriginalPinsBeforeStarting(
        protection.request().originalTuple(), association.launchBindingEvidence());
    long startingVersion = association.currentRowVersion();
    long runningVersion = Math.addExact(startingVersion, 1L);
    int updated =
        dsl.execute(
            "UPDATE game_instances SET status='RUNNING', row_version=? "
                + "WHERE tenant_id=? AND id=? AND game_instance_uuid=? "
                + "AND status='STARTING' AND row_version=?",
            runningVersion,
            association.gameSessionTenantId(),
            launch.gameInstanceId(),
            request.canonicalGameInstanceId(),
            startingVersion);
    if (updated != 1) {
      throw conflict("Human runtime STARTING owner changed before completion");
    }

    var protectedRequest = protection.request();
    var descriptor = association.launchBindingEvidence().descriptor();
    var release = association.launchBindingEvidence().releaseAttestation();
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (target_namespace, initial_admission_request_id, request_digest, "
                + "canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id, "
                + "playable_state_scope, canonical_game_instance_id, canonical_version_id, "
                + "active_lifecycle_epoch, expected_catalog_revision, origin_kind, "
                + "expected_prior_pointer_version, hold_id, hold_fence, hold_binding_digest, "
                + "game_session_tenant_id, game_instance_id, control_plane_request_id, "
                + "captured_starting_row_version, completed_running_row_version, "
                + "launch_descriptor_id, descriptor_request_digest, descriptor_result_digest, "
                + "release_attestation_digest, account_protection_id, account_protection_fence, "
                + "original_owner_attempt_id, original_owner_mutation_id, original_owner_fence, "
                + "original_start_session_tuple, account_redemption_projection, "
                + "account_protection_evidence_bytes, world_lifecycle_state, completed_at) "
                + "SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, "
                + "o.post_authorization_execution_tuple, o.account_redemption_projection, ?, "
                + "'ACTIVE', clock_timestamp() FROM game_session_start_session_operator_attempt o "
                + "WHERE o.target_namespace=? AND o.owner_attempt_id=? RETURNING *",
            request.targetNamespace(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.activeLifecycleEpoch(),
            request.expectedCatalogRevision(),
            request.originKind().name(),
            request.expectedPriorPointerVersion(),
            request.holdId(),
            request.holdFence(),
            request.holdBindingDigest(),
            association.gameSessionTenantId(),
            launch.gameInstanceId(),
            association.controlPlaneRequestId(),
            association.capturedStartingRowVersion(),
            runningVersion,
            association.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest(),
            protection.accountProtectionId(),
            protection.accountProtectionFence(),
            protectedRequest.gameSessionOwnerAttemptId(),
            protectedRequest.gameSessionOwnerMutationId(),
            protectedRequest.gameSessionOwnerFence(),
            protection.canonicalBytes(),
            request.targetNamespace(),
            protectedRequest.gameSessionOwnerAttemptId());
    if (inserted == null) {
      throw conflict("Original Human owner disappeared before completion evidence was retained");
    }
    return requireExactReplay(inserted, request, protection);
  }

  /** Historical readback only; this value grants no current runtime or admission authority. */
  public Optional<Completion> read(String namespace, String initialAdmissionRequestId) {
    requireWorkloadNamespace(namespace);
    Record row = find(namespace, initialAdmissionRequestId, false);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  private void requireWorkloadNamespace(String namespace) {
    if (!workloadNamespace.equals(namespace)) {
      throw new IllegalArgumentException("Human completion targets another workload namespace");
    }
  }

  private Record find(String namespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + TABLE
            + " WHERE target_namespace=? AND initial_admission_request_id=?"
            + (forUpdate ? " FOR UPDATE" : ""),
        namespace,
        requestId);
  }

  private Completion requireExactReplay(
      Record row,
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionEvidence protection) {
    Completion completion = decode(row);
    if (!completion.request().equals(request)
        || !completion.accountProtectionId().equals(protection.accountProtectionId())
        || completion.accountProtectionFence() != protection.accountProtectionFence()
        || !Arrays.equals(
            completion.accountProtectionEvidenceBytes(), protection.canonicalBytes())) {
      throw conflict("Human runtime completion retry changed request or Account protection");
    }
    return completion;
  }

  private static Completion decode(Record row) {
    CanonicalInitialAdmissionRequest request =
        new CanonicalInitialAdmissionRequest(
            required(row, "target_namespace", String.class),
            required(row, "canonical_tenant_id", UUID.class),
            required(row, "world_slug", String.class),
            required(row, "realm_id", UUID.class),
            required(row, "playable_state_namespace_id", UUID.class),
            required(row, "playable_state_scope", String.class),
            required(row, "canonical_game_instance_id", UUID.class),
            required(row, "canonical_version_id", UUID.class),
            required(row, "active_lifecycle_epoch", Long.class),
            required(row, "expected_catalog_revision", Long.class),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                required(row, "origin_kind", String.class)),
            row.get("expected_prior_pointer_version", Long.class),
            required(row, "initial_admission_request_id", String.class),
            required(row, "request_digest", String.class),
            required(row, "hold_id", UUID.class),
            required(row, "hold_fence", UUID.class),
            required(row, "hold_binding_digest", String.class));
    return new Completion(
        request,
        required(row, "account_protection_id", UUID.class),
        required(row, "account_protection_fence", Long.class),
        required(row, "captured_starting_row_version", Long.class),
        required(row, "completed_running_row_version", Long.class),
        required(row, "account_protection_evidence_bytes", byte[].class),
        required(row, "completed_at", OffsetDateTime.class).toInstant());
  }

  private static void requireExactLaunch(
      CanonicalInitialAdmissionRequest request, CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    if (!request.targetNamespace().equals(association.targetNamespace())
        || !request.canonicalTenantId().equals(association.canonicalTenantId())
        || !request.worldSlug().equals(association.worldSlug())
        || !request.realmId().equals(launch.realmId())
        || !request.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || !request.playableStateScope().equals(association.playableStateScope().name())
        || !request.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !request.canonicalVersionId().equals(launch.canonicalVersionId())) {
      throw conflict("Human runtime completion launch differs from the exact request");
    }
  }

  private void lockTarget(CanonicalInitialAdmissionRequest request) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ? || ':' || ?, 0))",
        request.targetNamespace(),
        request.canonicalTenantId().toString(),
        request.realmId().toString());
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || dsl.connectionResult(
            connection -> {
              try {
                return connection.isReadOnly()
                    || connection.getTransactionIsolation()
                        != Connection.TRANSACTION_READ_COMMITTED;
              } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(
                    "Unable to inspect completion transaction", exception);
              }
            })) {
      throw new IllegalStateException(
          "Human runtime completion requires a writable READ COMMITTED transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) throw new IllegalStateException("Runtime completion is missing " + field);
    return value;
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  /** Immutable historical completion receipt; its retained bytes never authorize another write. */
  public record Completion(
      CanonicalInitialAdmissionRequest request,
      UUID accountProtectionId,
      long accountProtectionFence,
      long capturedStartingRowVersion,
      long completedRunningRowVersion,
      byte[] accountProtectionEvidenceBytes,
      Instant completedAt) {
    public Completion {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(accountProtectionId, "accountProtectionId");
      accountProtectionEvidenceBytes =
          Objects.requireNonNull(accountProtectionEvidenceBytes, "accountProtectionEvidenceBytes")
              .clone();
      Objects.requireNonNull(completedAt, "completedAt");
    }

    @Override
    public byte[] accountProtectionEvidenceBytes() {
      return accountProtectionEvidenceBytes.clone();
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof Completion other
          && request.equals(other.request)
          && accountProtectionId.equals(other.accountProtectionId)
          && accountProtectionFence == other.accountProtectionFence
          && capturedStartingRowVersion == other.capturedStartingRowVersion
          && completedRunningRowVersion == other.completedRunningRowVersion
          && Arrays.equals(accountProtectionEvidenceBytes, other.accountProtectionEvidenceBytes)
          && completedAt.equals(other.completedAt);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              request,
              accountProtectionId,
              accountProtectionFence,
              capturedStartingRowVersion,
              completedRunningRowVersion,
              completedAt);
      return 31 * result + Arrays.hashCode(accountProtectionEvidenceBytes);
    }
  }
}
