package net.firedevops.firemud.accountservice.charactercreate;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;

/**
 * Manually composed Account delegation. Source resolution remains the explicit existing dependency;
 * use AccountCharacterCreateOwnerClients.unavailable() until its genuine selected-source and
 * player-bootstrap chain is qualified. These clients neither supply that authority nor register it.
 */
public final class AccountCharacterCreateDelegationClients
    implements AccountCharacterCreateOwnerClients {
  private final String namespace;
  private final AccountCharacterCreateOwnerClients sourceResolver;
  private final AccountCharacterCreateGameSessionClient gameSession;
  private final AccountCharacterCreateWorldClient world;
  private final AccountCharacterCreateEntityClient entity;

  public AccountCharacterCreateDelegationClients(
      String namespace,
      AccountCharacterCreateOwnerClients sourceResolver,
      AccountCharacterCreateGameSessionClient gameSession,
      AccountCharacterCreateWorldClient world,
      AccountCharacterCreateEntityClient entity) {
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
    this.sourceResolver = Objects.requireNonNull(sourceResolver);
    this.gameSession = Objects.requireNonNull(gameSession);
    this.world = Objects.requireNonNull(world);
    this.entity = Objects.requireNonNull(entity);
  }

  @Override
  public ResolvedSource resolvePublicProduction(
      String requestedNamespace, AccountCharacterCreatePrincipal principal, String connectScope) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    if (!namespace.equals(requestedNamespace))
      throw AccountCharacterCreateDelegationBindings.invalid();
    return sourceResolver.resolvePublicProduction(namespace, principal, connectScope);
  }

  @Override
  public void requireExactOriginalSource(AccountCharacterCreateIntent intent) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    if (!namespace.equals(intent.targetNamespace()))
      throw AccountCharacterCreateDelegationBindings.invalid();
    sourceResolver.requireExactOriginalSource(intent);
  }

  @Override
  public void delegateProtected(
      AccountCharacterCreateIntent intent, AccountCharacterCreateRepository.Protection protection) {
    submitProtected(intent, protection);
  }

  /**
   * Only independently read Entity terminal content is returned. On failure every acquired owner
   * protection remains retained. Retrying repeats the exact Account selector; each owner's durable
   * first acquisition supplies the same reference, including after an ambiguous prior RPC outcome.
   * There is no abort, release, refresh, lease renewal or request-identity allocation here.
   */
  public EntityCharacterCreateTerminal submitProtected(
      AccountCharacterCreateIntent intent, AccountCharacterCreateRepository.Protection protection) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    var account = AccountCharacterCreateDelegationBindings.account(namespace, intent, protection);
    var target = gameSession.acquireAndReadOriginal(account);
    var lifecycle = world.acquireAndReadOriginal(account);
    return entity.commitAndReadOriginal(account, target, lifecycle);
  }
}
