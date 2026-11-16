package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Private fixture hold storage. Not registered as a Human or public admission owner. */
public final class WorldFixtureInitialAdmissionHoldRepository {
  private final DSLContext dsl;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;

  public WorldFixtureInitialAdmissionHoldRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    writeTransaction = new TransactionTemplate(transactionManager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    writeTransaction.setReadOnly(false);
    readTransaction = new TransactionTemplate(transactionManager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
  }

  /** Acquires or exactly replays a hold after locking and rereading its actual World lifecycle. */
  public HoldIdentity acquire(WorldFixtureInitialAdmissionHoldRequest fixtureRequest) {
    Objects.requireNonNull(fixtureRequest, "fixtureRequest");
    Request request = fixtureRequest.selector();
    requireNoAmbientTransaction("Canonical World hold acquisition");
    return writeTransaction.execute(
        status -> {
          requireWritableOwnerTransaction();
          WorldCanonicalInstanceAssociation association =
              associationRepository
                  .readFixtureActivationAssociationInHoldTransaction(fixtureRequest.activation())
                  .orElseThrow(
                      () ->
                          new InvalidHoldEvidenceException(
                              "Canonical World association is unavailable"));
          requireAssociationTarget(request, association);
          LockedWorldLifecycle lockedLifecycle = lockWorldLifecycleRow(request, association);

          long localTenantKey = association.worldPrivateKeys().tenantId();
          Record existing =
              dsl.fetchOne(
                  "SELECT * FROM initial_admission_bind_hold "
                      + "WHERE tenant_id=? AND initial_admission_request_id=? FOR UPDATE",
                  localTenantKey,
                  request.initialAdmissionRequestId());
          if (existing != null) {
            requireFixtureBinding(existing, fixtureRequest);
            HoldIdentity identity = requireStoredIdentity(existing, request);
            String existingStatus = required(existing, "status", String.class);
            if ("PENDING".equals(existingStatus)) {
              if (!"ACTIVE".equals(lockedLifecycle.status())
                  || lockedLifecycle.epoch() != request.activeLifecycleEpoch()) {
                throw new InvalidHoldEvidenceException(
                    "Pending canonical World hold lost its exact ACTIVE lifecycle epoch");
              }
            } else if (!"COMMITTED".equals(existingStatus) && !"ABORTED".equals(existingStatus)) {
              throw new InvalidHoldEvidenceException(
                  "Retained canonical World hold has an unsupported status");
            }
            return identity;
          }
          if (!"ACTIVE".equals(lockedLifecycle.status())
              || lockedLifecycle.epoch() != request.activeLifecycleEpoch()) {
            throw new InvalidHoldEvidenceException(
                "Canonical World hold requires the exact current ACTIVE lifecycle epoch");
          }

          Record activeForRealm =
              dsl.fetchOne(
                  "SELECT hold_id FROM initial_admission_bind_hold "
                      + "WHERE tenant_id=? AND realm_uuid=? "
                      + "AND status IN ('PENDING','RECONCILIATION_REQUIRED') LIMIT 1",
                  localTenantKey,
                  request.realmId());
          if (activeForRealm != null) {
            throw new HoldConflictException(
                "A nonterminal initial-admission hold already owns this canonical realm");
          }

          UUID holdId = UUID.randomUUID();
          UUID holdFence = UUID.randomUUID();
          byte[] canonicalRequestBytes = request.canonicalRequestBytes();
          dsl.execute(
              "INSERT INTO initial_admission_bind_hold ("
                  + "hold_id,hold_fence,tenant_id,realm_uuid,playable_state_namespace_uuid,"
                  + "playable_state_scope,game_instance_id,version_id,active_lifecycle_epoch,"
                  + "initial_admission_request_id,request_digest,expected_no_prior_pointer,"
                  + "expected_catalog_revision,status,diagnostic_expires_at,canonical_target_namespace,"
                  + "canonical_tenant_id,canonical_world_slug,canonical_game_instance_id,"
                  + "canonical_version_id,initial_admission_origin,expected_prior_pointer_version,"
                  + "canonical_request_bytes,hold_binding_digest,fixture_activation_request_id,fixture_hold_request_bytes) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP + INTERVAL '15 minutes',?,?,?,?,?,?,?,?,?,?,?)",
              holdId,
              holdFence,
              localTenantKey,
              request.realmId(),
              request.playableStateNamespaceId(),
              request.playableStateScope(),
              association.worldPrivateKeys().gameInstanceId(),
              association.worldPrivateKeys().versionId(),
              request.activeLifecycleEpoch(),
              request.initialAdmissionRequestId(),
              request.initialAdmissionRequestDigest(),
              request.initialAdmissionOrigin()
                  == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
              request.expectedCatalogRevision(),
              request.targetNamespace(),
              request.canonicalTenantId(),
              request.worldSlug(),
              request.canonicalGameInstanceId(),
              request.canonicalVersionId(),
              request.initialAdmissionOrigin().name(),
              request.expectedPriorPointerVersion(),
              canonicalRequestBytes,
              request.holdBindingDigest(),
              fixtureRequest.activation().activationRequestId(),
              fixtureRequest.canonicalBytes());
          return new HoldIdentity(request, holdId, holdFence);
        });
  }

  /** Historical immutable identity read. Absence is not an abort or a release signal. */
  public Optional<HoldIdentity> readIdentity(
      WorldFixtureInitialAdmissionHoldRequest fixtureRequest) {
    Objects.requireNonNull(fixtureRequest, "fixtureRequest");
    Request request = fixtureRequest.selector();
    requireNoAmbientTransaction("Canonical World hold identity read");
    return readTransaction.execute(
        status -> {
          requireReadOnlyRepeatableReadOwnerTransaction();
          Record row =
              dsl.fetchOne(
                  "SELECT * FROM initial_admission_bind_hold "
                      + "WHERE canonical_target_namespace=? AND canonical_tenant_id=? "
                      + "AND canonical_world_slug=? AND canonical_game_instance_id=? "
                      + "AND initial_admission_request_id=? AND canonical_request_bytes=?",
                  request.targetNamespace(),
                  request.canonicalTenantId(),
                  request.worldSlug(),
                  request.canonicalGameInstanceId(),
                  request.initialAdmissionRequestId(),
                  request.canonicalRequestBytes());
          if (row == null) return Optional.empty();
          requireFixtureBinding(row, fixtureRequest);
          return Optional.of(requireStoredIdentity(row, request));
        });
  }

  /** Fixture receipt and hold are observed in one physical read-only repeatable-read snapshot. */
  public Optional<HoldStatus> readState(
      WorldFixtureInitialAdmissionHoldRequest fixture, HoldIdentity identity) {
    Objects.requireNonNull(fixture, "fixture");
    Objects.requireNonNull(identity, "identity");
    if (!fixture.selector().equals(identity.request()))
      throw new InvalidHoldEvidenceException("Fixture selector differs");
    requireNoAmbientTransaction("Fixture hold read");
    return readTransaction.execute(
        status -> {
          requireReadOnlyRepeatableReadOwnerTransaction();
          Record row =
              dsl.fetchOne(
                  "SELECT * FROM initial_admission_bind_hold WHERE hold_id=? AND hold_fence=?",
                  identity.holdId(),
                  identity.holdFence());
          if (row == null) return Optional.empty();
          requireStoredIdentity(row, identity.request(), identity);
          requireFixtureBinding(row, fixture);
          var association =
              associationRepository
                  .readFixtureActivationAssociationInOwnerTransaction(fixture.activation())
                  .orElseThrow(
                      () ->
                          new InvalidHoldEvidenceException(
                              "Fixture activation receipt unavailable"));
          requireAssociationTarget(identity.request(), association);
          return Optional.of(parseStatus(required(row, "status", String.class)));
        });
  }

  /** Applies only an exact terminal Game Session owner proof through the guarded SQL finalizer. */
  void settle(HoldIdentity identity, WorldFixtureInitialAdmissionTerminalProof proof) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(proof, "proof");
    requireNoAmbientTransaction("Canonical World hold settlement");
    if (!identity.request().equals(proof.request().selector())
        || !identity.holdId().equals(proof.holdId())
        || !identity.holdFence().equals(proof.holdFence())) {
      throw new InvalidHoldEvidenceException(
          "Game Session terminal proof changed the hold identity");
    }
    if (proof.outcome() == WorldFixtureInitialAdmissionTerminalProof.Outcome.PENDING) {
      return;
    }
    byte[] proofBytes = proof.canonicalBytes();
    String proofBytesDigest = prefixedDigest(proofBytes);
    String ownerProofDigest = proofBytesDigest.substring("sha256:".length());

    writeTransaction.executeWithoutResult(
        status -> {
          requireWritableOwnerTransaction();
          // Keep the preliminary status check unlocked. The guarded finalizer locks the actual
          // World lifecycle row first and the hold second, matching acquisition and termination.
          Record held =
              dsl.fetchOne(
                  "SELECT * FROM initial_admission_bind_hold WHERE hold_id=?", identity.holdId());
          if (held == null) {
            throw new InvalidHoldEvidenceException("Canonical World hold identity is not retained");
          }
          requireStoredIdentity(held, identity.request(), identity);
          requireFixtureBinding(held, proof.request());
          String currentStatus = required(held, "status", String.class);
          if ("COMMITTED".equals(currentStatus) || "ABORTED".equals(currentStatus)) {
            byte[] currentProof = held.get("canonical_owner_proof_bytes", byte[].class);
            String currentDigest = held.get("canonical_owner_proof_digest", String.class);
            if (currentStatus.equals(proof.outcome().name())
                && Arrays.equals(currentProof, proofBytes)
                && proofBytesDigest.equals(currentDigest)) {
              return;
            }
            throw new InvalidHoldEvidenceException(
                "Canonical World hold is already settled by a different terminal proof");
          }
          if (!"PENDING".equals(currentStatus)) {
            throw new InvalidHoldEvidenceException(
                "Canonical World hold is not in its exact pending state");
          }

          Long pointerVersion = proof.pointerVersion();
          String auditEventId =
              proof.auditEventId() == null ? null : Long.toString(proof.auditEventId());
          dsl.fetchOne(
              "SELECT world_finalize_canonical_initial_admission_hold("
                  + "?::uuid,?::uuid,?::text,?::text,?::text,?::text,?::bigint,"
                  + "?::timestamptz,?::boolean,?::bytea,?::text)",
              identity.holdId(),
              identity.holdFence(),
              proof.outcome().name(),
              identity.request().initialAdmissionRequestId(),
              ownerProofDigest,
              auditEventId,
              pointerVersion,
              OffsetDateTime.ofInstant(proof.terminalAt(), ZoneOffset.UTC),
              proof.positiveDurableAbort(),
              proofBytes,
              proofBytesDigest);
        });
  }

