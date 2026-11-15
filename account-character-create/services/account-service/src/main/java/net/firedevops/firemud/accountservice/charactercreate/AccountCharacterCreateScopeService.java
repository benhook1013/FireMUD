package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.PlayerBootstrapPrincipal;

/** Unregistered Account scope composition; issuance grants neither membership nor admission. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Account owner collaborators are internal and are not exposed.")
public final class AccountCharacterCreateScopeService {
  private final String namespace;
  private final AccountServiceImpl accounts;
  private final AccountCharacterCreatePublicSourceClient sources;

  public AccountCharacterCreateScopeService(
      String namespace,
      AccountServiceImpl accounts,
      AccountCharacterCreatePublicSourceClient sources) {
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
    this.accounts = Objects.requireNonNull(accounts);
    this.sources = Objects.requireNonNull(sources);
  }

  public CanonicalJoinScopeV2 issue(
      PlayerBootstrapPrincipal principal, String worldSlug, String realmSlug, UUID readRequestId) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    Objects.requireNonNull(principal).authenticateCurrentAccount();
    var target = sources.resolve(worldSlug, realmSlug, readRequestId);
    if (!namespace.equals(target.response().getRequest().getTargetNamespace())) {
      throw AccountCharacterCreateDelegationBindings.invalid();
    }
    return accounts.issueCanonicalCharacterCreateScope(principal, target);
  }

  public CanonicalJoinScopeV2 verify(PlayerBootstrapPrincipal principal, String opaqueScope) {
    return accounts.verifyCanonicalCharacterCreateScope(principal, opaqueScope, namespace);
  }
}
