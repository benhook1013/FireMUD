package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification.Lease;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Qualification-boundary source proof; no process-local production qualifier is supplied. */
class InitialGameplayLoginContextRedisClientTest {
  @AfterEach
  void clearTransactions() {
    TransactionSynchronizationManager.clear();
  }

  @ParameterizedTest
  @ValueSource(strings = {"scope", "binding", "zeroFence", "missing"})
  void refusesIncompleteOrMismatchedExternalQualificationBeforeAnyConnection(String boundary) {
    var authority = mock(InitialGameplayLoginCoordinationQualification.class);
    var lease = mock(Lease.class);
    when(lease.scope())
        .thenReturn(
            boundary.equals("scope")
                ? "other:"
                : InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE);
    when(lease.protectedBindingId())
        .thenReturn(boundary.equals("binding") ? "other" : "protected-binding");
    when(lease.fence()).thenReturn(boundary.equals("zeroFence") ? 0L : 7L);
    when(authority.acquire(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE, "protected-binding"))
        .thenReturn(boundary.equals("missing") ? Optional.empty() : Optional.of(lease));
    var client = client(authority);
    assertThat(client.acquireQualification()).isEmpty();
    if (!boundary.equals("missing")) verify(lease).close();
  }

  @Test
  void currentLeaseRequiresExactStableFenceAndReleasesExternalExclusion() {
    var authority = mock(InitialGameplayLoginCoordinationQualification.class);
    var lease = mock(Lease.class);
    when(lease.scope()).thenReturn(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE);
    when(lease.protectedBindingId()).thenReturn("protected-binding");
    when(lease.fence()).thenReturn(7L);
    when(authority.acquire(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE, "protected-binding"))
        .thenReturn(Optional.of(lease));
    var held = client(authority).acquireQualification().orElseThrow();
    when(lease.fence()).thenReturn(8L);
    org.assertj.core.api.Assertions.assertThatThrownBy(held::requireCurrent)
        .isInstanceOf(IllegalStateException.class);
    held.close();
    verify(lease).close();
  }

  @Test
  void ambientSqlTransactionCannotStartRemoteQualificationOrRedisWork() {
    var authority = mock(InitialGameplayLoginCoordinationQualification.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThat(client(authority).acquireQualification()).isEmpty();
    verifyNoInteractions(authority);
  }

  private static InitialGameplayLoginContextRedisClient client(
      InitialGameplayLoginCoordinationQualification authority) {
    var binding =
        new InitialGameplayLoginRedisBinding(
            "protected-binding",
            () -> {
              throw new AssertionError("Qualification checks must not open Redis");
            },
            new AcknowledgementPolicy(Profile.REPLICATED, 1, 1, 100));
    return new InitialGameplayLoginContextRedisClient(
        binding,
        RedisScriptCatalog.fromContributions(
            List.of(new InitialGameplayLoginRedisScriptContribution())),
        authority);
  }
}
