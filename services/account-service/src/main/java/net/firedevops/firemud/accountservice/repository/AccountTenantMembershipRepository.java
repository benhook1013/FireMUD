package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_TENANT_MEMBERSHIP;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountTenantMembershipRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String UNBRIDGED_RETAINED = "UNBRIDGED_RETAINED";

  private final DSLContext dsl;
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository;

  public AccountTenantMembershipRepository(
      DSLContext dsl,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository) {
    this.dsl = dsl;
    this.accountRepository = accountRepository;
    this.tenantIdentityResolver = tenantIdentityResolver;
    this.freshTenantIdentityAssociationRepository = freshTenantIdentityAssociationRepository;
  }

  public Optional<AccountTenantMembership> findByAccountIdAndTenantId(
      Long accountId, Long tenantId) {
    return Optional.ofNullable(
        baseSelect()
            .where(
                ACCOUNT_TENANT_MEMBERSHIP
                    .ACCOUNT_ID
                    .eq(accountId)
                    .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)))
            .fetchOne(this::toEntity));
  }

  /**
   * Reads one canonical membership by UUID identity under the Account owner transaction. The
   * returned row includes its exact stored tenant source tuple; neither UUID path manufactures a
   * numeric tenant selector.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountTenantMembership> findCanonicalMembershipForUpdate(
      UUID accountUuid, UUID tenantUuid) {
    requireCanonicalOwnerTransaction();
    Account account = lockAccountByUuid(accountUuid);
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    Record row =
        dsl.fetchOne(
            "SELECT m.id, m.account_id, m.tenant_id, m.tenant_uuid, "
                + "m.tenant_provenance_kind, m.tenant_source_operation_id, "
                + "m.tenant_provenance_digest, m.gameplay_admission_allowed, "
                + "m.lifecycle_state, m.membership_version, "
                + "m.membership_authority_generation, m.authority_provenance "
                + "FROM account_tenant_membership m "
                + "WHERE m.account_id = ? AND m.tenant_uuid = ? FOR UPDATE",
            account.getId(),
            tenantUuid);
    if (row == null) {
      return Optional.empty();
    }
    AccountTenantMembership membership = toCanonicalEntity(row, account);
    requireVerifiedTenantProvenance(tenantUuid, provenanceFromMembership(membership));
    return Optional.of(membership);
  }

  /**
   * Writes a UUID-qualified membership only after exact Account and immutable tenant source
   * readback in the surrounding Account transaction. Fresh rows persist no legacy tenant ID;
   * approved retained rows preserve the supplied private bridge key.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountTenantMembership saveCanonical(
      AccountTenantMembership entity,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance) {
    requireCanonicalOwnerTransaction();
    if (entity == null
        || entity.getAccount() == null
        || entity.getAccount().getId() == null
        || entity.getAccount().getId() <= 0L) {
      throw new IllegalArgumentException("A persisted Account row is required for membership");
    }
    Account account = lockAccount(entity.getAccount().getId(), accountUuid);
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    VerifiedTenantProvenance provenance =
        requireVerifiedTenantProvenance(tenantUuid, expectedProvenance);
    if (!Objects.equals(entity.getTenantId(), provenance.legacyTenantId())) {
      throw new IllegalArgumentException(
          "Membership legacy tenant key differs from verified canonical provenance");
    }

    long accountId = account.getId();
    if (entity.getId() == null) {
      Long membershipId =
          dsl.resultQuery(
                  "INSERT INTO account_tenant_membership "
                      + "(account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
                      + "tenant_source_operation_id, tenant_provenance_digest, "
                      + "gameplay_admission_allowed, lifecycle_state, membership_version, "
                      + "membership_authority_generation, authority_provenance) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                  accountId,
                  provenance.legacyTenantId(),
                  tenantUuid,
                  provenance.kind().name(),
                  provenance.sourceOperationId(),
                  provenance.digest(),
                  entity.isGameplayAdmissionAllowed(),
                  entity.getLifecycleState(),
                  entity.getMembershipVersion(),
                  entity.getMembershipAuthorityGeneration(),
                  entity.getAuthorityProvenance())
              .fetchOne(0, Long.class);
      if (membershipId == null || membershipId <= 0L) {
        throw new IllegalStateException(
            "Canonical Account membership insert did not return its ID");
      }
      entity.setId(membershipId);
    } else {
      requireExistingMembershipMayBindOrUpdate(
          entity.getId(), accountId, entity.getTenantId(), tenantUuid, provenance);
      int updated =
          dsl.execute(
              "UPDATE account_tenant_membership SET tenant_id = ?, tenant_uuid = ?, "
                  + "tenant_provenance_kind = ?, tenant_source_operation_id = ?, "
                  + "tenant_provenance_digest = ?, gameplay_admission_allowed = ?, "
                  + "lifecycle_state = ?, membership_version = ?, "
                  + "membership_authority_generation = ?, authority_provenance = ? "
                  + "WHERE id = ? AND account_id = ?",
              provenance.legacyTenantId(),
              tenantUuid,
              provenance.kind().name(),
              provenance.sourceOperationId(),
              provenance.digest(),
              entity.isGameplayAdmissionAllowed(),
              entity.getLifecycleState(),
              entity.getMembershipVersion(),
              entity.getMembershipAuthorityGeneration(),
              entity.getAuthorityProvenance(),
              entity.getId(),
              accountId);
      if (updated != 1) {
        throw JooqAccountRepositorySupport.staleWrite("account_tenant_membership", entity.getId());
      }
    }

    AccountTenantMembership readback =
        findCanonicalMembershipForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical Account membership was not readable after its write"));
    if (!sameMembershipState(entity, readback)
        || !Objects.equals(entity.getId(), readback.getId())
        || !Objects.equals(entity.getTenantId(), readback.getTenantId())
        || !tenantUuid.equals(readback.getTenantUuid())
        || !provenanceFromMembership(readback).equals(provenance)) {
      throw new IllegalStateException(
          "Canonical Account membership readback differs from its write");
    }
    copyIdentity(readback, entity);
    return readback;
  }

  /** Reads and locks the exact membership row for a JOIN reconciliation proof. */
  public Optional<JoinMembershipProof> findJoinProofForUpdate(long accountId, long tenantId) {
    return dsl.select(
            ACCOUNT_TENANT_MEMBERSHIP.ID,
            ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID,
            ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID,
            ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
            ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE,
            ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION,
            ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION,
            ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE)
        .from(ACCOUNT_TENANT_MEMBERSHIP)
        .where(
            ACCOUNT_TENANT_MEMBERSHIP
                .ACCOUNT_ID
                .eq(accountId)
                .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)))
        .forUpdate()
        .fetchOptional(
            record ->
                new JoinMembershipProof(
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.ID),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID),
                    Boolean.TRUE.equals(
                        record.get(ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED)),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION),
                    record.get(ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE)));
  }

  public boolean existsByAccountIdAndTenantId(Long accountId, Long tenantId) {
    return dsl.fetchExists(
        ACCOUNT_TENANT_MEMBERSHIP,
        ACCOUNT_TENANT_MEMBERSHIP
            .ACCOUNT_ID
            .eq(accountId)
            .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)));
  }

  public boolean existsByAccountId(Long accountId) {
    return dsl.fetchExists(
        ACCOUNT_TENANT_MEMBERSHIP, ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(accountId));
  }

  public List<AccountTenantMembership> findByAccountId(Long accountId) {
    return baseSelect()
        .where(
            ACCOUNT_TENANT_MEMBERSHIP
                .ACCOUNT_ID
                .eq(accountId)
                .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.isNotNull()))
        .orderBy(ACCOUNT_TENANT_MEMBERSHIP.ID.asc())
        .fetch(this::toEntity);
  }

  public AccountTenantMembership save(AccountTenantMembership entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getTenantId() == null
        || "FRESH_GAME_DESIGN".equals(entity.getTenantProvenanceKind())) {
      throw new IllegalArgumentException(
          "Numeric Account membership writes require a retained tenant selector");
    }
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACCOUNT_TENANT_MEMBERSHIP)
              .set(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID, accountId)
              .set(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID, entity.getTenantId())
              .set(
                  ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
                  entity.isGameplayAdmissionAllowed())
              .set(ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE, entity.getLifecycleState())
              .set(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION, entity.getMembershipVersion())
              .set(
                  ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION,
                  entity.getMembershipAuthorityGeneration())
              .set(ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE, entity.getAuthorityProvenance())
              .returningResult(ACCOUNT_TENANT_MEMBERSHIP.ID)
              .fetchOne(ACCOUNT_TENANT_MEMBERSHIP.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(ACCOUNT_TENANT_MEMBERSHIP)
            .set(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID, accountId)
            .set(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID, entity.getTenantId())
            .set(
                ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
                entity.isGameplayAdmissionAllowed())
            .set(ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE, entity.getLifecycleState())
            .set(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION, entity.getMembershipVersion())
            .set(
                ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION,
                entity.getMembershipAuthorityGeneration())
            .set(ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE, entity.getAuthorityProvenance())
            .where(ACCOUNT_TENANT_MEMBERSHIP.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("account_tenant_membership", entity.getId());
    }
    return entity;
  }

  public AccountTenantMembership saveAndFlush(AccountTenantMembership entity) {
    return save(entity);
  }

  public void delete(AccountTenantMembership entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
          .where(ACCOUNT_TENANT_MEMBERSHIP.ID.eq(entity.getId()))
          .execute();
    }
  }

  public void deleteByAccountIdAndTenantId(Long accountId, Long tenantId) {
    dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
        .where(
            ACCOUNT_TENANT_MEMBERSHIP
                .ACCOUNT_ID
                .eq(accountId)
                .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)))
        .execute();
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
        .where(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            ACCOUNT_TENANT_MEMBERSHIP.ID,
            ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID,
            ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID,
            org.jooq.impl.DSL.field(
                org.jooq.impl.DSL.name("account_tenant_membership", "tenant_uuid"), UUID.class),
            org.jooq.impl.DSL.field(
                org.jooq.impl.DSL.name("account_tenant_membership", "tenant_provenance_kind"),
                String.class),
            org.jooq.impl.DSL.field(
                org.jooq.impl.DSL.name("account_tenant_membership", "tenant_source_operation_id"),
                UUID.class),
            org.jooq.impl.DSL.field(
                org.jooq.impl.DSL.name("account_tenant_membership", "tenant_provenance_digest"),
                String.class),
            ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
            ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE,
            ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION,
            ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION,
            ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(ACCOUNT_TENANT_MEMBERSHIP)
        .join(ACCOUNTS)
        .on(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private AccountTenantMembership toEntity(Record record) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(record.get(ACCOUNT_TENANT_MEMBERSHIP.ID));
    membership.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    membership.setTenantId(record.get(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID));
    membership.setTenantUuid(record.get("tenant_uuid", UUID.class));
    membership.setTenantProvenanceKind(record.get("tenant_provenance_kind", String.class));
    membership.setTenantSourceOperationId(record.get("tenant_source_operation_id", UUID.class));
    membership.setTenantProvenanceDigest(record.get("tenant_provenance_digest", String.class));
    membership.setGameplayAdmissionAllowed(
        Boolean.TRUE.equals(record.get(ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED)));
    membership.setLifecycleState(record.get(ACCOUNT_TENANT_MEMBERSHIP.LIFECYCLE_STATE));
    membership.setMembershipVersion(record.get(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_VERSION));
    membership.setMembershipAuthorityGeneration(
        record.get(ACCOUNT_TENANT_MEMBERSHIP.MEMBERSHIP_AUTHORITY_GENERATION));
    membership.setAuthorityProvenance(record.get(ACCOUNT_TENANT_MEMBERSHIP.AUTHORITY_PROVENANCE));
    return membership;
  }

  private Account lockAccountByUuid(UUID accountUuid) {
    requireCanonicalUuid(accountUuid, "Account UUID");
    Account indexed =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Canonical Account identity is absent"));
    Account locked = lockAccount(indexed.getId(), accountUuid);
    if (!Objects.equals(indexed.getId(), locked.getId())
        || indexed.getAccountUuidProvenance() != locked.getAccountUuidProvenance()
        || !Objects.equals(
            indexed.getAccountUuidSourceNumericId(), locked.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Canonical Account identity changed at its row fence");
    }
    return locked;
  }

  private Account lockAccount(long accountId, UUID expectedAccountUuid) {
    requireCanonicalUuid(expectedAccountUuid, "Account UUID");
    Account account =
        accountRepository
            .findByIdForUpdate(accountId)
            .orElseThrow(() -> new IllegalStateException("Canonical Account row is absent"));
    if (!expectedAccountUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new IllegalStateException(
          "Canonical Account UUID does not exactly identify its persisted private row");
    }
    return account;
  }

  private VerifiedTenantProvenance requireVerifiedTenantProvenance(
      UUID tenantUuid, VerifiedTenantProvenance expected) {
    if (expected == null) {
      throw new IllegalArgumentException("Verified canonical tenant provenance is required");
    }
    VerifiedTenantProvenance actual;
    if (expected.kind() == TenantProvenanceKind.APPROVED_RETAINED) {
      var association = tenantIdentityResolver.resolve(tenantUuid);
      actual =
          new VerifiedTenantProvenance(
              association.legacyTenantId(),
              TenantProvenanceKind.APPROVED_RETAINED,
              association.operationId(),
              association.manifestDigest());
    } else if (expected.kind() == TenantProvenanceKind.FRESH_GAME_DESIGN) {
      FreshTenantCreationEvidence association =
          freshTenantIdentityAssociationRepository
              .read(tenantUuid)
              .orElseThrow(
                  () -> new IllegalStateException("Fresh Account tenant association is absent"));
      actual =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              association.operationId(),
              association.evidenceDigest());
    } else {
      throw new IllegalArgumentException("Canonical membership requires an approved or fresh UUID");
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException(
          "Supplied canonical tenant provenance differs from immutable source readback");
    }
    return actual;
  }

  private void requireExistingMembershipMayBindOrUpdate(
      long membershipId,
      long accountId,
      Long legacyTenantId,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance) {
    Record existing =
        dsl.fetchOne(
            "SELECT tenant_id, tenant_uuid, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest "
                + "FROM account_tenant_membership "
                + "WHERE id = ? AND account_id = ? FOR UPDATE",
            membershipId,
            accountId);
    if (existing == null
        || !Objects.equals(existing.get("tenant_id", Long.class), legacyTenantId)) {
      throw JooqAccountRepositorySupport.staleWrite("account_tenant_membership", membershipId);
    }
    String kind = existing.get("tenant_provenance_kind", String.class);
    if (UNBRIDGED_RETAINED.equals(kind)) {
      if (provenance.kind() != TenantProvenanceKind.APPROVED_RETAINED
          || existing.get("tenant_uuid", UUID.class) != null
          || existing.get("tenant_source_operation_id", UUID.class) != null
          || existing.get("tenant_provenance_digest", String.class) != null) {
        throw new IllegalStateException(
            "Only an exact approved retained association may bind an unbridged membership");
      }
      return;
    }
    if (!tenantUuid.equals(existing.get("tenant_uuid", UUID.class))
        || !provenance.kind().name().equals(kind)
        || !provenance
            .sourceOperationId()
            .equals(existing.get("tenant_source_operation_id", UUID.class))
        || !provenance.digest().equals(existing.get("tenant_provenance_digest", String.class))) {
      throw new IllegalStateException(
          "Canonical Account membership identity differs from its immutable stored provenance");
    }
  }

  private AccountTenantMembership toCanonicalEntity(Record record, Account account) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(record.get("id", Long.class));
    membership.setAccount(account);
    membership.setTenantId(record.get("tenant_id", Long.class));
    membership.setTenantUuid(record.get("tenant_uuid", UUID.class));
    membership.setTenantProvenanceKind(record.get("tenant_provenance_kind", String.class));
    membership.setTenantSourceOperationId(record.get("tenant_source_operation_id", UUID.class));
    membership.setTenantProvenanceDigest(record.get("tenant_provenance_digest", String.class));
    membership.setGameplayAdmissionAllowed(
        Boolean.TRUE.equals(record.get("gameplay_admission_allowed", Boolean.class)));
    membership.setLifecycleState(record.get("lifecycle_state", String.class));
    membership.setMembershipVersion(record.get("membership_version", Long.class));
    membership.setMembershipAuthorityGeneration(
        record.get("membership_authority_generation", Long.class));
    membership.setAuthorityProvenance(record.get("authority_provenance", String.class));
    return membership;
  }

  private VerifiedTenantProvenance provenanceFromMembership(AccountTenantMembership membership) {
    if (membership.getTenantProvenanceKind() == null
        || UNBRIDGED_RETAINED.equals(membership.getTenantProvenanceKind())) {
      throw new IllegalStateException(
          "Canonical Account membership lacks authenticated tenant provenance");
    }
    try {
      return new VerifiedTenantProvenance(
          membership.getTenantId(),
          TenantProvenanceKind.valueOf(membership.getTenantProvenanceKind()),
          membership.getTenantSourceOperationId(),
          membership.getTenantProvenanceDigest());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Canonical Account membership provenance is malformed", ex);
    }
  }

  private boolean sameMembershipState(
      AccountTenantMembership expected, AccountTenantMembership actual) {
    return Objects.equals(expected.getAccount().getId(), actual.getAccount().getId())
        && expected.isGameplayAdmissionAllowed() == actual.isGameplayAdmissionAllowed()
        && Objects.equals(expected.getLifecycleState(), actual.getLifecycleState())
        && expected.getMembershipVersion() == actual.getMembershipVersion()
        && expected.getMembershipAuthorityGeneration() == actual.getMembershipAuthorityGeneration()
        && Objects.equals(expected.getAuthorityProvenance(), actual.getAuthorityProvenance());
  }

  private void copyIdentity(AccountTenantMembership source, AccountTenantMembership target) {
    target.setTenantId(source.getTenantId());
    target.setTenantUuid(source.getTenantUuid());
    target.setTenantProvenanceKind(source.getTenantProvenanceKind());
    target.setTenantSourceOperationId(source.getTenantSourceOperationId());
    target.setTenantProvenanceDigest(source.getTenantProvenanceDigest());
  }

  private void requireCanonicalUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil canonical UUID");
    }
  }

  private void requireCanonicalOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account membership access requires an active owner transaction");
    }
  }

  public record JoinMembershipProof(
      long membershipId,
      long accountId,
      long tenantId,
      boolean gameplayAdmissionAllowed,
      String lifecycleState,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance) {}
}
