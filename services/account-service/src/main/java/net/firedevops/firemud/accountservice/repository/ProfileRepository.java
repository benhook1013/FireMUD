package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.PROFILES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ProfileRepository {
  private final DSLContext dsl;

  public ProfileRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Profile> findByAccountIdAndTenantId(Long accountId, Long tenantId) {
    return Optional.ofNullable(
        baseSelect()
            .where(PROFILES.ACCOUNT_ID.eq(accountId).and(PROFILES.TENANT_ID.eq(tenantId)))
            .fetchOne(this::toEntity));
  }

  public List<Profile> findByAccountId(Long accountId) {
    return baseSelect()
        .where(PROFILES.ACCOUNT_ID.eq(accountId))
        .orderBy(PROFILES.ID.asc())
        .fetch(this::toEntity);
  }

  public Profile save(Profile entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    String policy =
        entity.getPresenceVisibilityPolicy() == null
            ? ProfilePresenceVisibilityPolicy.FRIENDS_ONLY.name()
            : entity.getPresenceVisibilityPolicy().name();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(PROFILES)
              .set(PROFILES.ACCOUNT_ID, accountId)
              .set(PROFILES.TENANT_ID, entity.getTenantId())
              .set(PROFILES.DISPLAY_NAME, entity.getDisplayName())
              .set(PROFILES.BIO, entity.getBio())
              .set(PROFILES.PRESENCE_VISIBILITY_POLICY, policy)
              .returningResult(PROFILES.ID)
              .fetchOne(PROFILES.ID);
      entity.setId(id);
      entity.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.valueOf(policy));
      return entity;
    }
    int updated =
        dsl.update(PROFILES)
            .set(PROFILES.ACCOUNT_ID, accountId)
            .set(PROFILES.TENANT_ID, entity.getTenantId())
            .set(PROFILES.DISPLAY_NAME, entity.getDisplayName())
            .set(PROFILES.BIO, entity.getBio())
            .set(PROFILES.PRESENCE_VISIBILITY_POLICY, policy)
            .where(PROFILES.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("profiles", entity.getId());
    }
    entity.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.valueOf(policy));
    return entity;
  }

  public void delete(Profile entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(PROFILES).where(PROFILES.ID.eq(entity.getId())).execute();
    }
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(PROFILES).where(PROFILES.ACCOUNT_ID.eq(accountId)).execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            PROFILES.ID,
            PROFILES.ACCOUNT_ID,
            PROFILES.TENANT_ID,
            PROFILES.DISPLAY_NAME,
            PROFILES.BIO,
            PROFILES.PRESENCE_VISIBILITY_POLICY,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.TWO_FACTOR_SECRET,
            ACCOUNTS.EMAIL_VERIFIED)
        .from(PROFILES)
        .join(ACCOUNTS)
        .on(PROFILES.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private Profile toEntity(Record record) {
    Profile profile = new Profile();
    profile.setId(record.get(PROFILES.ID));
    profile.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.TWO_FACTOR_SECRET),
            record.get(ACCOUNTS.EMAIL_VERIFIED)));
    profile.setTenantId(record.get(PROFILES.TENANT_ID));
    profile.setDisplayName(record.get(PROFILES.DISPLAY_NAME));
    profile.setBio(record.get(PROFILES.BIO));
    profile.setPresenceVisibilityPolicy(
        ProfilePresenceVisibilityPolicy.valueOf(record.get(PROFILES.PRESENCE_VISIBILITY_POLICY)));
    return profile;
  }
}
