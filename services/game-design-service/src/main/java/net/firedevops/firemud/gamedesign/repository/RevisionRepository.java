package net.firedevops.firemud.gamedesign.repository;

import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.jsonbParam;
import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.nullableString;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.gamedesign.entity.Revision;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RevisionRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("revision"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Long> AUTHOR_ACCOUNT_ID =
      DSL.field(DSL.name("author_account_id"), Long.class);
  private static final Field<JSONB> DATA = DSL.field(DSL.name("data"), JSONB.class);
  private static final Field<String> REVISION_KIND =
      DSL.field(DSL.name("revision_kind"), String.class);
  private static final Field<String> LOGICAL_REVISION_ID =
      DSL.field(DSL.name("logical_revision_id"), String.class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public RevisionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Revision save(Revision revision) {
    LocalDateTime createdAt =
        revision.getCreatedAt() == null ? LocalDateTime.now() : revision.getCreatedAt();
    if (revision.getId() == null) {
      Long generatedId =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, revision.getTenantId())
              .set(VERSION_ID, revision.getVersionId())
              .set(AUTHOR_ACCOUNT_ID, revision.getAuthorAccountId())
              .set(DATA, jsonbParam(revision.getData()))
              .set(REVISION_KIND, revision.getRevisionKind())
              .set(LOGICAL_REVISION_ID, revision.getLogicalRevisionId())
              .set(CREATED_AT, createdAt)
              .returningResult(ID)
              .fetchOne(ID);
      return findById(generatedId);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, revision.getTenantId())
        .set(VERSION_ID, revision.getVersionId())
        .set(AUTHOR_ACCOUNT_ID, revision.getAuthorAccountId())
        .set(DATA, jsonbParam(revision.getData()))
        .set(REVISION_KIND, revision.getRevisionKind())
        .set(LOGICAL_REVISION_ID, revision.getLogicalRevisionId())
        .set(CREATED_AT, createdAt)
        .where(ID.eq(revision.getId()))
        .execute();
    return findById(revision.getId());
  }

  public long count() {
    return dsl.fetchCount(TABLE_REF);
  }

  public List<Revision> findAll() {
    return dsl.selectFrom(TABLE_REF).orderBy(ID.asc()).fetch(this::toEntity);
  }

  public Revision findById(Long id) {
    return dsl.selectFrom(TABLE_REF).where(ID.eq(id)).fetchOne(this::toEntity);
  }

  private Revision toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Revision revision = new Revision();
    revision.setId(record.get(ID));
    revision.setTenantId(record.get(TENANT_ID));
    revision.setVersionId(record.get(VERSION_ID));
    revision.setAuthorAccountId(record.get(AUTHOR_ACCOUNT_ID));
    revision.setData(nullableString(record.get(DATA)));
    revision.setRevisionKind(record.get(REVISION_KIND));
    revision.setLogicalRevisionId(record.get(LOGICAL_REVISION_ID));
    revision.setCreatedAt(record.get(CREATED_AT));
    return revision;
  }
}
