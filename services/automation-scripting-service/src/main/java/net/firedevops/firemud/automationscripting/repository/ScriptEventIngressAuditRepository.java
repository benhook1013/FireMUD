package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventIngressAuditRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptEventIngressAuditRepository {
  private static final Field<String> SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID =
      field(name("script_pin_control_plane_request_id"), String.class);
  private static final Field<OffsetDateTime> CLAIM_STARTED_AT =
      field(name("claim_started_at"), OffsetDateTime.class);
  private static final Field<Boolean> INSERTED_ROW =
      field("xmax = 0", Boolean.class).as("inserted");
  private final DSLContext dsl;

  public ScriptEventIngressAuditRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptEventIngressAudit>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptEventIdAndDryRunAndSourceService(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          Long scriptPinEpoch,
          String scriptEventId,
          boolean dryRun,
          String sourceService) {
    return findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRunAndSourceService(
        tenantId,
        gameInstanceId,
        regionId,
        regionEpoch,
        entityId,
        playableStateScope,
        eventType,
        eventSchemaVersion,
        scriptPatchVersion,
        scriptPinEpoch,
        null,
        scriptEventId,
        dryRun,
        sourceService);
  }

  public Optional<ScriptEventIngressAudit>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRunAndSourceService(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          Long scriptPinEpoch,
          String scriptPinControlPlaneRequestId,
          String scriptEventId,
          boolean dryRun,
          String sourceService) {
    return dsl.select(selectFields())
        .from(SCRIPT_EVENT_INGRESS_AUDIT)
        .where(
            SCRIPT_EVENT_INGRESS_AUDIT
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID.isNotDistinctFrom(gameInstanceId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID.isNotDistinctFrom(regionId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH.isNotDistinctFrom(regionEpoch))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID.isNotDistinctFrom(entityId))
                .and(
                    SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE.isNotDistinctFrom(
                        playableStateScope))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE.eq(eventType))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_EPOCH.isNotDistinctFrom(scriptPinEpoch))
                .and(
                    SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                        scriptPinControlPlaneRequestId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID.eq(scriptEventId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN.eq(dryRun))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE.eq(sourceService)))
        .fetchOptional(this::toEntity);
  }

  public ScriptEventIngressAudit save(ScriptEventIngressAudit entity) {
    if (entity.getId() == null) {
      return insertIfAbsentByIdentity(entity).audit();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_EVENT_INGRESS_AUDIT)
            .set(SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID, entity.getRegionId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH, entity.getRegionEpoch())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID, entity.getEntityId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID, entity.getScriptPinControlPlaneRequestId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.QUOTA_CLASS, entity.getQuotaClass())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID, entity.getScriptEventId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE, entity.getSourceService())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.TRIGGER_MODE, entity.getTriggerMode())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_KIND, entity.getSourceKind())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE, entity.getSourceState())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_ORDINAL, entity.getSourceOrdinal())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_TICK_ID, entity.getSourceDueTickId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_AT_MS, entity.getSourceDueAtMs())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN, entity.isDryRun())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.READ_SNAPSHOT_TOKEN, entity.getReadSnapshotToken())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PAYLOAD_JSON, entity.getPayloadJson())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMITTED, entity.isAdmitted())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_OUTCOME, entity.getAdmissionOutcome())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_REASON, entity.getAdmissionReason())
            .set(
                SCRIPT_EVENT_INGRESS_AUDIT.RESOLVED_HANDLER_COUNT, entity.getResolvedHandlerCount())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_EVENT_INGRESS_AUDIT
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_event_ingress_audit", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  /**
   * Reclaims a stale in-progress claim under the same event identity.
   *
   * <p>The expected row version is the abandoned owner's fence. A successful compare-and-set
   * advances that fence before the new owner can resolve or materialize handlers; the abandoned
   * owner's later final save therefore fails closed as a stale write. A zero-row update means a
   * concurrent owner already reclaimed or finalized the claim.
   */
  public Optional<ScriptEventIngressAudit> reclaimStaleInProgress(
      ScriptEventIngressAudit claim, Instant staleBefore, Instant now) {
    if (claim.getId() == null || claim.getRowVersion() < 0 || claim.getClaimStartedAt() == null) {
      return Optional.empty();
    }
    int updated =
        dsl.update(SCRIPT_EVENT_INGRESS_AUDIT)
            .set(CLAIM_STARTED_AT, now.atOffset(java.time.ZoneOffset.UTC))
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_REASON, "ingress_reclaimed_stale")
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION, claim.getRowVersion() + 1)
            .where(
                SCRIPT_EVENT_INGRESS_AUDIT
                    .ID
                    .eq(claim.getId())
                    .and(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION.eq(claim.getRowVersion()))
                    .and(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE.eq("IN_PROGRESS"))
                    .and(CLAIM_STARTED_AT.le(staleBefore.atOffset(java.time.ZoneOffset.UTC))))
            .execute();
    if (updated != 1) {
      return Optional.empty();
    }
    return findById(claim.getId());
  }

  /** Atomically claims the event-scope identity, including its nullable pre-instance branch. */
  public IdempotentInsertResult insertIfAbsentByIdentity(ScriptEventIngressAudit entity) {
    if (entity.getId() != null) {
      throw new IllegalArgumentException("A new script event ingress audit is required");
    }
    ScriptEventIngressAuditRecord record = dsl.newRecord(SCRIPT_EVENT_INGRESS_AUDIT);
    populate(record, entity);
    List<SelectFieldOrAsterisk> returningFields = new ArrayList<>();
    Collections.addAll(returningFields, SCRIPT_EVENT_INGRESS_AUDIT.fields());
    returningFields.add(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID);
    returningFields.add(CLAIM_STARTED_AT);
    returningFields.add(INSERTED_ROW);
    boolean instanceScoped = entity.getGameInstanceId() != null;
    Field<?>[] conflictFields =
        instanceScoped
            ? runtimeConflictFields(
                entity.getScriptPinEpoch() != null, entity.getScriptPinEpoch() != null)
            : onLoadConflictFields();
    Condition conflictPredicate =
        instanceScoped
            ? SCRIPT_EVENT_INGRESS_AUDIT
                .GAME_INSTANCE_ID
                .isNotNull()
                .and(
                    entity.getScriptPinEpoch() == null
                        ? nullScriptPinEpochCondition()
                        : SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_EPOCH.isNotNull())
            : SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID.isNull();
    if (!instanceScoped) {
      // Keep the explicit pre-instance/null-pin branch visible in the claim predicate. The
      // corresponding partial index has the same predicate, so this remains an index-backed
      // atomic claim rather than a nullable lookup followed by an insert.
      conflictPredicate = conflictPredicate.and(nullScriptPinEpochCondition());
    }
    var insert =
        dsl.insertInto(SCRIPT_EVENT_INGRESS_AUDIT)
            .set(record)
            .onConflict(conflictFields)
            .where(conflictPredicate)
            .doUpdate()
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ID, SCRIPT_EVENT_INGRESS_AUDIT.ID)
            .returningResult(returningFields);
    return insert
        .fetchOptional(
            returned ->
                new IdempotentInsertResult(
                    toEntity(returned), Boolean.TRUE.equals(returned.get(INSERTED_ROW))))
        .orElseThrow(() -> new IllegalStateException("Event identity claim returned no row"));
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The claimed ingress audit is the repository result contract.")
  public record IdempotentInsertResult(ScriptEventIngressAudit audit, boolean inserted) {}

  private static Condition nullScriptPinEpochCondition() {
    return condition("script_pin_epoch is null");
  }

  private static List<SelectFieldOrAsterisk> selectFields() {
    List<SelectFieldOrAsterisk> fields = new ArrayList<>();
    Collections.addAll(fields, SCRIPT_EVENT_INGRESS_AUDIT.fields());
    fields.add(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID);
    fields.add(CLAIM_STARTED_AT);
    return fields;
  }

  private static Field<?>[] runtimeConflictFields(
      boolean includeScriptPinEpoch, boolean includeScriptPinControlPlaneRequestId) {
    List<Field<?>> fields =
        new ArrayList<>(
            List.of(
                SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID,
                SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID,
                SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID,
                SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH,
                SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID,
                SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE,
                SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE,
                SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION,
                SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION));
    if (includeScriptPinEpoch) {
      fields.add(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_EPOCH);
    }
    if (includeScriptPinControlPlaneRequestId) {
      fields.add(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID);
    }
    fields.add(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID);
    fields.add(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN);
    fields.add(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE);
    return fields.toArray(Field<?>[]::new);
  }

  private static Field<?>[] onLoadConflictFields() {
    return new Field<?>[] {
      SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID,
      SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_ID,
      SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE,
      SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION,
      SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION,
      SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID,
      SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN,
      SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE
    };
  }

  private Optional<ScriptEventIngressAudit> findById(Long id) {
    return dsl.select(selectFields())
        .from(SCRIPT_EVENT_INGRESS_AUDIT)
        .where(SCRIPT_EVENT_INGRESS_AUDIT.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(ScriptEventIngressAuditRecord record, ScriptEventIngressAudit entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setRegionEpoch(entity.getRegionEpoch());
    record.setEntityId(entity.getEntityId());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setScriptId(entity.getScriptId());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.set(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID, entity.getScriptPinControlPlaneRequestId());
    record.set(CLAIM_STARTED_AT, toOffsetDateTime(entity.getClaimStartedAt()));
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setQuotaClass(entity.getQuotaClass());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptEventId(entity.getScriptEventId());
    record.setSourceService(entity.getSourceService());
    record.setTriggerMode(entity.getTriggerMode());
    record.setSourceKind(entity.getSourceKind());
    record.setSourceState(entity.getSourceState());
    record.setSourceOrdinal(entity.getSourceOrdinal());
    record.setSourceDueTickId(entity.getSourceDueTickId());
    record.setSourceDueAtMs(entity.getSourceDueAtMs());
    record.setDryRun(entity.isDryRun());
    record.setReadSnapshotToken(entity.getReadSnapshotToken());
    record.setPayloadJson(entity.getPayloadJson());
    record.setAdmitted(entity.isAdmitted());
    record.setAdmissionOutcome(entity.getAdmissionOutcome());
    record.setAdmissionReason(entity.getAdmissionReason());
    record.setResolvedHandlerCount(entity.getResolvedHandlerCount());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptEventIngressAudit toEntity(Record record) {
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ID));
    entity.setTenantId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID));
    entity.setRegionEpoch(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH));
    entity.setEntityId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_EVENT_INGRESS_AUDIT.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.POINTER_VERSION));
    entity.setScriptId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_ID));
    entity.setPluginId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_VERSION_ID));
    Long scriptPinEpoch = record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch);
    entity.setScriptPinControlPlaneRequestId(record.get(SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID));
    entity.setClaimStartedAt(toInstant(record.get(CLAIM_STARTED_AT)));
    entity.setEventType(record.get(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION));
    entity.setQuotaClass(record.get(SCRIPT_EVENT_INGRESS_AUDIT.QUOTA_CLASS));
    entity.setScriptPatchVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION));
    entity.setScriptEventId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID));
    entity.setSourceService(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE));
    entity.setTriggerMode(record.get(SCRIPT_EVENT_INGRESS_AUDIT.TRIGGER_MODE));
    entity.setSourceKind(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_KIND));
    entity.setSourceState(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE));
    entity.setSourceOrdinal(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_ORDINAL));
    entity.setSourceDueTickId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_TICK_ID));
    entity.setSourceDueAtMs(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_AT_MS));
    Boolean dryRun = record.get(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN);
    entity.setDryRun(Boolean.TRUE.equals(dryRun));
    entity.setReadSnapshotToken(record.get(SCRIPT_EVENT_INGRESS_AUDIT.READ_SNAPSHOT_TOKEN));
    entity.setPayloadJson(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PAYLOAD_JSON));
    Boolean admitted = record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMITTED);
    entity.setAdmitted(Boolean.TRUE.equals(admitted));
    entity.setAdmissionOutcome(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_OUTCOME));
    entity.setAdmissionReason(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_REASON));
    Integer resolvedHandlerCount = record.get(SCRIPT_EVENT_INGRESS_AUDIT.RESOLVED_HANDLER_COUNT);
    entity.setResolvedHandlerCount(resolvedHandlerCount == null ? 0 : resolvedHandlerCount);
    entity.setCreatedAt(toInstant(record.get(SCRIPT_EVENT_INGRESS_AUDIT.CREATED_AT)));
    Integer rowVersion = record.get(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
