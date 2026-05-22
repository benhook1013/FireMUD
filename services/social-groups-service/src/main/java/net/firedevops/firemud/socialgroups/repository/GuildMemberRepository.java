package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.GuildMembers.GUILD_MEMBERS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.jooq.tables.records.GuildMembersRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GuildMemberRepository {
  private final DSLContext dsl;

  public GuildMemberRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GuildMember> findFirstByTenantIdAndGuildIdAndAccountId(
      Long tenantId, Long guildId, Long accountId) {
    return dsl.selectFrom(GUILD_MEMBERS)
        .where(
            GUILD_MEMBERS
                .TENANT_ID
                .eq(tenantId)
                .and(GUILD_MEMBERS.GUILD_ID.eq(guildId))
                .and(GUILD_MEMBERS.ACCOUNT_ID.eq(accountId)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public GuildMember save(GuildMember entity) {
    if (entity.getId() == null) {
      GuildMembersRecord record = dsl.newRecord(GUILD_MEMBERS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(GUILD_MEMBERS)
            .set(GUILD_MEMBERS.TENANT_ID, entity.getTenantId())
            .set(GUILD_MEMBERS.GUILD_ID, entity.getGuildId())
            .set(GUILD_MEMBERS.ACCOUNT_ID, entity.getAccountId())
            .set(GUILD_MEMBERS.ROLE, entity.getRole())
            .where(GUILD_MEMBERS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(GUILD_MEMBERS.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(GuildMember entity) {
    if (entity.getId() == null) {
      return;
    }
    dsl.deleteFrom(GUILD_MEMBERS).where(GUILD_MEMBERS.ID.eq(entity.getId())).execute();
  }

  private Optional<GuildMember> findById(Long id) {
    return dsl.selectFrom(GUILD_MEMBERS)
        .where(GUILD_MEMBERS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(GuildMembersRecord record, GuildMember entity) {
    record.setTenantId(entity.getTenantId());
    record.setGuildId(entity.getGuildId());
    record.setAccountId(entity.getAccountId());
    record.setRole(entity.getRole());
  }

  private GuildMember toEntity(Record record) {
    GuildMember entity = new GuildMember();
    entity.setId(record.get(GUILD_MEMBERS.ID));
    entity.setTenantId(record.get(GUILD_MEMBERS.TENANT_ID));
    entity.setGuildId(record.get(GUILD_MEMBERS.GUILD_ID));
    entity.setAccountId(record.get(GUILD_MEMBERS.ACCOUNT_ID));
    entity.setRole(record.get(GUILD_MEMBERS.ROLE));
    return entity;
  }
}