  private static void requireFixtureBinding(
      Record row, WorldFixtureInitialAdmissionHoldRequest request) {
    if (!request
            .activation()
            .activationRequestId()
            .equals(row.get("fixture_activation_request_id", UUID.class))
        || !Arrays.equals(
            request.canonicalBytes(), row.get("fixture_hold_request_bytes", byte[].class))) {
      throw new InvalidHoldEvidenceException(
          "Retained fixture hold changed its activation or request");
    }
  }

  private static HoldIdentity requireStoredIdentity(Record row, Request expected) {
    UUID holdId = required(row, "hold_id", UUID.class);
    UUID holdFence = required(row, "hold_fence", UUID.class);
    HoldIdentity identity = new HoldIdentity(expected, holdId, holdFence);
    return requireStoredIdentity(row, expected, identity);
  }

  private static HoldIdentity requireStoredIdentity(
      Record row, Request expected, HoldIdentity identity) {
    byte[] storedRequest = required(row, "canonical_request_bytes", byte[].class);
    if (storedRequest == null
        || !Arrays.equals(storedRequest, expected.canonicalRequestBytes())
        || !expected.holdBindingDigest().equals(required(row, "hold_binding_digest", String.class))
        || !expected
            .targetNamespace()
            .equals(required(row, "canonical_target_namespace", String.class))
        || !expected.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !expected.worldSlug().equals(required(row, "canonical_world_slug", String.class))
        || !expected
            .canonicalGameInstanceId()
            .equals(required(row, "canonical_game_instance_id", UUID.class))
        || !expected.canonicalVersionId().equals(required(row, "canonical_version_id", UUID.class))
        || !expected.realmId().equals(required(row, "realm_uuid", UUID.class))
        || !expected
            .playableStateNamespaceId()
            .equals(required(row, "playable_state_namespace_uuid", UUID.class))
        || !expected
            .playableStateScope()
            .equals(required(row, "playable_state_scope", String.class))
        || !expected
            .initialAdmissionRequestId()
            .equals(required(row, "initial_admission_request_id", String.class))
        || !expected
            .initialAdmissionRequestDigest()
            .equals(required(row, "request_digest", String.class))
        || !expected
            .canonicalGameInstanceId()
            .equals(required(row, "canonical_game_instance_id", UUID.class))
        || !expected.canonicalVersionId().equals(required(row, "canonical_version_id", UUID.class))
        || expected.activeLifecycleEpoch() != required(row, "active_lifecycle_epoch", Long.class)
        || expected.expectedCatalogRevision()
            != required(row, "expected_catalog_revision", Long.class)
        || !expected
            .initialAdmissionOrigin()
            .name()
            .equals(required(row, "initial_admission_origin", String.class))
        || !Objects.equals(
            expected.expectedPriorPointerVersion(),
            row.get("expected_prior_pointer_version", Long.class))
        || !Boolean.valueOf(
                expected.initialAdmissionOrigin()
                    == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER)
            .equals(required(row, "expected_no_prior_pointer", Boolean.class))
        || !identity.holdId().equals(required(row, "hold_id", UUID.class))
        || !identity.holdFence().equals(required(row, "hold_fence", UUID.class))) {
      throw new InvalidHoldEvidenceException(
          "Retained World hold differs from the complete canonical acquisition identity");
    }
    return identity;
  }

