package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.AccountFriendLinks.ACCOUNT_FRIEND_LINKS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.jooq.tables.records.AccountFriendLinksRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Repository for account-level friend links. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountFriendLinkRepository {
  private final DSLContext dsl;

  public AccountFriendLinkRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<AccountFriendLink> findByTenantIdAndAccountIdAndStatus(
      Long tenantId, Long accountId, String status) {
    return dsl.selectFrom(ACCOUNT_FRIEND_LINKS)
        .where(
            ACCOUNT_FRIEND_LINKS
                .TENANT_ID
                .eq(tenantId)
                .and(ACCOUNT_FRIEND_LINKS.ACCOUNT_ID.eq(accountId))
                .and(ACCOUNT_FRIEND_LINKS.STATUS.eq(status)))
        .orderBy(ACCOUNT_FRIEND_LINKS.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<AccountFriendLink> findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
      Long tenantId, Long accountId, Long friendAccountId, String status) {
    return dsl.selectFrom(ACCOUNT_FRIEND_LINKS)
        .where(
            ACCOUNT_FRIEND_LINKS
                .TENANT_ID
                .eq(tenantId)
                .and(ACCOUNT_FRIEND_LINKS.ACCOUNT_ID.eq(accountId))
                .and(ACCOUNT_FRIEND_LINKS.FRIEND_ACCOUNT_ID.eq(friendAccountId))
                .and(ACCOUNT_FRIEND_LINKS.STATUS.eq(status)))
        .orderBy(ACCOUNT_FRIEND_LINKS.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public AccountFriendLink save(AccountFriendLink entity) {
    if (entity.getId() == null) {
      AccountFriendLinksRecord record = dsl.newRecord(ACCOUNT_FRIEND_LINKS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(ACCOUNT_FRIEND_LINKS)
            .set(ACCOUNT_FRIEND_LINKS.TENANT_ID, entity.getTenantId())
            .set(ACCOUNT_FRIEND_LINKS.ACCOUNT_ID, entity.getAccountId())
            .set(ACCOUNT_FRIEND_LINKS.FRIEND_ACCOUNT_ID, entity.getFriendAccountId())
            .set(ACCOUNT_FRIEND_LINKS.STATUS, entity.getStatus())
            .set(
                ACCOUNT_FRIEND_LINKS.CREATED_AT,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()))
            .where(ACCOUNT_FRIEND_LINKS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(
          ACCOUNT_FRIEND_LINKS.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(AccountFriendLink entity) {
    if (entity.getId() == null) {
      return;
    }
    dsl.deleteFrom(ACCOUNT_FRIEND_LINKS)
        .where(ACCOUNT_FRIEND_LINKS.ID.eq(entity.getId()))
        .execute();
  }

  public void deleteAll() {
    dsl.deleteFrom(ACCOUNT_FRIEND_LINKS).execute();
  }

  private Optional<AccountFriendLink> findById(Long id) {
    return dsl.selectFrom(ACCOUNT_FRIEND_LINKS)
        .where(ACCOUNT_FRIEND_LINKS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(AccountFriendLinksRecord record, AccountFriendLink entity) {
    record.setTenantId(entity.getTenantId());
    record.setAccountId(entity.getAccountId());
    record.setFriendAccountId(entity.getFriendAccountId());
    record.setStatus(entity.getStatus());
    record.setCreatedAt(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()));
  }

  private AccountFriendLink toEntity(Record record) {
    AccountFriendLink entity = new AccountFriendLink();
    entity.setId(record.get(ACCOUNT_FRIEND_LINKS.ID));
    entity.setTenantId(record.get(ACCOUNT_FRIEND_LINKS.TENANT_ID));
    entity.setAccountId(record.get(ACCOUNT_FRIEND_LINKS.ACCOUNT_ID));
    entity.setFriendAccountId(record.get(ACCOUNT_FRIEND_LINKS.FRIEND_ACCOUNT_ID));
    entity.setStatus(record.get(ACCOUNT_FRIEND_LINKS.STATUS));
    entity.setCreatedAt(
        JooqSocialGroupsRepositorySupport.toInstant(record.get(ACCOUNT_FRIEND_LINKS.CREATED_AT)));
    return entity;
  }
}
