package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInitialAdmissionHoldFinalizationRepositoryTest {
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final Instant TERMINAL_AT = Instant.parse("2026-10-07T01:02:03.123456Z");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void ownerSnapshotReadReturnsOnlyDigestAndIdentityVerifiedCommittedProof() {
    Request request = request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    GameSessionCanonicalInitialAdmissionOwnerProof proof = committed(identity, 1L);
    Map<String, Object> values = terminalValues(request, identity, proof);
    Fixture fixture = fixture(values);
    WorldCanonicalInstanceAssociation association = association();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    when(fixture.associations.readOwnerAssociationInOwnerTransaction(INSTANCE))
        .thenReturn(Optional.of(association));

    assertThat(fixture.repository.readCommittedInOwnerTransaction(HOLD_ID)).contains(proof);
    assertThat(((LocalDateTime) values.get("terminal_at")).toInstant(ZoneOffset.UTC))
        .isEqualTo(proof.terminalAt());
  }

  @Test
  void ownerSnapshotReadVerifiesExpectedClosedPointerVersionsBeyondLongCacheRange() {
    Request request = request(InitialAdmissionOrigin.EXPECT_CLOSED, 300L);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    GameSessionCanonicalInitialAdmissionOwnerProof proof = committed(identity, 301L);
    Fixture fixture = fixture(terminalValues(request, identity, proof));
    WorldCanonicalInstanceAssociation association = association();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    when(fixture.associations.readOwnerAssociationInOwnerTransaction(INSTANCE))
        .thenReturn(Optional.of(association));

    assertThat(fixture.repository.readCommittedInOwnerTransaction(HOLD_ID)).contains(proof);
  }

  @Test
  void persistedProofDigestMismatchIsDeniedInTheSameOwnerSnapshot() {
    Request request = request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    Map<String, Object> values = terminalValues(request, identity, committed(identity, 1L));
    values.put("canonical_owner_proof_digest", "sha256:" + "0".repeat(64));
    Fixture fixture = fixture(values);
    WorldCanonicalInstanceAssociation association = association();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    when(fixture.associations.readOwnerAssociationInOwnerTransaction(INSTANCE))
        .thenReturn(Optional.of(association));

    assertThatThrownBy(() -> fixture.repository.readCommittedInOwnerTransaction(HOLD_ID))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationRepository
                .InvalidFinalizationEvidenceException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void exactTerminalRetryReturnsRetainedProofWithoutAnotherUpdate() {
    Request request = request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    GameSessionCanonicalInitialAdmissionOwnerProof proof = committed(identity, 1L);
    Fixture fixture = fixture(terminalValues(request, identity, proof));
    WorldCanonicalInstanceAssociation association = association();
    when(fixture.associations.readOwnerAssociationInActivationTransaction(INSTANCE))
        .thenReturn(Optional.of(association));
    var held = heldProof(proof);

    assertThat(fixture.repository.finalizeTerminal(identity, proof, held)).isEqualTo(proof);
    assertThat(fixture.manager.updateAttempted).isFalse();
    assertThat(fixture.manager.commitCount).isEqualTo(1);
    assertThat(fixture.manager.rollbackCount).isZero();
  }

  @Test
  void heldOwnerProofLossAfterUpdateRollsBackBeforeCommit() {
    Request request = request(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    GameSessionCanonicalInitialAdmissionOwnerProof proof = committed(identity, 1L);
    Map<String, Object> values = pendingValues(request, identity);
    Fixture fixture = fixture(values);
    WorldCanonicalInstanceAssociation association = association();
    when(fixture.associations.readOwnerAssociationInActivationTransaction(INSTANCE))
        .thenReturn(Optional.of(association));
    when(fixture.lifecycle.readForActivationInOwnerTransaction(any()))
        .thenAnswer(
            invocation -> {
              WorldCanonicalInstanceLifecycleEvidence evidence =
                  Mockito.mock(WorldCanonicalInstanceLifecycleEvidence.class);
              when(evidence.request()).thenReturn(invocation.getArgument(0));
              when(evidence.lifecycleStatus()).thenReturn("ACTIVE");
              when(evidence.lifecycleEpoch()).thenReturn(7L);
              return Optional.of(evidence);
            });

    AtomicInteger checks = new AtomicInteger();
    var heldProof = heldProof(proof, () -> fixture.manager.updateAttempted, checks);

    assertThatThrownBy(() -> fixture.repository.finalizeTerminal(identity, proof, heldProof))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lost before World commit");
    assertThat(fixture.manager.updateAttempted).isTrue();
    assertThat(fixture.manager.commitCount).isZero();
    assertThat(fixture.manager.rollbackCount).isEqualTo(1);
    assertThat(values.get("status")).isEqualTo("PENDING");
    assertThat(values.get("canonical_owner_proof_bytes")).isNull();
  }

  private static Fixture fixture(Map<String, Object> values) {
    DSLContext dsl = Mockito.mock(DSLContext.class);
    RecordingTransactionManager manager = new RecordingTransactionManager();
    WorldCanonicalInstanceAssociationRepository associations =
        Mockito.mock(WorldCanonicalInstanceAssociationRepository.class);
    WorldCanonicalInstanceLifecycleReadRepository lifecycle =
        Mockito.mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    Record holdRow = row(values);
    when(dsl.fetchOne(contains("current_setting"))).thenAnswer(invocation -> transactionState());
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM initial_admission_bind_hold")) return holdRow;
              if (sql.contains("world_finalize_canonical_initial_admission_hold")) {
                Object[] bindings = (Object[]) invocation.getRawArguments()[1];
                applyTerminal(values, bindings);
                manager.updateAttempted = true;
                return holdRow;
              }
              return null;
            });
    manager.rollbackAction = () -> restorePending(values);
    return new Fixture(
        new WorldCanonicalInitialAdmissionHoldFinalizationRepository(
            dsl, manager, associations, lifecycle),
        associations,
        lifecycle,
        manager);
  }

  private static WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof heldProof(
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    return heldProof(proof, () -> false, new AtomicInteger());
  }

  private static WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof heldProof(
      GameSessionCanonicalInitialAdmissionOwnerProof proof,
      java.util.function.BooleanSupplier updated,
      AtomicInteger checks) {
    return new WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof() {
      @Override
      public GameSessionCanonicalInitialAdmissionOwnerProof proof() {
        return proof;
      }

      @Override
      public void requireHeld() {
        if (updated.getAsBoolean() && checks.incrementAndGet() >= 4) {
          throw new IllegalStateException("owner proof hold was lost before World commit");
        }
        if (!updated.getAsBoolean()) checks.incrementAndGet();
      }

      @Override
      public void close() {}
    };
  }

  private static WorldCanonicalInstanceAssociation association() {
    WorldCanonicalInstanceAssociation association =
        Mockito.mock(WorldCanonicalInstanceAssociation.class);
    var identity =
        new WorldCanonicalInstanceAssociation.CanonicalIdentity(
            INSTANCE,
            "prod",
            TENANT,
            "green-hollow",
            PLAYABLE_NAMESPACE,
            "SHARED",
            true,
            "control-request");
    var prepare =
        new WorldCanonicalInstanceAssociation.WorldPrepareFields(
            31L,
            41L,
            101L,
            1L,
            "control-request",
            "launch-descriptor",
            12L,
            null,
            "{}",
            "generation-1",
            1L,
            "release-ref",
            1L,
            null);
    WorldCompleteLaunchBindingReceipt binding =
        Mockito.mock(WorldCompleteLaunchBindingReceipt.class);
    CompleteLaunchBindingEvidence launchEvidence =
        Mockito.mock(CompleteLaunchBindingEvidence.class);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        Mockito.mock(AuthoredWorldLaunchDescriptorEvidence.class);
    AuthoredWorldReleaseAttestationEvidence release =
        Mockito.mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(association.identity()).thenReturn(identity);
    when(association.worldPrepareFields()).thenReturn(prepare);
    when(association.canonicalVersionId()).thenReturn(VERSION);
    when(association.completeLaunchBinding()).thenReturn(binding);
    when(binding.descriptor()).thenReturn(descriptor);
    when(binding.evidence()).thenReturn(launchEvidence);
    when(launchEvidence.releaseAttestation()).thenReturn(release);
    when(descriptor.requestDigest()).thenReturn("sha256:" + "1".repeat(64));
    when(descriptor.resultDigest()).thenReturn("sha256:" + "2".repeat(64));
    when(release.evidenceDigest()).thenReturn("sha256:" + "3".repeat(64));
    return association;
  }

  private static Map<String, Object> pendingValues(Request request, HoldIdentity identity) {
    Map<String, Object> values = baseValues(request, identity);
    values.put("status", "PENDING");
    values.put("owner_proof_id", null);
    values.put("owner_proof_digest", null);
    values.put("owner_pointer_audit_id", null);
    values.put("owner_pointer_version", null);
    values.put("terminal_at", null);
    values.put("row_version", 0L);
    values.put("canonical_owner_proof_bytes", null);
    values.put("canonical_owner_proof_digest", null);
    return values;
  }

  private static Map<String, Object> terminalValues(
      Request request,
      HoldIdentity identity,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    Map<String, Object> values = pendingValues(request, identity);
    applyTerminal(
        values,
        new Object[] {
          identity.holdId(),
          identity.holdFence(),
          proof.outcome().name(),
          request.initialAdmissionRequestId(),
          proof.proofDigest().substring("sha256:".length()),
          proof.auditEventId().toString(),
          proof.committedPointerVersion(),
          proof.terminalAt().atOffset(ZoneOffset.UTC),
          proof.positiveDurableAbort(),
          GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof),
          digest(GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof))
        });
    return values;
  }

  private static Map<String, Object> baseValues(Request request, HoldIdentity identity) {
    Map<String, Object> values = new HashMap<>();
    values.put("hold_id", identity.holdId());
    values.put("hold_fence", identity.holdFence());
    values.put("tenant_id", 41L);
    values.put("realm_uuid", request.realmId());
    values.put("playable_state_namespace_uuid", request.playableStateNamespaceId());
    values.put("playable_state_scope", request.playableStateScope());
    values.put("game_instance_id", 101L);
    values.put("version_id", 12L);
    values.put("active_lifecycle_epoch", request.activeLifecycleEpoch());
    values.put("initial_admission_request_id", request.initialAdmissionRequestId());
    values.put("request_digest", request.initialAdmissionRequestDigest());
    values.put(
        "expected_no_prior_pointer",
        request.initialAdmissionOrigin() == InitialAdmissionOrigin.NO_PRIOR_POINTER);
    values.put("expected_catalog_revision", request.expectedCatalogRevision());
    values.put("canonical_target_namespace", request.targetNamespace());
    values.put("canonical_tenant_id", request.canonicalTenantId());
    values.put("canonical_world_slug", request.worldSlug());
    values.put("canonical_game_instance_id", request.canonicalGameInstanceId());
    values.put("canonical_version_id", request.canonicalVersionId());
    values.put("initial_admission_origin", request.initialAdmissionOrigin().name());
    values.put("expected_prior_pointer_version", request.expectedPriorPointerVersion());
    values.put("canonical_request_bytes", request.canonicalRequestBytes());
    values.put("hold_binding_digest", request.holdBindingDigest());
    return values;
  }

  private static void applyTerminal(Map<String, Object> values, Object[] bindings) {
    values.put("status", bindings[2]);
    values.put("owner_proof_id", bindings[3]);
    values.put("owner_proof_digest", bindings[4]);
    values.put("owner_pointer_audit_id", bindings[5]);
    values.put("owner_pointer_version", bindings[6]);
    values.put(
        "terminal_at",
        LocalDateTime.ofInstant(((OffsetDateTime) bindings[7]).toInstant(), ZoneOffset.UTC));
    values.put("canonical_owner_proof_bytes", bindings[9]);
    values.put("canonical_owner_proof_digest", bindings[10]);
    values.put("row_version", 1L);
  }

  private static void restorePending(Map<String, Object> values) {
    values.put("status", "PENDING");
    values.put("owner_proof_id", null);
    values.put("owner_proof_digest", null);
    values.put("owner_pointer_audit_id", null);
    values.put("owner_pointer_version", null);
    values.put("terminal_at", null);
    values.put("row_version", 0L);
    values.put("canonical_owner_proof_bytes", null);
    values.put("canonical_owner_proof_digest", null);
  }

  private static Record transactionState() {
    boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    String isolationName =
        Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(isolation)
            ? "repeatable read"
            : "read committed";
    return row(Map.of("isolation", isolationName, "read_only", readOnly ? "on" : "off"));
  }

  private static Record row(Map<String, Object> values) {
    Record row = Mockito.mock(Record.class);
    doAnswer(
            invocation -> {
              String name = invocation.getArgument(0);
              Object value = values.get(name);
              Class<?> type = invocation.getArgument(1);
              return value == null || type.isInstance(value) ? value : type.cast(value);
            })
        .when(row)
        .get(anyString(), any(Class.class));
    return row;
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof committed(
      HoldIdentity identity, long pointerVersion) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        pointerVersion,
        27L,
        "sha256:" + "a".repeat(64),
        false,
        TERMINAL_AT);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static Request request(InitialAdmissionOrigin origin, Long priorVersion) {
    return new Request(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        7L,
        "gs-initial-admission-17",
        "a".repeat(64),
        origin,
        12L,
        priorVersion);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInitialAdmissionHoldFinalizationRepository repository,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      RecordingTransactionManager manager) {}

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private Runnable rollbackAction = () -> {};
    private boolean updateAttempted;
    private int commitCount;
    private int rollbackCount;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition effective =
          definition == null ? TransactionDefinition.withDefaults() : definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(effective.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          effective.getIsolationLevel());
      TransactionSynchronizationManager.initSynchronization();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      try {
        for (TransactionSynchronization synchronization :
            TransactionSynchronizationManager.getSynchronizations()) {
          synchronization.beforeCommit(false);
        }
        commitCount++;
        TransactionSynchronizationManager.clear();
      } catch (RuntimeException failure) {
        rollback(status);
        throw failure;
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbackCount++;
      rollbackAction.run();
      TransactionSynchronizationManager.clear();
    }
  }
}
