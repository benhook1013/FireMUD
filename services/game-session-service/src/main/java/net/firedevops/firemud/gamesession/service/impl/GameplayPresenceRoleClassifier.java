package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;

final class GameplayPresenceRoleClassifier {
  private GameplayPresenceRoleClassifier() {}

  /**
   * Current session context carries no fresh Account-authenticated gameplay-grant evidence. JWT
   * roles, including tenant-scoped administrative roles, are not a grant carrier and cannot
   * preserve gameplay elevation across reconnects.
   */
  static GameplayPresenceRole classifyRole() {
    return GameplayPresenceRole.PLAYER;
  }
}
