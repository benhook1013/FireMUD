package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered original-only reconciliation. Expiry, absence and transport failure never settle.
 */
public final class AccountCharacterCreateSettlementService {
  private final AccountCharacterCreateRepository repository;
  private final AccountCharacterCreateEntityClient entity;
  private final TransactionTemplate transaction;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected repository is the shared Account owner for settlement transactions and is not a copyable value.")
  public AccountCharacterCreateSettlementService(
      AccountCharacterCreateRepository repository,
      AccountCharacterCreateEntityClient entity,
      PlatformTransactionManager manager,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.entity = Objects.requireNonNull(entity);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(manager));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
  }

  public EntityCharacterCreateTerminal reconcile(UUID operation, String mutationDigest) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    AccountCharacterCreateIntent.requireId(operation);
    if (mutationDigest == null || !mutationDigest.matches("[0-9a-f]{64}")) {
      throw AccountCharacterCreateDelegationBindings.invalid();
    }
    var original =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  var intent =
                      repository
                          .findOperation(namespace, operation)
                          .orElseThrow(AccountCharacterCreateDelegationBindings::invalid);
                  if (!mutationDigest.equals(intent.mutationDigest()))
                    throw AccountCharacterCreateDelegationBindings.invalid();
                  return repository
                      .findProtection(intent)
                      .orElseThrow(AccountCharacterCreateDelegationBindings::invalid);
                }));
    var retained = transaction.execute(status -> repository.findSettlement(original));
    if (retained != null && retained.isPresent()) return retained.orElseThrow();
    var account =
        AccountCharacterCreateDelegationBindings.account(namespace, original.intent(), original);
    // The actual authenticated Entity lookup completes before any owner lock is acquired.
    var verified = entity.readVerifiedOriginalTerminal(account);
    return Objects.requireNonNull(
        transaction.execute(status -> repository.settle(original, verified)));
  }
}
