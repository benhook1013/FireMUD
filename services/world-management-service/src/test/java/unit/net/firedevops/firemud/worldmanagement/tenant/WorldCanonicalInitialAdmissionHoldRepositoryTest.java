package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation.CanonicalIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation.WorldPrepareFields;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInitialAdmissionHoldRepositoryTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID CANONICAL_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID READ_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_ID = "gs-initial-admission-17";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String OTHER_REQUEST_DIGEST = "b".repeat(64);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void acquisitionRejectsAmbientActualAndSynchronizationTransactions() {
    Fixture fixture = fixture(noPriorRequest(), activeLifecycle("ACTIVE", 7L), null);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> fixture.repository.acquire(noPriorRequest(), selector(noPriorRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> fixture.repository.acquire(noPriorRequest(), selector(noPriorRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(fixture.dsl, fixture.associations, fixture.lifecycle);
    assertThat(fixture.manager.startedWith).isNull();
  }

  @Test
  void exactRetryReturnsTheOriginalHoldAndFenceWithoutInsertChurn() {
    Request request = noPriorRequest();
    Record existing = storedRow(request, HOLD_ID, HOLD_FENCE);
    Fixture fixture = fixture(request, activeLifecycle("ACTIVE", 7L), existing);

    HoldIdentity result = fixture.repository.acquire(request, selector(request));

    assertThat(result.request()).isEqualTo(request);
    assertThat(result.holdId()).isEqualTo(HOLD_ID);
    assertThat(result.holdFence()).isEqualTo(HOLD_FENCE);
    assertThat(result.canonicalRequestBytes()).containsExactly(request.canonicalRequestBytes());
    assertThat(result.holdBindingDigest()).isEqualTo(request.holdBindingDigest());
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
    verify(fixture.lifecycle)
        .readForActivationInOwnerTransaction(selector(request));
    verify(fixture.associations)
        .readOwnerAssociationInActivationTransaction(CANONICAL_INSTANCE);
  }

  @Test
  void changedRequestAndLegacyUnmappedCollisionAreDenied() {
    Request original = noPriorRequest();
    Request changed = request(OTHER_REQUEST_DIGEST, InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    Fixture changedFixture =
        fixture(changed, activeLifecycle("ACTIVE", 7L), storedRow(original, HOLD_ID, HOLD_FENCE));

    assertThatThrownBy(() -> changedFixture.repository.acquire(changed, selector(changed)))
        .isInstanceOf(WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException.class);
    verify(changedFixture.dsl, never()).execute(anyString(), any(Object[].class));

    Fixture legacyFixture =
        fixture(original, activeLifecycle("ACTIVE", 7L), legacyStoredRow(original));
    assertThatThrownBy(() -> legacyFixture.repository.acquire(original, selector(original)))
        .isInstanceOf(WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException.class);
    verify(legacyFixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void incompleteMetadataBadBindingDigestAndSubstitutedScopeAreDenied() {
    Request request = noPriorRequest();

    Map<String, Object> incomplete = storedValues(request, HOLD_ID, HOLD_FENCE);
    incomplete.remove("canonical_version_id");
    assertStoredRowDenied(request, row(incomplete));

    Map<String, Object> badDigest = storedValues(request, HOLD_ID, HOLD_FENCE);
    badDigest.put("hold_binding_digest", "sha256:" + "0".repeat(64));
    assertStoredRowDenied(request, row(badDigest));

    Map<String, Object> substitutedScope = storedValues(request, HOLD_ID, HOLD_FENCE);
    substitutedScope.put("canonical_world_slug", "different-world");
    assertStoredRowDenied(request, row(substitutedScope));
  }

  @Test
  void staleEpochOrNonActiveLifecycleCannotAcquire() {
    Request request = noPriorRequest();
    for (WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence :
        new WorldCanonicalInstanceLifecycleEvidence[] {
          activeLifecycle("PREPARING", 7L), activeLifecycle("ACTIVE", 6L)
        }) {
      Fixture fixture = fixture(request, lifecycleEvidence, null);

      assertThatThrownBy(() -> fixture.repository.acquire(request, selector(request)))
          .isInstanceOf(WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException.class);

      verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
      verify(fixture.associations, never())
          .readOwnerAssociationInActivationTransaction(CANONICAL_INSTANCE);
    }
  }

  @Test
  void expectClosedAcquisitionPersistsItsTagPriorVersionAndExactRequestReadback() {
    Request request = expectClosedRequest(13L);
    Fixture fixture = fixture(request, activeLifecycle("ACTIVE", 7L), null);
    AtomicReference<Record> insertedRow = new AtomicReference<>();
    fixture.holdRow.set(null);
    doAnswer(
            invocation -> {
              Object[] bindings = invocation.getArgument(1);
              insertedRow.set(
                  storedRow(
                      request,
                      UUID.fromString(bindings[0].toString()),
                      UUID.fromString(bindings[1].toString())));
              fixture.holdRow.set(insertedRow.get());
              return 1;
            })
        .when(fixture.dsl)
        .execute(anyString(), any(Object[].class));

    HoldIdentity result = fixture.repository.acquire(request, selector(request));

    assertThat(result.request()).isEqualTo(request);
    assertThat(result.holdBindingDigest()).isNotEqualTo(request.initialAdmissionRequestDigest());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> bindings = ArgumentCaptor.forClass(Object[].class);
    verify(fixture.dsl).execute(sql.capture(), bindings.capture());
    assertThat(sql.getValue()).contains("'PENDING'").contains("CURRENT_TIMESTAMP");
    assertThat(sql.getValue()).contains("ON CONFLICT DO NOTHING");
    assertThat(bindings.getValue()[11]).isEqualTo(false);
    assertThat(bindings.getValue()[18]).isEqualTo("EXPECT_CLOSED");
    assertThat(bindings.getValue()[19]).isEqualTo(13L);
    assertThat(bindings.getValue()[20]).isEqualTo(request.canonicalRequestBytes());
    assertThat(bindings.getValue()[21]).isEqualTo(request.holdBindingDigest());
    assertThat(result.holdId()).isEqualTo(UUID.fromString(bindings.getValue()[0].toString()));
    assertThat(result.holdFence()).isEqualTo(UUID.fromString(bindings.getValue()[1].toString()));
  }

  @Test
  void insertWithoutExactReadbackFailsClosed() {
    Request request = noPriorRequest();
    Fixture fixture = fixture(request, activeLifecycle("ACTIVE", 7L), null);
    when(fixture.dsl.execute(anyString(), any(Object[].class))).thenReturn(0);

    assertThatThrownBy(() -> fixture.repository.acquire(request, selector(request)))
        .isInstanceOf(WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException.class)
        .hasMessageContaining("did not produce exact request readback");
  }

  @Test
  void conflictInsertUsesExactWinningReadbackWithoutReturningItsCandidateIds() {
    Request request = noPriorRequest();
    Fixture fixture = fixture(request, activeLifecycle("ACTIVE", 7L), null);
    AtomicReference<Object[]> candidateBindings = new AtomicReference<>();
    doAnswer(
            invocation -> {
              candidateBindings.set(invocation.getArgument(1));
              fixture.holdRow.set(storedRow(request, HOLD_ID, HOLD_FENCE));
              return 0;
            })
        .when(fixture.dsl)
        .execute(anyString(), any(Object[].class));

    HoldIdentity result = fixture.repository.acquire(request, selector(request));
    Object[] candidate = candidateBindings.get();

    assertThat(result.holdId()).isEqualTo(HOLD_ID);
    assertThat(result.holdFence()).isEqualTo(HOLD_FENCE);
    assertThat(candidate[0]).isNotEqualTo(HOLD_ID);
    assertThat(candidate[1]).isNotEqualTo(HOLD_FENCE);
  }

  @Test
  void identityReadReturnsHistoricalAcquisitionWithoutUsingCurrentLifecycleRead() {
    Request request = noPriorRequest();
    Fixture fixture = fixture(request, activeLifecycle("TERMINATED", 20L), storedRow(request, HOLD_ID, HOLD_FENCE));

    Optional<HoldIdentity> result = fixture.repository.readIdentity(request);

    assertThat(result).contains(new HoldIdentity(request, HOLD_ID, HOLD_FENCE));
    verify(fixture.lifecycle, never())
        .readForActivationInOwnerTransaction(any(WorldCanonicalInstanceLifecycleEvidence.Request.class));
    verify(fixture.associations)
        .readOwnerAssociationInOwnerTransaction(CANONICAL_INSTANCE);
  }

  private static void assertStoredRowDenied(Request request, Record storedRow) {
    Fixture fixture = fixture(request, activeLifecycle("ACTIVE", 7L), storedRow);
    assertThatThrownBy(() -> fixture.repository.acquire(request, selector(request)))
        .isInstanceOf(IllegalStateException.class);
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static Fixture fixture(
      Request request,
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence,
      Record existingHold) {
    DSLContext dsl = Mockito.mock(DSLContext.class);
    RecordingTransactionManager manager = new RecordingTransactionManager();
    WorldCanonicalInstanceAssociationRepository associations =
        Mockito.mock(WorldCanonicalInstanceAssociationRepository.class);
    WorldCanonicalInstanceLifecycleReadRepository lifecycle =
        Mockito.mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    WorldCanonicalInstanceAssociation association = association();
    AtomicReference<Record> holdRow = new AtomicReference<>(existingHold);

    when(lifecycle.readForActivationInOwnerTransaction(selector(request)))
        .thenReturn(Optional.of(lifecycleEvidence));
    when(associations.readOwnerAssociationInActivationTransaction(CANONICAL_INSTANCE))
        .thenReturn(Optional.of(association));
    when(associations.readOwnerAssociationInOwnerTransaction(CANONICAL_INSTANCE))
        .thenReturn(Optional.of(association));
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("current_setting")) {
                return transactionState();
              }
              if (sql.contains("FROM initial_admission_bind_hold")) {
                return holdRow.get();
              }
              return null;
            });

    return new Fixture(
        new WorldCanonicalInitialAdmissionHoldRepository(
            dsl, manager, associations, lifecycle),
        dsl,
        manager,
        associations,
        lifecycle,
        holdRow);
  }

  private static WorldCanonicalInstanceAssociation association() {
    WorldCanonicalInstanceAssociation association =
        Mockito.mock(WorldCanonicalInstanceAssociation.class);
    CanonicalIdentity identity =
        new CanonicalIdentity(
            CANONICAL_INSTANCE,
            "prod",
            TENANT,
            "green-hollow",
            PLAYABLE_NAMESPACE,
            "SHARED",
            true,
            "control-request");
    WorldPrepareFields prepareFields =
        new WorldPrepareFields(
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
    when(association.identity()).thenReturn(identity);
    when(association.worldPrepareFields()).thenReturn(prepareFields);
    when(association.canonicalVersionId()).thenReturn(VERSION);
    return association;
  }

  private static WorldCanonicalInstanceLifecycleEvidence activeLifecycle(
      String status, long epoch) {
    WorldCanonicalInstanceLifecycleEvidence evidence =
        Mockito.mock(WorldCanonicalInstanceLifecycleEvidence.class);
    Request request = noPriorRequest();
    when(evidence.request()).thenReturn(selector(request));
    when(evidence.lifecycleStatus()).thenReturn(status);
    when(evidence.lifecycleEpoch()).thenReturn(epoch);
    return evidence;
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request selector(Request request) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        READ_ID,
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.canonicalGameInstanceId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        true,
        "control-request",
        request.canonicalVersionId(),
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static Request noPriorRequest() {
    return request(REQUEST_DIGEST, InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
  }

  private static Request expectClosedRequest(long pointerVersion) {
    return request(REQUEST_DIGEST, InitialAdmissionOrigin.EXPECT_CLOSED, pointerVersion);
  }

  private static Request request(String digest, InitialAdmissionOrigin origin, Long priorPointerVersion) {
    return new Request(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        CANONICAL_INSTANCE,
        VERSION,
        7L,
        REQUEST_ID,
        digest,
        origin,
        12L,
        priorPointerVersion);
  }

  private static Record storedRow(Request request, UUID holdId, UUID holdFence) {
    return row(storedValues(request, holdId, holdFence));
  }

  private static Map<String, Object> storedValues(Request request, UUID holdId, UUID holdFence) {
    Map<String, Object> values = new HashMap<>();
    values.put("hold_id", holdId);
    values.put("hold_fence", holdFence);
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

  private static Record legacyStoredRow(Request request) {
    Map<String, Object> values = storedValues(request, HOLD_ID, HOLD_FENCE);
    values.put("canonical_target_namespace", null);
    values.put("canonical_tenant_id", null);
    values.put("canonical_world_slug", null);
    values.put("canonical_game_instance_id", null);
    values.put("canonical_version_id", null);
    values.put("initial_admission_origin", null);
    values.put("expected_prior_pointer_version", null);
    values.put("canonical_request_bytes", null);
    values.put("hold_binding_digest", null);
    return row(values);
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

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInitialAdmissionHoldRepository repository,
      DSLContext dsl,
      RecordingTransactionManager manager,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      AtomicReference<Record> holdRow) {}

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    private TransactionDefinition startedWith;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }
  }
}
