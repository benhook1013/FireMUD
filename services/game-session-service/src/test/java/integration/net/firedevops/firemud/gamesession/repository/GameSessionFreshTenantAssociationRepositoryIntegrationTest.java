package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociationConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.InvalidFreshTenantSourceException;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the V25 fresh-source-bound tenant owner producer. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionFreshTenantAssociationRepositoryIntegrationTest {
  private static final String NAMESPACE = "fresh-tenant-association-it";
  private static final UUID TENANT = uuid(601);
  private static final UUID REGISTRATION_REQUEST = uuid(602);
  private static final UUID SOURCE_OPERATION = uuid(603);
  private static final UUID INTAKE_REQUEST = uuid(604);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void allocatesPrivateIdentityAndReturnsTheOriginalExactAssociationOnRetry() {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    UUID associationOperationId = uuid(605);

    FreshGameSessionTenantAssociation first =
        fixture.associate(associationOperationId, NAMESPACE, source);
    FreshGameSessionTenantAssociation retry =
        fixture.associate(associationOperationId, NAMESPACE, source);

    assertThat(retry).isEqualTo(first);
    assertThat(first.associationOperationId()).isEqualTo(associationOperationId);
    assertThat(first.legacyGameSessionTenantId()).isPositive();
    assertThat(first.sourceEvidence().requestId())
        .isEqualTo(creationEvidence(source.source()).creationRequestId());
    assertThat(first.sourceEvidence().canonicalTenantId()).isEqualTo(TENANT);
    assertThat(first.sourceEvidence().sourceGameRowId())
        .isEqualTo(source.source().sourceGameRowId());
    assertThat(first.sourceEvidence().sourceGameTenantKey())
        .isEqualTo(source.source().sourceGameTenantKey());
    assertThat(first.sourceEvidence().provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT reservation_kind FROM game_session_tenant_scope_reservation "
                            + "WHERE game_session_tenant_id = ?",
                        first.legacyGameSessionTenantId()),
                    "expected fresh source-bound reservation row")
                .get("reservation_kind", String.class))
        .isEqualTo("FRESH_SOURCE_BOUND");
  }

  @Test
  void skipsExplicitLegacyReservationWhenTheDefaultIdentitySequenceCollides() {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    fixture.dsl.execute(
        "INSERT INTO game_session_tenant_scope_reservation"
            + " (game_session_tenant_id, reservation_kind, reserved_at)"
            + " VALUES (1, 'LEGACY_OCCUPIED', TIMESTAMPTZ '2020-01-02 03:04:05+00')");
    setTenantReservationSequenceNextValue(fixture.dsl, 1L);
    var legacyReservation =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT reservation_kind, reserved_at, xmin::text AS xmin"
                    + " FROM game_session_tenant_scope_reservation"
                    + " WHERE game_session_tenant_id = 1"),
            "expected explicit legacy reservation");

    FreshGameSessionTenantAssociation association = fixture.associate(uuid(627), NAMESPACE, source);

    assertThat(association.legacyGameSessionTenantId()).isEqualTo(2L);
    var preservedReservation =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT reservation_kind, reserved_at, xmin::text AS xmin"
                    + " FROM game_session_tenant_scope_reservation"
                    + " WHERE game_session_tenant_id = 1"),
            "expected legacy reservation after allocation");
    assertThat(preservedReservation.get("reservation_kind", String.class))
        .isEqualTo(legacyReservation.get("reservation_kind", String.class));
    assertThat(preservedReservation.get("reserved_at"))
        .isEqualTo(legacyReservation.get("reserved_at"));
    assertThat(preservedReservation.get("xmin", String.class))
        .isEqualTo(legacyReservation.get("xmin", String.class));
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(2);
  }

  @Test
  void skipsExistingFreshReservationAndKeepsItsExactAssociationReplay() {
    Fixture fixture = fixture();
    IntakeReceipt originalSource = fixture.registerFreshSource();
    FreshGameSessionTenantAssociation original =
        fixture.associate(uuid(628), NAMESPACE, originalSource);
    long originalTenantId = original.legacyGameSessionTenantId();
    var originalReservation =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT reservation_kind, reserved_at, xmin::text AS xmin"
                    + " FROM game_session_tenant_scope_reservation"
                    + " WHERE game_session_tenant_id = ?",
                originalTenantId),
            "expected original fresh reservation");
    IntakeReceipt nextSource =
        fixture.registerSource(uuid(629), uuid(630), uuid(631), uuid(632), 968L, "second-world");
    setTenantReservationSequenceNextValue(fixture.dsl, originalTenantId);

    FreshGameSessionTenantAssociation next = fixture.associate(uuid(633), NAMESPACE, nextSource);
    FreshGameSessionTenantAssociation replay =
        fixture.associate(uuid(628), NAMESPACE, originalSource);

    assertThat(next.legacyGameSessionTenantId()).isEqualTo(originalTenantId + 1);
    assertThat(replay).isEqualTo(original);
    var preservedReservation =
        Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT reservation_kind, reserved_at, xmin::text AS xmin"
                    + " FROM game_session_tenant_scope_reservation"
                    + " WHERE game_session_tenant_id = ?",
                originalTenantId),
            "expected original fresh reservation after allocation and replay");
    assertThat(preservedReservation.get("reservation_kind", String.class))
        .isEqualTo("FRESH_SOURCE_BOUND");
    assertThat(preservedReservation.get("reserved_at"))
        .isEqualTo(originalReservation.get("reserved_at"));
    assertThat(preservedReservation.get("xmin", String.class))
        .isEqualTo(originalReservation.get("xmin", String.class));
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
        .isEqualTo(2);
  }

  @Test
  void refusesAfterBoundedReservationCollisionsWithoutCreatingPartialAssociation() {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    fixture.dsl.execute(
        "INSERT INTO game_session_tenant_scope_reservation"
            + " (game_session_tenant_id, reservation_kind)"
            + " SELECT occupied_id, 'LEGACY_OCCUPIED'"
            + " FROM generate_series(1, 64) AS occupied(occupied_id)");
    setTenantReservationSequenceNextValue(fixture.dsl, 1L);

    assertThatThrownBy(() -> fixture.associate(uuid(634), NAMESPACE, source))
        .isExactlyInstanceOf(InvalidFreshTenantAssociationException.class)
        .hasMessageContaining("bounded allocation attempts");

    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(64);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
  }

  private static void setTenantReservationSequenceNextValue(DSLContext dsl, long nextValue) {
    dsl.fetchOne(
        "SELECT setval(pg_get_serial_sequence("
            + "'game_session_tenant_scope_reservation', 'game_session_tenant_id'), ?, false)",
        nextValue);
  }

  @Test
  void exactHumanRetryReadsCommittedAssociationAfterAdvisoryWaitOnRepeatableReadDefault()
      throws Exception {
    HumanRetryFixture humanFixture = fixtureWithRepeatableReadDefault();
    Fixture fixture = humanFixture.fixture();
    IntakeReceipt source =
        fixture.registerSource(
            uuid(641), uuid(642), uuid(643), UUID.randomUUID(), 966L, "human-retry-world");
    FreshTenantCreationEvidence creation = creationEvidence(source.source());
    when(humanFixture
            .gameDesignClient()
            .resolveCreation(creation.creationRequestId(), creation.requestDigest()))
        .thenReturn(creation);
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne("SHOW default_transaction_isolation"),
                    "PostgreSQL did not return the transaction isolation setting")
                .get(0, String.class))
        .isEqualTo("repeatable read");

    String canonicalLockKey =
        "fresh-tenant-canonical:" + creation.targetNamespace() + ":" + creation.canonicalTenantId();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Future<FreshTenantAssociation>> retry = new AtomicReference<>();
    try {
      FreshGameSessionTenantAssociation first =
          fixture.transactions.execute(
              status -> {
                fixture.dsl.fetch(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", canonicalLockKey);
                retry.set(
                    executor.submit(
                        () ->
                            fixture.associationRepository.associateFreshTenant(
                                creation.creationRequestId(), creation.requestDigest())));
                awaitAdvisoryWait(fixture, canonicalLockKey);
                return fixture.associateFresh(uuid(640), NAMESPACE, source);
              });

      FreshTenantAssociation actualRetry = retry.get().get(15, TimeUnit.SECONDS);
      assertThat(actualRetry.targetNamespace()).isEqualTo(creation.targetNamespace());
      assertThat(actualRetry.canonicalTenantId()).isEqualTo(creation.canonicalTenantId());
      assertThat(actualRetry.gameSessionTenantId()).isEqualTo(first.legacyGameSessionTenantId());
      assertThat(actualRetry.associationOperationId()).isEqualTo(first.associationOperationId());
      assertThat(actualRetry.sourceEvidence()).isEqualTo(creation);
      assertThat(
              fixture.associationRepository.readByCanonicalTenantId(creation.canonicalTenantId()))
          .contains(actualRetry);
      assertThat(first.sourceEvidence().requestId()).isEqualTo(creation.creationRequestId());
      assertThat(first.sourceEvidence().canonicalTenantId())
          .isEqualTo(creation.canonicalTenantId());
      assertThat(first.sourceEvidence().sourceGameRowId()).isEqualTo(creation.sourceGameRowId());
      assertThat(first.sourceEvidence().sourceGameTenantKey())
          .isEqualTo(creation.sourceGameTenantKey());
      assertThat(first.sourceEvidence().provenanceKind()).isEqualTo(creation.provenanceKind());
      verify(humanFixture.gameDesignClient())
          .resolveCreation(creation.creationRequestId(), creation.requestDigest());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsChangedReceiptMissingReceiptAndNamespaceMismatchBeforeAllocation() {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    UUID associationOperationId = uuid(606);

    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE, changedReceipt(source)))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("Complete creation and authored-source receipts differ");
    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE + "-other", source))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("namespace");

    IntakeReceipt missing = receiptFor(uuid(607), freshSource(uuid(608), uuid(609), TENANT, 960L));
    assertThatThrownBy(() -> fixture.associate(associationOperationId, NAMESPACE, missing))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("is missing");

    assertNoFreshAssociation(fixture);
  }

  @Test
  void rejectsSameOperationWithDifferentSourceOrNamespaceAsAnIdempotencyConflict() {
    Fixture fixture = fixture();
    IntakeReceipt firstSource = fixture.registerFreshSource();
    IntakeReceipt secondSource =
        fixture.registerSource(uuid(610), uuid(611), uuid(612), uuid(613), 962L, "second-world");
    UUID associationOperationId = uuid(615);

    FreshGameSessionTenantAssociation committed =
        fixture.associate(associationOperationId, NAMESPACE, firstSource);
    assertThatThrownBy(() -> fixture.associate(associationOperationId, NAMESPACE, secondSource))
        .isExactlyInstanceOf(FreshTenantAssociationConflictException.class);
    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE + "-other", firstSource))
        .isInstanceOf(InvalidFreshTenantSourceException.class);

    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT legacy_game_session_tenant_id FROM game_session_fresh_tenant_association "
                            + "WHERE association_operation_id = ?",
                        associationOperationId),
                    "expected retained fresh tenant association row")
                .get("legacy_game_session_tenant_id", Long.class))
        .isEqualTo(committed.legacyGameSessionTenantId());
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isEqualTo(1);
  }

  @Test
  void oneConcurrentContenderOwnsTheCanonicalTenantMapping() throws Exception {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Attempt> first =
          executor.submit(() -> contender(fixture, uuid(616), source, ready, start));
      Future<Attempt> second =
          executor.submit(() -> contender(fixture, uuid(617), source, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      Attempt firstResult = first.get(20, TimeUnit.SECONDS);
      Attempt secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult.conflict()).isNotEqualTo(secondResult.conflict());
      FreshGameSessionTenantAssociation owner =
          firstResult.conflict() ? secondResult.association() : firstResult.association();
      assertThat(owner).isNotNull();
      assertThat(
              fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
          .isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
          .isEqualTo(1);
      assertThat(
              fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void retainedSourceProvenanceCannotCreateAFreshTenantMapping() {
    Fixture fixture = fixture();
    IntakeReceipt retainedSource = fixture.registerRetainedSource(uuid(618));
    assertThat(retainedSource.source().provenanceKind()).isEqualTo("RETAINED_GAME_V29");
    String bindingXmin =
        Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT xmin::text AS xmin FROM game_session_authored_world_tenant_source_binding "
                        + "WHERE canonical_tenant_id = ?",
                    retainedSource.source().canonicalTenantId()),
                "expected retained tenant source binding")
            .get("xmin", String.class);
    String intakeXmin =
        Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                        + "WHERE operation_id = ?",
                    retainedSource.operationId()),
                "expected retained source intake")
            .get("xmin", String.class);

    // A complete NEW_GAME_ROW candidate is deliberately paired with the committed retained
    // receipt so the real GS intake reader exercises its provenance refusal before allocation.
    assertThatThrownBy(
            () ->
                fixture.associate(
                    uuid(622),
                    NAMESPACE,
                    retainedSource,
                    creationEvidence(retainedSource.source(), "NEW_GAME_ROW")))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("NEW_GAME_ROW");

    assertThat(
            Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT xmin::text AS xmin, provenance_kind "
                        + "FROM game_session_authored_world_tenant_source_binding "
                        + "WHERE canonical_tenant_id = ?",
                    retainedSource.source().canonicalTenantId()),
                "expected retained tenant source binding after denial"))
        .satisfies(
            row -> {
              assertThat(row.get("xmin", String.class)).isEqualTo(bindingXmin);
              assertThat(row.get("provenance_kind", String.class)).isEqualTo("RETAINED_GAME_V29");
            });
    assertThat(
            Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT xmin::text AS xmin, source_provenance_kind "
                        + "FROM game_session_authored_world_source_intake WHERE operation_id = ?",
                    retainedSource.operationId()),
                "expected retained source intake after denial"))
        .satisfies(
            row -> {
              assertThat(row.get("xmin", String.class)).isEqualTo(intakeXmin);
              assertThat(row.get("source_provenance_kind", String.class))
                  .isEqualTo("RETAINED_GAME_V29");
            });
    assertNoFreshAssociation(fixture);
  }

  private static Attempt contender(
      Fixture fixture,
      UUID associationOperationId,
      IntakeReceipt source,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent tenant association gate was not released");
    }
    try {
      return new Attempt(fixture.associate(associationOperationId, NAMESPACE, source), false);
    } catch (InvalidFreshTenantAssociationException conflict) {
      assertThat(conflict)
          .hasMessageContaining("Association operation differs from its immutable original");
      return new Attempt(null, true);
    }
  }

  private static void assertNoFreshAssociation(Fixture fixture) {
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_scope_reservation"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_tenant_canonical_claim"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_fresh_tenant_association"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer")))).isZero();
  }

  /** Controlled upstream creation receipt; this GS owner test does not execute Game Design. */
  private static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    return creationEvidence(source, source.provenanceKind());
  }

  private static FreshTenantCreationEvidence creationEvidence(
      AuthoredWorldSourceEvidence source, String provenanceKind) {
    UUID request =
        UUID.nameUUIDFromBytes(
            ("creation-request/" + source.canonicalTenantId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID operation =
        UUID.nameUUIDFromBytes(
            ("creation-operation/" + source.canonicalTenantId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            source.targetNamespace(),
            request,
            source.sourceGameTenantKey(),
            source.worldDisplayName(),
            null);
    return new FreshTenantCreationEvidence(
        1,
        source.targetNamespace(),
        request,
        operation,
        requestDigest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            request,
            operation,
            requestDigest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            provenanceKind));
  }

  private static IntakeReceipt changedReceipt(IntakeReceipt receipt) {
    AuthoredWorldSourceEvidence source = receipt.source();
    String changedName = source.worldDisplayName() + " changed";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            changedName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.operationId(),
            sourceRequestDigest,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            changedName,
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    AuthoredWorldSourceEvidence changedSource =
        new AuthoredWorldSourceEvidence(
            source.schemaVersion(),
            source.targetNamespace(),
            source.registrationRequestId(),
            source.operationId(),
            sourceRequestDigest,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            changedName,
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            sourceEvidenceDigest);
    return receiptFor(receipt.operationId(), receipt.intakeRequestId(), changedSource);
  }

  private static IntakeReceipt receiptFor(UUID operationId, AuthoredWorldSourceEvidence source) {
    return receiptFor(operationId, uuid(623), source);
  }

  private static IntakeReceipt receiptFor(
      UUID operationId, UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
    String requestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(intakeRequestId, source);
    return new IntakeReceipt(
        operationId,
        intakeRequestId,
        requestDigest,
        source,
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            operationId, requestDigest, source.evidenceDigest()));
  }

  private static AuthoredWorldSourceEvidence freshSource(
      UUID registrationRequestId,
      UUID sourceOperationId,
      UUID canonicalTenantId,
      long sourceRowId) {
    return freshSource(
        registrationRequestId, sourceOperationId, canonicalTenantId, sourceRowId, "source-world");
  }

  private static AuthoredWorldSourceEvidence freshSource(
      UUID registrationRequestId,
      UUID sourceOperationId,
      UUID canonicalTenantId,
      long sourceRowId,
      String worldSlug) {
    return sourceWithProvenance(
        registrationRequestId,
        sourceOperationId,
        canonicalTenantId,
        sourceRowId,
        worldSlug,
        "NEW_GAME_ROW");
  }

  private static AuthoredWorldSourceEvidence sourceWithProvenance(
      UUID registrationRequestId,
      UUID sourceOperationId,
      UUID canonicalTenantId,
      long sourceRowId,
      String worldSlug,
      String provenanceKind) {
    String tenantSlug = "fresh-tenant-" + canonicalTenantId.toString().substring(0, 8);
    String displayName = "Fresh World";
    String sourceKey = "game-design-source-" + sourceRowId;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            sourceKey,
            provenanceKind);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRowId,
        sourceKey,
        provenanceKind,
        evidenceDigest);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Attempt(FreshGameSessionTenantAssociation association, boolean conflict) {}

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionFreshTenantAssociationRepository associationRepository) {
    IntakeReceipt registerFreshSource() {
      return registerSource(
          INTAKE_REQUEST, REGISTRATION_REQUEST, SOURCE_OPERATION, TENANT, 961L, "source-world");
    }

    IntakeReceipt registerSource(
        UUID intakeRequestId,
        UUID registrationRequestId,
        UUID sourceOperationId,
        UUID canonicalTenantId,
        long sourceRowId,
        String worldSlug) {
      return registerSource(
          intakeRequestId,
          registrationRequestId,
          sourceOperationId,
          canonicalTenantId,
          sourceRowId,
          worldSlug,
          "NEW_GAME_ROW");
    }

    IntakeReceipt registerRetainedSource(UUID intakeRequestId) {
      return registerSource(
          intakeRequestId,
          uuid(625),
          uuid(626),
          uuid(624),
          964L,
          "retained-world",
          "RETAINED_GAME_V29");
    }

    IntakeReceipt registerSource(
        UUID intakeRequestId,
        UUID registrationRequestId,
        UUID sourceOperationId,
        UUID canonicalTenantId,
        long sourceRowId,
        String worldSlug,
        String provenanceKind) {
      AuthoredWorldSourceEvidence source =
          sourceWithProvenance(
              registrationRequestId,
              sourceOperationId,
              canonicalTenantId,
              sourceRowId,
              worldSlug,
              provenanceKind);
      return transactions.execute(status -> sourceRepository.register(intakeRequestId, source));
    }

    FreshGameSessionTenantAssociation associateFresh(
        UUID associationOperationId, String namespace, IntakeReceipt source) {
      return transactions.execute(
          status ->
              associationRepository.associateFresh(
                  associationOperationId, namespace, source, creationEvidence(source.source())));
    }

    FreshGameSessionTenantAssociation associate(
        UUID associationOperationId, String namespace, IntakeReceipt source) {
      return associate(
          associationOperationId, namespace, source, creationEvidence(source.source()));
    }

    FreshGameSessionTenantAssociation associate(
        UUID associationOperationId,
        String namespace,
        IntakeReceipt source,
        FreshTenantCreationEvidence creationEvidence) {
      return Objects.requireNonNull(
          transactions.execute(
              status ->
                  associationRepository.associateFresh(
                      associationOperationId, namespace, source, creationEvidence)),
          "fresh tenant association result");
    }
  }

  private Fixture fixture() {
    String schema = "gs_fresh_tenant_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
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
        .load()
        .migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        dsl,
        transactions,
        new GameSessionAuthoredWorldSourceRepository(dsl),
        new GameSessionFreshTenantAssociationRepository(dsl));
  }

  private HumanRetryFixture fixtureWithRepeatableReadDefault() {
    String schema = "gs_fresh_tenant_rr_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return connection;
          }
        };
    dataSource.setDriverClassName("org.postgresql.Driver");
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
        .load()
        .migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    GameDesignFreshTenantCreationClient gameDesignClient =
        mock(GameDesignFreshTenantCreationClient.class);
    GameSessionFreshTenantAssociationRepository associationRepository =
        new GameSessionFreshTenantAssociationRepository(
            dsl, transactionManager, gameDesignClient, NAMESPACE);
    return new HumanRetryFixture(
        new Fixture(dsl, transactions, sourceRepository, associationRepository), gameDesignClient);
  }

  private static void awaitAdvisoryWait(Fixture fixture, String lockKey) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          Objects.requireNonNull(
                  fixture.dsl.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' "
                          + "AND NOT granted AND objsubid = 1 "
                          + "AND classid = (((hashtextextended(?, 0) >> 32) & 4294967295)::oid) "
                          + "AND objid = ((hashtextextended(?, 0) & 4294967295)::oid))",
                      lockKey,
                      lockKey),
                  "PostgreSQL did not return the advisory-lock wait query result")
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while awaiting the owner advisory lock", interrupted);
      }
    }
    throw new AssertionError("Human retry did not wait for the canonical association owner lock");
  }

  private record HumanRetryFixture(
      Fixture fixture, GameDesignFreshTenantCreationClient gameDesignClient) {}
}
