package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.GuildStorageItems.GUILD_STORAGE_ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.GuildStorageItem;
import net.firedevops.firemud.socialgroups.jooq.tables.records.GuildStorageItemsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GuildStorageItemRepository {
  private final DSLContext dsl;

  public GuildStorageItemRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public GuildStorageItem save(GuildStorageItem entity) {
    if (entity.getId() == null) {
      GuildStorageItemsRecord record = dsl.newRecord(GUILD_STORAGE_ITEMS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(GUILD_STORAGE_ITEMS)
            .set(GUILD_STORAGE_ITEMS.TENANT_ID, entity.getTenantId())
            .set(GUILD_STORAGE_ITEMS.GUILD_ID, entity.getGuildId())
            .set(GUILD_STORAGE_ITEMS.ITEM_NAME, entity.getItemName())
            .set(GUILD_STORAGE_ITEMS.QUANTITY, entity.getQuantity())
            .where(GUILD_STORAGE_ITEMS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(
          GUILD_STORAGE_ITEMS.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<GuildStorageItem> findById(Long id) {
    return dsl.selectFrom(GUILD_STORAGE_ITEMS)
        .where(GUILD_STORAGE_ITEMS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(GuildStorageItemsRecord record, GuildStorageItem entity) {
    record.setTenantId(entity.getTenantId());
    record.setGuildId(entity.getGuildId());
    record.setItemName(entity.getItemName());
    record.setQuantity(entity.getQuantity());
  }

  private GuildStorageItem toEntity(Record record) {
    GuildStorageItem entity = new GuildStorageItem();
    entity.setId(record.get(GUILD_STORAGE_ITEMS.ID));
    entity.setTenantId(record.get(GUILD_STORAGE_ITEMS.TENANT_ID));
    entity.setGuildId(record.get(GUILD_STORAGE_ITEMS.GUILD_ID));
    entity.setItemName(record.get(GUILD_STORAGE_ITEMS.ITEM_NAME));
    entity.setQuantity(record.get(GUILD_STORAGE_ITEMS.QUANTITY));
    return entity;
  }
}
