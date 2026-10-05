package net.firedevops.firemud.accountservice.service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered owner-local read of current membership authority for derived projection work.
 *
 * <p>The returned state is source evidence only. It does not authorize gameplay, expose a runtime
 * RPC, or enable membership delivery.
 */
public final class AccountMembershipSourceReader {
  private final AccountMembershipAuthorityEventProducer membershipProducer;
  private final AccountAuthorityGenerationRepository generationRepository;
  private final TransactionTemplate ownerTransaction;

  public AccountMembershipSourceReader(
      AccountMembershipAuthorityEventProducer membershipProducer,
      AccountAuthorityGenerationRepository generationRepository,
      PlatformTransactionManager transactionManager) {
    this.membershipProducer =
        Objects.requireNonNull(membershipProducer, "membership source producer is required");
    this.generationRepository =
        Objects.requireNonNull(generationRepository, "authority-generation repository is required");
    Objects.requireNonNull(transactionManager, "Account transaction manager is required");

    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /** Reads one exact, pre-enrolled membership source and its current generation state. */
  public MembershipSourceSnapshot readCurrent(UUID accountId, UUID tenantId) {
    requireCanonicalUuid(accountId, "Account UUID");
    requireCanonicalUuid(tenantId, "tenant UUID");
    requireNoAmbientTransaction();

    MembershipSourceSnapshot snapshot =
        ownerTransaction.execute(
            status -> {
              RuntimeMembershipSnapshotDto membership =
                  membershipProducer.readExistingRuntimeMembershipSnapshot(accountId, tenantId);
              ScopeState sourceState =
                  generationRepository.read(AuthorityScope.membership(accountId, tenantId));
              return new MembershipSourceSnapshot(accountId, tenantId, membership, sourceState);
            });
    if (snapshot == null) {
      throw new IllegalStateException("Account membership source transaction returned no state");
    }
    return snapshot;
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account membership source reader cannot join an ambient transaction");
    }
  }

  private void requireCanonicalUuid(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
  }

  /** Exact membership authority state, event snapshot, and independent durable source counters. */
  public record MembershipSourceSnapshot(
      UUID accountId,
      UUID tenantId,
      RuntimeMembershipSnapshotDto snapshot,
      ScopeState sourceState) {
    public MembershipSourceSnapshot {
      Objects.requireNonNull(accountId, "Account UUID is required");
      Objects.requireNonNull(tenantId, "tenant UUID is required");
      Objects.requireNonNull(snapshot, "current membership snapshot is required");
      Objects.requireNonNull(sourceState, "membership authority source state is required");

      AuthorityScope expectedScope = AuthorityScope.membership(accountId, tenantId);
      if (!expectedScope.equals(sourceState.scope())
          || sourceState.generation() <= 0L
          || sourceState.sourceVersion() <= 0L) {
        throw new IllegalArgumentException(
            "Membership authority source state differs from its exact scope");
      }
      if (new UUID(0L, 0L).equals(accountId)
          || new UUID(0L, 0L).equals(tenantId)
          || sourceState.issuanceFence() == null
          || !accountId.equals(sourceState.issuanceFence().accountId())
          || sourceState.issuanceFence().value() <= 0L
          || sourceState.issuanceFence().sourceVersion() <= 0L) {
        throw new IllegalArgumentException(
            "Membership source state has no exact positive Account issuance fence");
      }
      if (!accountId.toString().equals(snapshot.accountUuid())
          || !tenantId.toString().equals(snapshot.tenantUuid())
          || !accountId.toString().equals(snapshot.requestAccountUuid())
          || !tenantId.toString().equals(snapshot.requestTenantUuid())) {
        throw new IllegalArgumentException(
            "Membership snapshot differs from its exact Account/tenant source scope");
      }
      if (!Long.toString(sourceState.issuanceFence().value()).equals(snapshot.issuanceFence())) {
        throw new IllegalArgumentException(
            "Membership snapshot issuance fence differs from its Account source state");
      }

      Map<String, String> versions = snapshot.membershipBaseline().membershipVersion();
      if (versions.size() != 1
          || !versions.containsKey(tenantId.toString())
          || !Long.toString(sourceState.generation())
              .equals(snapshot.membershipBaseline().membershipAuthorityGeneration())) {
        throw new IllegalArgumentException(
            "Membership snapshot version map or generation differs from its source state");
      }
    }
  }
}
