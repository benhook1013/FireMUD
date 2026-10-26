package net.firedevops.firemud.gamesession.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalGameplayAccountMemberRepairOperationTest {
  private static final UUID TRANSITION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID COVERAGE_FENCE =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID ACCOUNT_INDEX_FENCE =
      UUID.fromString("66666666-6666-4666-8666-666666666666");

  @Test
  void retainsExactTenantQualifiedMemberAndBindingLocalAckScope() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation(TENANT_ID);

    assertThat(operation.bindingRef().tenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(operation.member())
        .isEqualTo(
            new CanonicalGameplayAccountIndexMember(
                    operation.bindingRef(), operation.bindingGeneration(), ACCOUNT_INDEX_FENCE)
                .value());

    var acknowledgement =
        new CanonicalGameplayAccountMemberAcknowledgement(
            operation, BigInteger.valueOf(12), operation.member());
    assertThat(acknowledgement.schemaVersion()).isEqualTo(2);
    assertThat(acknowledgement.accountCoverageState())
        .isEqualTo(
            CanonicalGameplayAccountMemberAcknowledgement.AccountCoverageState
                .NO_ACTIVE_ACCOUNT_WIDE_FLOW);
    assertThat(acknowledgement.accountCoverageOperationId())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.accountCoverageLifecycle())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.accountCoverageOperationFence())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.accountAdmissionFence())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.historicalAccountAdmissionFence())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.resolvedScope())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.NotApplicable.NOT_APPLICABLE);
    assertThat(acknowledgement.coverageGeneration()).isGreaterThan(BigInteger.valueOf(11));
    assertThat(acknowledgement.readback().state())
        .isEqualTo(CanonicalGameplayAccountMemberAcknowledgement.ReadbackState.PRESENT);
  }

  @Test
  void rejectsCrossTenantBindingReferenceAndMemberThatDoesNotMatchFrozenTuple() {
    CanonicalGameplayBindingRef reference = reference(TENANT_ID);
    var obligation = obligation(reference, TENANT_ID);
    String exactMember =
        new CanonicalGameplayAccountIndexMember(reference, BigInteger.TEN, ACCOUNT_INDEX_FENCE)
            .value();

    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountMemberRepairOperation(
                    TRANSITION_ID,
                    OPERATION_ID,
                    BigInteger.ONE,
                    COVERAGE_FENCE,
                    BigInteger.valueOf(11),
                    reference,
                    ACCOUNT_ID,
                    UUID.fromString("77777777-7777-4777-8777-777777777777"),
                    BigInteger.TEN,
                    ACCOUNT_INDEX_FENCE,
                    obligation,
                    exactMember,
                    BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bindingRef tenant segment");

    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountMemberRepairOperation(
                    TRANSITION_ID,
                    OPERATION_ID,
                    BigInteger.ONE,
                    COVERAGE_FENCE,
                    BigInteger.valueOf(11),
                    reference,
                    ACCOUNT_ID,
                    TENANT_ID,
                    BigInteger.TEN,
                    ACCOUNT_INDEX_FENCE,
                    obligation,
                    exactMember + "-foreign",
                    BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("member differs");
  }

  @Test
  void requiresLaterEvidenceGenerationAndOriginalNewObligation() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation(TENANT_ID);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountMemberAcknowledgement(
                    operation, operation.inventorySnapshotRevision(), operation.member()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("newer than");

    CanonicalGameplayBindingRef reference = reference(TENANT_ID);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountMemberRepairOperation(
                    TRANSITION_ID,
                    OPERATION_ID,
                    BigInteger.ONE,
                    COVERAGE_FENCE,
                    BigInteger.valueOf(11),
                    reference,
                    ACCOUNT_ID,
                    TENANT_ID,
                    BigInteger.TEN,
                    ACCOUNT_INDEX_FENCE,
                    obligation(reference, TENANT_ID, BigInteger.ONE),
                    new CanonicalGameplayAccountIndexMember(
                            reference, BigInteger.TEN, ACCOUNT_INDEX_FENCE)
                        .value(),
                    BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact outstanding NEW");
  }

  private static CanonicalGameplayAccountMemberRepairOperation operation(UUID tenantId) {
    CanonicalGameplayBindingRef reference = reference(tenantId);
    return new CanonicalGameplayAccountMemberRepairOperation(
        TRANSITION_ID,
        OPERATION_ID,
        BigInteger.ONE,
        COVERAGE_FENCE,
        BigInteger.valueOf(11),
        reference,
        ACCOUNT_ID,
        tenantId,
        BigInteger.TEN,
        ACCOUNT_INDEX_FENCE,
        obligation(reference, tenantId),
        new CanonicalGameplayAccountIndexMember(reference, BigInteger.TEN, ACCOUNT_INDEX_FENCE)
            .value(),
        BigInteger.ZERO);
  }

  private static CanonicalGameplayBindingRef reference(UUID tenantId) {
    return CanonicalGameplayBindingRef.of(
        tenantId,
        UUID.fromString("88888888-8888-4888-8888-888888888888"),
        "99999999-9999-4999-8999-999999999999");
  }

  private static CanonicalGameplayBindingAccountIndexObligation obligation(
      CanonicalGameplayBindingRef reference, UUID tenantId) {
    return obligation(reference, tenantId, null);
  }

  private static CanonicalGameplayBindingAccountIndexObligation obligation(
      CanonicalGameplayBindingRef reference, UUID tenantId, BigInteger expectedPriorGeneration) {
    return new CanonicalGameplayBindingAccountIndexObligation(
        TRANSITION_ID,
        0,
        reference,
        ACCOUNT_ID,
        tenantId,
        CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN,
        BigInteger.TEN,
        expectedPriorGeneration,
        ACCOUNT_INDEX_FENCE,
        CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS,
        CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED,
        BigInteger.TEN,
        CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED,
        null,
        null);
  }
}
