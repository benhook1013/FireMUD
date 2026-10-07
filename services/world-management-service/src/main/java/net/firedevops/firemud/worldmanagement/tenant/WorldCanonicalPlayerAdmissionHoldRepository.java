package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account-to-World acquisition/readback component. V51 serializes insertion and every
 * lifecycle mutation on the actual owner row, and versions that tuple without changing its state or
 * counters so older MVCC snapshots cannot overlook the committed hold.
 */
public final class WorldCanonicalPlayerAdmissionHoldRepository {
  private final DSLContext dsl;
  private final String namespace;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;
  private final WorldCanonicalInstanceAssociationRepository associations;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycles;

  public WorldCanonicalPlayerAdmissionHoldRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      String namespace,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycles) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.namespace = WorldCanonicalPlayerAdmissionHoldService.requireNamespace(namespace);
    this.associations = Objects.requireNonNull(associations, "associations");
    this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
    writeTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readTransaction = new TransactionTemplate(transactionManager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
  }

  public WorldCanonicalPlayerAdmissionHoldEvidence acquire(
      WorldCanonicalPlayerAdmissionHoldEvidence.Request request) {
    requireBoundary(request);
    return Objects.requireNonNull(
        writeTransaction.execute(
            status -> {
              requireActualTransaction(false, "read committed");
              Record previous = findOriginal(request);
              if (previous != null) return retained(previous, request);

              var association =
                  associations
                      .readOwnerAssociationInActivationTransaction(
                          request.canonicalGameInstanceId())
                      .orElseThrow(() -> denied("Canonical World instance association is missing"));
              var selector = selector(association, request.attemptId());
              request.requireExactWorldSelector(selector);
              var current =
                  lifecycles
                      .readForActivationInOwnerTransaction(selector)
                      .orElseThrow(() -> denied("Canonical World lifecycle source is unavailable"));
              request.requireExactActiveWorld(current);

              // Another exact retry may have inserted while this transaction waited on the row.
              previous = findOriginal(request);
              if (previous != null) return retained(previous, request);
              UUID id = UUID.randomUUID();
              UUID fence = UUID.randomUUID();
              dsl.execute(
                  "INSERT INTO world_canonical_player_admission_hold "
                      + "(hold_id,hold_fence,lease_id,attempt_id,lease_sha256,lease_bytes,"
                      + "canonical_game_instance_id,world_instance_id,active_lifecycle_epoch,"
                      + "active_row_version,world_evidence_bytes,diagnostic_expires_at_millis) "
                      + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                  id,
                  fence,
                  request.leaseId(),
                  request.attemptId(),
                  request.lease().sha256(),
                  request.lease().canonicalJson().getBytes(StandardCharsets.UTF_8),
                  request.canonicalGameInstanceId(),
                  association.worldInstanceId(),
                  current.lifecycleEpoch(),
                  current.rowVersion(),
                  current.canonicalBytes(),
                  Long.parseLong((String) request.lease().carrier().get("expiresAt")));
              var result = retained(Objects.requireNonNull(findOriginal(request)), request);
              if (!id.equals(result.holdId()) || !fence.equals(result.holdFence())) {
                throw denied("New hold readback differs from allocated identity");
              }
              return result;
            }));
  }

  /** Exact durable retained identity; absence is UNKNOWN, never an abort or release result. */
  public Optional<WorldCanonicalPlayerAdmissionHoldEvidence> read(
      WorldCanonicalPlayerAdmissionHoldEvidence.Request request) {
    requireBoundary(request);
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireActualTransaction(true, "repeatable read");
              Record row = findOriginal(request);
              return row == null ? null : retained(row, request);
            }));
  }

  private void requireBoundary(WorldCanonicalPlayerAdmissionHoldEvidence.Request request) {
    WorldCanonicalPlayerAdmissionHoldService.requireAccountPeer(namespace);
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw denied("Lease namespace differs from World");
  }

  private Record findOriginal(WorldCanonicalPlayerAdmissionHoldEvidence.Request request) {
    return dsl.fetchOne(
        "SELECT * FROM world_canonical_player_admission_hold WHERE lease_id=? OR attempt_id=?",
        request.leaseId(),
        request.attemptId());
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence retained(
      Record row, WorldCanonicalPlayerAdmissionHoldEvidence.Request expected) {
    byte[] bytes = Objects.requireNonNull(row.get("lease_bytes", byte[].class));
    var lease =
        AccountGameplayAdmissionLeaseEvidence.parseCanonical(
            new String(bytes, StandardCharsets.UTF_8));
    var request =
        new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
            lease,
            Objects.requireNonNull(row.get("active_lifecycle_epoch", Long.class)),
            Objects.requireNonNull(row.get("active_row_version", Long.class)));
    if (!expected.sameBinding(request)
        || !lease.sha256().equals(row.get("lease_sha256", String.class))
        || !request.leaseId().equals(row.get("lease_id", UUID.class))
        || !request.attemptId().equals(row.get("attempt_id", UUID.class))
        || !request
            .canonicalGameInstanceId()
            .equals(row.get("canonical_game_instance_id", UUID.class))
        || Long.parseLong((String) lease.carrier().get("expiresAt"))
            != Objects.requireNonNull(row.get("diagnostic_expires_at_millis", Long.class))
        || !"HELD".equals(row.get("hold_state", String.class))
        || row.get("account_terminal_evidence_bytes") != null
        || row.get("game_session_completion_evidence_bytes") != null) {
      throw denied("Retained hold conflicts with the complete original lease/attempt binding");
    }
    return new WorldCanonicalPlayerAdmissionHoldEvidence(
        row.get("hold_id", UUID.class),
        row.get("hold_fence", UUID.class),
        request,
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            row.get("world_evidence_bytes", byte[].class)));
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request selector(
      WorldCanonicalInstanceAssociation association, UUID readId) {
    var identity = association.identity();
    var binding = association.completeLaunchBinding();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        readId,
        identity.targetNamespace(),
        identity.canonicalTenantId(),
        identity.worldSlug(),
        identity.canonicalGameInstanceId(),
        identity.playableStateNamespaceId(),
        identity.playableStateScope(),
        identity.publicProduction(),
        identity.controlPlaneRequestId(),
        association.canonicalVersionId(),
        binding.descriptor().requestDigest(),
        binding.descriptor().resultDigest(),
        binding.evidence().releaseAttestation().evidenceDigest());
  }

  private void requireActualTransaction(boolean readOnly, String isolation) {
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"));
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly() != readOnly
        || !isolation.equals(state.get("isolation", String.class))
        || !(readOnly ? "on" : "off").equals(state.get("read_only", String.class))) {
      throw denied("World hold requires its owned transaction isolation and mutability");
    }
  }

  static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw denied("World hold requires no ambient transaction");
    }
  }

  private static IllegalStateException denied(String message) {
    return new IllegalStateException(message);
  }
}
