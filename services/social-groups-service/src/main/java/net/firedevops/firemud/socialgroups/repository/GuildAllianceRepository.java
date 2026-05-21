package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.GuildAlliances.GUILD_ALLIANCES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.GuildAlliance;
import net.firedevops.firemud.socialgroups.jooq.tables.records.GuildAlliancesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GuildAllianceRepository {
  private final DSLContext dsl;

  public GuildAllianceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public GuildAlliance save(GuildAlliance entity) {
    if (entity.getId() == null) {
      GuildAlliancesRecord record = dsl.newRecord(GUILD_ALLIANCES);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(GUILD_ALLIANCES)
            .set(GUILD_ALLIANCES.TENANT_ID, entity.getTenantId())
            .set(GUILD_ALLIANCES.GUILD_ID, entity.getGuildId())
            .set(GUILD_ALLIANCES.ALLY_GUILD_ID, entity.getAllyGuildId())
            .set(
                GUILD_ALLIANCES.CREATED_AT,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()))
            .where(GUILD_ALLIANCES.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(GUILD_ALLIANCES.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<GuildAlliance> findById(Long id) {
    return dsl.selectFrom(GUILD_ALLIANCES)
        .where(GUILD_ALLIANCES.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(GuildAlliancesRecord record, GuildAlliance entity) {
    record.setTenantId(entity.getTenantId());
    record.setGuildId(entity.getGuildId());
    record.setAllyGuildId(entity.getAllyGuildId());
    record.setCreatedAt(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getCreatedAt()));
  }

  private GuildAlliance toEntity(Record record) {
    GuildAlliance entity = new GuildAlliance();
    entity.setId(record.get(GUILD_ALLIANCES.ID));
    entity.setTenantId(record.get(GUILD_ALLIANCES.TENANT_ID));
    entity.setGuildId(record.get(GUILD_ALLIANCES.GUILD_ID));
    entity.setAllyGuildId(record.get(GUILD_ALLIANCES.ALLY_GUILD_ID));
    entity.setCreatedAt(
        JooqSocialGroupsRepositorySupport.toInstant(record.get(GUILD_ALLIANCES.CREATED_AT)));
    return entity;
  }
}
