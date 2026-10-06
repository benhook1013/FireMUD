package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountState;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {
  private final DSLContext dsl;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; Spring must proxy this non-final repository.")
  public AccountRepository(DSLContext dsl) {
    this(
        dsl,
        new AccountAuthoritySourceEvidenceRepository(
            dsl,
            new AccountAuthorityGenerationRepository(dsl),
            new AccountAuthorityOutboxRepository(dsl)));
  }

  @org.springframework.beans.factory.annotation.Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve required injected collaborators; Spring must proxy this non-final repository.")
  public AccountRepository(
      DSLContext dsl, AccountAuthoritySourceEvidenceRepository sourceEvidence) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "source evidence is required");
  }

  public Optional<Account> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOne(this::toEntity));
  }

  /** Locks one exact persisted Account row before an owner-local authority mutation. */
  @Transactional
  public Optional<Account> findByIdForUpdate(Long id) {
    if (id == null || id <= 0L) {
      throw new IllegalArgumentException("A positive persisted Account ID is required");
    }
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).forUpdate().fetchOne(this::toEntity));
  }

  public Optional<Account> findByAccountUuid(UUID accountUuid) {
    Objects.requireNonNull(accountUuid, "accountUuid must not be null");
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS)
            .where(ACCOUNTS.ACCOUNT_UUID.eq(accountUuid))
            .fetchOne(this::toEntity));
  }

  public Optional<Account> findByUsername(String username) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.USERNAME.eq(username)).fetchOne(this::toEntity));
  }

  public Optional<Account> findByEmail(String email) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS)
            .where(ACCOUNTS.EMAIL.eq(EmailCanonicalization.normalize(email)))
            .fetchOne(this::toEntity));
  }

  @Transactional
  public Account save(Account entity) {
    Objects.requireNonNull(entity, "Account is required");
    entity.setEmail(EmailCanonicalization.normalize(entity.getEmail()));
    if (entity.getId() == null) {
      UUID expectedUuid = UUID.randomUUID();
      AccountsRecord inserted =
          dsl.insertInto(ACCOUNTS)
              .set(ACCOUNTS.ACCOUNT_UUID, expectedUuid)
              .set(
                  ACCOUNTS.ACCOUNT_UUID_PROVENANCE,
                  AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name())
              .set(ACCOUNTS.USERNAME, entity.getUsername())
              .set(ACCOUNTS.EMAIL, entity.getEmail())
              .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
              .set(ACCOUNTS.ROLE, entity.getRole())
              .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
              .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
              .set(ACCOUNTS.LIFECYCLE_STATE, entity.getLifecycleState().storageValue())
              .returning()
              .fetchOne();
      if (inserted == null) {
        throw new IllegalStateException("Account insert did not return its persisted identity");
      }
      requireExactIdentityReadback(
          inserted, expectedUuid, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      copyIdentityToEntity(entity, inserted);
      sourceEvidence.initializeFreshAccount(new FreshAccountInsert(inserted.getId(), expectedUuid));
      return entity;
    }
    UUID expectedUuid = entity.getAccountUuid();
    AccountIdentityProvenance expectedProvenance = entity.getAccountUuidProvenance();
    Long expectedSourceNumericId = entity.getAccountUuidSourceNumericId();
    AccountsRecord before =
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(entity.getId())).forUpdate().fetchOne();
    if (before == null
        || (expectedUuid != null && !expectedUuid.equals(before.getAccountUuid()))
        || (expectedProvenance != null
            && !expectedProvenance.name().equals(before.getAccountUuidProvenance()))
        || (expectedSourceNumericId != null
            && !expectedSourceNumericId.equals(before.getAccountUuidSourceNumericId()))) {
      throw JooqAccountRepositorySupport.staleWrite("accounts", entity.getId());
    }
    AccountLifecycleState storedLifecycleState =
        AccountLifecycleState.fromStorageValue(before.getLifecycleState());
    if (entity.getLifecycleState() != null && entity.getLifecycleState() != storedLifecycleState) {
      throw new IllegalStateException(
          "Account lifecycle changes are unavailable through generic Account saves");
    }
    AccountAuthorityState beforeState = authorityState(before);
    var update =
        dsl.update(ACCOUNTS)
            .set(ACCOUNTS.USERNAME, entity.getUsername())
            .set(ACCOUNTS.EMAIL, entity.getEmail())
            .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
            .set(ACCOUNTS.ROLE, entity.getRole())
            .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
            .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
            .set(ACCOUNTS.LIFECYCLE_STATE, before.getLifecycleState())
            .where(ACCOUNTS.ID.eq(entity.getId()));
    if (expectedUuid != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID.eq(expectedUuid));
    }
    if (expectedProvenance != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID_PROVENANCE.eq(expectedProvenance.name()));
    }
    if (expectedSourceNumericId != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID_SOURCE_NUMERIC_ID.eq(expectedSourceNumericId));
    }
    AccountsRecord updated = update.returning().fetchOne();
    if (updated == null) {
      throw JooqAccountRepositorySupport.staleWrite("accounts", entity.getId());
    }
    requireExactIdentityReadback(
        updated,
        expectedUuid == null ? updated.getAccountUuid() : expectedUuid,
        expectedProvenance == null
            ? AccountIdentityProvenance.fromStorageValue(updated.getAccountUuidProvenance())
            : expectedProvenance);
    copyIdentityToEntity(entity, updated);
    entity.setLifecycleState(AccountLifecycleState.fromStorageValue(updated.getLifecycleState()));
    AccountAuthorityState afterState = authorityState(updated);
    sourceEvidence.recordAccountUpdate(
        new AccountUpdateEvidence(
            updated.getId(),
            updated.getAccountUuid(),
            UUID.randomUUID().toString(),
            beforeState,
            afterState));
    return entity;
  }

  public void delete(Account entity) {
    if (entity != null && entity.getId() != null) {
      throw new IllegalStateException(
          "Account hard deletion is unavailable until the pending-deletion retention workflow exists");
    }
  }

  private Account toEntity(AccountsRecord record) {
    Account account =
        JooqAccountRepositorySupport.partialAccount(
            record.getId(),
            record.getUsername(),
            record.getEmail(),
            record.getPasswordHash(),
            record.getRole(),
            record.getEmailVerified(),
            record.getLoginAuthModes(),
            record.getLifecycleState());
    if (account != null) {
      account.setAccountUuid(record.getAccountUuid());
      account.setAccountUuidProvenance(
          AccountIdentityProvenance.fromStorageValue(record.getAccountUuidProvenance()));
      account.setAccountUuidSourceNumericId(record.getAccountUuidSourceNumericId());
      requireExactIdentityReadback(
          record, record.getAccountUuid(), account.getAccountUuidProvenance());
    }
    return account;
  }

  private void requireExactIdentityReadback(
      AccountsRecord record, UUID expectedUuid, AccountIdentityProvenance expectedProvenance) {
    if (record.getId() == null
        || expectedUuid == null
        || new UUID(0L, 0L).equals(expectedUuid)
        || expectedProvenance == null
        || !Objects.equals(record.getAccountUuid(), expectedUuid)
        || !Objects.equals(record.getAccountUuidProvenance(), expectedProvenance.name())
        || !Objects.equals(record.getAccountUuidSourceNumericId(), record.getId())) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
  }

  private void copyIdentityToEntity(Account entity, AccountsRecord record) {
    entity.setId(record.getId());
    entity.setAccountUuid(record.getAccountUuid());
    entity.setAccountUuidProvenance(
        AccountIdentityProvenance.fromStorageValue(record.getAccountUuidProvenance()));
    entity.setAccountUuidSourceNumericId(record.getAccountUuidSourceNumericId());
  }

  private String normalizedLoginAuthModes(Account entity) {
    return AccountLoginAuthModes.normalize(entity.getLoginAuthModes());
  }

  private static AccountAuthorityState authorityState(AccountsRecord record) {
    return new AccountAuthorityState(
        record.getPasswordHash(),
        Boolean.TRUE.equals(record.getEmailVerified()),
        AccountLoginAuthModes.read(record.getLoginAuthModes()).stream()
            .map(Enum::name)
            .sorted()
            .toList(),
        record.getRole(),
        AccountLifecycleState.fromStorageValue(record.getLifecycleState()).name());
  }

  /** Immutable capability created only after this repository reads back a fresh Account insert. */
  public static final class FreshAccountInsert {
    private final long accountId;
    private final UUID accountUuid;

    private FreshAccountInsert(long accountId, UUID accountUuid) {
      if (accountId <= 0L || accountUuid == null || new UUID(0L, 0L).equals(accountUuid)) {
        throw new IllegalArgumentException("Fresh Account insert identity is invalid");
      }
      this.accountId = accountId;
      this.accountUuid = accountUuid;
    }

    public long accountId() {
      return accountId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public AccountIdentityProvenance accountUuidProvenance() {
      return AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT;
    }
  }

  /** Transaction-scoped, exact before/after image produced only by {@link #save(Account)}. */
  public static final class AccountUpdateEvidence {
    private final long accountId;
    private final UUID accountUuid;
    private final String mutationRequestId;
    private final AccountAuthorityState before;
    private final AccountAuthorityState after;

    private AccountUpdateEvidence(
        long accountId,
        UUID accountUuid,
        String mutationRequestId,
        AccountAuthorityState before,
        AccountAuthorityState after) {
      this.accountId = accountId;
      this.accountUuid = Objects.requireNonNull(accountUuid);
      this.mutationRequestId = Objects.requireNonNull(mutationRequestId);
      this.before = Objects.requireNonNull(before);
      this.after = Objects.requireNonNull(after);
    }

    public long accountId() {
      return accountId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public String mutationRequestId() {
      return "account-authority-update:" + mutationRequestId;
    }

    public AccountAuthorityState before() {
      return before;
    }

    public AccountAuthorityState after() {
      return after;
    }
  }

  /** Never serialized; the password hash is retained only for exact in-transaction comparison. */
  public static final class AccountAuthorityState {
    private final String passwordHash;
    private final AccountState eventState;

    private AccountAuthorityState(
        String passwordHash,
        boolean emailVerified,
        List<String> loginAuthModes,
        String globalRole,
        String lifecycleState) {
      this.passwordHash = passwordHash;
      this.eventState = new AccountState(emailVerified, loginAuthModes, globalRole, lifecycleState);
    }

    public String passwordHash() {
      return passwordHash;
    }

    public boolean emailVerified() {
      return eventState.emailVerified();
    }

    public List<String> loginAuthModes() {
      return eventState.loginAuthModes();
    }

    public String globalRole() {
      return eventState.globalRole();
    }

    public String lifecycleState() {
      return eventState.lifecycleState();
    }

    public AccountState toEventState() {
      return eventState;
    }

    @Override
    public String toString() {
      return "AccountAuthorityState[passwordHash=<redacted>, eventState=" + eventState + "]";
    }
  }
}
