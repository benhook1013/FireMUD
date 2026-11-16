package net.firedevops.firemud.accountservice.charactercreate;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;

/** Unregistered Account scope composition; issuance grants neither membership nor admission. */
public final class AccountCharacterCreateScopeService {
  private final String namespace;
  private final AccountCharacterCreateScopeOwner accounts;
  private final AccountCharacterCreatePublicSourceClient sources;

  public AccountCharacterCreateScopeService(
      String namespace,
      AccountCharacterCreateScopeOwner accounts,
      AccountCharacterCreatePublicSourceClient sources) {
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
    this.accounts = Objects.requireNonNull(accounts);
    this.sources = Objects.requireNonNull(sources);
  }

  public CanonicalJoinScopeV2 issue(
      AccountCharacterCreatePrincipal principal,
      String worldSlug,
      String realmSlug,
      UUID readRequestId) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    Objects.requireNonNull(principal).authenticateCurrentAccount();
    var target = sources.resolve(worldSlug, realmSlug, readRequestId);
    if (!namespace.equals(target.response().getRequest().getTargetNamespace())) {
      throw AccountCharacterCreateDelegationBindings.invalid();
    }
    return accounts.issueCanonicalCharacterCreateScope(principal, target);
  }

  public CanonicalJoinScopeV2 verify(
      AccountCharacterCreatePrincipal principal, String opaqueScope) {
    return accounts.verifyCanonicalCharacterCreateScope(principal, opaqueScope, namespace);
  }
}
