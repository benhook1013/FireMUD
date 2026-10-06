package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipPairAuthorityRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String EVENT_ID_TEXT = "join-event-1";
  private static final String EVENT_DIGEST = "sha256:" + "b".repeat(64);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void freshProvenanceCannotCarryInventedNumericTenantIdentity() {
    VerifiedTenantProvenance fresh =
        new VerifiedTenantProvenance(
            null, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, DIGEST);

    org.assertj.core.api.Assertions.assertThat(fresh.legacyTenantId()).isNull();
    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    87L, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a legacy tenant ID");
  }

  @Test
  void pairAuthorityRepresentsSequenceZeroOnlyAsNonmembershipEvidence() {
    VerifiedTenantProvenance fresh =
        new VerifiedTenantProvenance(
            null, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, DIGEST);

    PairAuthority baseline =
        new PairAuthority(ACCOUNT_UUID, TENANT_UUID, fresh, false, 1L, 1L, 0L, null, null, false);
    assertThat(baseline.membershipExists()).isFalse();
    assertThat(baseline.eventSequence()).isZero();
    assertThatThrownBy(
            () ->
                new PairAuthority(
                    ACCOUNT_UUID, TENANT_UUID, fresh, true, 1L, 1L, 0L, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot claim membership or event evidence");
  }

  @Test
  void repositoryRequiresOwnerTransactionBeforeReading() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);

    assertThatThrownBy(() -> repository.readPositive(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(dsl);
  }

  @Test
  void positiveReadRejectsTheSequenceZeroBaselineAsNonAuthority() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    doReturn(pairRecord(false, 1L, 1L, 0L, null, null, false))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readPositive(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no positive authority event");
  }

  @Test
  void positiveReadOfAnAbsentPairReturnsEmptyWithoutWriting() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    doReturn(null).when(dsl).fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThat(repository.readPositive(ACCOUNT_UUID, TENANT_UUID)).isEmpty();
    verify(dsl).fetchOne(anyString(), any(Object[].class));
    verify(dsl, org.mockito.Mockito.never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void positiveReadRejectsACommittedNonmembershipState() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    doReturn(pairRecord(false, 2L, 1L, 1L, EVENT_ID_TEXT, EVENT_DIGEST, false))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readPositive(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no positive authority event");
  }

  @Test
  void enrollAbsencePersistsOnlyTheExactFreshEvidenceBaseline() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    doReturn(pairRecord(false, 1L, 1L, 0L, null, null, false))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    PairAuthority baseline = repository.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance());

    assertThat(baseline.membershipExists()).isFalse();
    assertThat(baseline.eventSequence()).isZero();
    verify(dsl).execute(anyString(), any(Object[].class));
  }

  @Test
  void enrollAbsenceRejectsPriorPairHistoryWithoutResettingIt() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    doReturn(pairRecord(true, 4L, 2L, 3L, "join-event-3", EVENT_DIGEST, true))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("baseline readback differs");
    verify(dsl).execute(anyString(), any(Object[].class));
  }

  @Test
  void pairMutationRejectsReadOnlyOwnerTransactionsAndRetainedProvenance() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(() -> repository.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    VerifiedTenantProvenance retained =
        new VerifiedTenantProvenance(
            87L, TenantProvenanceKind.APPROVED_RETAINED, OPERATION_ID, DIGEST);
    assertThatThrownBy(() -> repository.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, retained))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fresh Game Design");
    verifyNoInteractions(dsl);
  }

  @Test
  void firstJoinCompareAndAdvanceRequiresExactEventLinkageAndReadback() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    PairAuthority advanced =
        new PairAuthority(
            ACCOUNT_UUID,
            TENANT_UUID,
            freshProvenance(),
            true,
            2L,
            1L,
            1L,
            EVENT_ID_TEXT,
            EVENT_DIGEST,
            false);
    Record baselineRow = pairRecord(false, 1L, 1L, 0L, null, null, false);
    Record advancedRow = pairRecord(true, 2L, 1L, 1L, EVENT_ID_TEXT, EVENT_DIGEST, false);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenReturn(baselineRow)
        .thenReturn(advancedRow);
    when(dsl.execute(anyString(), any(Object[].class))).thenReturn(1);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    PairAuthority actual =
        repository.commitTransition(
            baseline, new PairTransition(true, 1L, EVENT_ID_TEXT, EVENT_DIGEST, false));

    assertThat(actual).isEqualTo(advanced);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).execute(sql.capture(), any(Object[].class));
    assertThat(sql.getValue())
        .contains("AND legacy_tenant_id IS NULL")
        .contains("AND tenant_provenance_kind = ?")
        .contains("AND tenant_source_operation_id = ?")
        .contains("AND tenant_provenance_digest = ?")
        .contains("AND membership_exists = FALSE")
        .contains("AND membership_version = ?")
        .contains("AND membership_authority_generation = ?")
        .contains("AND last_event_sequence = 0")
        .contains("AND last_event_id IS NULL")
        .contains("AND last_event_digest IS NULL")
        .contains("AND last_transition_invalidated = FALSE");
  }

  @Test
  void firstJoinPairCompareAndAdvanceRejectsStaleVersionBeforeWriting() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    PairAuthority staleExpected =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 2L, 1L, 0L, null, null, false);
    doReturn(pairRecord(false, 1L, 1L, 0L, null, null, false))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                repository.commitTransition(
                    staleExpected,
                    new PairTransition(true, 1L, EVENT_ID_TEXT, EVENT_DIGEST, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stale or contradictory");
    verify(dsl, org.mockito.Mockito.never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void firstJoinPairCompareAndAdvanceRejectsLaterSequenceBeforeWriting() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                repository.commitTransition(
                    baseline, new PairTransition(true, 2L, EVENT_ID_TEXT, EVENT_DIGEST, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("first positive JOIN transition");
    verifyNoInteractions(dsl);
  }

  private static VerifiedTenantProvenance freshProvenance() {
    return new VerifiedTenantProvenance(
        null, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, DIGEST);
  }

  private static Record pairRecord(
      boolean membershipExists,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long eventSequence,
      String eventId,
      String eventDigest,
      boolean invalidated) {
    Record row = mock(Record.class);
    when(row.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_UUID);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(TENANT_UUID);
    when(row.get("legacy_tenant_id", Long.class)).thenReturn(null);
    when(row.get("tenant_provenance_kind", String.class)).thenReturn("FRESH_GAME_DESIGN");
    when(row.get("tenant_source_operation_id", UUID.class)).thenReturn(OPERATION_ID);
    when(row.get("tenant_provenance_digest", String.class)).thenReturn(DIGEST);
    when(row.get("membership_exists", Boolean.class)).thenReturn(membershipExists);
    when(row.get("membership_version", Long.class)).thenReturn(membershipVersion);
    when(row.get("membership_authority_generation", Long.class))
        .thenReturn(membershipAuthorityGeneration);
    when(row.get("last_event_sequence", Long.class)).thenReturn(eventSequence);
    when(row.get("last_event_id", String.class)).thenReturn(eventId);
    when(row.get("last_event_digest", String.class)).thenReturn(eventDigest);
    when(row.get("last_transition_invalidated", Boolean.class)).thenReturn(invalidated);
    return row;
  }
}
