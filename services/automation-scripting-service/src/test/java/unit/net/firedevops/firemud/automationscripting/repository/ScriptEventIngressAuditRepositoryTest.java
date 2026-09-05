package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @Test
  void rejectsMismatchedPinTupleBeforeSelectingConflictTarget() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setScriptPinEpoch(2L);

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("script_pin_control_plane_request_id");
  }

  @Test
  void rejectsNewClaimWithoutCanonicalRequestDigest() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setRequestDigest("not-a-sha256-digest");

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("request_digest must be a canonical 64-character hexadecimal digest");
  }

  @Test
  void rejectsPositivePinEpochWithoutGameInstanceBeforeSelectingConflictTarget() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("game_instance_id is required for a positive script_pin_epoch");
  }

  @Test
  void rejectsPositivePinEpochWithoutGameInstanceBeforeUpdate() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setId(7L);
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("game_instance_id is required for a positive script_pin_epoch");
  }

  @Test
  void rejectsNullPinEpochForInstanceScopedIngressBeforeSelectingConflictTarget() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setGameInstanceId("game-1");
    entity.setRequestDigest(REQUEST_DIGEST);

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("script_pin_epoch is required for an instance-scoped ingress audit");
  }

  @Test
  void rejectsMismatchedPinTupleBeforeUpdate() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setId(7L);
    entity.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("script_pin_control_plane_request_id requires a positive script_pin_epoch");
  }

  @Test
  void saveFencesImmutablePinTupleEvidenceWithTheRowVersionCas() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(0)};
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setId(7L);
    entity.setRowVersion(4);
    entity.setGameInstanceId("game-1");
    entity.setScriptPatchVersion("patch-2");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
        .hasMessage("Stale write rejected for script_event_ingress_audit id=7");
    assertThat(sqlRef.get())
        .contains(
            "script_patch_version", "script_pin_epoch", "script_pin_control_plane_request_id");
    assertThat(bindingsRef.get()).contains("patch-2", 2L, "pin-request-2");
  }

  @Test
  void staleInProgressReclaimUsesRowVersionFenceAndRefreshesClaimLease() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    ScriptEventIngressAuditRecord row = new ScriptEventIngressAuditRecord();
    row.setId(12L);
    row.setTenantId("tenant-1");
    row.setSourceState("IN_PROGRESS");
    row.setRowVersion(4);
    MockDataProvider provider =
        context -> {
          if (context.sql().trim().toLowerCase(Locale.ROOT).startsWith("update")) {
            sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
            bindingsRef.set(context.bindings());
            return new MockResult[] {new MockResult(1)};
          }
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_INGRESS_AUDIT.fields());
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(SCRIPT_EVENT_INGRESS_AUDIT.CLAIM_STARTED_AT, OffsetDateTime.now());
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptEventIngressAudit claim = new ScriptEventIngressAudit();
    claim.setId(12L);
    claim.setRowVersion(4);
    claim.setSourceState("IN_PROGRESS");
    claim.setClaimStartedAt(Instant.now().minusSeconds(60));

    Instant staleBefore = Instant.parse("2026-08-01T00:00:30Z");
    Instant now = Instant.parse("2026-08-01T00:01:00Z");
    Optional<ScriptEventIngressAudit> reclaimed =
        repository.reclaimStaleInProgress(claim, staleBefore, now);

    assertThat(reclaimed).isPresent();
    assertThat(sqlRef.get()).contains("source_state", "row_version", "claim_started_at");
    assertContainsTimestamp(bindingsRef.get(), now);
    assertContainsTimestamp(bindingsRef.get(), staleBefore);
    assertThat(bindingsRef.get()).contains("ingress_reclaimed_stale", 5, 12L, 4, "IN_PROGRESS");
  }

  @Test
  void renewClaimIfCurrentUsesRowVersionAndStateFence() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    Instant now = Instant.parse("2026-08-01T00:01:00Z");
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(1)};
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptEventIngressAudit claim = new ScriptEventIngressAudit();
    claim.setId(12L);
    claim.setRowVersion(4);
    claim.setSourceState("IN_PROGRESS");
    claim.setClaimStartedAt(Instant.now());

    assertThat(repository.renewClaimIfCurrent(claim, now)).isTrue();
    assertThat(sqlRef.get()).contains("update", "claim_started_at", "row_version", "source_state");
    assertContainsTimestamp(bindingsRef.get(), now);
    assertThat(bindingsRef.get()).contains(12L, 4, "IN_PROGRESS");
  }

  private static String postgresTimestamp(Instant instant) {
    return instant.toString().replace('T', ' ').replace("Z", "+00:00");
  }

  private static void assertContainsTimestamp(Object[] bindings, Instant expected) {
    assertThat(
            Arrays.stream(bindings)
                .anyMatch(
                    binding ->
                        binding instanceof OffsetDateTime offsetDateTime
                            ? offsetDateTime.toInstant().equals(expected)
                            : binding instanceof String
                                && postgresTimestamp(expected).equals(binding)))
        .as("bindings should contain timestamp %s", expected)
        .isTrue();
  }

  @Test
  void insertIfAbsentByIdentityClaimsNullEpochBranchAtomically() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
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
          bindingsRef.set(context.bindings());
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_INGRESS_AUDIT.fields());
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID, "");
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
    entity.setScriptPinControlPlaneRequestId(" ");
    entity.setRequestDigest(REQUEST_DIGEST);

    ScriptEventIngressAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByIdentity(entity);

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(11L);
    assertThat(result.audit().getScriptPinControlPlaneRequestId()).isNull();
    assertThat(bindingsRef.get()).doesNotContain(" ");
    assertThat(sqlRef.get()).contains("on conflict", "do update", "script_pin_epoch\" is null");
  }

  @Test
  void insertIfAbsentByIdentityRejectsInstanceScopedNullEpochBeforeConflictTarget() {
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setRequestDigest(REQUEST_DIGEST);

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("script_pin_epoch is required for an instance-scoped ingress audit");
  }

  @Test
  void insertIfAbsentByIdentityRejectsDifferentPinnedOwnerRequestOnConflict() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    ScriptEventIngressAuditRecord row = new ScriptEventIngressAuditRecord();
    row.setId(13L);
    row.setTenantId("tenant-1");
    row.setGameInstanceId("game-1");
    row.setRegionId("region-1");
    row.setRegionEpoch(7L);
    row.setEntityId("entity-1");
    row.setEventType("onCommand");
    row.setEventSchemaVersion("v1");
    row.setScriptPatchVersion("patch-1");
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId("pin-request-1");
    row.setScriptEventId("event-1");
    row.setSourceService("game-session-service");
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
    entity.setGameInstanceId("game-1");
    entity.setRegionId("region-1");
    entity.setRegionEpoch(7L);
    entity.setEntityId("entity-1");
    entity.setPlayableStateScope(null);
    entity.setEventType("onCommand");
    entity.setEventSchemaVersion("v1");
    entity.setScriptPatchVersion("patch-1");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");
    entity.setScriptEventId("event-1");
    entity.setSourceService("game-session-service");
    entity.setRequestDigest(REQUEST_DIGEST);

    assertThatThrownBy(() -> repository.insertIfAbsentByIdentity(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(sqlRef.get())
        .contains(
            "on conflict",
            "script_pin_epoch",
            "game_instance_id\" is not null",
            "script_pin_epoch\" is not null");
    String conflictTarget =
        sqlRef.get().substring(sqlRef.get().toLowerCase(Locale.ROOT).indexOf("on conflict"));
    assertThat(conflictTarget.substring(0, conflictTarget.indexOf(" do update")))
        .doesNotContain("script_pin_control_plane_request_id");
  }

  @Test
  void insertIfAbsentByIdentityReturnsExistingRowForExactPinnedOwnerRetry() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    ScriptEventIngressAuditRecord row = pinnedIngressRow("pin-request-1");
    MockDataProvider provider =
        context -> {
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

    ScriptEventIngressAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByIdentity(pinnedIngressEntity("pin-request-1"));

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(13L);
  }

  @Test
  void insertIfAbsentByIdentityRejectsDifferentPinnedOwnerRequestFromFallbackLookup() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    ScriptEventIngressAuditRecord row = pinnedIngressRow("pin-request-1");
    AtomicReference<Integer> calls = new AtomicReference<>(0);
    MockDataProvider provider =
        context -> {
          calls.updateAndGet(value -> value + 1);
          if (calls.get() == 1) {
            return new MockResult[] {
              new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_INGRESS_AUDIT))
            };
          }
          Result<ScriptEventIngressAuditRecord> result =
              resultDsl.newResult(SCRIPT_EVENT_INGRESS_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThatThrownBy(
            () -> repository.insertIfAbsentByIdentity(pinnedIngressEntity("pin-request-2")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void lookupUsesCanonicalEventScopeFieldsAndSourceService() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_INGRESS_AUDIT))
          };
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    ScriptEventIngressAuditRepository repository = new ScriptEventIngressAuditRepository(dsl);

    assertThat(
            repository
                .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRunAndSourceService(
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
                    "pin-request-1",
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
    assertThat(whereClause).doesNotContain("script_pin_control_plane_request_id");
    assertThat(bindingsRef.get()).contains(4L).doesNotContain("pin-request-1");
  }

  @Test
  void lookupCanonicalizesNullPlayableStateScopeForInstanceScopedRows() {
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          bindingsRef.set(context.bindings());
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_INGRESS_AUDIT))
          };
        };
    ScriptEventIngressAuditRepository repository =
        new ScriptEventIngressAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRunAndSourceService(
            "tenant-1",
            "instance-1",
            "region-1",
            7L,
            "entity-1",
            null,
            "onCommand",
            "v1",
            "patch-1",
            null,
            null,
            "event-1",
            false,
            "game-session-service");

    assertThat(bindingsRef.get()).contains("");
  }

  private static ScriptEventIngressAuditRecord pinnedIngressRow(String requestId) {
    ScriptEventIngressAuditRecord row = new ScriptEventIngressAuditRecord();
    row.setId(13L);
    row.setTenantId("tenant-1");
    row.setGameInstanceId("game-1");
    row.setRegionId("region-1");
    row.setRegionEpoch(7L);
    row.setEntityId("entity-1");
    row.setEventType("onCommand");
    row.setEventSchemaVersion("v1");
    row.setScriptPatchVersion("patch-1");
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId(requestId);
    row.setScriptEventId("event-1");
    row.setSourceService("game-session-service");
    return row;
  }

  private static ScriptEventIngressAudit pinnedIngressEntity(String requestId) {
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setRegionId("region-1");
    entity.setRegionEpoch(7L);
    entity.setEntityId("entity-1");
    entity.setPlayableStateScope(null);
    entity.setEventType("onCommand");
    entity.setEventSchemaVersion("v1");
    entity.setScriptPatchVersion("patch-1");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId(requestId);
    entity.setScriptEventId("event-1");
    entity.setSourceService("game-session-service");
    entity.setRequestDigest(REQUEST_DIGEST);
    return entity;
  }
}
