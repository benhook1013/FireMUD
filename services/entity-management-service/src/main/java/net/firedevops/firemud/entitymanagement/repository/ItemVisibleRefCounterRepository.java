package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEM_VISIBLE_REF_COUNTERS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemVisibleRefCounter;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ItemVisibleRefCounterRepository {
  private final DSLContext dsl;

  public ItemVisibleRefCounterRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ItemVisibleRefCounter> findByTenantIdAndVisibleRefToken(
      Long tenantId, String token) {
    return Optional.ofNullable(
        dsl.selectFrom(ITEM_VISIBLE_REF_COUNTERS)
            .where(
                ITEM_VISIBLE_REF_COUNTERS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(ITEM_VISIBLE_REF_COUNTERS.VISIBLE_REF_TOKEN.eq(token)))
            .forUpdate()
            .fetchOne(this::toEntity));
  }

  public ItemVisibleRefCounter save(ItemVisibleRefCounter counter) {
    if (counter.getId() == null) {
      Long id =
          dsl.insertInto(ITEM_VISIBLE_REF_COUNTERS)
              .set(ITEM_VISIBLE_REF_COUNTERS.TENANT_ID, counter.getTenantId())
              .set(ITEM_VISIBLE_REF_COUNTERS.VISIBLE_REF_TOKEN, counter.getVisibleRefToken())
              .set(ITEM_VISIBLE_REF_COUNTERS.NEXT_SEQUENCE, counter.getNextSequence())
              .set(ITEM_VISIBLE_REF_COUNTERS.VERSION, counter.getVersion())
              .returningResult(ITEM_VISIBLE_REF_COUNTERS.ID)
              .fetchOne(ITEM_VISIBLE_REF_COUNTERS.ID);
      counter.setId(id);
      return counter;
    }
    dsl.update(ITEM_VISIBLE_REF_COUNTERS)
        .set(ITEM_VISIBLE_REF_COUNTERS.NEXT_SEQUENCE, counter.getNextSequence())
        .set(ITEM_VISIBLE_REF_COUNTERS.VERSION, counter.getVersion() + 1)
        .where(ITEM_VISIBLE_REF_COUNTERS.ID.eq(counter.getId()))
        .execute();
    counter.setVersion(counter.getVersion() + 1);
    return counter;
  }

  public ItemVisibleRefCounter saveAndFlush(ItemVisibleRefCounter counter) {
    return save(counter);
  }

  private ItemVisibleRefCounter toEntity(Record record) {
    if (record == null) {
      return null;
    }
    ItemVisibleRefCounter counter = new ItemVisibleRefCounter();
    counter.setId(record.get(ITEM_VISIBLE_REF_COUNTERS.ID));
    counter.setTenantId(record.get(ITEM_VISIBLE_REF_COUNTERS.TENANT_ID));
    counter.setVisibleRefToken(record.get(ITEM_VISIBLE_REF_COUNTERS.VISIBLE_REF_TOKEN));
    counter.setNextSequence(record.get(ITEM_VISIBLE_REF_COUNTERS.NEXT_SEQUENCE));
    counter.setVersion(record.get(ITEM_VISIBLE_REF_COUNTERS.VERSION));
    return counter;
  }
}
