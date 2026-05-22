package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.Guilds.GUILDS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.Guild;
import net.firedevops.firemud.socialgroups.jooq.tables.records.GuildsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GuildRepository {
  private final DSLContext dsl;

  public GuildRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Guild save(Guild entity) {
    if (entity.getId() == null) {
      GuildsRecord record = dsl.newRecord(GUILDS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(GUILDS)
            .set(GUILDS.TENANT_ID, entity.getTenantId())
            .set(GUILDS.NAME, entity.getName())
            .set(GUILDS.OWNER_ACCOUNT_ID, entity.getOwnerAccountId())
            .set(
                GUILDS.CREATED_AT,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()))
            .where(GUILDS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(GUILDS.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<Guild> findById(Long id) {
    return dsl.selectFrom(GUILDS).where(GUILDS.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(GuildsRecord record, Guild entity) {
    record.setTenantId(entity.getTenantId());
    record.setName(entity.getName());
    record.setOwnerAccountId(entity.getOwnerAccountId());
    record.setCreatedAt(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()));
  }

  private Guild toEntity(Record record) {
    Guild entity = new Guild();
    entity.setId(record.get(GUILDS.ID));
    entity.setTenantId(record.get(GUILDS.TENANT_ID));
    entity.setName(record.get(GUILDS.NAME));
    entity.setOwnerAccountId(record.get(GUILDS.OWNER_ACCOUNT_ID));
    entity.setCreatedAt(JooqSocialGroupsRepositorySupport.toInstant(record.get(GUILDS.CREATED_AT)));
    return entity;
  }
}
