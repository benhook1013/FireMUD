package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalGameplayAccountInitialBarrierValueTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void initialBarrierValueRetainsTheExactPositivelyReadEpochIncludingZero() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();

    var value =
        CanonicalGameplayAccountInitialBarrierValue.open(
            accountId,
            tenantId,
            namespaceId,
            "g7-" + "b".repeat(32),
            initializerId,
            BigInteger.ZERO,
            DIGEST);

    assertThat(value.fields())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "schema",
                CanonicalGameplayAccountInitialBarrierValue.SCHEMA,
                "accountId",
                accountId.toString(),
                "tenantId",
                tenantId.toString(),
                "playableStateNamespaceId",
                namespaceId.toString(),
                "tenantGameplayTag",
                "g7-" + "b".repeat(32),
                "ownerOperationId",
                initializerId.toString(),
                "ownerFenceEpoch",
                "0",
                "incarnationDigest",
                DIGEST,
                "state",
                "OPEN"));
    assertThat(value.fields().keySet())
        .containsExactlyInAnyOrderElementsOf(CanonicalGameplayAccountInitialBarrierValue.FIELDS);
  }

  @Test
  void currentOpenProofRequiresExactCapturedEpochOriginalAttemptAndNineFields() {
    UUID initializerId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var value =
        CanonicalGameplayAccountInitialBarrierValue.open(
            accountId,
            tenantId,
            namespaceId,
            "g8-" + "c".repeat(32),
            initializerId,
            BigInteger.valueOf(9),
            DIGEST);

    var proof =
        new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
            initializerId,
            requestId,
            callerContextId,
            9,
            accountId,
            tenantId,
            namespaceId,
            "session:game:account-admission:{g8-"
                + "c".repeat(32)
                + "}:"
                + accountId
                + ":"
                + namespaceId,
            BigInteger.valueOf(9),
            BigInteger.ONE,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            31_000L,
            value.fields());

    assertThat(proof.originalAttemptFence()).isEqualTo(9L);
    assertThat(proof.sourceControlEpoch()).isEqualTo(BigInteger.valueOf(9));
    assertThat(proof.exactFields()).containsEntry("ownerFenceEpoch", "9");
    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
                    initializerId,
                    requestId,
                    callerContextId,
                    9,
                    accountId,
                    tenantId,
                    namespaceId,
                    proof.key(),
                    BigInteger.ONE,
                    BigInteger.ONE,
                    DIGEST,
                    DIGEST,
                    DIGEST,
                    1_000L,
                    31_000L,
                    value.fields()))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, String> changed = new LinkedHashMap<>(value.fields());
    changed.put("ownerFenceEpoch", "1");
    assertThatThrownBy(
            () ->
                new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
                    initializerId,
                    requestId,
                    callerContextId,
                    9,
                    accountId,
                    tenantId,
                    namespaceId,
                    proof.key(),
                    BigInteger.ZERO,
                    BigInteger.ONE,
                    DIGEST,
                    DIGEST,
                    DIGEST,
                    1_000L,
                    31_000L,
                    changed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unavailableSourcesHaveTypedNonAdmittingRefusals() {
    assertThat(
            new CanonicalGameplayAccountInitialOpenProof.Refused(
                CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN))
        .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class)
        .extracting(CanonicalGameplayAccountInitialOpenProof.Refused::reason)
        .isEqualTo(CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN);
  }

  @Test
  void initialKeyUsesCanonicalBarrierSerializationOnlyForItsPersistedOriginalTarget() {
    UUID initializerId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.tenantGameplayTag()).thenReturn("g7-" + tenantId.toString().replace("-", ""));
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);

    String key =
        CanonicalGameplayAccountInitialBarrierKey.forTarget(assignment, initializerId, requestId);

    assertThat(key)
        .isEqualTo(
            "session:game:account-admission:{g7-"
                + tenantId.toString().replace("-", "")
                + "}:"
                + accountId
                + ":"
                + namespaceId);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAccountInitialBarrierKey.forTarget(
                    assignment, UUID.randomUUID(), requestId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
