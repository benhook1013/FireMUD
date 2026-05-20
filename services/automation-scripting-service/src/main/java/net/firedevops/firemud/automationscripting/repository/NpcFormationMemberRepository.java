package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.NpcFormationMember.NPC_FORMATION_MEMBER;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.NpcFormation;
import net.firedevops.firemud.automationscripting.entity.NpcFormationMember;
import net.firedevops.firemud.automationscripting.jooq.tables.records.NpcFormationMemberRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class NpcFormationMemberRepository {
  private final DSLContext dsl;

  public NpcFormationMemberRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<NpcFormationMember> findByFormation_TenantIdAndFormation_Id(
      Long tenantId, Long formationId) {
    return dsl.select(
            NPC_FORMATION_MEMBER.ID,
            NPC_FORMATION_MEMBER.FORMATION_ID,
            NPC_FORMATION_MEMBER.NPC_ID,
            NPC_FORMATION_MEMBER.ROW_VERSION)
        .from(NPC_FORMATION_MEMBER)
        .join(NPC_FORMATION_MEMBER.npcFormations())
        .where(
            NPC_FORMATION_MEMBER
                .npcFormations()
                .TENANT_ID
                .eq(tenantId)
                .and(NPC_FORMATION_MEMBER.FORMATION_ID.eq(formationId)))
        .fetch(this::toEntity);
  }

  public NpcFormationMember save(NpcFormationMember entity) {
    if (entity.getId() == null) {
      NpcFormationMemberRecord record = dsl.newRecord(NPC_FORMATION_MEMBER);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int nextVersion = entity.getVersion() + 1;
    Long formationId = entity.getFormation() == null ? null : entity.getFormation().getId();
    int updated =
        dsl.update(NPC_FORMATION_MEMBER)
            .set(NPC_FORMATION_MEMBER.FORMATION_ID, formationId)
            .set(NPC_FORMATION_MEMBER.NPC_ID, entity.getNpcId())
            .set(NPC_FORMATION_MEMBER.ROW_VERSION, nextVersion)
            .where(
                NPC_FORMATION_MEMBER
                    .ID
                    .eq(entity.getId())
                    .and(NPC_FORMATION_MEMBER.ROW_VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "npc_formation_member", entity.getId());
    }
    entity.setVersion(nextVersion);
    return findById(entity.getId());
  }

  private NpcFormationMember findById(Long id) {
    return dsl.selectFrom(NPC_FORMATION_MEMBER)
        .where(NPC_FORMATION_MEMBER.ID.eq(id))
        .fetchOne(this::toEntity);
  }

  private void populate(NpcFormationMemberRecord record, NpcFormationMember entity) {
    record.setFormationId(entity.getFormation() == null ? null : entity.getFormation().getId());
    record.setNpcId(entity.getNpcId());
    record.setRowVersion(entity.getVersion());
  }

  private NpcFormationMember toEntity(Record record) {
    NpcFormationMember entity = new NpcFormationMember();
    entity.setId(record.get(NPC_FORMATION_MEMBER.ID));
    Long formationId = record.get(NPC_FORMATION_MEMBER.FORMATION_ID);
    if (formationId != null) {
      NpcFormation formation = new NpcFormation();
      formation.setId(formationId);
      entity.setFormation(formation);
    }
    entity.setNpcId(record.get(NPC_FORMATION_MEMBER.NPC_ID));
    Integer rowVersion = record.get(NPC_FORMATION_MEMBER.ROW_VERSION);
    entity.setVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
