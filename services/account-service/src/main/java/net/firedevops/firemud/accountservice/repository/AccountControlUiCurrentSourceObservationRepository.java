package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, existing-only informational observation of the fresh unscoped source cut.
 *
 * <p>This does not authenticate a lookup UUID, inspect a token, produce an authority Snapshot,
 * satisfy current restriction evidence, or replace captureAccount/verifyAccount. No caller or
 * registration uses this component. Missing or contradictory sources never cause enrollment.
 */
public final class AccountControlUiCurrentSourceObservationRepository {
  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountAuthoritySourceEvidenceRepository sources;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Retain the transaction-bound SQL context and owner repositories; these injected collaborators cannot be defensively copied.")
  public AccountControlUiCurrentSourceObservationRepository(
      DSLContext dsl,
      AccountRepository accounts,
      AccountAuthoritySourceEvidenceRepository sources) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.sources = Objects.requireNonNull(sources);
  }

  /** All source reads and locks belong to this caller's one writable READ_COMMITTED transaction. */
  public Observation observeExisting(UUID lookupAccountId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw unavailable();
          }
        });
    Objects.requireNonNull(lookupAccountId);
    var account =
        accounts
            .findByAccountUuidForUpdate(lookupAccountId)
            .orElseThrow(AccountControlUiCurrentSourceObservationRepository::unavailable);
    if (!lookupAccountId.equals(account.getAccountUuid())
        || account.getId() == null
        || account.getId() <= 0L
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE) throw unavailable();
    // Preserve the current Account/composite owner's lock order, not the obsolete donor APIs.
    var current =
        sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, lookupAccountId);
    if (!AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER)
            .equals(current.issuer().scope())
        || !AuthorityScope.account(lookupAccountId).equals(current.account().scope())
        || !lookupAccountId.equals(current.issuanceFence().accountId())
        || !current.issuanceFence().equals(current.account().issuanceFence())
        || !account.getId().equals(current.account().accountSourceNumericId())
        || !account
            .getAccountUuidProvenance()
            .name()
            .equals(current.account().accountUuidProvenance())
        || current.canonicalIssuerProjection() == null
        || current.canonicalAccountProjection() == null) throw unavailable();
    var roles =
        dsl.fetchOne(
            "SELECT account_uuid,account_uuid_source_numeric_id,account_uuid_provenance,"
                + "global_roles,global_role_source_version FROM account_global_role_sources "
                + "WHERE account_uuid=? FOR SHARE",
            lookupAccountId);
    if (roles == null
        || !lookupAccountId.equals(roles.get("account_uuid", UUID.class))
        || !account.getId().equals(roles.get("account_uuid_source_numeric_id", Long.class))
        || !account
            .getAccountUuidProvenance()
            .name()
            .equals(roles.get("account_uuid_provenance", String.class))) {
      throw unavailable();
    }
    String[] values = roles.get("global_roles", String[].class);
    Long version = roles.get("global_role_source_version", Long.class);
    if (values == null || values.length != 0 || version == null || version <= 0L)
      throw unavailable();
    return new Observation(lookupAccountId, current, version);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Existing unscoped Account source observation unavailable");
  }

  /** Owner-minted informational values only, deliberately incompatible with authorization types. */
  public static final class Observation {
    private final UUID accountId;
    private final IssuerAccountSourceSnapshot sources;
    private final long globalRoleSourceVersion;

    private Observation(UUID accountId, IssuerAccountSourceSnapshot sources, long roleVersion) {
      this.accountId = accountId;
      this.sources = sources;
      this.globalRoleSourceVersion = roleVersion;
    }

    public UUID accountId() {
      return accountId;
    }

    public IssuerAccountSourceSnapshot sources() {
      return sources;
    }

    public long globalRoleSourceVersion() {
      return globalRoleSourceVersion;
    }

    @Override
    public String toString() {
      return "AccountCurrentSourceObservation[non-authorizing]";
    }
  }
}
