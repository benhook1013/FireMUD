package net.firedevops.firemud.gamesession.repository;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner-local STARTING to RUNNING completion of the run-owned fixture launch. The fixture has no
 * Account protection; the retained row binds the exact pending fixture attempt, attached World hold
 * and fixture provenance digest, and the storage guard requires it for the RUNNING update.
 */
public final class RunOwnedFixtureRuntimeCompletionRepository {
  private static final String TABLE = "game_session_run_owned_fixture_runtime_completion";
  private final DSLContext dsl;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;

  /** The launch repository is the workload-namespace owner; its locks refuse other namespaces. */
  public RunOwnedFixtureRuntimeCompletionRepository(
      DSLContext dsl, CanonicalGameInstanceLaunchAssociationRepository launchRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.launchRepository = Objects.requireNonNull(launchRepository, "launchRepository");
  }

  /**
   * Completes only the exact fixture STARTING row while its fixture attempt is PENDING. An exact
   * replay returns the retained completion. It creates no pointer, terminal or hold release.
   */
  public Completion complete(
      CanonicalInitialAdmissionRequest request, CanonicalInitialAdmissionWorldProof worldProof) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(worldProof, "worldProof");
    worldProof.requireMatches(request);
    requireWritableReadCommittedTransaction();
    lockTarget(request);

    Record retained = find(request.targetNamespace(), request.initialAdmissionRequestId(), true);
    if (retained != null) {
      return requireExactReplay(retained, request);
    }

    Record attempt =
        dsl.fetchOne(
            "SELECT status, fixture_provenance_digest "
                + "FROM game_session_canonical_initial_admission_attempt "
                + "WHERE target_namespace=? AND initial_admission_request_id=? "
                + "AND request_digest=? AND hold_id=? AND hold_fence=? FOR UPDATE",
            request.targetNamespace(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            request.holdId(),
            request.holdFence());
    if (attempt == null
        || !"PENDING".equals(attempt.get("status", String.class))
        || attempt.get("fixture_provenance_digest", String.class) == null) {
      throw conflict("Fixture runtime completion requires the exact pending fixture reservation");
    }

    CanonicalInitialAdmissionLaunchTarget launch =
        launchRepository.lockStartingForFixtureRuntimeCompletion(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.canonicalGameInstanceId());
    requireExactLaunch(request, launch);
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    String provenance = association.attestationDigest();
    if (!provenance.equals(attempt.get("fixture_provenance_digest", String.class))) {
      throw conflict("Fixture reservation provenance differs from its launch");
    }

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
      throw conflict("Fixture runtime STARTING owner changed before completion");
    }

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
                + "fixture_provenance_digest, completed_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, clock_timestamp()) "
                + "RETURNING *",
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
            provenance);
    if (inserted == null) {
      throw conflict("Fixture runtime completion evidence was not retained");
    }
    return requireExactReplay(inserted, request);
  }

  /** Historical readback only; this value grants no current runtime or admission authority. */
  public Optional<Completion> read(String namespace, String initialAdmissionRequestId) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixture completion namespace is invalid");
    }
    Record row = find(namespace, initialAdmissionRequestId, false);
    return row == null ? Optional.empty() : Optional.of(decode(row));
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

  private static Completion requireExactReplay(
      Record row, CanonicalInitialAdmissionRequest request) {
    Completion completion = decode(row);
    if (!completion.request().equals(request)) {
      throw conflict("Fixture runtime completion retry changed its exact request");
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
        required(row, "game_session_tenant_id", Long.class),
        required(row, "game_instance_id", Long.class),
        required(row, "captured_starting_row_version", Long.class),
        required(row, "completed_running_row_version", Long.class),
        required(row, "fixture_provenance_digest", String.class),
        required(row, "completed_at", OffsetDateTime.class).toInstant());
  }

  private static void requireExactLaunch(
      CanonicalInitialAdmissionRequest request, CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    if (!association.isRunOwnedFixture()
        || !request.targetNamespace().equals(association.targetNamespace())
        || !request.canonicalTenantId().equals(association.canonicalTenantId())
        || !request.worldSlug().equals(association.worldSlug())
        || !request.realmId().equals(launch.realmId())
        || !request.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || !request.playableStateScope().equals(association.playableStateScope().name())
        || !request.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !request.canonicalVersionId().equals(launch.canonicalVersionId())) {
      throw conflict("Fixture runtime completion launch differs from the exact request");
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
                return connection.getAutoCommit()
                    || connection.isReadOnly()
                    || connection.getTransactionIsolation()
                        != Connection.TRANSACTION_READ_COMMITTED;
              } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(
                    "Unable to inspect completion transaction", exception);
              }
            })) {
      throw new IllegalStateException(
          "Fixture runtime completion requires a writable READ COMMITTED transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw new IllegalStateException("Fixture runtime completion is missing " + field);
    }
    return value;
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  /** Immutable historical fixture completion receipt; it never authorizes another write. */
  public record Completion(
      CanonicalInitialAdmissionRequest request,
      long gameSessionTenantId,
      long gameInstanceId,
      long capturedStartingRowVersion,
      long completedRunningRowVersion,
      String fixtureProvenanceDigest,
      Instant completedAt) {
    public Completion {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(fixtureProvenanceDigest, "fixtureProvenanceDigest");
      Objects.requireNonNull(completedAt, "completedAt");
    }
  }
}
