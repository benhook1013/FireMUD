package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.CategorySource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.SourceKind;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fresh source prerequisite only. Retained Accounts and later moderation writes are not enrolled.
 */
@Repository
public class AccountPlatformRestrictionBirthRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Preserve required DSLContext; Spring must proxy this repository.")
  public AccountPlatformRestrictionBirthRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /** Called only after the existing fresh-insert capability and sequence-zero source readback. */
  @Transactional(propagation = Propagation.MANDATORY)
  void initializeFreshAccount(
      AccountRepository.FreshAccountInsert proof, long insertTransactionId, ScopeState birth) {
    requireTransaction();
    Objects.requireNonNull(proof, "Fresh Account insert proof is required");
    Record account = lockAccount(proof.accountUuid());
    if (positive(account, "id") != proof.accountId()
        || positive(account, "account_repository_insert_transaction_id") != insertTransactionId
        || positive(account, "current_xid") != insertTransactionId
        || !"active".equals(account.get("lifecycle_state", String.class))) {
      throw new BirthSourceUnavailableException();
    }
    ScopeState current = currentAuthority(proof.accountUuid());
    if (!current.equals(birth)
        || birth.generation() != 1
        || birth.sourceVersion() != 1
        || birth.issuanceFence() == null
        || birth.issuanceFence().value() != 1
        || birth.issuanceFence().sourceVersion() != 1) {
      throw new BirthSourceUnavailableException();
    }
    CategorySource lock =
        insertCategory(
            proof.accountUuid(),
            proof.accountId(),
            insertTransactionId,
            birth,
            Category.ACCOUNT_SECURITY_LOCK);
    CategorySource ban =
        insertCategory(
            proof.accountUuid(),
            proof.accountId(),
            insertTransactionId,
            birth,
            Category.PLATFORM_ACCESS_BAN);
    AccountPlatformRestrictionBirthSource expected =
        new AccountPlatformRestrictionBirthSource(
            proof.accountUuid(), proof.accountId(), insertTransactionId, birth, current, lock, ban);
    if (!expected.equals(readCurrentBirthSource(proof.accountUuid()))) {
      throw new BirthSourceUnavailableException();
    }
  }

  /**
   * Locks Account first and requires both explicit CATEGORY projections, exact immutable birth
   * results and their distinct restriction outbox evidence. Missing or changed evidence denies.
   * NONRESTRICTED is the historical birth result, not proof of current protective-lock absence.
   * This source read does not authorize a player or manufacture an auth-authority event.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountPlatformRestrictionBirthSource readCurrentBirthSource(UUID accountId) {
    requireTransaction();
    Record account = lockAccount(accountId);
    ScopeState current = currentAuthority(accountId);
    CategoryReadback lock = readCategory(accountId, Category.ACCOUNT_SECURITY_LOCK, account);
    CategoryReadback ban = readCategory(accountId, Category.PLATFORM_ACCESS_BAN, account);
    if (!lock.birthAuthority().equals(ban.birthAuthority())) {
      throw new BirthSourceUnavailableException();
    }
    try {
      return new AccountPlatformRestrictionBirthSource(
          accountId,
          positive(account, "id"),
          positive(account, "account_repository_insert_transaction_id"),
          lock.birthAuthority(),
          current,
          lock.source(),
          ban.source());
    } catch (IllegalArgumentException malformed) {
      throw new BirthSourceUnavailableException();
    }
  }

  private CategorySource insertCategory(
      UUID accountId, long numericId, long transactionId, ScopeState birth, Category category) {
    // No upsert or retry minting: this method is reachable only inside the exact new row's tx.
    // Committed readback always returns the original retained identities.
    UUID operationId = UUID.randomUUID();
    UUID resultId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String stream = AccountPlatformRestrictionBirthSource.streamKey(accountId, category);
    String digest =
        AccountPlatformRestrictionBirthSource.birthDigest(
            accountId, numericId, transactionId, birth, category, operationId, resultId, eventId);
    int inserted =
        dsl.execute(
            "INSERT INTO account_platform_restriction_births (account_uuid, category, revision, "
                + "enforcement_epoch, source_kind, restriction_state, operation_id, result_id, event_id, payload_digest, "
                + "account_source_numeric_id, account_insert_transaction_id, account_generation, "
                + "account_source_version, issuance_fence, fence_source_version) "
                + "VALUES (?, ?, 1, 1, 'CATEGORY', 'NONRESTRICTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            accountId,
            category.storageValue(),
            operationId,
            resultId,
            eventId,
            digest,
            numericId,
            transactionId,
            birth.generation(),
            birth.sourceVersion(),
            birth.issuanceFence().value(),
            birth.issuanceFence().sourceVersion());
    int projected =
        dsl.execute(
            "INSERT INTO account_platform_restriction_projections "
                + "(account_uuid, category, revision, enforcement_epoch, result_id) VALUES (?, ?, 1, 1, ?)",
            accountId,
            category.storageValue(),
            resultId);
    int emitted =
        dsl.execute(
            "INSERT INTO account_platform_restriction_birth_outbox "
                + "(account_uuid, category, revision, enforcement_epoch, operation_id, result_id, "
                + "event_id, payload_digest, outbox_stream_key, outbox_sequence, source_kind, restriction_state) "
                + "VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, 1, 'CATEGORY', 'NONRESTRICTED')",
            accountId,
            category.storageValue(),
            operationId,
            resultId,
            eventId,
            digest,
            stream);
    if (inserted != 1 || projected != 1 || emitted != 1) {
      throw new BirthSourceUnavailableException();
    }
    return new CategorySource(
        category,
        SourceKind.CATEGORY,
        RestrictionState.NONRESTRICTED,
        1,
        1,
        operationId,
        resultId,
        eventId,
        digest,
        stream,
        1);
  }

  private CategoryReadback readCategory(UUID accountId, Category category, Record account) {
    Record birth =
        dsl.fetchOne(
            "SELECT * FROM account_platform_restriction_births "
                + "WHERE account_uuid = ? AND category = ? FOR SHARE",
            accountId,
            category.storageValue());
    Record projection =
        dsl.fetchOne(
            "SELECT * FROM account_platform_restriction_projections "
                + "WHERE account_uuid = ? AND category = ? FOR SHARE",
            accountId,
            category.storageValue());
    Record event =
        dsl.fetchOne(
            "SELECT * FROM account_platform_restriction_birth_outbox "
                + "WHERE account_uuid = ? AND category = ? FOR SHARE",
            accountId,
            category.storageValue());
    if (birth == null
        || projection == null
        || event == null
        || positive(birth, "account_source_numeric_id") != positive(account, "id")
        || positive(birth, "account_insert_transaction_id")
            != positive(account, "account_repository_insert_transaction_id")) {
      throw new BirthSourceUnavailableException();
    }
    for (String field :
        new String[] {"account_uuid", "category", "revision", "enforcement_epoch", "result_id"}) {
      if (!Objects.equals(birth.get(field), projection.get(field))
          || !Objects.equals(birth.get(field), event.get(field))) {
        throw new BirthSourceUnavailableException();
      }
    }
    for (String field :
        new String[] {
          "operation_id", "event_id", "payload_digest", "source_kind", "restriction_state"
        }) {
      if (!Objects.equals(birth.get(field), event.get(field))) {
        throw new BirthSourceUnavailableException();
      }
    }
    if (!accountId.equals(birth.get("account_uuid", UUID.class))
        || !category.storageValue().equals(birth.get("category", String.class))
        || !"CATEGORY".equals(birth.get("source_kind", String.class))
        || !"NONRESTRICTED".equals(birth.get("restriction_state", String.class))
        || positive(birth, "revision") != 1
        || positive(birth, "enforcement_epoch") != 1
        || positive(event, "outbox_sequence") != 1
        || !AccountPlatformRestrictionBirthSource.streamKey(accountId, category)
            .equals(event.get("outbox_stream_key", String.class))) {
      throw new BirthSourceUnavailableException();
    }
    try {
      var fence =
          new AccountAuthorityGenerationRepository.IssuanceFence(
              accountId,
              positive(birth, "issuance_fence"),
              positive(birth, "fence_source_version"));
      var authority =
          new ScopeState(
              AuthorityScope.account(accountId),
              positive(birth, "account_generation"),
              positive(birth, "account_source_version"),
              fence);
      if (authority.generation() != 1
          || authority.sourceVersion() != 1
          || fence.value() != 1
          || fence.sourceVersion() != 1) {
        throw new BirthSourceUnavailableException();
      }
      UUID operationId = birth.get("operation_id", UUID.class);
      UUID resultId = birth.get("result_id", UUID.class);
      UUID eventId = event.get("event_id", UUID.class);
      if (operationId == null || resultId == null || eventId == null) {
        throw new BirthSourceUnavailableException();
      }
      return new CategoryReadback(
          authority,
          new CategorySource(
              category,
              SourceKind.CATEGORY,
              RestrictionState.NONRESTRICTED,
              1,
              1,
              operationId,
              resultId,
              eventId,
              birth.get("payload_digest", String.class),
              event.get("outbox_stream_key", String.class),
              1));
    } catch (IllegalArgumentException malformed) {
      throw new BirthSourceUnavailableException();
    }
  }

  private Record lockAccount(UUID accountId) {
    if (accountId == null || accountId.version() != 4 || accountId.variant() != 2) {
      throw new BirthSourceUnavailableException();
    }
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, lifecycle_state, "
                + "account_repository_insert_transaction_id, txid_current() AS current_xid "
                + "FROM accounts WHERE account_uuid = ? FOR UPDATE",
            accountId);
    if (account == null
        || !accountId.equals(account.get("account_uuid", UUID.class))
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(account.get("account_uuid_provenance", String.class))
        || positive(account, "id") != positive(account, "account_uuid_source_numeric_id")) {
      throw new BirthSourceUnavailableException();
    }
    positive(account, "account_repository_insert_transaction_id");
    return account;
  }

  private ScopeState currentAuthority(UUID accountId) {
    ScopeState state =
        new AccountAuthorityGenerationRepository(dsl).read(AuthorityScope.account(accountId));
    if (state.issuanceFence() == null || !accountId.equals(state.issuanceFence().accountId())) {
      throw new BirthSourceUnavailableException();
    }
    return state;
  }

  private static long positive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0) throw new BirthSourceUnavailableException();
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Restriction birth source requires an Account transaction");
    }
  }

  private record CategoryReadback(ScopeState birthAuthority, CategorySource source) {}

  public static final class BirthSourceUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public BirthSourceUnavailableException() {
      super("Exact Account restriction birth source is unavailable");
    }
  }
}
