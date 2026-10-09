package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered owner-local acquisition and immutable readback for canonical first-open holds.
 *
 * <p>This repository records acquisition identity only. It does not resolve or terminalize the
 * hold, establish current admission authority, or supply Game Session outcome evidence.
 */
public final class WorldCanonicalInitialAdmissionHoldRepository {
  private static final String HOLD_SELECT =
      "SELECT hold_id, hold_fence, tenant_id, realm_uuid, playable_state_namespace_uuid, "
          + "playable_state_scope, game_instance_id, version_id, active_lifecycle_epoch, "
          + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
          + "expected_catalog_revision, canonical_target_namespace, canonical_tenant_id, "
          + "canonical_world_slug, canonical_game_instance_id, canonical_version_id, "
          + "initial_admission_origin, expected_prior_pointer_version, canonical_request_bytes, "
          + "hold_binding_digest FROM initial_admission_bind_hold ";
  private static final String HOLD_STATE_SELECT =
      HOLD_SELECT.replace("SELECT hold_id,", "SELECT status, hold_id,");
  private static final String TRANSACTION_STATE_SQL =
      "SELECT current_setting('transaction_isolation') AS isolation, "
          + "current_setting('transaction_read_only') AS read_only";

  private final DSLContext dsl;
  private final TransactionTemplate readTransaction;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleReadRepository;

  public WorldCanonicalInitialAdmissionHoldRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleReadRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    PlatformTransactionManager manager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.lifecycleReadRepository =
        Objects.requireNonNull(lifecycleReadRepository, "lifecycleReadRepository");

    readTransaction = new TransactionTemplate(manager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);

    writeTransaction = new TransactionTemplate(manager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Acquires one immutable hold after locking and proving the exact current ACTIVE lifecycle row.
   * Exact retries return the original hold identity; changed request bindings fail closed.
   */
  public HoldIdentity acquire(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleSelector) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(lifecycleSelector, "lifecycleSelector");
    requireNoAmbientTransaction("World canonical initial-admission hold acquisition");
    requireSelectorMatchesRequest(request, lifecycleSelector);

    return writeTransaction.execute(
        status -> {
          requireWritableReadCommittedTransaction();
          WorldCanonicalInstanceLifecycleEvidence lifecycle =
              lifecycleReadRepository
                  .readForActivationInOwnerTransaction(lifecycleSelector)
                  .orElseThrow(
                      () ->
                          new HoldConflictException(
                              "Canonical World initial-admission lifecycle selector is not materialized"));
          if (!lifecycleSelector.equals(lifecycle.request())
              || !"ACTIVE".equals(lifecycle.lifecycleStatus())
              || lifecycle.lifecycleEpoch() != request.activeLifecycleEpoch()) {
            throw new HoldConflictException(
                "Canonical World initial-admission target is not the exact requested ACTIVE epoch");
          }

          WorldCanonicalInstanceAssociation association =
              associationRepository
                  .readOwnerAssociationInActivationTransaction(request.canonicalGameInstanceId())
                  .orElseThrow(
                      () ->
                          new HoldConflictException(
                              "Canonical World initial-admission association is missing"));
          requireAssociationMatchesRequest(request, association);
          requireSelectorMatchesAssociation(lifecycleSelector, association);
          WorldCanonicalInstanceAssociation.WorldPrepareFields privateKeys =
              association.worldPrepareFields();
          Record existing = readByOwnerRequest(privateKeys.privateTenantKey(), request);
          if (existing != null) {
            return verifyStoredIdentity(existing, request, association);
          }

          HoldIdentity allocated = new HoldIdentity(request, UUID.randomUUID(), UUID.randomUUID());
          int inserted = insert(request, privateKeys, allocated);

          Record stored = readByOwnerRequest(privateKeys.privateTenantKey(), request);
          if (stored == null) {
            throw new HoldConflictException(
                "Canonical World initial-admission hold insert did not produce exact request readback");
          }
          HoldIdentity readback = verifyStoredIdentity(stored, request, association);
          if (inserted == 1
              && !Arrays.equals(readback.canonicalBytes(), allocated.canonicalBytes())) {
            throw new InvalidHoldIdentityException(
                "Canonical World initial-admission hold readback changed its newly allocated identity");
          }
          return readback;
        });
  }

