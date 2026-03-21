package net.firedevops.firemud.gamesession.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayPathReadinessHealthIndicatorTest {

  @Test
  void healthReturnsUpWhenAccountAndGameLogicAreReachable() {
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(
            mock(AccountClient.class), mock(GameLogicClient.class));

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("UP", health.getDetails().get("accountService"));
    assertEquals("UP", health.getDetails().get("gameLogicService"));
  }

  @Test
  void healthReturnsOutOfServiceWhenAccountDependencyFails() {
    AccountClient accountClient = mock(AccountClient.class);
    doThrow(new IllegalStateException("account down")).when(accountClient).ping();
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(accountClient, mock(GameLogicClient.class));

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", health.getDetails().get("accountService"));
  }

  @Test
  void healthReturnsOutOfServiceWhenGameLogicDependencyFails() {
    GameLogicClient gameLogicClient = mock(GameLogicClient.class);
    doThrow(new IllegalStateException("logic down")).when(gameLogicClient).ping();
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(mock(AccountClient.class), gameLogicClient);

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("UP", health.getDetails().get("accountService"));
    assertEquals("DOWN", health.getDetails().get("gameLogicService"));
  }
}
