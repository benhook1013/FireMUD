package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.GameManifest.GAME_MANIFEST;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameManifestRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameManifestRepository {
  private final DSLContext dsl;

  public GameManifestRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(GAME_MANIFEST);
  }

  public List<GameManifest> findAll() {
    return dsl.selectFrom(GAME_MANIFEST).orderBy(GAME_MANIFEST.ID.asc()).fetch(this::toEntity);
  }

  public GameManifest save(GameManifest entity) {
    if (entity.getId() == null) {
      GameManifestRecord record = dsl.newRecord(GAME_MANIFEST);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int updated =
        dsl.update(GAME_MANIFEST)
            .set(GAME_MANIFEST.VERSION_ID, entity.getVersionId())
            .set(GAME_MANIFEST.DESCRIPTION, entity.getDescription())
            .where(GAME_MANIFEST.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update game_manifest id=" + entity.getId());
    }
    return findById(entity.getId());
  }

  private GameManifest findById(Long id) {
    return dsl.selectFrom(GAME_MANIFEST)
        .where(GAME_MANIFEST.ID.eq(id))
        .fetchOptional(this::toEntity)
        .orElseThrow();
  }

  private void populate(GameManifestRecord record, GameManifest entity) {
    record.setVersionId(entity.getVersionId());
    record.setDescription(entity.getDescription());
  }

  private GameManifest toEntity(Record record) {
    GameManifest entity = new GameManifest();
    entity.setId(record.get(GAME_MANIFEST.ID));
    entity.setVersionId(record.get(GAME_MANIFEST.VERSION_ID));
    entity.setDescription(record.get(GAME_MANIFEST.DESCRIPTION));
    return entity;
  }
}
