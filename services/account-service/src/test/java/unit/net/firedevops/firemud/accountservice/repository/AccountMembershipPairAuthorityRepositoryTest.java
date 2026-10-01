package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.ProvenPositiveCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AccountMembershipPairAuthorityRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String EVENT_DIGEST = "sha256:" + "a".repeat(64);
  private static final String SOURCE_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void verifiedProvenanceRequiresKindQualifiedLegacyIdentity() {
    VerifiedTenantProvenance fresh = freshProvenance();
    VerifiedTenantProvenance retained = retainedProvenance(72L);

    assertThat(fresh.legacyTenantId()).isNull();
    assertThat(fresh.kind()).isEqualTo(TenantProvenanceKind.FRESH_GAME_DESIGN);
    assertThat(retained.legacyTenantId()).isEqualTo(72L);
    assertThat(retained.kind()).isEqualTo(TenantProvenanceKind.APPROVED_RETAINED);

    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    72L, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, SOURCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a legacy tenant ID");
    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    null, TenantProvenanceKind.APPROVED_RETAINED, OPERATION_ID, SOURCE_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive legacy tenant ID");
    assertThatThrownBy(() -> retainedProvenance(0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive legacy tenant ID");
    assertThatThrownBy(() -> retainedProvenance(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive legacy tenant ID");
  }

  @Test
  void pairRecordsPreservePositiveBaselineAndEventShapes() {
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    assertThat(baseline.membershipVersion()).isEqualTo(1L);
    assertThat(baseline.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(baseline.lastEventSequence()).isZero();
    assertThat(baseline.membershipExists()).isFalse();

    PairAuthority joined =
        new PairAuthority(
            ACCOUNT_UUID,
            TENANT_UUID,
            freshProvenance(),
            true,
            2L,
            1L,
            1L,
            "join-event-1",
            EVENT_DIGEST,
            false);
    assertThat(joined.membershipVersion()).isEqualTo(2L);
    assertThat(joined.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(joined.lastEventSequence()).isEqualTo(1L);

    PairTransition leave = new PairTransition(false, 2L, "leave-event-2", EVENT_DIGEST, true);
    assertThat(leave.eventSequence()).isEqualTo(2L);
    assertThat(leave.callerBoundAuthorityInvalidated()).isTrue();
    ProvenPositiveCheckpoint retainedCheckpoint =
        new ProvenPositiveCheckpoint(8L, 3L, 11L, "retained-event-11", EVENT_DIGEST, true);
    assertThat(retainedCheckpoint.membershipVersion()).isEqualTo(8L);
    assertThat(retainedCheckpoint.membershipAuthorityGeneration()).isEqualTo(3L);
    assertThat(retainedCheckpoint.eventSequence()).isEqualTo(11L);

    assertThatThrownBy(
            () ->
                new PairAuthority(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    freshProvenance(),
                    false,
                    0L,
                    1L,
                    0L,
                    null,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
    assertThatThrownBy(
            () ->
                new PairAuthority(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    freshProvenance(),
                    false,
                    1L,
                    0L,
                    0L,
                    null,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
    assertThatThrownBy(
            () ->
                new PairAuthority(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    freshProvenance(),
                    true,
                    1L,
                    1L,
                    0L,
                    null,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sequence-zero");
    assertThatThrownBy(
            () ->
                new PairAuthority(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    freshProvenance(),
                    true,
                    2L,
                    1L,
                    1L,
                    " ",
                    EVENT_DIGEST,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event evidence");
    assertThatThrownBy(
            () -> new ProvenPositiveCheckpoint(8L, 3L, 0L, "event-0", EVENT_DIGEST, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
    assertThatThrownBy(() -> new PairTransition(true, 0L, "event-0", EVENT_DIGEST, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
  }

  @Test
  void directRepositoryOperationsRequireOwnerTransactionBeforeDslAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipPairAuthorityRepository repository =
        new AccountMembershipPairAuthorityRepository(dsl);
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    PairTransition transition = new PairTransition(true, 1L, "join-event-1", EVENT_DIGEST, false);
    ProvenPositiveCheckpoint checkpoint =
        new ProvenPositiveCheckpoint(8L, 3L, 11L, "retained-event-11", EVENT_DIGEST, true);

    assertThatThrownBy(() -> repository.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThatThrownBy(
            () ->
                repository.enrollProvenPositive(
                    ACCOUNT_UUID, TENANT_UUID, retainedProvenance(72L), checkpoint))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThatThrownBy(() -> repository.readForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThatThrownBy(() -> repository.commitTransition(baseline, transition))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");

    verifyNoInteractions(dsl);
  }

  private VerifiedTenantProvenance freshProvenance() {
    return new VerifiedTenantProvenance(
        null, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION_ID, SOURCE_DIGEST);
  }

  private VerifiedTenantProvenance retainedProvenance(long legacyTenantId) {
    return new VerifiedTenantProvenance(
        legacyTenantId, TenantProvenanceKind.APPROVED_RETAINED, OPERATION_ID, SOURCE_DIGEST);
  }
}