  private static void requireAssociationTarget(
      Request request, WorldCanonicalInstanceAssociation association) {
    var target = association.identity();
    if (!request.targetNamespace().equals(target.targetNamespace())
        || !request.canonicalTenantId().equals(target.canonicalTenantId())
        || !request.worldSlug().equals(target.worldSlug())
        || !request.realmId().equals(target.canonicalRealmId())
        || !request.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !request.playableStateScope().equals(target.playableStateScope())
        || !request.canonicalGameInstanceId().equals(target.canonicalGameInstanceId())
        || !request.canonicalVersionId().equals(association.canonicalVersionId())
        || request.expectedCatalogRevision() != target.catalogRevision()) {
      throw new InvalidHoldEvidenceException(
          "Canonical World hold differs from the current immutable World association");
    }
  }

  private LockedWorldLifecycle lockWorldLifecycleRow(
      Request request, WorldCanonicalInstanceAssociation association) {
    Record row =
        dsl.fetchOne(
            "SELECT tenant_id,game_instance_id,version_id,status,lifecycle_epoch,"
                + "canonical_game_instance_id,canonical_target_namespace,canonical_tenant_id,"
                + "canonical_world_slug,playable_state_namespace_id,playable_state_scope,"
                + "public_production,canonical_realm_id,catalog_revision,"
                + "canonical_launch_binding_operation_id "
                + "FROM world_instance WHERE id=? FOR UPDATE",
            association.worldInstanceId());
    if (row == null) {
      throw new InvalidHoldEvidenceException("Canonical World lifecycle row is unavailable");
    }
    var identity = association.identity();
    var prepareFields = association.worldPrivateKeys();
    if (required(row, "tenant_id", Long.class) != prepareFields.tenantId()
        || required(row, "game_instance_id", Long.class) != prepareFields.gameInstanceId()
        || required(row, "version_id", Long.class) != prepareFields.versionId()
        || !required(row, "canonical_game_instance_id", UUID.class)
            .equals(identity.canonicalGameInstanceId())
        || !required(row, "canonical_target_namespace", String.class)
            .equals(identity.targetNamespace())
        || !required(row, "canonical_tenant_id", UUID.class).equals(identity.canonicalTenantId())
        || !required(row, "canonical_world_slug", String.class).equals(identity.worldSlug())
        || !required(row, "playable_state_namespace_id", UUID.class)
            .equals(identity.playableStateNamespaceId())
        || !required(row, "playable_state_scope", String.class)
            .equals(identity.playableStateScope())
        || !required(row, "public_production", Boolean.class).equals(identity.publicProduction())
        || !required(row, "canonical_realm_id", UUID.class).equals(identity.canonicalRealmId())
        || required(row, "catalog_revision", Long.class) != identity.catalogRevision()
        || row.get("canonical_launch_binding_operation_id", UUID.class) != null
        || !request.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())) {
      throw new InvalidHoldEvidenceException(
          "Locked World lifecycle row differs from the immutable canonical association");
    }
    String status = required(row, "status", String.class);
    Long epoch = required(row, "lifecycle_epoch", Long.class);
    if (epoch <= 0) {
      throw new InvalidHoldEvidenceException("Locked World lifecycle epoch is invalid");
    }
    return new LockedWorldLifecycle(status, epoch);
  }

  private static HoldStatus parseStatus(String status) {
    try {
      return HoldStatus.valueOf(status);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidHoldEvidenceException("Canonical World hold status is unsupported", invalid);
    }
  }

  private static String prefixedDigest(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String column, Class<T> type) {
    T value = row.get(column, type);
    if (value == null) {
      throw new InvalidHoldEvidenceException("Canonical World hold column is missing: " + column);
    }
    return value;
  }

  private static void requireNoAmbientTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(operation + " must not join an ambient transaction");
    }
  }

  private void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical World hold mutation requires a writable READ COMMITTED owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World hold mutation requires a writable READ COMMITTED owner transaction");
    }
  }

  private void requireReadOnlyRepeatableReadOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical World hold read requires a read-only REPEATABLE READ owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"repeatable read".equals(state.get("isolation", String.class))
        || !"on".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World hold read requires a read-only REPEATABLE READ owner transaction");
    }
  }

  public static final class HoldConflictException extends DataAccessException {
    public HoldConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidHoldEvidenceException extends IllegalStateException {
    public InvalidHoldEvidenceException(String message) {
      super(message);
    }

    public InvalidHoldEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record LockedWorldLifecycle(String status, long epoch) {}
}
