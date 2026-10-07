package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local terminal storage and immutable Game Session proof readback for canonical holds. */
public final class WorldCanonicalInitialAdmissionHoldFinalizationRepository {
  private static final String TRANSACTION_STATE_SQL =
      "SELECT current_setting('transaction_isolation') AS isolation, "
          + "current_setting('transaction_read_only') AS read_only";
  private static final String HOLD_SELECT =
      "SELECT hold_id, hold_fence, tenant_id, realm_uuid, playable_state_namespace_uuid, "
          + "playable_state_scope, game_instance_id, version_id, active_lifecycle_epoch, "
          + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
          + "expected_catalog_revision, status, owner_proof_id, owner_proof_digest, "
          + "owner_pointer_audit_id, owner_pointer_version, terminal_at, row_version, "
          + "canonical_target_namespace, canonical_tenant_id, canonical_world_slug, "
          + "canonical_game_instance_id, canonical_version_id, initial_admission_origin, "
          + "expected_prior_pointer_version, canonical_request_bytes, hold_binding_digest, "
          + "canonical_owner_proof_bytes, canonical_owner_proof_digest "
          + "FROM initial_admission_bind_hold ";
  private static final String FINALIZE_SQL =
      "SELECT world_finalize_canonical_initial_admission_hold("
          + "?::uuid, ?::uuid, ?::text, ?::text, ?::text, ?::text, ?::bigint, "
          + "?::timestamptz, ?::boolean, ?::bytea, ?::text)";

  private final DSLContext dsl;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleReadRepository;

  public WorldCanonicalInitialAdmissionHoldFinalizationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleReadRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.lifecycleReadRepository =
        Objects.requireNonNull(lifecycleReadRepository, "lifecycleReadRepository");
    writeTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Reads and verifies a committed typed hold in the caller's exact owner snapshot. The caller owns
   * lifecycle freshness; this method returns no current ACTIVE or admission authority.
   */
  public Optional<GameSessionCanonicalInitialAdmissionOwnerProof> readCommittedInOwnerTransaction(
      UUID holdId) {
    Objects.requireNonNull(holdId, "holdId");
    OwnerTransaction owner = requireOwnerTransaction();
    Record row = readByHoldId(holdId, false);
    if (row == null) return Optional.empty();
    if (!hasCanonicalRequest(row)) {
      requireNoPartialCanonicalMetadata(row);
      return Optional.empty();
    }

    Request request = readRequest(row);
    WorldCanonicalInstanceAssociation association =
        associationForOwnerSnapshot(request.canonicalGameInstanceId(), owner);
    HoldIdentity identity = verifyStoredIdentity(row, request, association);
    String status = required(row, "status", String.class);
    if ("PENDING".equals(status)) {
      requireNoTerminalProof(row);
      return Optional.empty();
    }
    if (!"COMMITTED".equals(status) && !"ABORTED".equals(status)) {
      throw invalid("Canonical initial-admission hold has an unsupported terminal state");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof = verifyStoredProof(row, identity);
    return proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
        ? Optional.of(proof)
        : Optional.empty();
  }

  /**
   * Applies one authenticated terminal outcome in an independent writable READ COMMITTED
   * transaction. The held verifier is checked both before mutation and in the transaction's
   * before-commit phase, so authority loss rolls back the terminal write.
   */
  GameSessionCanonicalInitialAdmissionOwnerProof finalizeTerminal(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof proof,
      WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof heldProof) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(proof, "proof");
    Objects.requireNonNull(heldProof, "heldProof");
    requireNoAmbientTransaction("World canonical initial-admission hold finalization");
    requireProofForIdentity(expectedIdentity, proof);

    return writeTransaction.execute(
        status -> {
          requireWritableReadCommittedTransaction();
          heldProof.requireHeld();
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                  heldProof.requireHeld();
                }
              });

