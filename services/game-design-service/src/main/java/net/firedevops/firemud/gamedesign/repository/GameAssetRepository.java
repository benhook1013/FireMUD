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
  private static final Table<?> VERSION_ASSET_TABLE = DSL.table(DSL.name("version_asset"));
  private static final Field<Long> ID =
      DSL.field(DSL.name("game_assets", "id"), Long.class);
  private static final Field<String> TENANT_ID =
      DSL.field(DSL.name("game_assets", "tenant_id"), String.class);
  private static final Field<String> FILE_NAME =
      DSL.field(DSL.name("game_assets", "file_name"), String.class);
  private static final Field<String> CONTENT_TYPE =
      DSL.field(DSL.name("game_assets", "content_type"), String.class);
  private static final Field<byte[]> DATA =
      DSL.field(DSL.name("game_assets", "data"), byte[].class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("game_assets", "created_at"), LocalDateTime.class);
  private static final Field<String> VERSION_ASSET_TENANT_ID =
      DSL.field(DSL.name("version_asset", "tenant_id"), String.class);
  private static final Field<Long> VERSION_ASSET_VERSION_ID =
      DSL.field(DSL.name("version_asset", "version_id"), Long.class);
  private static final Field<Long> VERSION_ASSET_ASSET_ID =
      DSL.field(DSL.name("version_asset", "asset_id"), Long.class);

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

  public List<GameAsset> findByTenantIdAndVersionId(String tenantId, long versionId) {
    return dsl.select(
            ID,
            TENANT_ID,
            FILE_NAME,
            CONTENT_TYPE,
            DATA,
            CREATED_AT)
        .from(TABLE_REF)
        .join(VERSION_ASSET_TABLE)
        .on(
            VERSION_ASSET_ASSET_ID
                .eq(ID)
                .and(VERSION_ASSET_TENANT_ID.eq(TENANT_ID)))
        .where(
            VERSION_ASSET_TENANT_ID
                .eq(tenantId)
                .and(VERSION_ASSET_VERSION_ID.eq(versionId)))
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
