package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Donor247 read-state vectors through the actual repository, with controlled SQL/owner responses.
 * This is unit proof, not physical snapshot isolation, authenticated owner or restart-scan proof.
 */
class WorldCanonicalInitialAdmissionHoldReadStateTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID REALM = UUID.randomUUID();
  private static final UUID PLAYABLE_NAMESPACE = UUID.randomUUID();
  private static final UUID GAME_INSTANCE = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();
  private static final UUID HOLD_ID = UUID.randomUUID();
  private static final UUID HOLD_FENCE = UUID.randomUUID();
  private static final Request REQUEST =
      new Request(
          "test",
          TENANT,
          "starter-world",
          REALM,
          PLAYABLE_NAMESPACE,
          "SHARED",
          GAME_INSTANCE,
          VERSION,
          7L,
          "initial-admission-request",
          "a".repeat(64),
          WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
          3L,
          null);
  private static final HoldIdentity IDENTITY = new HoldIdentity(REQUEST, HOLD_ID, HOLD_FENCE);
  private static final WorldCanonicalInstanceLifecycleEvidence.Request SELECTOR =
      new WorldCanonicalInstanceLifecycleEvidence.Request(
          1,
          UUID.randomUUID(),
          "test",
          TENANT,
          "starter-world",
          GAME_INSTANCE,
          PLAYABLE_NAMESPACE,
          "SHARED",
          true,
          "control-request",
          VERSION,
          "sha256:" + "1".repeat(64),
          "sha256:" + "2".repeat(64),
          "sha256:" + "3".repeat(64));

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pendingReadUsesOneReadOnlyRepeatableReadSnapshotAndExactOwnersWithoutMutation() {
    Fixture f = fixture(storedValues(), "ACTIVE", 7L);
    var state = f.repository().readState(IDENTITY, SELECTOR).orElseThrow();

    assertThat(state.holdIdentity()).isEqualTo(IDENTITY);
    assertThat(state.holdStatus().name()).isEqualTo("PENDING");
    assertThat(state.lifecycleEvidence()).isSameAs(f.evidence());
    assertThat(state.isPendingAtExpectedActiveEpoch()).isTrue();
    assertThat(f.manager().definition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(f.manager().definition.isReadOnly()).isTrue();
    assertThat(f.manager().started).isEqualTo(1);
    assertThat(f.manager().committed).isEqualTo(1);
    verify(f.associations()).readOwnerAssociationInOwnerTransaction(GAME_INSTANCE);
    verify(f.lifecycle()).readForCurrentLocationInOwnerTransaction(SELECTOR);
    verify(f.sql())
        .fetchOne(
            "SELECT * FROM initial_admission_bind_hold WHERE hold_id=? AND hold_fence=?",
            HOLD_ID,
            HOLD_FENCE);
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMMITTED", "ABORTED", "RECONCILIATION_REQUIRED"})
  void terminalAndReconciliationStatesAreObservationsNotPendingAuthority(String status) {
    Map<String, Object> values = storedValues();
    values.put("status", status);
    Fixture f = fixture(values, "ACTIVE", 7L);
    var state = f.repository().readState(IDENTITY, SELECTOR).orElseThrow();
    assertThat(state.holdIdentity()).isEqualTo(IDENTITY);
    assertThat(state.holdStatus().name()).isEqualTo(status);
    assertThat(state.isPendingAtExpectedActiveEpoch()).isFalse();
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @MethodSource("advancedLifecycles")
  void advancedLifecycleCannotRequalifyPendingHold(String status, long epoch) {
    Fixture f = fixture(storedValues(), status, epoch);
    var state = f.repository().readState(IDENTITY, SELECTOR).orElseThrow();
    assertThat(state.lifecycleEvidence().lifecycleStatus()).isEqualTo(status);
    assertThat(state.lifecycleEvidence().lifecycleEpoch()).isEqualTo(epoch);
    assertThat(state.isPendingAtExpectedActiveEpoch()).isFalse();
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  private static Stream<Arguments> advancedLifecycles() {
    return Stream.of(
        Arguments.of("ACTIVE", 8L),
        Arguments.of("TERMINATING", 8L),
        Arguments.of("TERMINATED", 9L));
  }

  @Test
  void missingHoldStopsBeforeAssociationOrLifecycleRead() {
    Fixture f = fixture(null, "ACTIVE", 7L);
    assertThat(f.repository().readState(IDENTITY, SELECTOR)).isEmpty();
    verifyNoInteractions(f.associations(), f.lifecycle());
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @MethodSource("corruptedStoredFields")
  void changedOrIncompleteStoredIdentityRefusesBeforeOwnerLookup(String field, Object value) {
    Map<String, Object> values = storedValues();
    values.put(field, value);
    Fixture f = fixture(values, "ACTIVE", 7L);
    assertThatThrownBy(() -> f.repository().readState(IDENTITY, SELECTOR))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);
    verifyNoInteractions(f.associations(), f.lifecycle());
    assertThat(f.manager().rolledBack).isEqualTo(1);
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  private static Stream<Arguments> corruptedStoredFields() {
    return Stream.of(
        Arguments.of("canonical_request_bytes", new byte[] {1}),
        Arguments.of("hold_binding_digest", "sha256:" + "b".repeat(64)),
        Arguments.of("canonical_target_namespace", "other"),
        Arguments.of("canonical_tenant_id", UUID.randomUUID()),
        Arguments.of("canonical_version_id", UUID.randomUUID()),
        Arguments.of("hold_id", UUID.randomUUID()),
        Arguments.of("hold_fence", UUID.randomUUID()),
        Arguments.of("expected_catalog_revision", 4L),
        Arguments.of("active_lifecycle_epoch", 8L),
        Arguments.of("initial_admission_origin", "EXPECT_CLOSED"),
        Arguments.of("expected_prior_pointer_version", 1L),
        Arguments.of("canonical_game_instance_id", UUID.randomUUID()),
        Arguments.of("request_digest", "c".repeat(64)),
        Arguments.of("canonical_request_bytes", null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", ""})
  void unsupportedStatusRefusesRatherThanFabricatingPendingOrTerminal(String status) {
    Map<String, Object> values = storedValues();
    values.put("status", status);
    Fixture f = fixture(values, "ACTIVE", 7L);
    assertThatThrownBy(() -> f.repository().readState(IDENTITY, SELECTOR))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class)
        .hasMessage("Canonical World hold status is unsupported");
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void missingStatusRefusesRatherThanFabricatingPendingOrTerminal() {
    Map<String, Object> values = storedValues();
    values.remove("status");
    Fixture f = fixture(values, "ACTIVE", 7L);
    assertThatThrownBy(() -> f.repository().readState(IDENTITY, SELECTOR))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class)
        .hasMessage("Canonical World hold column is missing: status");
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void absentAssociationOrLifecycleRefusesWithoutQualifyingStoredHoldAlone() {
    Fixture absentAssociation = fixture(storedValues(), "ACTIVE", 7L);
    when(absentAssociation.associations().readOwnerAssociationInOwnerTransaction(GAME_INSTANCE))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> absentAssociation.repository().readState(IDENTITY, SELECTOR))
        .hasMessage("Canonical World association is unavailable in the hold snapshot");
    verifyNoInteractions(absentAssociation.lifecycle());

    Fixture absentLifecycle = fixture(storedValues(), "ACTIVE", 7L);
    when(absentLifecycle.lifecycle().readForCurrentLocationInOwnerTransaction(SELECTOR))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> absentLifecycle.repository().readState(IDENTITY, SELECTOR))
        .hasMessage("Canonical World lifecycle is unavailable in the hold snapshot");
    verify(absentAssociation.sql(), never()).execute(anyString(), any(Object[].class));
    verify(absentLifecycle.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void substitutedAssociationRefusesBeforeLifecycleLookup() {
    Fixture f = fixture(storedValues(), "ACTIVE", 7L);
    when(f.association().canonicalVersionId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> f.repository().readState(IDENTITY, SELECTOR))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class)
        .hasMessage("Canonical World hold differs from the current immutable World association");
    verifyNoInteractions(f.lifecycle());
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void historicalIdentityReadDoesNotReconstructCurrentLifecycleOrAdmission() {
    Fixture f = fixture(storedValues(), "TERMINATED", 9L);
    assertThat(f.repository().readIdentity(REQUEST)).contains(IDENTITY);
    verifyNoInteractions(f.associations(), f.lifecycle());
    assertThat(f.manager().definition.isReadOnly()).isTrue();
    assertThat(f.manager().definition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    verify(f.sql(), never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"isolation", "read_only"})
  void sqlReportedTransactionSettingsMustAgreeWithReadOnlyRepeatableRead(String changed) {
    Fixture f = fixture(storedValues(), "ACTIVE", 7L);
    Map<String, Object> state =
        new HashMap<>(Map.of("isolation", "repeatable read", "read_only", "on"));
    state.put(changed, changed.equals("isolation") ? "read committed" : "off");
    Record reportedState = row(state);
    doReturn(reportedState).when(f.sql()).fetchOne(anyString());
    assertThatThrownBy(() -> f.repository().readState(IDENTITY, SELECTOR))
        .hasMessage(
            "Canonical World hold read requires a read-only REPEATABLE READ owner transaction");
    verify(f.sql(), never()).fetchOne(anyString(), any(Object[].class));
    verifyNoInteractions(f.associations(), f.lifecycle());
  }

  private static Fixture fixture(Map<String, Object> values, String status, long epoch) {
    DSLContext sql = mock(DSLContext.class);
    RecordingTransactionManager manager = new RecordingTransactionManager();
    var associations = mock(WorldCanonicalInstanceAssociationRepository.class);
    var lifecycle = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var association = mock(WorldCanonicalInstanceAssociation.class);
    when(association.identity())
        .thenReturn(
            new WorldCanonicalInstanceAssociation.CanonicalIdentity(
                GAME_INSTANCE,
                "test",
                TENANT,
                "starter-world",
                PLAYABLE_NAMESPACE,
                "SHARED",
                true,
                REALM,
                3L,
                "control-request"));
    when(association.canonicalVersionId()).thenReturn(VERSION);
    when(associations.readOwnerAssociationInOwnerTransaction(GAME_INSTANCE))
        .thenReturn(Optional.of(association));
    var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(evidence.request()).thenReturn(SELECTOR);
    when(evidence.lifecycleStatus()).thenReturn(status);
    when(evidence.lifecycleEpoch()).thenReturn(epoch);
    // Controlled owner response only: this unit does not claim valid wire or authenticated bytes.
    when(evidence.canonicalBytes()).thenReturn(new byte[] {1});
    when(lifecycle.readForCurrentLocationInOwnerTransaction(SELECTOR))
        .thenReturn(Optional.of(evidence));
    when(sql.fetchOne(anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
              assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
              return row(Map.of("isolation", "repeatable read", "read_only", "on"));
            });
    Record stored = values == null ? null : row(values);
    when(sql.fetchOne(anyString(), any(Object[].class))).thenReturn(stored);
    return new Fixture(
        new WorldCanonicalInitialAdmissionHoldRepository(sql, manager, associations, lifecycle),
        sql,
        manager,
        associations,
        lifecycle,
        evidence,
        association);
  }

  private static Map<String, Object> storedValues() {
    Map<String, Object> values = new HashMap<>();
    values.put("hold_id", HOLD_ID);
    values.put("hold_fence", HOLD_FENCE);
    values.put("status", "PENDING");
    values.put("canonical_request_bytes", REQUEST.canonicalRequestBytes());
    values.put("hold_binding_digest", REQUEST.holdBindingDigest());
    values.put("canonical_target_namespace", REQUEST.targetNamespace());
    values.put("canonical_tenant_id", TENANT);
    values.put("canonical_world_slug", REQUEST.worldSlug());
    values.put("canonical_game_instance_id", GAME_INSTANCE);
    values.put("canonical_version_id", VERSION);
    values.put("realm_uuid", REALM);
    values.put("playable_state_namespace_uuid", PLAYABLE_NAMESPACE);
    values.put("playable_state_scope", "SHARED");
    values.put("initial_admission_request_id", REQUEST.initialAdmissionRequestId());
    values.put("request_digest", REQUEST.initialAdmissionRequestDigest());
    values.put("active_lifecycle_epoch", 7L);
    values.put("expected_catalog_revision", 3L);
    values.put("initial_admission_origin", "NO_PRIOR_POINTER");
    values.put("expected_prior_pointer_version", null);
    values.put("expected_no_prior_pointer", true);
    return values;
  }

  private static Record row(Map<String, Object> values) {
    Record row = mock(Record.class);
    doAnswer(invocation -> values.get(invocation.getArgument(0)))
        .when(row)
        .get(anyString(), any(Class.class));
    return row;
  }

  private record Fixture(
      WorldCanonicalInitialAdmissionHoldRepository repository,
      DSLContext sql,
      RecordingTransactionManager manager,
      WorldCanonicalInstanceAssociationRepository associations,
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      WorldCanonicalInstanceLifecycleEvidence evidence,
      WorldCanonicalInstanceAssociation association) {}

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private TransactionDefinition definition;
    private int started;
    private int committed;
    private int rolledBack;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition requested) {
      definition = requested == null ? TransactionDefinition.withDefaults() : requested;
      started++;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      committed++;
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rolledBack++;
      TransactionSynchronizationManager.clear();
    }
  }
}
