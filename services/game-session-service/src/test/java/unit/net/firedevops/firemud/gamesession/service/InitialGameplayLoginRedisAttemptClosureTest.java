package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextRedisClient.HeldQualification;
import org.junit.jupiter.api.Test;

/**
 * Controlled adapter ordering proof; the native suite owns physical Redis and fixture
 * qualification.
 */
class InitialGameplayLoginRedisAttemptClosureTest {
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 7);

  @Test
  void continuationRunsOnceInsideTheHeldExternalExclusionAfterExactClosure() {
    var client = mock(InitialGameplayLoginContextRedisClient.class);
    var held = mock(HeldQualification.class);
    var calls = new AtomicInteger();
    when(client.acquireQualification()).thenReturn(Optional.of(held));
    when(client.closeOriginal(ORIGINAL, held))
        .thenReturn(Optional.of(InitialGameplayLoginContextState.closed(ORIGINAL)));
    var adapter = new InitialGameplayLoginRedisAttemptClosure(client);
    assertThat(
            adapter.withAcknowledgedClosure(
                ORIGINAL,
                receipt -> {
                  assertThat(receipt.coordinates()).isEqualTo(ORIGINAL);
                  verify(held, never()).close();
                  calls.incrementAndGet();
                  return "retirement";
                }))
        .contains("retirement");
    assertThat(calls).hasValue(1);
    var order = inOrder(client, held);
    order.verify(client).acquireQualification();
    order.verify(client).closeOriginal(ORIGINAL, held);
    order.verify(held, org.mockito.Mockito.times(2)).requireCurrent();
    order.verify(held).close();
  }

  @Test
  void unavailableClosureNeverDispatchesAndReleasesItsQualification() {
    var client = mock(InitialGameplayLoginContextRedisClient.class);
    var held = mock(HeldQualification.class);
    when(client.acquireQualification()).thenReturn(Optional.of(held));
    when(client.closeOriginal(ORIGINAL, held)).thenReturn(Optional.empty());
    var adapter = new InitialGameplayLoginRedisAttemptClosure(client);
    assertThat(
            adapter.withAcknowledgedClosure(
                ORIGINAL,
                receipt -> {
                  throw new AssertionError("No dispatch");
                }))
        .isEmpty();
    verify(held).close();
  }

  @Test
  void lostQualificationBeforeContinuationNeverDispatches() {
    var client = mock(InitialGameplayLoginContextRedisClient.class);
    var held = mock(HeldQualification.class);
    when(client.acquireQualification()).thenReturn(Optional.of(held));
    when(client.closeOriginal(ORIGINAL, held))
        .thenReturn(Optional.of(InitialGameplayLoginContextState.closed(ORIGINAL)));
    doThrow(new IllegalStateException("qualification lost")).when(held).requireCurrent();
    var adapter = new InitialGameplayLoginRedisAttemptClosure(client);
    assertThatThrownBy(
            () ->
                adapter.withAcknowledgedClosure(
                    ORIGINAL,
                    receipt -> {
                      throw new AssertionError("No dispatch");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(held).close();
  }

  @Test
  void continuationFailureIsNotHiddenOrRetriedAndExclusionIsReleased() {
    var client = mock(InitialGameplayLoginContextRedisClient.class);
    var held = mock(HeldQualification.class);
    var calls = new AtomicInteger();
    when(client.acquireQualification()).thenReturn(Optional.of(held));
    when(client.closeOriginal(ORIGINAL, held))
        .thenReturn(Optional.of(InitialGameplayLoginContextState.closed(ORIGINAL)));
    var adapter = new InitialGameplayLoginRedisAttemptClosure(client);
    assertThatThrownBy(
            () ->
                adapter.withAcknowledgedClosure(
                    ORIGINAL,
                    receipt -> {
                      calls.incrementAndGet();
                      throw new IllegalStateException("Account unavailable");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account unavailable");
    assertThat(calls).hasValue(1);
    verify(held).close();
  }
}
