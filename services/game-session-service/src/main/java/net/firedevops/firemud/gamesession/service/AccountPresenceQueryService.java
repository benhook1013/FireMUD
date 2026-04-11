package net.firedevops.firemud.gamesession.service;

import java.util.List;

/** Resolves account-scoped social presence from running sessions plus live gameplay presence. */
public interface AccountPresenceQueryService {
  List<AccountPresenceSnapshot> queryAccountPresence(
      long tenantId, long viewerAccountId, List<Long> accountIds);
}
