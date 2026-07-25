package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameAssetRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("game_assets"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> FILE_NAME = DSL.field(DSL.name("file_name"), String.class);
  private static final Field<String> CONTENT_TYPE =
      DSL.field(DSL.name("content_type"), String.class);
  private static final Field<byte[]> DATA = DSL.field(DSL.name("data"), byte[].class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public GameAssetRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<GameAsset> findByTenantId(String tenantId) {
    return dsl.selectFrom(TABLE_REF)
        .where(TENANT_ID.eq(tenantId))
        .orderBy(ID.asc())
        .fetch(this::toEntity);
  }

  public GameAsset save(GameAsset asset) {
    LocalDateTime createdAt =
        asset.getCreatedAt() == null ? LocalDateTime.now() : asset.getCreatedAt();
    if (asset.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, asset.getTenantId())
              .set(FILE_NAME, asset.getFileName())
              .set(CONTENT_TYPE, asset.getContentType())
              .set(DATA, asset.getData())
              .set(CREATED_AT, createdAt)
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, asset.getTenantId())
        .set(FILE_NAME, asset.getFileName())
        .set(CONTENT_TYPE, asset.getContentType())
        .set(DATA, asset.getData())
        .set(CREATED_AT, createdAt)
        .where(ID.eq(asset.getId()))
        .execute();
    return dsl.selectFrom(TABLE_REF).where(ID.eq(asset.getId())).fetchOne(this::toEntity);
  }

  private GameAsset toEntity(Record record) {
    if (record == null) {
      return null;
    }
    GameAsset asset = new GameAsset();
    asset.setId(record.get(ID));
    asset.setTenantId(record.get(TENANT_ID));
    asset.setFileName(record.get(FILE_NAME));
    asset.setContentType(record.get(CONTENT_TYPE));
    asset.setData(record.get(DATA));
    asset.setCreatedAt(record.get(CREATED_AT));
    return asset;
  }
}
