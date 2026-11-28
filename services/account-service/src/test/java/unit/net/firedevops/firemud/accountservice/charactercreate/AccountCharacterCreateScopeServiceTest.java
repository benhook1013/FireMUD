package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreatePublicSourceClient.AuthenticatedTarget;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;
import org.junit.jupiter.api.Test;

/** Controlled composition proof; mocked capabilities do not establish genuine owner authority. */
class AccountCharacterCreateScopeServiceTest {
  private final AccountCharacterCreateScopeOwner accounts =
      mock(AccountCharacterCreateScopeOwner.class);
  private final AccountCharacterCreatePublicSourceClient sources =
      mock(AccountCharacterCreatePublicSourceClient.class);
  private final AccountCharacterCreatePrincipal principal =
      mock(AccountCharacterCreatePrincipal.class);
  private final AccountCharacterCreateScopeService service =
      new AccountCharacterCreateScopeService("scope-proof", accounts, sources);

  @Test
  void authenticatesBeforeResolvingFriendlySelectorsAndIssuingFromOwnerCapability() {
    UUID read = UUID.randomUUID();
    when(principal.authenticateCurrentAccount()).thenReturn(UUID.randomUUID());
    AuthenticatedTarget target = target("scope-proof");
    when(sources.resolve("demo", "production", read)).thenReturn(target);
    CanonicalJoinScopeV2 scope = mock(CanonicalJoinScopeV2.class);
    when(accounts.issueCanonicalCharacterCreateScope(principal, target)).thenReturn(scope);

    assertThat(service.issue(principal, "demo", "production", read)).isSameAs(scope);
    var order = inOrder(principal, sources, accounts);
    order.verify(principal).authenticateCurrentAccount();
    order.verify(sources).resolve("demo", "production", read);
    order.verify(accounts).issueCanonicalCharacterCreateScope(principal, target);
  }

  @Test
  void deniesDifferentNamespaceBeforeAccountIssuance() {
    UUID read = UUID.randomUUID();
    AuthenticatedTarget target = target("other-namespace");
    when(sources.resolve("demo", "production", read)).thenReturn(target);
    assertThatThrownBy(() -> service.issue(principal, "demo", "production", read))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(accounts);
  }

  @Test
  void bootstrapRevocationStopsBeforeTargetRead() {
    when(principal.authenticateCurrentAccount()).thenThrow(new IllegalStateException("revoked"));
    assertThatThrownBy(() -> service.issue(principal, "demo", "production", UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sources, accounts);
  }

  @Test
  void verificationUsesOnlyConfiguredNamespaceAndActualAccountOwner() {
    CanonicalJoinScopeV2 scope = mock(CanonicalJoinScopeV2.class);
    when(accounts.verifyCanonicalCharacterCreateScope(principal, "opaque-scope", "scope-proof"))
        .thenReturn(scope);
    assertThat(service.verify(principal, "opaque-scope")).isSameAs(scope);
    verifyNoInteractions(sources);
  }

  private static AuthenticatedTarget target(String namespace) {
    AuthenticatedTarget target = mock(AuthenticatedTarget.class);
    when(target.response())
        .thenReturn(
            ResolveCharacterCreatePublicTargetResponse.newBuilder()
                .setRequest(
                    ResolveCharacterCreatePublicTargetRequest.newBuilder()
                        .setTargetNamespace(namespace))
                .build());
    return target;
  }
}