  /**
   * Reads immutable acquisition identity from an independent read-only owner snapshot. A present
   * result is historical identity only, not current lifecycle or admission proof.
   */
  public Optional<HoldIdentity> readIdentity(Request request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction("World canonical initial-admission hold identity read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              Optional<WorldCanonicalInstanceAssociation> maybeAssociation =
                  associationRepository.readOwnerAssociationInOwnerTransaction(
                      request.canonicalGameInstanceId());
              if (maybeAssociation.isEmpty()) return null;
              WorldCanonicalInstanceAssociation association = maybeAssociation.orElseThrow();
              requireAssociationMatchesRequest(request, association);
              Record row =
                  readByOwnerRequest(association.worldPrepareFields().privateTenantKey(), request);
              return row == null ? null : verifyStoredIdentity(row, request, association);
            }));
  }

  /**
   * Reads the exact immutable hold, its actual status, and current lifecycle in one read-only owner
   * snapshot. This is a sampled observation only; it cannot acquire or settle the hold.
   */
  public Optional<WorldCanonicalInitialAdmissionHoldState> readState(
      HoldIdentity expectedIdentity,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleSelector) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(lifecycleSelector, "lifecycleSelector");
    requireNoAmbientTransaction("World canonical initial-admission hold state read");
    Request expectedRequest = expectedIdentity.request();
    requireSelectorMatchesRequest(expectedRequest, lifecycleSelector);

    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              Optional<WorldCanonicalInstanceAssociation> maybeAssociation;
              try {
                maybeAssociation =
                    associationRepository.readOwnerAssociationInOwnerTransaction(
                        expectedRequest.canonicalGameInstanceId());
              } catch (
                  WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException
                      inconsistent) {
                throw new InvalidHoldIdentityException(
                    "Canonical World hold association is incomplete or inconsistent", inconsistent);
              }
              if (maybeAssociation.isEmpty()) {
                Record detachedTypedHold =
                    dsl.fetchOne(
                        "SELECT hold_id FROM initial_admission_bind_hold "
                            + "WHERE canonical_game_instance_id = ?",
                        expectedRequest.canonicalGameInstanceId());
                if (detachedTypedHold != null) {
                  throw new InvalidHoldIdentityException(
                      "Canonical World hold exists without its required persisted association");
                }
                return null;
              }
              WorldCanonicalInstanceAssociation association = maybeAssociation.orElseThrow();
              requireAssociationMatchesRequest(expectedRequest, association);
              requireSelectorMatchesAssociation(lifecycleSelector, association);

              Record row =
                  dsl.fetchOne(
                      HOLD_STATE_SELECT
                          + "WHERE tenant_id = ? AND initial_admission_request_id = ?",
                      association.worldPrepareFields().privateTenantKey(),
                      expectedRequest.initialAdmissionRequestId());
              if (row == null) return null;

              HoldIdentity storedIdentity = verifyStoredIdentity(row, expectedRequest, association);
              if (!Arrays.equals(
                  expectedIdentity.canonicalBytes(), storedIdentity.canonicalBytes())) {
                throw new HoldConflictException(
                    "Canonical World initial-admission hold ID or fence differs from the exact "
                        + "expected identity");
              }
              WorldCanonicalInitialAdmissionHoldState.HoldStatus holdStatus;
              try {
                holdStatus =
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.valueOf(
                        required(row, "status", String.class));
              } catch (RuntimeException invalid) {
                throw new InvalidHoldIdentityException(
                    "Persisted canonical World initial-admission hold status is missing or invalid",
                    invalid);
              }

              WorldCanonicalInstanceLifecycleEvidence lifecycle;
              try {
                lifecycle =
                    lifecycleReadRepository
                        .readForCurrentLocationInOwnerTransaction(lifecycleSelector)
                        .orElseThrow(
                            () ->
                                new InvalidHoldIdentityException(
                                    "Canonical World lifecycle evidence is missing for an existing "
                                        + "hold"));
              } catch (
                  WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException
                      inconsistent) {
                throw new InvalidHoldIdentityException(
                    "Current canonical World lifecycle evidence is inconsistent", inconsistent);
              }
              if (!lifecycleSelector.equals(lifecycle.request())) {
                throw new InvalidHoldIdentityException(
                    "Current canonical World lifecycle evidence substituted its exact read request");
              }
              try {
                return new WorldCanonicalInitialAdmissionHoldState(
                    storedIdentity, holdStatus, lifecycle);
              } catch (IllegalArgumentException invalid) {
                throw new InvalidHoldIdentityException(
                    "Canonical World hold state is inconsistent with current lifecycle evidence",
                    invalid);
              }
            }));
  }

  private int insert(
      Request request,
      WorldCanonicalInstanceAssociation.WorldPrepareFields privateKeys,
      HoldIdentity allocated) {
    boolean noPriorPointer =
        request.initialAdmissionOrigin() == InitialAdmissionOrigin.NO_PRIOR_POINTER;
    int inserted =
        dsl.execute(
            "INSERT INTO initial_admission_bind_hold ("
                + "hold_id, hold_fence, tenant_id, realm_uuid, playable_state_namespace_uuid, "
                + "playable_state_scope, game_instance_id, version_id, active_lifecycle_epoch, "
                + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
                + "expected_catalog_revision, status, diagnostic_expires_at, created_at, updated_at, "
                + "row_version, canonical_target_namespace, canonical_tenant_id, canonical_world_slug, "
                + "canonical_game_instance_id, canonical_version_id, initial_admission_origin, "
                + "expected_prior_pointer_version, canonical_request_bytes, hold_binding_digest) "
                + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, ?::uuid, "
                + "?, ?::uuid, ?::uuid, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            allocated.holdId(),
            allocated.holdFence(),
            privateKeys.privateTenantKey(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            privateKeys.privateGameInstanceKey(),
            privateKeys.localVersionKey(),
            request.activeLifecycleEpoch(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            noPriorPointer,
            request.expectedCatalogRevision(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.initialAdmissionOrigin().name(),
            request.expectedPriorPointerVersion(),
            allocated.canonicalRequestBytes(),
            allocated.holdBindingDigest());
    if (inserted < 0 || inserted > 1) {
      throw new InvalidHoldIdentityException(
          "Canonical World initial-admission hold insert returned an invalid row count");
    }
    return inserted;
  }

  private Record readByOwnerRequest(long privateTenantKey, Request request) {
    return dsl.fetchOne(
        HOLD_SELECT + "WHERE tenant_id = ? AND initial_admission_request_id = ?",
        privateTenantKey,
        request.initialAdmissionRequestId());
  }

  private HoldIdentity verifyStoredIdentity(
      Record row, Request expected, WorldCanonicalInstanceAssociation association) {
    try {
      if (!associationMatchesRequest(expected, association)) {
        throw new InvalidHoldIdentityException(
            "Canonical World hold request differs from its persisted owner association");
      }
      byte[] requestBytes = required(row, "canonical_request_bytes", byte[].class);
      String bindingDigest = required(row, "hold_binding_digest", String.class);
      if (requestBytes == null || bindingDigest == null) {
        throw new InvalidHoldIdentityException(
            "Legacy untyped World initial-admission hold cannot prove canonical identity");
      }
      Request storedRequest = Request.fromStored(requestBytes);
      if (!Arrays.equals(requestBytes, expected.canonicalRequestBytes())
          || !storedRequest.equals(expected)
          || !storedRequest.holdBindingDigest().equals(bindingDigest)) {
        throw new HoldConflictException(
            "Canonical World initial-admission request identity was reused with changed bindings");
      }

      var privateKeys = association.worldPrepareFields();
      if (required(row, "tenant_id", Long.class) != privateKeys.privateTenantKey()
          || required(row, "game_instance_id", Long.class) != privateKeys.privateGameInstanceKey()
          || required(row, "version_id", Long.class) != privateKeys.localVersionKey()
          || !required(row, "realm_uuid", UUID.class).equals(expected.realmId())
          || !required(row, "playable_state_namespace_uuid", UUID.class)
              .equals(expected.playableStateNamespaceId())
          || !required(row, "playable_state_scope", String.class)
              .equals(expected.playableStateScope())
          || required(row, "active_lifecycle_epoch", Long.class) != expected.activeLifecycleEpoch()
          || !required(row, "initial_admission_request_id", String.class)
              .equals(expected.initialAdmissionRequestId())
          || !required(row, "request_digest", String.class)
              .equals(expected.initialAdmissionRequestDigest())
          || required(row, "expected_no_prior_pointer", Boolean.class)
              != (expected.initialAdmissionOrigin() == InitialAdmissionOrigin.NO_PRIOR_POINTER)
          || required(row, "expected_catalog_revision", Long.class)
              != expected.expectedCatalogRevision()
          || !required(row, "canonical_target_namespace", String.class)
              .equals(expected.targetNamespace())
          || !required(row, "canonical_tenant_id", UUID.class).equals(expected.canonicalTenantId())
          || !required(row, "canonical_world_slug", String.class).equals(expected.worldSlug())
          || !required(row, "canonical_game_instance_id", UUID.class)
              .equals(expected.canonicalGameInstanceId())
          || !required(row, "canonical_version_id", UUID.class)
              .equals(expected.canonicalVersionId())
          || !required(row, "initial_admission_origin", String.class)
              .equals(expected.initialAdmissionOrigin().name())
          || !Objects.equals(
              row.get("expected_prior_pointer_version", Long.class),
              expected.expectedPriorPointerVersion())) {
        throw new InvalidHoldIdentityException(
            "Persisted canonical World initial-admission hold differs from its request or private association");
      }

      HoldIdentity identity =
          new HoldIdentity(
              storedRequest,
              required(row, "hold_id", UUID.class),
              required(row, "hold_fence", UUID.class));
      if (!identity.holdBindingDigest().equals(bindingDigest)) {
        throw new InvalidHoldIdentityException(
            "Persisted canonical World initial-admission hold digest is inconsistent");
      }
      return identity;
    } catch (InvalidHoldIdentityException | HoldConflictException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new InvalidHoldIdentityException(
          "Persisted canonical World initial-admission hold identity is incomplete or invalid",
          invalid);
    }
  }

  private static void requireSelectorMatchesRequest(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request selector) {
    if (!selector.targetNamespace().equals(request.targetNamespace())
        || !selector.canonicalTenantId().equals(request.canonicalTenantId())
        || !selector.worldSlug().equals(request.worldSlug())
        || !selector.playableStateNamespaceId().equals(request.playableStateNamespaceId())
        || !selector.playableStateScope().equals(request.playableStateScope())
        || !selector.canonicalGameInstanceId().equals(request.canonicalGameInstanceId())
        || !selector.canonicalVersionId().equals(request.canonicalVersionId())
        || !selector.publicProduction()) {
      throw new HoldConflictException(
          "Canonical World lifecycle selector differs from the complete hold target scope");
    }
  }

  private static void requireSelectorMatchesAssociation(
      WorldCanonicalInstanceLifecycleEvidence.Request selector,
      WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    if (!selector.targetNamespace().equals(identity.targetNamespace())
        || !selector.canonicalTenantId().equals(identity.canonicalTenantId())
        || !selector.worldSlug().equals(identity.worldSlug())
        || !selector.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || !selector.playableStateScope().equals(identity.playableStateScope())
        || !selector.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        || !selector.controlPlaneRequestId().equals(identity.controlPlaneRequestId())
        || !selector.canonicalVersionId().equals(association.canonicalVersionId())) {
      throw new HoldConflictException(
          "Canonical World lifecycle selector differs from its persisted association");
    }
  }

  private static void requireAssociationMatchesRequest(
      Request request, WorldCanonicalInstanceAssociation association) {
    if (!associationMatchesRequest(request, association)) {
      throw new HoldConflictException(
          "Canonical World initial-admission request differs from its persisted association");
    }
  }

  private static boolean associationMatchesRequest(
      Request request, WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    return request.targetNamespace().equals(identity.targetNamespace())
        && request.canonicalTenantId().equals(identity.canonicalTenantId())
        && request.worldSlug().equals(identity.worldSlug())
        && request.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        && request.playableStateScope().equals(identity.playableStateScope())
        && request.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        && request.canonicalVersionId().equals(association.canonicalVersionId());
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical World initial-admission acquisition requires a writable READ COMMITTED transaction");
    }
    Record state = transactionState();
    if (!"read committed".equals(required(state, "isolation", String.class))
        || !"off".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World initial-admission acquisition requires a writable READ COMMITTED transaction");
    }
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical World initial-admission identity read requires read-only REPEATABLE READ");
    }
    Record state = transactionState();
    if (!"repeatable read".equals(required(state, "isolation", String.class))
        || !"on".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World initial-admission identity read requires read-only REPEATABLE READ");
    }
  }

  private Record transactionState() {
    return Objects.requireNonNull(
        dsl.fetchOne(TRANSACTION_STATE_SQL), "World transaction state query returned no row");
  }

  private static void requireNoAmbientTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(label + " must not join an ambient transaction");
    }
  }

  private static <T> T required(Record row, String column, Class<T> type) {
    return Objects.requireNonNull(row.get(column, type), "Persisted " + column + " is null");
  }

  public static final class HoldConflictException extends IllegalStateException {
    public HoldConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidHoldIdentityException extends IllegalStateException {
    public InvalidHoldIdentityException(String message) {
      super(message);
    }

    public InvalidHoldIdentityException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
