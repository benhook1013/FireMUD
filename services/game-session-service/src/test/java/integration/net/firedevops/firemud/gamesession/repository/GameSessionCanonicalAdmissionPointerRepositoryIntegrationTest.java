package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalClosedAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalClosedAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.CanonicalClosedPointerConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.ExpectedClosedEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.PreparedExpectedClosed;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.PreparedInitialClosed;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalAdmissionPointerRepositoryIntegrationTest {
  private static final String NAMESPACE = "canonical-closed-pointer-it";
  private static final long RETAINED_TENANT_ID = 917L;
  private static final long RETAINED_INSTANCE_ID = 501L;
  private static final UUID RETAINED_REALM_ID = uuid(801);
  private static final UUID RETAINED_NAMESPACE_ID = uuid(802);
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void commitsCatalogBackedClosedPointerWithoutNumericOrRuntimeAliases() {
    Fixture fixture = fixture(true);
    Created created = fixture.create(uuid(20), "Café 🐉 owner", "initial close reason");

    assertThat(created.snapshot.admissionState()).isEqualTo("CLOSED");
    assertThat(created.snapshot.admissibleGameInstanceId()).isNull();
    assertThat(created.snapshot.pointerVersion()).isEqualTo(1L);
    assertThat(created.snapshot.catalogSnapshot()).isEqualTo(created.catalog);
    assertThat(created.snapshot.updatedAt())
        .isEqualTo(created.snapshot.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(fixture.count("gameplay_admission_pointer")).isEqualTo(2L);
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request"))
        .isEqualTo(1L);

    Record pointer =
        fixture.required(
            "SELECT * FROM gameplay_admission_pointer WHERE representation_version = 2");
    assertThat(pointer.get("tenant_id", Long.class)).isNull();
    assertThat(pointer.get("game_instance_id", Long.class)).isNull();
    assertThat(pointer.get("canonical_tenant_id", UUID.class))
        .isEqualTo(created.catalog.tenantId());
    assertThat(pointer.get("realm_id", UUID.class)).isEqualTo(created.catalog.realmId());
    assertThat(pointer.get("playable_state_namespace_id", UUID.class)).isNull();
    assertThat(pointer.get("admission_state", String.class)).isEqualTo("CLOSED");
    assertThat(pointer.get("world_display_name", String.class)).isNull();
    assertThat(pointer.get("character_creation_policy", String.class)).isNull();
  }

  @Test
  void exactRetryAndLostResponseReadbackPreserveTheOriginalOutcomeAndEvent() {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest request =
        fixture.createCatalog(uuid(21), uuid(31), "actor/é", "close/東京");
    PreparedInitialClosed prepared = fixture.pointerRepository.prepareInitialClosed(request);
    fixture.commit(prepared);
    CanonicalClosedAdmissionPointerSnapshot original =
        fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId()).orElseThrow();
    fixture.commit(fixture.pointerRepository.prepareInitialClosed(request));
    CanonicalClosedAdmissionPointerSnapshot replay =
        fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId()).orElseThrow();

    assertThat(replay).isEqualTo(original);
    assertThat(replay.updatedAt()).isEqualTo(original.updatedAt());
    assertThat(replay.auditEventId()).isEqualTo(original.auditEventId());
    assertThat(fixture.count("gameplay_admission_pointer WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request"))
        .isEqualTo(1L);
  }

  @Test
  void expectedClosedLocksAndReturnsOnlyTheExactNeverOpenOriginEvidence() {
    Fixture fixture = fixture(false);
    Created created = fixture.create(uuid(28), "expected-close actor", "expected-close reason");
    PreparedExpectedClosed prepared =
        fixture.pointerRepository.prepareExpectedClosed(
            NAMESPACE, created.snapshot.requestId(), 1L, 1L);

    ExpectedClosedEvidence evidence = fixture.lockExpectedClosed(prepared);

    assertThat(evidence.isNeverOpenOrigin()).isTrue();
    assertThat(evidence.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(evidence.originalRequestId()).isEqualTo(created.snapshot.requestId());
    assertThat(evidence.canonicalTenantId()).isEqualTo(created.catalog.tenantId());
    assertThat(evidence.realmId()).isEqualTo(created.catalog.realmId());
    assertThat(evidence.pointerVersion()).isEqualTo(1L);
    assertThat(evidence.catalogRevision()).isEqualTo(1L);
    assertThat(evidence.auditEventId()).isEqualTo(created.snapshot.auditEventId());
    assertThat(evidence.requestDigest()).isEqualTo(created.snapshot.requestDigest());
    assertThat(evidence.receiptDigest()).isEqualTo(created.snapshot.receiptDigest());
  }

  @Test
  void expectedClosedRejectsMissingForgedAndWrongVersionOriginSelectors() {
    Fixture fixture = fixture(false);
    Created created = fixture.create(uuid(29), "expected-close actor", "expected-close reason");

    assertThatThrownBy(
            () -> fixture.pointerRepository.prepareExpectedClosed(NAMESPACE, uuid(998), 1L, 1L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("origin is missing");
    assertThatThrownBy(
            () ->
                fixture.pointerRepository.prepareExpectedClosed(
                    NAMESPACE + "-other", created.snapshot.requestId(), 1L, 1L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("origin is missing");
    assertThatThrownBy(
            () ->
                fixture.pointerRepository.prepareExpectedClosed(
                    NAMESPACE, created.snapshot.requestId(), 2L, 1L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("versions do not match");
    assertThatThrownBy(
            () ->
                fixture.pointerRepository.prepareExpectedClosed(
                    NAMESPACE, created.snapshot.requestId(), 1L, 2L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("versions do not match");
  }

  @Test
  void expectedClosedLockRejectsDriftAndPartialOriginalEvidence() {
    Fixture drifted = fixture(false);
    Created created = drifted.create(uuid(38), "drift actor", "original reason");
    PreparedExpectedClosed driftPrepared =
        drifted.pointerRepository.prepareExpectedClosed(
            NAMESPACE, created.snapshot.requestId(), 1L, 1L);
    drifted.dsl.execute(
        "ALTER TABLE gameplay_admission_pointer_event "
            + "DISABLE TRIGGER gameplay_admission_pointer_event_canonical_immutable");
    drifted.dsl.execute(
        "UPDATE gameplay_admission_pointer_event SET reason = 'changed after prepare' WHERE id = ?",
        created.snapshot.auditEventId());
    drifted.dsl.execute(
        "ALTER TABLE gameplay_admission_pointer_event "
            + "ENABLE TRIGGER gameplay_admission_pointer_event_canonical_immutable");

    assertThatThrownBy(() -> drifted.lockExpectedClosed(driftPrepared))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("audit event conflicts");

    Fixture partial = fixture(false);
    Created partialCreated = partial.create(uuid(39), "partial actor", "partial origin reason");
    PreparedExpectedClosed partialPrepared =
        partial.pointerRepository.prepareExpectedClosed(
            NAMESPACE, partialCreated.snapshot.requestId(), 1L, 1L);
    partial.dsl.execute(
        "ALTER TABLE game_session_canonical_closed_admission_pointer_request "
            + "DISABLE TRIGGER ALL");
    partial.dsl.execute("ALTER TABLE gameplay_admission_pointer_event DISABLE TRIGGER ALL");
    partial.dsl.execute(
        "DELETE FROM game_session_canonical_closed_admission_pointer_request "
            + "WHERE target_namespace = ? AND request_id = ?",
        NAMESPACE,
        partialCreated.snapshot.requestId());
    partial.dsl.execute(
        "DELETE FROM gameplay_admission_pointer_event WHERE id = ?",
        partialCreated.snapshot.auditEventId());
    partial.dsl.execute(
        "ALTER TABLE game_session_canonical_closed_admission_pointer_request "
            + "ENABLE TRIGGER ALL");
    partial.dsl.execute("ALTER TABLE gameplay_admission_pointer_event ENABLE TRIGGER ALL");

    assertThatThrownBy(() -> partial.lockExpectedClosed(partialPrepared))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("request outcome is missing");
    assertThatThrownBy(
            () ->
                partial.pointerRepository.prepareExpectedClosed(
                    NAMESPACE, partialCreated.snapshot.requestId(), 1L, 1L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("origin is missing");
  }

  @Test
  void expectedClosedRequiresCommittedPreflightAndWritableReadCommittedOwnerLock() {
    Fixture fixture = fixture(false);
    Created created = fixture.create(uuid(40), "boundary actor", "boundary reason");
    PreparedExpectedClosed prepared =
        fixture.pointerRepository.prepareExpectedClosed(
            NAMESPACE, created.snapshot.requestId(), 1L, 1L);

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.pointerRepository.prepareExpectedClosed(
                            NAMESPACE, created.snapshot.requestId(), 1L, 1L)))
        .hasMessageContaining("committed-outcome owner read");
    assertThatThrownBy(() -> fixture.pointerRepository.lockExpectedClosed(prepared))
        .hasMessageContaining("active owner transaction");

    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(status -> fixture.pointerRepository.lockExpectedClosed(prepared)))
        .hasMessageContaining("read-write transaction");

    TransactionTemplate serializable = new TransactionTemplate(fixture.transactionManager);
    serializable.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThatThrownBy(
            () ->
                serializable.execute(
                    status -> fixture.pointerRepository.lockExpectedClosed(prepared)))
        .hasMessageContaining("READ COMMITTED");

    assertThat(fixture.lockExpectedClosed(prepared).isNeverOpenOrigin()).isTrue();
  }

  @Test
  void changedAuditOrCatalogInputUnderTheSameRequestConflictsWithoutAddingRows() {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest original =
        fixture.createCatalog(uuid(22), uuid(32), "actor", "first reason");
    fixture.commit(fixture.pointerRepository.prepareInitialClosed(original));
    CreateCanonicalClosedAdmissionPointerRequest changedReason =
        new CreateCanonicalClosedAdmissionPointerRequest(
            original.requestId(),
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.realmId(),
            original.catalogCreationRequestId(),
            original.catalogRevision(),
            original.actorPrincipal(),
            "changed reason");
    CreateCanonicalClosedAdmissionPointerRequest changedActor =
        new CreateCanonicalClosedAdmissionPointerRequest(
            original.requestId(),
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.realmId(),
            original.catalogCreationRequestId(),
            original.catalogRevision(),
            "changed actor",
            original.reason());
    PreparedInitialClosed changedReasonPrepared =
        fixture.pointerRepository.prepareInitialClosed(changedReason);
    PreparedInitialClosed changedActorPrepared =
        fixture.pointerRepository.prepareInitialClosed(changedActor);
    CreateCanonicalClosedAdmissionPointerRequest changedCatalogReference =
        new CreateCanonicalClosedAdmissionPointerRequest(
            original.requestId(),
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.realmId(),
            uuid(999),
            original.catalogRevision(),
            original.actorPrincipal(),
            original.reason());

    assertThatThrownBy(() -> fixture.commit(changedReasonPrepared))
        .isInstanceOf(CanonicalClosedPointerConflictException.class)
        .hasMessageContaining("changed input");
    assertThatThrownBy(() -> fixture.commit(changedActorPrepared))
        .isInstanceOf(CanonicalClosedPointerConflictException.class)
        .hasMessageContaining("changed input");
    assertThatThrownBy(
            () -> fixture.pointerRepository.prepareInitialClosed(changedCatalogReference))
        .hasMessageContaining("catalog request is missing");
    assertThat(fixture.count("gameplay_admission_pointer WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request"))
        .isEqualTo(1L);
  }

  @Test
  void concurrentExactCreationLeavesOnePointerEventAndImmutableOutcome() throws Exception {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest request =
        fixture.createCatalog(uuid(23), uuid(33), "concurrent actor", "same intent");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> first = executor.submit(() -> raceCreate(fixture, request, ready, start));
      Future<Void> second = executor.submit(() -> raceCreate(fixture, request, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
      assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
    }

    assertThat(fixture.count("gameplay_admission_pointer WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isEqualTo(1L);
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request"))
        .isEqualTo(1L);
    assertThat(fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId())).isPresent();
  }

  @Test
  void rollingBackOwnerTransactionLeavesNoPointerEventOrOutcome() {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest request =
        fixture.createCatalog(uuid(24), uuid(34), "rollback actor", "rollback reason");
    PreparedInitialClosed prepared = fixture.pointerRepository.prepareInitialClosed(request);
    Void rolledBack =
        fixture.transactions.execute(
            status -> {
              fixture.pointerRepository.createInitialClosed(prepared);
              status.setRollbackOnly();
              return null;
            });

    assertThat(rolledBack).isNull();
    assertThat(fixture.count("gameplay_admission_pointer WHERE representation_version = 2"))
        .isZero();
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isZero();
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request")).isZero();
    assertThat(fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId())).isEmpty();
  }

  @Test
  void committedReadbackRequiresExactOwnerTransactionBoundaries() {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest request =
        fixture.createCatalog(uuid(25), uuid(35), "boundary actor", "boundary reason");
    PreparedInitialClosed prepared = fixture.pointerRepository.prepareInitialClosed(request);

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> fixture.pointerRepository.prepareInitialClosed(request)))
        .hasMessageContaining("committed-outcome owner read");
    assertThatThrownBy(() -> fixture.pointerRepository.createInitialClosed(prepared))
        .hasMessageContaining("active owner transaction");
    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(
                    status -> {
                      fixture.pointerRepository.createInitialClosed(prepared);
                      return null;
                    }))
        .hasMessageContaining("read-write transaction");
    TransactionTemplate serializable = new TransactionTemplate(fixture.transactionManager);
    serializable.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThatThrownBy(
            () ->
                serializable.execute(
                    status -> {
                      fixture.pointerRepository.createInitialClosed(prepared);
                      return null;
                    }))
        .hasMessageContaining("READ COMMITTED");
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId())))
        .hasMessageContaining("committed-outcome owner read");
    assertThat(fixture.pointerRepository.readByRequest(NAMESPACE, request.requestId())).isEmpty();
  }

  @Test
  void partialPointerCannotCommitWithoutItsAuditAndRequestOutcome() {
    Fixture fixture = fixture(false);
    CreateCanonicalClosedAdmissionPointerRequest request =
        fixture.createCatalog(uuid(27), uuid(37), "partial actor", "partial reason");

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.transactionalDsl.execute(
                            "INSERT INTO gameplay_admission_pointer (world_slug, realm_slug, "
                                + "world_display_name, realm_display_name, tenant_id, game_instance_id, "
                                + "pointer_version, visible, requires_character_selection, state_scope, "
                                + "character_creation_policy, last_updated_by, last_update_reason, "
                                + "created_at, updated_at, public_production_realm, catalog_revision, "
                                + "realm_id, playable_state_namespace_id, representation_version, "
                                + "target_namespace, canonical_tenant_id, admission_state) "
                                + "SELECT world_slug, realm_slug, NULL, NULL, NULL, NULL, 1, NULL, NULL, "
                                + "NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, "
                                + "catalog_revision, realm_id, NULL, 2, target_namespace, "
                                + "canonical_tenant_id, 'CLOSED' FROM game_session_canonical_realm_catalog "
                                + "WHERE creation_request_id = ?",
                            request.catalogCreationRequestId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "Canonical CLOSED pointer, event, outcome and exact catalog must commit together");
    assertThat(fixture.count("gameplay_admission_pointer WHERE representation_version = 2"))
        .isZero();
    assertThat(fixture.count("gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isZero();
    assertThat(fixture.count("game_session_canonical_closed_admission_pointer_request")).isZero();
  }

  @Test
  void retainedNumericReadersRemainIsolatedAndCanonicalMutationGuardsHold() {
    Fixture fixture = fixture(true);
    Created created = fixture.create(uuid(26), "retained-safe actor", "retained-safe reason");

    assertThat(fixture.legacyPointerRepository.count()).isEqualTo(1L);
    assertThat(
            fixture.legacyPointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(
                RETAINED_TENANT_ID, "legacy-world", "legacy-realm"))
        .isPresent();
    assertThat(
            fixture.legacyPointerRepository.findAllByTenantIdAndGameInstanceId(
                RETAINED_TENANT_ID, RETAINED_INSTANCE_ID))
        .hasSize(1);
    assertThat(fixture.legacyPointerRepository.findAllByOrderByWorldSlugAscRealmSlugAsc())
        .hasSize(1)
        .allSatisfy(pointer -> assertThat(pointer.getTenantId()).isEqualTo(RETAINED_TENANT_ID));
    assertThat(
            fixture.eventRepository.findByTenantIdAndWorldSlugAndRealmSlugOrderByOccurredAtDesc(
                RETAINED_TENANT_ID, "legacy-world", "legacy-realm"))
        .hasSize(1)
        .first()
        .satisfies(event -> assertThat(event.getGameInstanceId()).isEqualTo(RETAINED_INSTANCE_ID));
    assertThat(
            fixture
                .required(
                    "SELECT tenant_id, game_instance_id, realm_id, playable_state_namespace_id "
                        + "FROM gameplay_admission_pointer WHERE id = ?",
                    fixture.retainedPointerId)
                .get("realm_id", UUID.class))
        .isEqualTo(RETAINED_REALM_ID);
    assertThat(
            fixture
                .required(
                    "SELECT tenant_id, game_instance_id, realm_id, playable_state_namespace_id "
                        + "FROM gameplay_admission_pointer WHERE id = ?",
                    fixture.retainedPointerId)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(RETAINED_NAMESPACE_ID);
    assertThat(
            fixture
                .required(
                    "SELECT (to_jsonb(pointer) - 'representation_version' - 'target_namespace' "
                        + "- 'canonical_tenant_id' - 'admission_state')::text AS row_json "
                        + "FROM gameplay_admission_pointer pointer WHERE id = ?",
                    fixture.retainedPointerId)
                .get("row_json", String.class))
        .isEqualTo(fixture.retainedPointerBeforeV13);
    assertThat(
            fixture
                .required(
                    "SELECT (to_jsonb(event) - 'representation_version' - 'target_namespace' "
                        + "- 'canonical_tenant_id' - 'realm_id' - 'catalog_revision' "
                        + "- 'admission_state')::text AS row_json "
                        + "FROM gameplay_admission_pointer_event event WHERE id = ?",
                    fixture.retainedEventId)
                .get("row_json", String.class))
        .isEqualTo(fixture.retainedEventBeforeV13);
    Record retainedPointer =
        fixture.required(
            "SELECT representation_version, target_namespace, canonical_tenant_id, admission_state "
                + "FROM gameplay_admission_pointer WHERE id = ?",
            fixture.retainedPointerId);
    assertThat(retainedPointer.get("representation_version", Integer.class)).isEqualTo(1);
    assertThat(retainedPointer.get("target_namespace", String.class)).isNull();
    assertThat(retainedPointer.get("canonical_tenant_id", UUID.class)).isNull();
    assertThat(retainedPointer.get("admission_state", String.class)).isNull();
    Record retainedEvent =
        fixture.required(
            "SELECT representation_version, target_namespace, canonical_tenant_id, realm_id, "
                + "catalog_revision, admission_state FROM gameplay_admission_pointer_event "
                + "WHERE id = ?",
            fixture.retainedEventId);
    assertThat(retainedEvent.get("representation_version", Integer.class)).isEqualTo(1);
    assertThat(retainedEvent.get("target_namespace", String.class)).isNull();
    assertThat(retainedEvent.get("canonical_tenant_id", UUID.class)).isNull();
    assertThat(retainedEvent.get("realm_id", UUID.class)).isNull();
    assertThat(retainedEvent.get("catalog_revision", Long.class)).isNull();
    assertThat(retainedEvent.get("admission_state", String.class)).isNull();

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE gameplay_admission_pointer SET tenant_id = ? "
                        + "WHERE representation_version = 2",
                    RETAINED_TENANT_ID))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE gameplay_admission_pointer SET admission_state = 'OPEN' "
                        + "WHERE representation_version = 2"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM gameplay_admission_pointer WHERE representation_version = 2"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE gameplay_admission_pointer_event SET reason = 'rewritten' "
                        + "WHERE representation_version = 2"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM gameplay_admission_pointer_event WHERE representation_version = 2"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> fixture.dsl.execute("TRUNCATE gameplay_admission_pointer_event"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_canonical_closed_admission_pointer_request "
                        + "SET reason = 'rewritten'"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_session_canonical_closed_admission_pointer_request"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "TRUNCATE game_session_canonical_closed_admission_pointer_request"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> fixture.dsl.execute("TRUNCATE gameplay_admission_pointer"))
        .isInstanceOf(RuntimeException.class);
    assertThat(fixture.pointerRepository.readByRequest(NAMESPACE, created.snapshot.requestId()))
        .isPresent();
  }

  private Void raceCreate(
      Fixture fixture,
      CreateCanonicalClosedAdmissionPointerRequest request,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for canonical closed-pointer race");
    }
    fixture.commit(fixture.pointerRepository.prepareInitialClosed(request));
    return null;
  }

  private Fixture fixture(boolean seedRetained) {
    String schema = "gs_canonical_closed_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("11"))
        .load()
        .migrate();

    DSLContext initialDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    Long retainedPointerId = null;
    if (seedRetained) {
      initialDsl.execute(
          "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
              + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
          RETAINED_TENANT_ID,
          RETAINED_NAMESPACE_ID);
      initialDsl.execute(
          "INSERT INTO gameplay_admission_pointer "
              + "(world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
              + "game_instance_id, pointer_version, visible, requires_character_selection, "
              + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
              + "public_production_realm, realm_id, playable_state_namespace_id) "
              + "VALUES ('legacy-world', 'Legacy World', 'legacy-realm', 'Legacy Realm', ?, ?, "
              + "1, true, false, 'SHARED', 'ALLOW_NEW', 'fixture', 'retained evidence', true, ?, ?)",
          RETAINED_TENANT_ID,
          RETAINED_INSTANCE_ID,
          RETAINED_REALM_ID,
          RETAINED_NAMESPACE_ID);
      retainedPointerId =
          java.util.Objects.requireNonNull(
                  initialDsl.fetchOne(
                      "SELECT id FROM gameplay_admission_pointer WHERE tenant_id = ?",
                      RETAINED_TENANT_ID),
                  "Retained pointer seed was not committed before V13")
              .get("id", Long.class);
    }
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("12"))
        .load()
        .migrate();
    Long retainedEventId = null;
    String retainedPointerBeforeV13 = null;
    String retainedEventBeforeV13 = null;
    if (seedRetained) {
      initialDsl.execute(
          "INSERT INTO gameplay_admission_pointer_event "
              + "(world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
              + "game_instance_id, pointer_version, visible, requires_character_selection, "
              + "state_scope, character_creation_policy, actor_principal, reason, "
              + "control_plane_request_id, occurred_at, public_production_realm) "
              + "VALUES ('legacy-world', 'legacy-realm', 'Legacy World', 'Legacy Realm', ?, ?, "
              + "1, true, false, 'SHARED', 'ALLOW_NEW', 'fixture', 'retained event', "
              + "'legacy-request', CURRENT_TIMESTAMP, true)",
          RETAINED_TENANT_ID,
          RETAINED_INSTANCE_ID);
      retainedEventId =
          java.util.Objects.requireNonNull(
                  initialDsl.fetchOne(
                      "SELECT id FROM gameplay_admission_pointer_event WHERE tenant_id = ?",
                      RETAINED_TENANT_ID),
                  "Retained pointer event seed was not committed before V13")
              .get("id", Long.class);
      retainedPointerBeforeV13 =
          java.util.Objects.requireNonNull(
                  initialDsl.fetchOne(
                      "SELECT to_jsonb(pointer)::text AS row_json FROM gameplay_admission_pointer pointer "
                          + "WHERE id = ?",
                      retainedPointerId),
                  "Retained pointer JSON must be readable before V13")
              .get("row_json", String.class);
      retainedEventBeforeV13 =
          java.util.Objects.requireNonNull(
                  initialDsl.fetchOne(
                      "SELECT to_jsonb(event)::text AS row_json FROM gameplay_admission_pointer_event event "
                          + "WHERE id = ?",
                      retainedEventId),
                  "Retained pointer event JSON must be readable before V13")
              .get("row_json", String.class);
    }
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(dsl);
    GameplayAdmissionPointerEventRepository eventRepository =
        new GameplayAdmissionPointerEventRepository(dsl);
    return new Fixture(
        initialDsl,
        dsl,
        transactionManager,
        transactions,
        sourceRepository,
        catalogRepository,
        new GameSessionCanonicalAdmissionPointerRepository(dsl, catalogRepository, eventRepository),
        new net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository(dsl),
        eventRepository,
        retainedPointerId,
        retainedEventId,
        retainedPointerBeforeV13,
        retainedEventBeforeV13);
  }

  private static AuthoredWorldSourceEvidence source(UUID tenantId, String worldSlug) {
    UUID registrationRequestId =
        UUID.nameUUIDFromBytes(
            ("registration-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID sourceOperationId =
        UUID.nameUUIDFromBytes(
            ("operation-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String tenantSlug = "tenant-" + tenantId.toString().substring(0, 8);
    String worldDisplayName = "Authored World";
    String sourceGameTenantKey = "gds-tenant-" + tenantId.toString().substring(0, 8);
    long sourceGameRowId = Math.abs(tenantId.getMostSignificantBits()) + 10L;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Created(
      CanonicalRealmCatalogSnapshot catalog, CanonicalClosedAdmissionPointerSnapshot snapshot) {}

  private record Fixture(
      DSLContext dsl,
      DSLContext transactionalDsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository
          legacyPointerRepository,
      GameplayAdmissionPointerEventRepository eventRepository,
      Long retainedPointerId,
      Long retainedEventId,
      String retainedPointerBeforeV13,
      String retainedEventBeforeV13) {
    CreateCanonicalClosedAdmissionPointerRequest createCatalog(
        UUID catalogRequestId, UUID pointerRequestId, String actor, String reason) {
      UUID tenantId = UUID.randomUUID();
      String worldSlug = "world-" + catalogRequestId.toString().substring(0, 8);
      AuthoredWorldSourceEvidence evidence = source(tenantId, worldSlug);
      UUID intakeRequestId =
          UUID.nameUUIDFromBytes(
              ("intake-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      IntakeReceipt receipt =
          java.util.Objects.requireNonNull(
              transactions.execute(status -> sourceRepository.register(intakeRequestId, evidence)));
      CreateCanonicalRealmCatalogRequest catalogRequest =
          new CreateCanonicalRealmCatalogRequest(
              catalogRequestId,
              NAMESPACE,
              tenantId,
              receipt.operationId(),
              "realm-" + catalogRequestId.toString().substring(0, 8),
              "Public Realm",
              true,
              true,
              "SHARED",
              "owner-policy-v1",
              null,
              null);
      var preparedCatalog = catalogRepository.prepareInitialPublicProduction(catalogRequest);
      CanonicalRealmCatalogSnapshot catalog =
          java.util.Objects.requireNonNull(
              transactions.execute(
                  status -> catalogRepository.createInitialPublicProduction(preparedCatalog)));
      return new CreateCanonicalClosedAdmissionPointerRequest(
          pointerRequestId,
          NAMESPACE,
          catalog.tenantId(),
          catalog.realmId(),
          catalog.creationRequestId(),
          catalog.catalogRevision(),
          actor,
          reason);
    }

    Created create(UUID pointerRequestId, String actor, String reason) {
      CreateCanonicalClosedAdmissionPointerRequest request =
          createCatalog(UUID.randomUUID(), pointerRequestId, actor, reason);
      commit(pointerRepository.prepareInitialClosed(request));
      return new Created(
          catalogRepository
              .readByRequest(NAMESPACE, request.catalogCreationRequestId())
              .orElseThrow(),
          pointerRepository.readByRequest(NAMESPACE, pointerRequestId).orElseThrow());
    }

    void commit(PreparedInitialClosed prepared) {
      Void result =
          transactions.execute(
              status -> {
                pointerRepository.createInitialClosed(prepared);
                return null;
              });
      assertThat(result).isNull();
    }

    long count(String tableOrPredicate) {
      return java.util.Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM " + tableOrPredicate),
              "Count query must return one row")
          .get(0, Long.class);
    }

    ExpectedClosedEvidence lockExpectedClosed(PreparedExpectedClosed prepared) {
      return java.util.Objects.requireNonNull(
          transactions.execute(status -> pointerRepository.lockExpectedClosed(prepared)));
    }

    Record required(String sql, Object... bindings) {
      return java.util.Objects.requireNonNull(dsl.fetchOne(sql, bindings));
    }
  }
}
