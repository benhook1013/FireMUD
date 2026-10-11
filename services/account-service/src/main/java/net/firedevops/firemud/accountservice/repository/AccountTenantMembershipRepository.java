package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_TENANT_MEMBERSHIP;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
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
  private static final String FRESH_GAME_DESIGN = "FRESH_GAME_DESIGN";
  private final DSLContext dsl;
  private final AccountRepository accountRepository;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;

  public AccountTenantMembershipRepository(
      DSLContext dsl,
      AccountRepository accountRepository,
      FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository) {
    this.dsl = dsl;
    this.accountRepository = accountRepository;
    this.freshTenantIdentityAssociationRepository = freshTenantIdentityAssociationRepository;
    this.pairAuthorityRepository = pairAuthorityRepository;
  }

  public Optional<AccountTenantMembership> findByAccountIdAndTenantId(
      Long accountId, Long tenantId) {
    requirePositiveNumericTenantId(tenantId);
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
   * Reads an existing positive fresh membership by canonical UUID identity only.
   *
   * <p>An absent row returns empty and never enrolls a pair baseline. A persisted row must match
   * the immutable fresh source association and an existing positive Account pair authority row;
   * neither a Game Design row ID nor tenant key is converted into an Account tenant selector.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<AccountTenantMembership> findFreshMembership(UUID accountUuid, UUID tenantUuid) {
    requireOwnerTransaction();
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    Account account =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Canonical Account identity is absent"));
    requireExactCanonicalAccount(accountUuid, account);
    VerifiedTenantProvenance expectedProvenance = readFreshProvenance(tenantUuid);
    Record row = readCanonicalRow(account.getId(), tenantUuid, false);
    if (row == null) {
      requirePairConsistentWithAbsence(
          accountUuid,
          tenantUuid,
          expectedProvenance,
          pairAuthorityRepository.readState(accountUuid, tenantUuid));
      return Optional.empty();
    }

    AccountTenantMembership membership = toCanonicalEntity(row, account);
    requireFreshMembershipIdentity(membership, account, tenantUuid, expectedProvenance);

    PairAuthority pair =
        pairAuthorityRepository
            .readPositive(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Positive Account membership pair is absent"));
    requireMembershipMatchesPair(membership, accountUuid, tenantUuid, expectedProvenance, pair);
    return Optional.of(membership);
  }

  /** Reads and locks one already-published fresh membership after the Account row fence. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountTenantMembership> findFreshMembershipForUpdate(
      UUID accountUuid, UUID tenantUuid) {
    return findFreshMembershipForUpdate(accountUuid, tenantUuid, false);
  }

  /**
   * Reads a fresh first-JOIN membership in the current Account owner transaction.
   *
   * <p>This storage read exists for the Account-owned event producer. It accepts either the exact
   * pending ACTIVE membership at version 2/generation 1 paired with its sequence-zero absence
   * baseline, or the exact first positive event-linked state for an idempotent producer replay. A
   * pending row is not caller-bound authority; the producer must append/read back the event and
   * advance the pair before the enclosing transaction commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountTenantMembership> findFreshJoinForPublicationForUpdate(
      UUID accountUuid, UUID tenantUuid) {
    return findFreshMembershipForUpdate(accountUuid, tenantUuid, true);
  }

  private Optional<AccountTenantMembership> findFreshMembershipForUpdate(
      UUID accountUuid, UUID tenantUuid, boolean allowPendingFirstJoin) {
    requireOwnerWriteTransaction();
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    Account account = lockCanonicalAccountByUuid(accountUuid);
    VerifiedTenantProvenance expectedProvenance = readFreshProvenance(tenantUuid);
    Record row = readCanonicalRow(account.getId(), tenantUuid, true);
    Optional<PairAuthority> pair = pairAuthorityRepository.readForUpdate(accountUuid, tenantUuid);
    if (row == null) {
      requirePairConsistentWithAbsence(accountUuid, tenantUuid, expectedProvenance, pair);
      return Optional.empty();
    }

    AccountTenantMembership membership = toCanonicalEntity(row, account);
    requireFreshMembershipIdentity(membership, account, tenantUuid, expectedProvenance);
    PairAuthority current =
        pair.orElseThrow(() -> new IllegalStateException("Account membership pair is absent"));
    if (allowPendingFirstJoin) {
      if (isPendingFirstJoinMembership(
          membership, accountUuid, tenantUuid, expectedProvenance, current)) {
        return Optional.of(membership);
      }
      if (isPublishedFirstJoinMembership(
          membership, accountUuid, tenantUuid, expectedProvenance, current)) {
        return Optional.of(membership);
      }
      throw new IllegalStateException("Fresh first-JOIN publication state differs from baseline");
    }
    if (current.membershipExists() && current.eventSequence() > 0L) {
      requireMembershipMatchesPair(
          membership, accountUuid, tenantUuid, expectedProvenance, current);
      return Optional.of(membership);
    }
    throw new IllegalStateException("Positive Account membership pair is absent");
  }

  private static boolean isPendingFirstJoinMembership(
      AccountTenantMembership membership,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      PairAuthority current) {
    PairAuthority absenceBaseline =
        new PairAuthority(
            accountUuid, tenantUuid, expectedProvenance, false, 1L, 1L, 0L, null, null, false);
    return absenceBaseline.equals(current)
        && membership.getMembershipVersion() == 2L
        && membership.getMembershipAuthorityGeneration() == 1L
        && membership.isGameplayAdmissionAllowed()
        && "ACTIVE".equals(membership.getLifecycleState())
        && "EXPLICIT_JOIN".equals(membership.getAuthorityProvenance());
  }

  private static boolean isPublishedFirstJoinMembership(
      AccountTenantMembership membership,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      PairAuthority current) {
    return current.membershipExists()
        && current.membershipVersion() == 2L
        && current.membershipAuthorityGeneration() == 1L
        && current.eventSequence() == 1L
        && current.eventId() != null
        && current.eventDigest() != null
        && !current.lastTransitionInvalidated()
        && accountUuid.equals(current.accountUuid())
        && tenantUuid.equals(current.tenantUuid())
        && expectedProvenance.equals(current.provenance())
        && membership.getMembershipVersion() == 2L
        && membership.getMembershipAuthorityGeneration() == 1L
        && membership.isGameplayAdmissionAllowed()
        && "ACTIVE".equals(membership.getLifecycleState())
        && "EXPLICIT_JOIN".equals(membership.getAuthorityProvenance());
  }

  /**
   * Creates the first fresh membership and sequence-zero pair baseline in the caller's Account
   * transaction. The Account-owned event producer must append/read back the real outbox event and
   * advance the pair in this same transaction before commit; this row is not caller-bound JOIN
   * authority by itself.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountTenantMembership createFreshMembershipForJoin(UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");

    Account account = lockCanonicalAccountByUuid(accountUuid);
    VerifiedTenantProvenance expectedProvenance = readFreshProvenance(tenantUuid);
    if (expectedProvenance.kind() != TenantProvenanceKind.FRESH_GAME_DESIGN
        || expectedProvenance.legacyTenantId() != null) {
      throw new IllegalStateException("Fresh JOIN requires UUID-only Game Design provenance");
    }

    Record existingMembership = readCanonicalRow(account.getId(), tenantUuid, true);
    if (existingMembership != null) {
      throw new IllegalStateException(
          "Fresh JOIN cannot overwrite an existing canonical membership row");
    }

    PairAuthority absenceBaseline =
        new PairAuthority(
            accountUuid, tenantUuid, expectedProvenance, false, 1L, 1L, 0L, null, null, false);
    Optional<PairAuthority> existingPair =
        pairAuthorityRepository.readForUpdate(accountUuid, tenantUuid);
    PairAuthority baseline;
    if (existingPair.isEmpty()) {
      baseline = pairAuthorityRepository.enrollAbsence(accountUuid, tenantUuid, expectedProvenance);
      if (!absenceBaseline.equals(baseline)) {
        throw new IllegalStateException(
            "Fresh first JOIN pair enrollment differs from the exact absence baseline");
      }
    } else if (!absenceBaseline.equals(existingPair.orElseThrow())) {
      throw new IllegalStateException(
          "Fresh first JOIN found prior or contradictory Account membership pair state");
    } else {
      baseline = existingPair.orElseThrow();
    }

    long membershipVersion = increment(baseline.membershipVersion(), "membership version");
    if (membershipVersion != 2L
        || baseline.membershipAuthorityGeneration() != 1L
        || baseline.eventSequence() != 0L) {
      throw new IllegalStateException("Fresh first JOIN requires the exact initial pair baseline");
    }
    long membershipAuthorityGeneration = baseline.membershipAuthorityGeneration();
    Long membershipId =
        dsl.resultQuery(
                "INSERT INTO account_tenant_membership "
                    + "(account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
                    + "tenant_source_operation_id, tenant_provenance_digest, "
                    + "gameplay_admission_allowed, lifecycle_state, membership_version, "
                    + "membership_authority_generation, authority_provenance) "
                    + "VALUES (?, NULL, ?, ?, ?, ?, TRUE, 'ACTIVE', ?, ?, 'EXPLICIT_JOIN') "
                    + "RETURNING id",
                account.getId(),
                tenantUuid,
                expectedProvenance.kind().name(),
                expectedProvenance.sourceOperationId(),
                expectedProvenance.digest(),
                membershipVersion,
                membershipAuthorityGeneration)
            .fetchOne(0, Long.class);
    if (membershipId == null || membershipId <= 0L) {
      throw new IllegalStateException("Fresh JOIN membership insert did not return a positive ID");
    }

    AccountTenantMembership readback =
        Optional.ofNullable(readCanonicalRow(account.getId(), tenantUuid, true))
            .map(row -> toCanonicalEntity(row, account))
            .orElseThrow(
                () -> new IllegalStateException("Fresh JOIN membership readback is absent"));
    requireFreshMembershipIdentity(readback, account, tenantUuid, expectedProvenance);
    if (!membershipId.equals(readback.getId())
        || readback.getMembershipVersion() != membershipVersion
        || readback.getMembershipAuthorityGeneration() != membershipAuthorityGeneration
        || !readback.isGameplayAdmissionAllowed()
        || !"ACTIVE".equals(readback.getLifecycleState())
        || !"EXPLICIT_JOIN".equals(readback.getAuthorityProvenance())) {
      throw new IllegalStateException("Fresh JOIN membership readback differs from its write");
    }
    PairAuthority pendingBaseline =
        pairAuthorityRepository
            .readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh JOIN pair absence baseline is absent"));
    if (!baseline.equals(pendingBaseline)) {
      throw new IllegalStateException("Fresh JOIN changed pair authority before event publication");
    }
    return readback;
  }

  /** Account-fenced absence enrollment for the fresh creator owner; creates no membership. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PairAuthority prepareFreshCreatorAbsence(UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    Account account = lockCanonicalAccountByUuid(accountUuid);
    VerifiedTenantProvenance provenance = readFreshProvenance(tenantUuid);
    if (readCanonicalRow(account.getId(), tenantUuid, true) != null) {
      throw new IllegalStateException("Fresh creator membership is not absent");
    }
    Record retainedRoleHistoryCheck =
        dsl.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM account_tenant_membership_role_snapshots roles "
                + "JOIN account_tenant_membership member ON member.id = roles.membership_id "
                + "WHERE member.account_id = ? AND member.tenant_uuid = ?)",
            account.getId(),
            tenantUuid);
    if (retainedRoleHistoryCheck == null) {
      throw new IllegalStateException("Fresh creator role history check is absent");
    }
    if (!Boolean.FALSE.equals(retainedRoleHistoryCheck.get(0, Boolean.class))) {
      throw new IllegalStateException("Fresh creator pair has retained role history");
    }
    return pairAuthorityRepository.enrollAbsence(accountUuid, tenantUuid, provenance);
  }

  /** Exact UUID source row, including a creator write awaiting its same-transaction event. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountTenantMembership> findCanonicalMembershipForUpdate(
      UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    Account account = lockCanonicalAccountByUuid(accountUuid);
    VerifiedTenantProvenance provenance = readFreshProvenance(tenantUuid);
    Record row = readCanonicalRow(account.getId(), tenantUuid, true);
    if (row == null) return Optional.empty();
    AccountTenantMembership membership = toCanonicalEntity(row, account);
    requireFreshMembershipIdentity(membership, account, tenantUuid, provenance);
    return Optional.of(membership);
  }

  /** Creates only the exact first control-only creator row from its durable absence pair. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountTenantMembership createFreshCreatorMembership(UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    Account account = lockCanonicalAccountByUuid(accountUuid);
    VerifiedTenantProvenance provenance = readFreshProvenance(tenantUuid);
    if (readCanonicalRow(account.getId(), tenantUuid, true) != null) {
      throw new IllegalStateException("Creator bootstrap cannot overwrite membership");
    }
    PairAuthority baseline =
        pairAuthorityRepository
            .readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Creator absence pair is absent"));
    if (!new PairAuthority(
            accountUuid, tenantUuid, provenance, false, 1L, 1L, 0L, null, null, false)
        .equals(baseline)) {
      throw new IllegalStateException("Creator pair differs from its exact absence baseline");
    }
    Long id =
        dsl.resultQuery(
                "INSERT INTO account_tenant_membership (account_id, tenant_id, tenant_uuid, "
                    + "tenant_provenance_kind, tenant_source_operation_id, tenant_provenance_digest, "
                    + "gameplay_admission_allowed, lifecycle_state, membership_version, "
                    + "membership_authority_generation, authority_provenance) "
                    + "VALUES (?, NULL, ?, ?, ?, ?, FALSE, 'ACTIVE', 2, 1, 'TENANT_CREATION') RETURNING id",
                account.getId(),
                tenantUuid,
                provenance.kind().name(),
                provenance.sourceOperationId(),
                provenance.digest())
            .fetchOne(0, Long.class);
    AccountTenantMembership result =
        findCanonicalMembershipForUpdate(accountUuid, tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Creator membership readback is absent"));
    if (id == null
        || id <= 0L
        || !id.equals(result.getId())
        || result.getMembershipVersion() != 2L
        || result.getMembershipAuthorityGeneration() != 1L
        || result.isGameplayAdmissionAllowed()
        || !"ACTIVE".equals(result.getLifecycleState())
        || !"TENANT_CREATION".equals(result.getAuthorityProvenance())
        || !Optional.of(baseline)
            .equals(pairAuthorityRepository.readForUpdate(accountUuid, tenantUuid))) {
      throw new IllegalStateException("Creator membership readback differs from its write");
    }
    return result;
  }

  public boolean existsByAccountIdAndTenantId(Long accountId, Long tenantId) {
    requirePositiveNumericTenantId(tenantId);
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
        || entity.getTenantUuid() != null
        || FRESH_GAME_DESIGN.equals(entity.getTenantProvenanceKind())) {
      throw new IllegalArgumentException(
          "Numeric membership writes require an existing numeric retained-tenant contract");
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
    requirePositiveNumericTenantId(tenantId);
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

  private Record readCanonicalRow(Long accountId, UUID tenantUuid, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT id, account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, "
            + "gameplay_admission_allowed, lifecycle_state, membership_version, "
            + "membership_authority_generation, authority_provenance "
            + "FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        accountId,
        tenantUuid);
  }

  private VerifiedTenantProvenance readFreshProvenance(UUID tenantUuid) {
    FreshTenantCreationEvidence freshAssociation =
        freshTenantIdentityAssociationRepository
            .read(tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh Account tenant association is absent"));
    if (!tenantUuid.equals(freshAssociation.canonicalTenantId())) {
      throw new IllegalStateException(
          "Fresh Account tenant association differs from the requested canonical UUID");
    }
    return new VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        freshAssociation.operationId(),
        freshAssociation.evidenceDigest());
  }

  private void requireFreshMembershipIdentity(
      AccountTenantMembership membership,
      Account account,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance) {
    if (membership.getId() == null
        || membership.getId() <= 0L
        || membership.getAccount() == null
        || !Objects.equals(membership.getAccount().getId(), account.getId())
        || membership.getTenantId() != null
        || !tenantUuid.equals(membership.getTenantUuid())
        || !Objects.equals(expectedProvenance.legacyTenantId(), membership.getTenantId())
        || !FRESH_GAME_DESIGN.equals(membership.getTenantProvenanceKind())
        || !expectedProvenance.sourceOperationId().equals(membership.getTenantSourceOperationId())
        || !expectedProvenance.digest().equals(membership.getTenantProvenanceDigest())
        || membership.getMembershipVersion() <= 0L
        || membership.getMembershipAuthorityGeneration() <= 0L
        || (!"ACTIVE".equals(membership.getLifecycleState())
            && !"INACTIVE".equals(membership.getLifecycleState()))) {
      throw new IllegalStateException(
          "Canonical fresh Account membership differs from immutable owner identity");
    }
  }

  private void requireMembershipMatchesPair(
      AccountTenantMembership membership,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      PairAuthority pair) {
    if (!accountUuid.equals(pair.accountUuid())
        || !tenantUuid.equals(pair.tenantUuid())
        || !expectedProvenance.equals(pair.provenance())
        || !pair.membershipExists()
        || pair.eventSequence() <= 0L
        || pair.membershipVersion() != membership.getMembershipVersion()
        || pair.membershipAuthorityGeneration() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Fresh Account membership differs from its positive pair authority snapshot");
    }
  }

  private void requirePairConsistentWithAbsence(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      Optional<PairAuthority> pair) {
    if (pair.isEmpty()) {
      return;
    }
    PairAuthority current = pair.orElseThrow();
    PairAuthority expectedBaseline =
        new PairAuthority(
            accountUuid, tenantUuid, expectedProvenance, false, 1L, 1L, 0L, null, null, false);
    if (!expectedBaseline.equals(current)) {
      throw new IllegalStateException(
          "Missing canonical membership conflicts with Account membership pair history");
    }
  }

  private Account lockCanonicalAccountByUuid(UUID accountUuid) {
    Account indexed =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Canonical Account identity is absent"));
    requireExactCanonicalAccount(accountUuid, indexed);
    Record locked =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                + "FROM accounts WHERE id = ? FOR UPDATE",
            indexed.getId());
    if (locked == null) {
      throw new IllegalStateException("Canonical Account row disappeared before its write fence");
    }
    UUID lockedUuid = locked.get("account_uuid", UUID.class);
    AccountIdentityProvenance lockedProvenance =
        AccountIdentityProvenance.fromStorageValue(
            locked.get("account_uuid_provenance", String.class));
    Long lockedSourceNumericId = locked.get("account_uuid_source_numeric_id", Long.class);
    Long lockedId = locked.get("id", Long.class);
    if (!accountUuid.equals(lockedUuid)
        || !Objects.equals(indexed.getAccountUuidProvenance(), lockedProvenance)
        || !Objects.equals(indexed.getAccountUuidSourceNumericId(), lockedSourceNumericId)
        || !Objects.equals(indexed.getId(), lockedId)
        || !Objects.equals(lockedSourceNumericId, lockedId)) {
      throw new IllegalStateException("Canonical Account identity changed at its row fence");
    }
    return indexed;
  }

  private void requireExactCanonicalAccount(UUID accountUuid, Account account) {
    if (!accountUuid.equals(account.getAccountUuid())
        || account.getId() == null
        || account.getId() <= 0L
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new IllegalStateException(
          "Canonical Account UUID does not identify its exact persisted private row");
    }
  }

  private static void requireCanonicalUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil canonical UUID");
    }
  }

  private static void requirePositiveNumericTenantId(Long tenantId) {
    if (tenantId == null || tenantId <= 0L) {
      throw new IllegalArgumentException(
          "Numeric Account membership lookup requires a positive tenant ID");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account membership read requires an active owner transaction");
    }
  }

  private static void requireOwnerWriteTransaction() {
    requireOwnerTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical Account membership mutation requires a writable owner transaction");
    }
  }

  private long increment(long value, String label) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account membership " + label + " is exhausted", overflow);
    }
  }
}
