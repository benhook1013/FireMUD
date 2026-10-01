package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;

final class GameplayPresenceRoleClassifier {
  private GameplayPresenceRoleClassifier() {}

  /**
   * Current session context carries no fresh Account-authenticated gameplay-grant evidence. JWT
   * roles, including tenant-scoped administrative roles, are not a grant carrier and cannot
   * preserve gameplay elevation across reconnects.
   */
  static GameplayPresenceRole classifyRole(SessionContext context, JwtUtil jwtUtil, Logger logger) {
    return GameplayPresenceRole.PLAYER;
  }
}
