package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import org.junit.jupiter.api.Test;

class GameplayPresenceRoleClassifierTest {
  @Test
  void classifyRoleReturnsPlayerUntilFreshGameplayGrantEvidenceIsModeled() {
    assertEquals(GameplayPresenceRole.PLAYER, GameplayPresenceRoleClassifier.classifyRole());
  }
}
