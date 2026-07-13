package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.client.AccountClient;
import org.junit.jupiter.api.Test;

class AccountPresenceVisibilityPolicyResolverTest {

  @Test
  void usesThePersistedAccountVisibilityPolicy() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.getPresenceVisibilityPolicy(22L, 123L))
        .thenReturn(AccountPresenceVisibilityPolicy.FRIENDS_ONLY);
    AccountPresenceVisibilityPolicyResolver resolver =
        new AccountPresenceVisibilityPolicyResolver(accountClient);

    assertEquals(AccountPresenceVisibilityPolicy.FRIENDS_ONLY, resolver.resolve(22L, 123L));
    verify(accountClient).getPresenceVisibilityPolicy(22L, 123L);
  }

  @Test
  void preservesAnExplicitSystemOwnedHiddenStaffPolicy() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.getPresenceVisibilityPolicy(22L, 123L))
        .thenReturn(AccountPresenceVisibilityPolicy.HIDDEN_STAFF);
    AccountPresenceVisibilityPolicyResolver resolver =
        new AccountPresenceVisibilityPolicyResolver(accountClient);

    assertEquals(AccountPresenceVisibilityPolicy.HIDDEN_STAFF, resolver.resolve(22L, 123L));
  }
}
