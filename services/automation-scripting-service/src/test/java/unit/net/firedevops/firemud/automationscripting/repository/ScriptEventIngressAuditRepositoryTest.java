package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptEventIngressAuditRepositoryTest {
  @Test
  void lookupUsesCanonicalEventScopeFieldsAndSourceService() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return new MockResult[0];
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    ScriptEventIngressAuditRepository repository = new ScriptEventIngressAuditRepository(dsl);

    assertThat(
            repository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                    "tenant-1",
                    "instance-1",
                    "region-1",
                    7L,
                    "entity-1",
                    "SHARED",
                    "onCommand",
                    "v1",
                    "patch-1",
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
        .doesNotContain("world_slug", "realm_slug", "pointer_version");
  }
}
