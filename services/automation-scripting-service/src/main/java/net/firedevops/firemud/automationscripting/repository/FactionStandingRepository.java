package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.FactionStanding.FACTION_STANDING;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.Faction;
import net.firedevops.firemud.automationscripting.entity.FactionStanding;
import net.firedevops.firemud.automationscripting.jooq.tables.records.FactionStandingRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class FactionStandingRepository {
  private final DSLContext dsl;

  public FactionStandingRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<FactionStanding> findByTenantIdAndCharacterIdAndPlayableStateKeyAndFaction_Id(
      Long tenantId, Long characterId, String playableStateKey, Long factionId) {
    return dsl.selectFrom(FACTION_STANDING)
        .where(
            FACTION_STANDING
                .TENANT_ID
                .eq(tenantId)
                .and(FACTION_STANDING.CHARACTER_ID.eq(characterId))
                .and(FACTION_STANDING.PLAYABLE_STATE_KEY.eq(playableStateKey))
                .and(FACTION_STANDING.FACTION_ID.eq(factionId)))
        .fetchOptional(this::toEntity);
  }

  public FactionStanding save(FactionStanding entity) {
    if (entity.getId() == null) {
      FactionStandingRecord record = dsl.newRecord(FACTION_STANDING);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextVersion = entity.getVersion() + 1;
    Long factionId = entity.getFaction() == null ? null : entity.getFaction().getId();
    int updated =
        dsl.update(FACTION_STANDING)
            .set(FACTION_STANDING.TENANT_ID, entity.getTenantId())
            .set(FACTION_STANDING.CHARACTER_ID, entity.getCharacterId())
            .set(FACTION_STANDING.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
            .set(FACTION_STANDING.FACTION_ID, factionId)
            .set(FACTION_STANDING.REPUTATION, entity.getReputation())
            .set(FACTION_STANDING.ROW_VERSION, nextVersion)
            .where(
                FACTION_STANDING
                    .ID
                    .eq(entity.getId())
                    .and(FACTION_STANDING.ROW_VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite("faction_standing", entity.getId());
    }
    entity.setVersion(nextVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<FactionStanding> findById(Long id) {
    return dsl.selectFrom(FACTION_STANDING)
        .where(FACTION_STANDING.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(FactionStandingRecord record, FactionStanding entity) {
    record.setTenantId(entity.getTenantId());
    record.setCharacterId(entity.getCharacterId());
    record.setPlayableStateKey(entity.getPlayableStateKey());
    record.setFactionId(entity.getFaction() == null ? null : entity.getFaction().getId());
    record.setReputation(entity.getReputation());
    record.setRowVersion(entity.getVersion());
  }

  private FactionStanding toEntity(Record record) {
    FactionStanding entity = new FactionStanding();
    entity.setId(record.get(FACTION_STANDING.ID));
    entity.setTenantId(record.get(FACTION_STANDING.TENANT_ID));
    entity.setCharacterId(record.get(FACTION_STANDING.CHARACTER_ID));
    entity.setPlayableStateKey(record.get(FACTION_STANDING.PLAYABLE_STATE_KEY));
    Long factionId = record.get(FACTION_STANDING.FACTION_ID);
    if (factionId != null) {
      Faction faction = new Faction();
      faction.setId(factionId);
      entity.setFaction(faction);
    }
    Integer reputation = record.get(FACTION_STANDING.REPUTATION);
    entity.setReputation(reputation == null ? 0 : reputation);
    Integer rowVersion = record.get(FACTION_STANDING.ROW_VERSION);
    entity.setVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
