package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociationConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.InvalidFreshTenantSourceException;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
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
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
    assertThat(first.legacyGameSessionTenantId()).isNotEqualTo(source.source().sourceGameRowId());
    assertThat(first.sourceEvidence().requestId())
        .isEqualTo(source.source().registrationRequestId());
    assertThat(first.sourceEvidence().canonicalTenantId()).isEqualTo(TENANT);
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
  void rejectsChangedReceiptMissingReceiptAndNamespaceMismatchBeforeAllocation() {
    Fixture fixture = fixture();
    IntakeReceipt source = fixture.registerFreshSource();
    UUID associationOperationId = uuid(606);

    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE, changedReceipt(source)))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("differs from the requested exact receipt");
    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE + "-other", source))
        .isInstanceOf(FreshTenantAssociationConflictException.class)
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
        .isInstanceOf(FreshTenantAssociationConflictException.class)
        .hasMessageContaining("different source or namespace");
    assertThatThrownBy(
            () -> fixture.associate(associationOperationId, NAMESPACE + "-other", firstSource))
        .isInstanceOf(FreshTenantAssociationConflictException.class);

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
    assertThat(retainedSource.source().provenanceKind()).isEqualTo("RETAINED_GAME_V30");

    assertThatThrownBy(() -> fixture.associate(uuid(622), NAMESPACE, retainedSource))
        .isInstanceOf(InvalidFreshTenantSourceException.class)
        .hasMessageContaining("NEW_GAME_ROW");

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
    } catch (FreshTenantAssociationConflictException conflict) {
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
          "RETAINED_GAME_V30");
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
              associationRepository.associateFresh(associationOperationId, namespace, source));
    }

    FreshGameSessionTenantAssociation associate(
        UUID associationOperationId, String namespace, IntakeReceipt source) {
      return Objects.requireNonNull(
          associateFresh(associationOperationId, namespace, source),
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
}
