package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountGameplayDelegationResponseEnvelopeServiceTest {
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final CallerIdentity CALLER =
      new CallerIdentity(
          "spiffe://firemud/ns/test/sa/game-session-service",
          UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436"));

  @Test
  void delegatesCandidateSealingAndReturnsOnlyNonAuthorizingMetadata()
      throws ReflectiveOperationException {
    AccountGameplayDelegationResponseEnvelopeRepository repository =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationResponseEnvelopeService service =
        new AccountGameplayDelegationResponseEnvelopeService(repository);
    String transientJwt = "candidate.jwt.bytes";
    SealedCandidateObservation observation =
        new SealedCandidateObservation(
            UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0"),
            REQUEST_ID,
            "a".repeat(64),
            13L,
            1_896_602_520_000L,
            "b".repeat(64),
            64);
    when(repository.sealPendingCandidate(REQUEST_ID, CALLER, transientJwt)).thenReturn(observation);

    assertThat(service.sealPendingCandidate(REQUEST_ID, CALLER, transientJwt))
        .isEqualTo(observation);
    assertThat(observation.toString()).doesNotContain(transientJwt);
    verify(repository).sealPendingCandidate(REQUEST_ID, CALLER, transientJwt);

    Transactional transaction =
        AccountGameplayDelegationResponseEnvelopeService.class
            .getMethod("sealPendingCandidate", UUID.class, CallerIdentity.class, String.class)
            .getAnnotation(Transactional.class);
    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