          Record candidate = readByHoldId(expectedIdentity.holdId(), false);
          if (candidate == null) {
            throw denied("Canonical initial-admission hold identity is missing");
          }
          if (!hasCanonicalRequest(candidate)) {
            requireNoPartialCanonicalMetadata(candidate);
            throw denied("Legacy untyped initial-admission hold cannot be finalized canonically");
          }
          Request request = readRequest(candidate);
          WorldCanonicalInstanceAssociation association =
              associationRepository
                  .readOwnerAssociationInActivationTransaction(request.canonicalGameInstanceId())
                  .orElseThrow(() -> denied("Canonical World association is missing"));
          HoldIdentity candidateIdentity = verifyStoredIdentity(candidate, request, association);
          if (!Arrays.equals(
              candidateIdentity.canonicalBytes(), expectedIdentity.canonicalBytes())) {
            throw denied(
                "Canonical World hold identity or fence differs from the requested outcome");
          }

          String candidateStatus = required(candidate, "status", String.class);
          if (isTerminal(candidateStatus)) {
            return exactTerminalRetry(candidate, candidateIdentity, proof);
          }
          if (!"PENDING".equals(candidateStatus)) {
            throw denied("Canonical World hold is not a terminalizable PENDING row");
          }

          WorldCanonicalInstanceLifecycleEvidence.Request selector = lifecycleSelector(association);
          WorldCanonicalInstanceLifecycleEvidence lifecycle =
              lifecycleReadRepository
                  .readForActivationInOwnerTransaction(selector)
                  .orElseThrow(() -> denied("Canonical World lifecycle row is missing"));
          if (!selector.equals(lifecycle.request())
              || !"ACTIVE".equals(lifecycle.lifecycleStatus())
              || lifecycle.lifecycleEpoch() != request.activeLifecycleEpoch()) {
            throw denied(
                "Canonical World target is not the exact requested ACTIVE lifecycle epoch");
          }

          Record locked = readByHoldId(expectedIdentity.holdId(), true);
          if (locked == null) {
            throw denied("Canonical initial-admission hold disappeared before terminalization");
          }
          HoldIdentity lockedIdentity = verifyStoredIdentity(locked, request, association);
          if (!Arrays.equals(lockedIdentity.canonicalBytes(), expectedIdentity.canonicalBytes())) {
            throw denied("Canonical World hold identity changed before terminalization");
          }
          String lockedStatus = required(locked, "status", String.class);
          if (isTerminal(lockedStatus)) {
            return exactTerminalRetry(locked, lockedIdentity, proof);
          }
          if (!"PENDING".equals(lockedStatus)) {
            throw denied("Canonical World hold is not a terminalizable PENDING row");
          }

