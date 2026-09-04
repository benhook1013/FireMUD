package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventIngressAuditRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptEventIngressAuditRepositoryTest {
  @Test
  void insertIfAbsentByIdentityClaimsNullEpochBranchAtomically() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    ScriptEventIngressAuditRecord row = new ScriptEventIngressAuditRecord();
    row.setId(11L);
    row.setTenantId("tenant-1");
    row.setScriptId("script-1");
    row.setEventType("onLoad");
    row.setEventSchemaVersion("v1");
    row.setScriptPatchVersion("patch-1");
    row.setScriptEventId("event-1");
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_INGRESS_AUDIT.fields());
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(insertedField, false);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setTenantId("tenant-1");
    entity.setScriptId("script-1");
    entity.setEventType("onLoad");
    entity.setEventSchemaVersion("v1");
    entity.setScriptPatchVersion("patch-1");
    entity.setScriptEventId("event-1");

    ScriptEventIngressAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByIdentity(entity);

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(11L);
    assertThat(sqlRef.get()).contains("on conflict", "do update", "script_pin_epoch is null");
  }

  @Test
  void lookupUsesCanonicalEventScopeFieldsAndSourceService() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_INGRESS_AUDIT))
          };
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    ScriptEventIngressAuditRepository repository = new ScriptEventIngressAuditRepository(dsl);

    assertThat(
            repository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptEventIdAndDryRunAndSourceService(
                    "tenant-1",
                    "instance-1",
                    "region-1",
                    7L,
                    "entity-1",
                    "SHARED",
                    "onCommand",
                    "v1",
                    "patch-1",
                    4L,
                    "event-1",
                    false,
                    "game-session-service"))
        .isEmpty();

    String whereClause =
        sqlRef
            .get()
            .substring(sqlRef.get().toLowerCase(Locale.ROOT).indexOf(" where "))
            .toLowerCase(Locale.ROOT);
    assertThat(whereClause)
        .contains("source_service")
        .contains("script_pin_epoch")
        .doesNotContain("world_slug", "realm_slug", "pointer_version");
  }
}
