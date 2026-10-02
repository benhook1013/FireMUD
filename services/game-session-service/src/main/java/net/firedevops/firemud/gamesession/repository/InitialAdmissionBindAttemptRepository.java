package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointer.GAMEPLAY_ADMISSION_POINTER;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayInitialAdmissionBindAttempt.GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayAdmissionPointerRecord;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayInitialAdmissionBindAttemptRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class InitialAdmissionBindAttemptRepository {
  private static final String ATTEMPT_LOCK_PREFIX = "initial-admission-bind-attempt:";
  private static final String REALM_LOCK_PREFIX = "initial-admission-bind-realm:";

  private final DSLContext dsl;

  public InitialAdmissionBindAttemptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void lockAttemptAndRealm(long tenantId, String requestId, UUID realmId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    List.of(
            ATTEMPT_LOCK_PREFIX + tenantId + ":" + requestId,
            REALM_LOCK_PREFIX + tenantId + ":" + realmId)
        .stream()
        .sorted()
        .forEach(this::lock);
  }

  public Optional<InitialAdmissionBindAttempt> findByTenantAndRequestId(
      long tenantId, String requestId) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
        .where(
            GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT
                .TENANT_ID
                .eq(tenantId)
                .and(
                    GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.INITIAL_ADMISSION_REQUEST_ID.eq(
                        requestId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<InitialAdmissionBindAttempt> findByTenantAndRequestIdForUpdate(
      long tenantId, String requestId) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
        .where(
            GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT
                .TENANT_ID
                .eq(tenantId)
                .and(
                    GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.INITIAL_ADMISSION_REQUEST_ID.eq(
                        requestId)))
        .forUpdate()
        .fetchOptional(this::toEntity);
  }

  public Optional<InitialAdmissionBindAttempt> findPendingByTenantAndRealmForUpdate(
      long tenantId, UUID realmId) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
        .where(
            GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.REALM_ID.eq(realmId))
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS.eq(Status.PENDING.name())))
        .forUpdate()
        .fetchOptional(this::toEntity);
  }

  public InitialAdmissionBindAttempt insertPending(InitialAdmissionBindAttempt attempt) {
    GameplayInitialAdmissionBindAttemptRecord record =
        dsl.newRecord(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT);
    record.setAttemptId(attempt.attemptId());
    record.setTenantId(attempt.tenantId());
    record.setInitialAdmissionRequestId(attempt.initialAdmissionRequestId());
    record.setRequestDigest(attempt.requestDigest());
    record.setRealmId(attempt.realmId());
    record.setPlayableStateNamespaceId(attempt.playableStateNamespaceId());
    record.setPlayableStateScope(attempt.playableStateScope());
    record.setExpectedNoPriorPointer(true);
    record.setCatalogRevision(attempt.catalogRevision());
    record.setGameInstanceId(attempt.gameInstanceId());
    record.setVersionId(attempt.versionId());
    record.setActiveLifecycleEpoch(attempt.activeLifecycleEpoch());
    record.setStatus(Status.PENDING.name());
    record.setCreatedAt(toLocalDateTime(attempt.createdAt()));
    record.setUpdatedAt(toLocalDateTime(attempt.updatedAt()));
    record.setTerminalAt(null);
    record.store();
    return findByTenantAndRequestId(attempt.tenantId(), attempt.initialAdmissionRequestId())
        .orElseThrow();
  }

  public InitialAdmissionBindAttempt attachHold(
      InitialAdmissionBindAttempt attempt, UUID holdId, UUID holdFence, Instant now) {
    int updated =
        dsl.update(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.HOLD_ID, holdId)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.HOLD_FENCE, holdFence)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.UPDATED_AT, toLocalDateTime(now))
            .where(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.ATTEMPT_ID.eq(attempt.attemptId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Initial admission attempt disappeared while attaching hold");
    }
    return findByTenantAndRequestId(attempt.tenantId(), attempt.initialAdmissionRequestId())
        .orElseThrow();
  }

  public InitialAdmissionBindAttempt markCommitted(
      InitialAdmissionBindAttempt attempt, long pointerId, long auditEventId, Instant now) {
    int updated =
        dsl.update(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS, Status.COMMITTED.name())
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.POINTER_ID, pointerId)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.AUDIT_EVENT_ID, auditEventId)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.UPDATED_AT, toLocalDateTime(now))
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.TERMINAL_AT, toLocalDateTime(now))
            .where(
                GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT
                    .ATTEMPT_ID
                    .eq(attempt.attemptId())
                    .and(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS.eq(Status.PENDING.name())))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Initial admission attempt was not pending at commit");
    }
    return findByTenantAndRequestId(attempt.tenantId(), attempt.initialAdmissionRequestId())
        .orElseThrow();
  }

  public InitialAdmissionBindAttempt markAborted(InitialAdmissionBindAttempt attempt, Instant now) {
    int updated =
        dsl.update(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT)
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS, Status.ABORTED.name())
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.UPDATED_AT, toLocalDateTime(now))
            .set(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.TERMINAL_AT, toLocalDateTime(now))
            .where(
                GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT
                    .ATTEMPT_ID
                    .eq(attempt.attemptId())
                    .and(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS.eq(Status.PENDING.name())))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Initial admission attempt was not pending at abort");
    }
    return findByTenantAndRequestId(attempt.tenantId(), attempt.initialAdmissionRequestId())
        .orElseThrow();
  }

  public boolean hasPointerForCatalog(InitialAdmissionBindCatalog catalog) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(GAMEPLAY_ADMISSION_POINTER)
            .where(
                GAMEPLAY_ADMISSION_POINTER
                    .TENANT_ID
                    .eq(catalog.tenantId())
                    .and(
                        GAMEPLAY_ADMISSION_POINTER
                            .REALM_ID
                            .eq(catalog.realmId())
                            .or(
                                GAMEPLAY_ADMISSION_POINTER
                                    .WORLD_SLUG
                                    .eq(catalog.worldSlug())
                                    .and(
                                        GAMEPLAY_ADMISSION_POINTER.REALM_SLUG.eq(
                                            catalog.realmSlug()))))));
  }

  public boolean hasPointerForRuntimeTarget(long tenantId, long gameInstanceId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(GAMEPLAY_ADMISSION_POINTER)
            .where(
                GAMEPLAY_ADMISSION_POINTER
                    .TENANT_ID
                    .eq(tenantId)
                    .and(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID.eq(gameInstanceId))));
  }

  public GameplayAdmissionPointer insertPointer(
      InitialAdmissionBindCatalog catalog, long gameInstanceId, Instant now) {
    GameplayAdmissionPointerRecord record = dsl.newRecord(GAMEPLAY_ADMISSION_POINTER);
    record.setWorldSlug(catalog.worldSlug());
    record.setWorldDisplayName(catalog.worldDisplayName());
    record.setRealmSlug(catalog.realmSlug());
    record.setRealmDisplayName(catalog.realmDisplayName());
    record.setTenantId(catalog.tenantId());
    record.setGameInstanceId(gameInstanceId);
    record.setPointerVersion(1L);
    record.setCatalogRevision(catalog.catalogRevision());
    record.setRealmId(catalog.realmId());
    record.setPlayableStateNamespaceId(catalog.playableStateNamespaceId());
    record.setVisible(true);
    record.setPublicProductionRealm(true);
    record.setRequiresCharacterSelection(catalog.requiresCharacterSelection());
    record.setStateScope("SHARED");
    record.setCharacterCreationPolicy("ALLOW_NEW");
    record.setLastUpdatedBy("game-session-initial-admission-bind");
    record.setLastUpdateReason("initial admission pointer bind");
    record.setCreatedAt(toLocalDateTime(now));
    record.setUpdatedAt(toLocalDateTime(now));
    record.store();
    return findPointerById(record.getId()).orElseThrow();
  }

  public Optional<GameplayAdmissionPointer> findPointerById(long pointerId) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(GAMEPLAY_ADMISSION_POINTER.ID.eq(pointerId))
        .fetchOptional(this::toPointer);
  }

  private Optional<GameplayAdmissionPointer> findPointerById(Long pointerId) {
    return dsl.selectFrom(GAMEPLAY_ADMISSION_POINTER)
        .where(GAMEPLAY_ADMISSION_POINTER.ID.eq(pointerId))
        .fetchOptional(this::toPointer);
  }

  private void lock(String key) {
    dsl.fetch("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))", key);
  }

  private InitialAdmissionBindAttempt toEntity(Record record) {
    return new InitialAdmissionBindAttempt(
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.ATTEMPT_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.TENANT_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.INITIAL_ADMISSION_REQUEST_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.REQUEST_DIGEST),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.REALM_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.PLAYABLE_STATE_NAMESPACE_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.PLAYABLE_STATE_SCOPE),
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.EXPECTED_NO_PRIOR_POINTER)),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.CATALOG_REVISION),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.GAME_INSTANCE_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.VERSION_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.ACTIVE_LIFECYCLE_EPOCH),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.HOLD_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.HOLD_FENCE),
        Status.valueOf(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.STATUS)),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.POINTER_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.AUDIT_EVENT_ID),
        toInstant(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.CREATED_AT)),
        toInstant(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.UPDATED_AT)),
        toInstant(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_ATTEMPT.TERMINAL_AT)));
  }

  private GameplayAdmissionPointer toPointer(Record record) {
    GameplayAdmissionPointer pointer = new GameplayAdmissionPointer();
    pointer.setId(record.get(GAMEPLAY_ADMISSION_POINTER.ID));
    pointer.setWorldSlug(record.get(GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG));
    pointer.setWorldDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER.WORLD_DISPLAY_NAME));
    pointer.setRealmSlug(record.get(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG));
    pointer.setRealmDisplayName(record.get(GAMEPLAY_ADMISSION_POINTER.REALM_DISPLAY_NAME));
    pointer.setTenantId(record.get(GAMEPLAY_ADMISSION_POINTER.TENANT_ID));
    pointer.setGameInstanceId(record.get(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID));
    pointer.setPointerVersion(record.get(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION));
    pointer.setCatalogRevision(record.get(GAMEPLAY_ADMISSION_POINTER.CATALOG_REVISION));
    pointer.setRealmId(record.get(GAMEPLAY_ADMISSION_POINTER.REALM_ID));
    pointer.setPlayableStateNamespaceId(
        record.get(GAMEPLAY_ADMISSION_POINTER.PLAYABLE_STATE_NAMESPACE_ID));
    pointer.setVisible(Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.VISIBLE)));
    pointer.setPublicProductionRealm(
        Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.PUBLIC_PRODUCTION_REALM)));
    pointer.setRequiresCharacterSelection(
        Boolean.TRUE.equals(record.get(GAMEPLAY_ADMISSION_POINTER.REQUIRES_CHARACTER_SELECTION)));
    pointer.setStateScope(record.get(GAMEPLAY_ADMISSION_POINTER.STATE_SCOPE));
    pointer.setCharacterCreationPolicy(
        record.get(GAMEPLAY_ADMISSION_POINTER.CHARACTER_CREATION_POLICY));
    pointer.setLastUpdatedBy(record.get(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATED_BY));
    pointer.setLastUpdateReason(record.get(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATE_REASON));
    pointer.setCreatedAt(toInstant(record.get(GAMEPLAY_ADMISSION_POINTER.CREATED_AT)));
    pointer.setUpdatedAt(toInstant(record.get(GAMEPLAY_ADMISSION_POINTER.UPDATED_AT)));
    return pointer;
  }
}