          heldProof.requireHeld();
          dsl.fetchOne(
              FINALIZE_SQL,
              expectedIdentity.holdId(),
              expectedIdentity.holdFence(),
              proof.outcome().name(),
              request.initialAdmissionRequestId(),
              proof.proofDigest().substring("sha256:".length()),
              proof.auditEventId() == null ? null : proof.auditEventId().toString(),
              proof.committedPointerVersion(),
              proof.terminalAt().atOffset(ZoneOffset.UTC),
              proof.positiveDurableAbort(),
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof),
              sha256Prefix(
                  GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof)));

          Record stored = readByHoldId(expectedIdentity.holdId(), false);
          if (stored == null) {
            throw invalid("Canonical initial-admission terminal write has no owner readback");
          }
          GameSessionCanonicalInitialAdmissionOwnerProof readback =
              verifyStoredProof(stored, expectedIdentity);
          if (!Arrays.equals(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(readback),
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof))) {
            throw invalid(
                "Canonical initial-admission terminal readback differs from Game Session proof");
          }
          heldProof.requireHeld();
          return readback;
        });
  }

  private GameSessionCanonicalInitialAdmissionOwnerProof exactTerminalRetry(
      Record row,
      HoldIdentity identity,
      GameSessionCanonicalInitialAdmissionOwnerProof requestedProof) {
    GameSessionCanonicalInitialAdmissionOwnerProof stored = verifyStoredProof(row, identity);
    if (!Arrays.equals(
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(stored),
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(requestedProof))) {
      throw denied("Canonical initial-admission hold already has a different terminal owner proof");
    }
    return stored;
  }

  private WorldCanonicalInstanceAssociation associationForOwnerSnapshot(
      UUID canonicalGameInstanceId, OwnerTransaction owner) {
    return (owner == OwnerTransaction.READ_ONLY_REPEATABLE_READ
            ? associationRepository.readOwnerAssociationInOwnerTransaction(canonicalGameInstanceId)
            : associationRepository.readOwnerAssociationInActivationTransaction(
                canonicalGameInstanceId))
        .orElseThrow(() -> denied("Canonical World association is missing"));
  }

  private WorldCanonicalInstanceLifecycleEvidence.Request lifecycleSelector(
      WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    var descriptor = association.completeLaunchBinding().descriptor();
    var release = association.completeLaunchBinding().evidence().releaseAttestation();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        UUID.randomUUID(),
        identity.targetNamespace(),
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

  private Record readByHoldId(UUID holdId, boolean lock) {
    return dsl.fetchOne(HOLD_SELECT + "WHERE hold_id = ?" + (lock ? " FOR UPDATE" : ""), holdId);
  }

  private HoldIdentity verifyStoredIdentity(
      Record row, Request request, WorldCanonicalInstanceAssociation association) {
    try {
      byte[] requestBytes = required(row, "canonical_request_bytes", byte[].class);
      Request storedRequest = WorldCanonicalInitialAdmissionHold.Request.fromStored(requestBytes);
      if (!request.equals(storedRequest)
          || !Arrays.equals(requestBytes, request.canonicalRequestBytes())
          || !request
              .holdBindingDigest()
              .equals(required(row, "hold_binding_digest", String.class))) {
        throw invalid("Persisted canonical World hold request bytes or digest are inconsistent");
      }

      var canonical = association.identity();
      var privateKeys = association.worldPrepareFields();
      if (!request.targetNamespace().equals(canonical.targetNamespace())
          || !request.canonicalTenantId().equals(canonical.canonicalTenantId())
          || !request.worldSlug().equals(canonical.worldSlug())
          || !request.playableStateNamespaceId().equals(canonical.playableStateNamespaceId())
          || !request.playableStateScope().equals(canonical.playableStateScope())
          || !request.canonicalGameInstanceId().equals(canonical.canonicalGameInstanceId())
          || !request.canonicalVersionId().equals(association.canonicalVersionId())
          || required(row, "tenant_id", Long.class) != privateKeys.privateTenantKey()
          || required(row, "game_instance_id", Long.class) != privateKeys.privateGameInstanceKey()
          || required(row, "version_id", Long.class) != privateKeys.localVersionKey()
          || !request.realmId().equals(required(row, "realm_uuid", UUID.class))
          || !request
              .playableStateNamespaceId()
              .equals(required(row, "playable_state_namespace_uuid", UUID.class))
          || !request
              .playableStateScope()
              .equals(required(row, "playable_state_scope", String.class))
          || request.activeLifecycleEpoch() != required(row, "active_lifecycle_epoch", Long.class)
          || !request
              .initialAdmissionRequestId()
              .equals(required(row, "initial_admission_request_id", String.class))
          || !request
              .initialAdmissionRequestDigest()
              .equals(required(row, "request_digest", String.class))
          || request.expectedCatalogRevision()
              != required(row, "expected_catalog_revision", Long.class)
          || (request.initialAdmissionOrigin() == InitialAdmissionOrigin.NO_PRIOR_POINTER)
              != required(row, "expected_no_prior_pointer", Boolean.class)
          || !request
              .targetNamespace()
              .equals(required(row, "canonical_target_namespace", String.class))
          || !request.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
          || !request.worldSlug().equals(required(row, "canonical_world_slug", String.class))
          || !request
              .canonicalGameInstanceId()
              .equals(required(row, "canonical_game_instance_id", UUID.class))
          || !request.canonicalVersionId().equals(required(row, "canonical_version_id", UUID.class))
          || !request
              .initialAdmissionOrigin()
              .name()
              .equals(required(row, "initial_admission_origin", String.class))
          || !Objects.equals(
              row.get("expected_prior_pointer_version", Long.class),
              request.expectedPriorPointerVersion())) {
        throw invalid(
            "Persisted canonical hold differs from its actual association or private keys");
      }
      return new HoldIdentity(
          request, required(row, "hold_id", UUID.class), required(row, "hold_fence", UUID.class));
    } catch (InvalidFinalizationEvidenceException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new InvalidFinalizationEvidenceException(
          "Persisted canonical initial-admission identity is incomplete or invalid", invalid);
    }
  }

  private Request readRequest(Record row) {
    try {
      byte[] bytes = required(row, "canonical_request_bytes", byte[].class);
      return WorldCanonicalInitialAdmissionHold.Request.fromStored(bytes);
    } catch (RuntimeException invalid) {
      throw new InvalidFinalizationEvidenceException(
          "Persisted canonical initial-admission request is invalid", invalid);
    }
  }

  private GameSessionCanonicalInitialAdmissionOwnerProof verifyStoredProof(
      Record row, HoldIdentity identity) {
    try {
      byte[] bytes = required(row, "canonical_owner_proof_bytes", byte[].class);
      String storedDigest = required(row, "canonical_owner_proof_digest", String.class);
      if (!sha256Prefix(bytes).equals(storedDigest)) {
        throw invalid("Persisted canonical owner proof digest differs from its complete bytes");
      }
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(bytes);
      if (!identity.equals(proof.holdIdentity())) {
        throw invalid(
            "Persisted Game Session owner proof differs from the exact World hold identity");
      }
      String status = required(row, "status", String.class);
      String expectedStatus = proof.outcome().name();
      if (!expectedStatus.equals(status)
          || "PENDING".equals(status)
          || !identity
              .request()
              .initialAdmissionRequestId()
              .equals(required(row, "owner_proof_id", String.class))
          || !proof
              .proofDigest()
              .substring("sha256:".length())
              .equals(required(row, "owner_proof_digest", String.class))) {
        throw invalid("Persisted canonical terminal fields differ from the complete owner proof");
      }

      LocalDateTime terminalAt = required(row, "terminal_at", LocalDateTime.class);
      Instant terminalInstant = terminalAt.toInstant(ZoneOffset.UTC);
      if (!proof.terminalAt().equals(terminalInstant)) {
        throw invalid("Persisted canonical terminal timestamp differs from the exact owner proof");
      }
      if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
        if (!Long.toString(proof.auditEventId())
                .equals(required(row, "owner_pointer_audit_id", String.class))
            || !Objects.equals(
                proof.committedPointerVersion(),
                required(row, "owner_pointer_version", Long.class))) {
          throw invalid("Persisted committed pointer and audit fields differ from owner proof");
        }
        requireNextPointerVersion(identity.request(), proof);
      } else if (proof.auditEventId() != null
          || proof.committedPointerVersion() != null
          || row.get("owner_pointer_audit_id", String.class) != null
          || row.get("owner_pointer_version", Long.class) != null
          || !proof.positiveDurableAbort()) {
        throw invalid("Persisted aborted outcome lacks positive durable abort-only evidence");
      }
      return proof;
    } catch (InvalidFinalizationEvidenceException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new InvalidFinalizationEvidenceException(
          "Persisted canonical Game Session terminal proof is incomplete or invalid", invalid);
    }
  }

  private static void requireProofForIdentity(
      HoldIdentity expectedIdentity, GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    if (!expectedIdentity.equals(proof.holdIdentity())
        || proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw denied("Game Session owner proof is not terminal for the exact requested hold");
    }
    if (proof.terminalAt().getNano() % 1_000 != 0) {
      throw denied("Game Session terminal timestamp exceeds World storage precision");
    }
    if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      requireNextPointerVersion(expectedIdentity.request(), proof);
      if (proof.positiveDurableAbort()) {
        throw denied("Committed owner proof cannot assert durable abort");
      }
    } else if (!proof.positiveDurableAbort()) {
      throw denied("Aborted owner proof requires positive durable fencing");
    }
  }

  private static void requireNextPointerVersion(
      Request request, GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    long expectedVersion;
    try {
      expectedVersion =
          switch (request.initialAdmissionOrigin()) {
            case NO_PRIOR_POINTER -> 1L;
            case EXPECT_CLOSED -> Math.addExact(request.expectedPriorPointerVersion(), 1L);
          };
    } catch (ArithmeticException overflow) {
      throw denied("Expected prior pointer version cannot advance without overflow");
    }
    if (!Long.valueOf(expectedVersion).equals(proof.committedPointerVersion())) {
      throw denied("Committed owner proof does not name the exact next pointer version");
    }
  }

  private static void requireNoTerminalProof(Record row) {
    if (row.get("canonical_owner_proof_bytes", byte[].class) != null
        || row.get("canonical_owner_proof_digest", String.class) != null
        || row.get("owner_proof_id", String.class) != null
        || row.get("owner_proof_digest", String.class) != null
        || row.get("owner_pointer_audit_id", String.class) != null
        || row.get("owner_pointer_version", Long.class) != null
        || row.get("terminal_at", LocalDateTime.class) != null) {
      throw invalid("Canonical PENDING hold contains terminal owner proof fields");
    }
  }

  private static boolean hasCanonicalRequest(Record row) {
    return row.get("canonical_request_bytes", byte[].class) != null;
  }

  private static void requireNoPartialCanonicalMetadata(Record row) {
    for (String field :
        new String[] {
          "canonical_target_namespace",
          "canonical_tenant_id",
          "canonical_world_slug",
          "canonical_game_instance_id",
          "canonical_version_id",
          "initial_admission_origin",
          "expected_prior_pointer_version",
          "hold_binding_digest",
          "canonical_owner_proof_bytes",
          "canonical_owner_proof_digest"
        }) {
      if (row.get(field, Object.class) != null) {
        throw invalid("Retained legacy hold has partial canonical metadata");
      }
    }
  }

  private static boolean isTerminal(String status) {
    return "COMMITTED".equals(status) || "ABORTED".equals(status);
  }

  private OwnerTransaction requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Typed hold read requires the caller's active owner transaction");
    }
    boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    OwnerTransaction owner;
    if (readOnly && Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(isolation)) {
      owner = OwnerTransaction.READ_ONLY_REPEATABLE_READ;
    } else if (!readOnly
        && Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(isolation)) {
      owner = OwnerTransaction.WRITABLE_READ_COMMITTED;
    } else {
      throw new IllegalStateException(
          "Typed hold read requires a read-only REPEATABLE READ or writable READ COMMITTED owner transaction");
    }
    Record state = transactionState();
    if (owner == OwnerTransaction.READ_ONLY_REPEATABLE_READ
        && (!"repeatable read".equals(required(state, "isolation", String.class))
            || !"on".equals(required(state, "read_only", String.class)))) {
      throw new IllegalStateException(
          "Typed hold read requires a read-only REPEATABLE READ owner transaction");
    }
    if (owner == OwnerTransaction.WRITABLE_READ_COMMITTED
        && (!"read committed".equals(required(state, "isolation", String.class))
            || !"off".equals(required(state, "read_only", String.class)))) {
      throw new IllegalStateException(
          "Typed hold read requires a writable READ COMMITTED owner transaction");
    }
    return owner;
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical World initial-admission finalization requires writable READ COMMITTED");
    }
    Record state = transactionState();
    if (!"read committed".equals(required(state, "isolation", String.class))
        || !"off".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World initial-admission finalization requires writable READ COMMITTED");
    }
  }

  private Record transactionState() {
    return Objects.requireNonNull(
        dsl.fetchOne(TRANSACTION_STATE_SQL), "World transaction state query returned no row");
  }

  private static String sha256Prefix(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    return Objects.requireNonNull(row.get(field, type), "Persisted " + field + " is null");
  }

  private static void requireNoAmbientTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(label + " must start outside an ambient transaction");
    }
  }

  private static InvalidFinalizationEvidenceException invalid(String message) {
    return new InvalidFinalizationEvidenceException(message);
  }

  private static FinalizationDeniedException denied(String message) {
    return new FinalizationDeniedException(message);
  }

  private enum OwnerTransaction {
    READ_ONLY_REPEATABLE_READ,
    WRITABLE_READ_COMMITTED
  }

  public static final class InvalidFinalizationEvidenceException extends IllegalStateException {
    public InvalidFinalizationEvidenceException(String message) {
      super(message);
    }

    public InvalidFinalizationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class FinalizationDeniedException extends IllegalStateException {
    public FinalizationDeniedException(String message) {
      super(message);
    }
  }
}
