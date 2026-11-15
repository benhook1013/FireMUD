package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import org.junit.jupiter.api.Test;

/**
 * Direct comparison tests for the terminal-first request identity; no database or authentication is
 * implied.
 */
class WorldProtectedActivationAcquisitionMatchTest {
  @Test
  void acceptsOnlyTheStableOriginalTupleOwnerAttemptFenceAndPreparingIdentity() {
    var input =
        mock(
            AccountStartSessionActivationProtectionAcquisitionInput.class,
            org.mockito.Mockito.withSettings()
                .defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
    var evidence =
        mock(
            AccountStartSessionActivationProtectionEvidence.class,
            org.mockito.Mockito.withSettings()
                .defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
    when(input.originalPostAuthorizationTuple()).thenReturn(new byte[] {1, 2, 3});
    when(input.gameSessionOwnerMutationId())
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(input.gameSessionOwnerAttemptId())
        .thenReturn(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    when(input.gameSessionOwnerFence()).thenReturn(5L);
    when(input.worldActivationRequest().activationRequestId())
        .thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    when(input.worldActivationRequest().canonicalRequestBytes()).thenReturn(new byte[] {4, 5, 6});

    when(evidence.request().originalPostAuthorizationTupleBytes()).thenReturn(new byte[] {1, 2, 3});
    when(evidence.request().gameSessionOwnerMutationId())
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(evidence.request().gameSessionOwnerAttemptId())
        .thenReturn(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    when(evidence.request().gameSessionOwnerFence()).thenReturn(5L);
    when(evidence.request().worldActivationRequest().activationRequestId())
        .thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    when(evidence.request().worldActivationRequest().canonicalRequestBytes())
        .thenReturn(new byte[] {4, 5, 6});

    assertThatCode(
            () ->
                WorldCanonicalInstanceActivationRepository.requireSameAcquisitionInput(
                    input, evidence))
        .doesNotThrowAnyException();

    when(input.originalPostAuthorizationTuple()).thenReturn(new byte[] {9});
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceActivationRepository.requireSameAcquisitionInput(
                    input, evidence))
        .isInstanceOf(WorldCanonicalInstanceActivationRepository.ActivationConflictException.class)
        .hasMessageContaining("changed its exact original caller acquisition statement");
  }
}
