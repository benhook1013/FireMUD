package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.common.EmailCanonicalization;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Seeds the canonical pre-V88 repository-created Account image in historical migration fixtures.
 *
 * <p>This reproduces the V40 sequence-zero Account source setup, including the immutable V74.1
 * empty-role source inserted by its Account trigger. It deliberately does not create V88
 * restriction-birth evidence, enroll a retained Account, or establish admission authority.
 */
public final class PreRestrictionBirthAccountFixture {
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final String ACCOUNT_STREAM_PREFIX = "account:auth-authority:v1:account/";

  private PreRestrictionBirthAccountFixture() {}

  /**
   * Inserts an Account and its pre-V88 fresh-source rows in one test transaction.
   *
   * <p>The target schema must already include V40 and V74.1. Use only when a test intentionally
   * starts from a historical schema before V88 and then verifies forward migration preservation.
   */
  public static Account create(
      DSLContext dsl, String username, String email, String passwordHash, String loginAuthModes) {
    Objects.requireNonNull(dsl, "DSLContext is required");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Historical Account setup requires a writable transaction");
    }
    Record restrictionBirthSchemaReadback =
        dsl.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?)",
            "account_platform_restriction_births");
    if (restrictionBirthSchemaReadback == null) {
      throw new IllegalStateException("Could not read restriction-birth schema guard");
    }
    Boolean restrictionBirthSchemaInstalled =
        Objects.requireNonNull(
            restrictionBirthSchemaReadback.get(0, Boolean.class),
            "Restriction-birth schema guard readback is required");
    if (Boolean.TRUE.equals(restrictionBirthSchemaInstalled)) {
      throw new IllegalStateException(
          "Pre-V88 Account fixture cannot run after restriction-birth schema installation");
    }
    Objects.requireNonNull(username, "username is required");
    Objects.requireNonNull(passwordHash, "password hash is required");

    String canonicalEmail = EmailCanonicalization.normalize(email);
    String normalizedLoginAuthModes = AccountLoginAuthModes.normalize(loginAuthModes);
    Account account = new Account();
    account.setUsername(username);
    account.setEmail(canonicalEmail);
    account.setPasswordHash(passwordHash);
    account.setLoginAuthModes(normalizedLoginAuthModes);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new PreV88SourceEvidence(dsl, generations, outbox);
    sources.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
    return new AccountRepository(dsl, sources).save(account);
  }

  private static final class PreV88SourceEvidence extends AccountAuthoritySourceEvidenceRepository {
    private final DSLContext dsl;
    private final AccountAuthorityGenerationRepository generations;

    private PreV88SourceEvidence(
        DSLContext dsl,
        AccountAuthorityGenerationRepository generations,
        AccountAuthorityOutboxRepository outbox) {
      super(dsl, generations, outbox);
      this.dsl = dsl;
      this.generations = generations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeFreshAccount(AccountRepository.FreshAccountInsert proof) {
      Objects.requireNonNull(proof, "fresh Account repository insert proof is required");
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
        throw new IllegalStateException("Historical Account setup requires a writable transaction");
      }

      Record account =
          dsl.fetchOne(
              "SELECT id, account_uuid, account_uuid_provenance, "
                  + "account_uuid_source_numeric_id, account_repository_insert_transaction_id, "
                  + "lifecycle_state, txid_current() AS current_xid "
                  + "FROM accounts WHERE id = ? FOR UPDATE",
              proof.accountId());
      if (account == null
          || !Objects.equals(account.get("id", Long.class), proof.accountId())
          || !Objects.equals(account.get("account_uuid", UUID.class), proof.accountUuid())
          || !Objects.equals(
              account.get("account_uuid_provenance", String.class),
              proof.accountUuidProvenance().name())
          || !Objects.equals(
              account.get("account_uuid_source_numeric_id", Long.class), proof.accountId())
          || !Objects.equals(
              account.get("account_repository_insert_transaction_id", Long.class),
              account.get("current_xid", Long.class))
          || !"active".equals(account.get("lifecycle_state", String.class))) {
        throw new IllegalStateException(
            "Historical source baseline requires the exact repository-created Account row");
      }

      long insertTransactionId =
          positive(
              account.get("account_repository_insert_transaction_id", Long.class),
              "Account insert transaction ID");
      ScopeState state =
          Objects.requireNonNull(
              generations.initializeAccountForFreshRepositoryInsert(proof),
              "Account generation baseline is required");
      long generation = positive(state.generation(), "Account generation");
      long sourceVersion = positive(state.sourceVersion(), "Account source version");
      IssuanceFence fence =
          Objects.requireNonNull(
              state.issuanceFence(), "Account issuance-fence baseline is required");
      positive(fence.value(), "Account issuance fence");
      positive(fence.sourceVersion(), "Account issuance-fence source version");
      String streamKey = ACCOUNT_STREAM_PREFIX + proof.accountUuid();
      requireOne(
          dsl.execute(
              "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
                  + "VALUES (?, 0)",
              streamKey),
          "Account authority outbox stream");
      requireOne(
          dsl.execute(
              "INSERT INTO account_authority_source_records "
                  + "(outbox_stream_key, scope_kind, issuer_id, account_uuid, "
                  + "baseline_generation, baseline_source_version, baseline_issuance_fence, "
                  + "initialization_provenance, account_source_numeric_id, account_uuid_provenance, "
                  + "account_repository_insert_transaction_id, current_generation, "
                  + "current_source_version, current_issuance_fence, "
                  + "current_issuance_fence_source_version, last_outbox_sequence) "
                  + "VALUES (?, 'ACCOUNT', NULL, ?, 1, 1, ?, 'ACCOUNT_REPOSITORY_INSERT', ?, ?, "
                  + "?, ?, ?, ?, ?, 0)",
              streamKey,
              proof.accountUuid(),
              fence.value(),
              proof.accountId(),
              proof.accountUuidProvenance().name(),
              insertTransactionId,
              generation,
              sourceVersion,
              fence.value(),
              fence.sourceVersion()),
          "Account sequence-zero source record");
      readCurrentIssuerAccountSources(ACCOUNT_ISSUER, proof.accountUuid());
    }
  }

  private static long positive(Long value, String label) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException(label + " must be positive");
    }
    return value;
  }

  private static void requireOne(int affectedRows, String label) {
    if (affectedRows != 1) {
      throw new IllegalStateException(label + " insert did not affect exactly one row");
    }
  }
}
