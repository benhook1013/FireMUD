package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.FreshTenantCreationReservation;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationReservationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationResponse;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
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
class GameTenantCreationReservationIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String NAMESPACE = "reservation-test";
  private static final String SOURCE_KEY = "fresh-reservation-source";
  private static final String NAME = "Fresh Realm";
  private static final String DESCRIPTION = "Reserved before Account authorization";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID INITIATING_ACCOUNT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Table<?> QUALIFICATIONS =
      DSL.table(DSL.name("game_tenant_creation_creator_qualifications"));
  private static final Table<?> RESERVATIONS =
      DSL.table(DSL.name("game_tenant_creation_reservations"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void reservationIsRestartSafeExactReadbackAndChangedInputConflicts() throws Exception {
    Fixture fixture = fixture(null);
    FreshTenantCreationReservation first =
        fixture.inTransaction(
            () ->
                fixture.reservations.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));

    assertThat(first.canonicalTenantId()).isNotNull();
    assertThat(first.canonicalTenantId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(first.operationId()).isNotNull();
    assertThat(first.operationId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(fixture.reservations.readExact(first)).contains(first);
    assertThat(fixture.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(fixture.dsl.fetchCount(QUALIFICATIONS)).isZero();
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(RESERVATIONS)
                    .set(DSL.field(DSL.name("name"), String.class), "rewritten name")
                    .where(
                        DSL.field(DSL.name("target_namespace"), String.class)
                            .eq(NAMESPACE)
                            .and(CREATION_REQUEST_ID.eq(REQUEST_ID)))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("reservations are immutable");
    assertThat(fixture.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);

    Fixture restarted = fixture.withFreshRepositories();
    assertThat(
            restarted.inTransaction(
                () ->
                    restarted.reservations.reserve(
                        NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION)))
        .isEqualTo(first);
    assertThatThrownBy(
            () ->
                restarted.inTransaction(
                    () ->
                        restarted.reservations.reserve(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, "changed description")))
        .isInstanceOf(GameTenantCreationReservationRepository.ReservationConflictException.class)
        .hasMessageContaining("changed input");
    assertThat(restarted.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);
    assertThat(restarted.dsl.fetchCount(GAME)).isZero();
  }

  @Test
  void committedReservationReadbackThroughGrpcReceiverUsesExactPostgresTuple() throws Exception {
    Fixture fixture = fixture(null);
    FreshTenantCreationReservation reservation =
        fixture.inTransaction(
            () ->
                fixture.reservations.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));
    TenantIdentityGrpcService receiver =
        new TenantIdentityGrpcService(
            new GameRepository(fixture.dsl),
            mock(TenantAssociationMigrationService.class),
            fixture.creation,
            fixture.reservations,
            mock(GameAuthoredWorldSourceRepository.class),
            mock(GameSessionTenantAssociationRepository.class),
            NAMESPACE);
    ReadFreshTenantCreationReservationRequest request =
        ReadFreshTenantCreationReservationRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId(READ_REQUEST_ID.toString())
            .setCreationRequestId(REQUEST_ID.toString())
            .setExpectedRequestDigest(reservation.requestDigest())
            .setExpectedCreationOperationId(reservation.operationId().toString())
            .setExpectedCanonicalTenantId(reservation.canonicalTenantId().toString())
            .build();
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service")
            .orElseThrow();
    ReservationObserver observer = new ReservationObserver();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> receiver.readFreshTenantCreationReservation(request, observer));

    assertThat(observer.errorCode).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value).isNotNull();
    assertThat(observer.value.getReadRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(observer.value.getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(observer.value.getRequestDigest()).isEqualTo(reservation.requestDigest());
    assertThat(observer.value.getCreationOperationId())
        .isEqualTo(reservation.operationId().toString());
    assertThat(observer.value.getCanonicalTenantId())
        .isEqualTo(reservation.canonicalTenantId().toString());
    assertThat(observer.value.getSourceGameTenantKey()).isEqualTo(SOURCE_KEY);
    assertThat(observer.value.getName()).isEqualTo(NAME);
    assertThat(observer.value.getDescription()).isEqualTo(DESCRIPTION);
    assertThat(fixture.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(fixture.dsl.fetchCount(QUALIFICATIONS)).isZero();
  }

  @Test
  void reservedCreationRetainsExactIdentityAndAtomicallyCompletesQualification() throws Exception {
    Fixture fixture = fixture(null);
    FreshTenantCreationReservation reservation =
        fixture.inTransaction(
            () ->
                fixture.reservations.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));

    FreshTenantCreatorEvidence first =
        fixture.inTransaction(
            () ->
                fixture.creation.createReservedCandidateWithCreator(
                    reservation,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));
    String gameXmin =
        fixture
            .dsl
            .fetch("SELECT xmin::text AS xmin FROM game WHERE tenant_id = ?", SOURCE_KEY)
            .getFirst()
            .get("xmin", String.class);

    assertThat(first.creationEvidence().canonicalTenantId())
        .isEqualTo(reservation.canonicalTenantId());
    assertThat(first.creationEvidence().operationId()).isEqualTo(reservation.operationId());
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(QUALIFICATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.select(STATUS).from(OPERATIONS).fetchSingle(STATUS))
        .isEqualTo("COMPLETED");
    assertThat(fixture.dsl.select(CANONICAL_TENANT_ID).from(GAME).fetchSingle(CANONICAL_TENANT_ID))
        .isEqualTo(reservation.canonicalTenantId());

    FreshTenantCreatorEvidence committedReadback =
        fixture
            .creation
            .readCreatorQualification(
                REQUEST_ID,
                NAMESPACE,
                first.creationEvidence().requestDigest(),
                first.creationEvidence().evidenceDigest(),
                INITIATING_ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST,
                first.evidenceDigest())
            .orElseThrow();
    assertThat(committedReadback).isEqualTo(first);

    Fixture restarted = fixture.withFreshRepositories();
    FreshTenantCreationReservation committedReservation =
        restarted.reservations.readExact(reservation).orElseThrow();
    FreshTenantCreationReservation forgedIdentity =
        new FreshTenantCreationReservation(
            reservation.schemaVersion(),
            reservation.targetNamespace(),
            reservation.creationRequestId(),
            reservation.requestDigest(),
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            reservation.sourceGameTenantKey(),
            reservation.name(),
            reservation.description());
    assertThatThrownBy(
            () ->
                restarted.inTransaction(
                    () ->
                        restarted.creation.createReservedCandidateWithCreator(
                            forgedIdentity,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("does not match the persisted request");
    FreshTenantCreationReservation substitutedInput =
        new FreshTenantCreationReservation(
            reservation.schemaVersion(),
            reservation.targetNamespace(),
            reservation.creationRequestId(),
            GameTenantCreationDigest.requestDigest(
                NAMESPACE, REQUEST_ID, SOURCE_KEY, "Substituted Realm", DESCRIPTION),
            reservation.operationId(),
            reservation.canonicalTenantId(),
            reservation.sourceGameTenantKey(),
            "Substituted Realm",
            reservation.description());
    assertThatThrownBy(
            () ->
                restarted.inTransaction(
                    () ->
                        restarted.creation.createReservedCandidateWithCreator(
                            substitutedInput,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("does not match the persisted request");
    FreshTenantCreatorEvidence exactRetry =
        restarted.inTransaction(
            () ->
                restarted.creation.createReservedCandidateWithCreator(
                    committedReservation,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));
    assertThat(exactRetry).isEqualTo(first);
    assertThatThrownBy(
            () ->
                restarted.inTransaction(
                    () ->
                        restarted.creation.createReservedCandidateWithCreator(
                            committedReservation,
                            UUID.fromString("99999999-9999-4999-8999-999999999999"),
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("changed Account authority");
    assertThat(
            restarted
                .dsl
                .fetch("SELECT xmin::text AS xmin FROM game WHERE tenant_id = ?", SOURCE_KEY)
                .getFirst()
                .get("xmin", String.class))
        .isEqualTo(gameXmin);
    assertThat(restarted.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(restarted.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(restarted.dsl.fetchCount(QUALIFICATIONS)).isEqualTo(1);
  }

  @Test
  void failedQualifiedCommitRollsBackGameAndCompletedOperationButRetainsReservation() {
    Fixture fixture = fixture(null);
    FreshTenantCreationReservation reservation =
        fixture.inTransaction(
            () ->
                fixture.reservations.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));
    fixture.dsl.execute(
        "CREATE FUNCTION reject_reserved_creator_qualification() RETURNS trigger AS $$ "
            + "BEGIN RAISE EXCEPTION 'forced creator qualification failure'; END; "
            + "$$ LANGUAGE plpgsql");
    fixture.dsl.execute(
        "CREATE TRIGGER reject_reserved_creator_qualification "
            + "BEFORE INSERT ON game_tenant_creation_creator_qualifications "
            + "FOR EACH ROW EXECUTE FUNCTION reject_reserved_creator_qualification()");

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.creation.createReservedCandidateWithCreator(
                            reservation,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("forced creator qualification failure");
    assertThat(fixture.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(fixture.dsl.fetchCount(QUALIFICATIONS)).isZero();
  }

  @Test
  void concurrentExactReservedCreationRetriesConvergeOnOneIdentity() throws Exception {
    Fixture fixture = fixture(null);
    FreshTenantCreationReservation reservation =
        fixture.inTransaction(
            () ->
                fixture.reservations.reserve(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));
    CountDownLatch firstCreated = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger firstBackendPid = new AtomicInteger();
    AtomicInteger secondBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FreshTenantCreatorEvidence> first =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        FreshTenantCreatorEvidence result =
                            fixture.creation.createReservedCandidateWithCreator(
                                reservation,
                                INITIATING_ACCOUNT_ID,
                                AUTHORIZATION_OPERATION_ID,
                                AUTHORIZATION_DIGEST);
                        firstBackendPid.set(currentBackendPid(fixture.dsl));
                        firstCreated.countDown();
                        awaitLatch(allowFirstCommit);
                        return result;
                      }));
      assertThat(firstCreated.await(10, TimeUnit.SECONDS)).isTrue();
      Future<FreshTenantCreatorEvidence> second =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        secondBackendPid.set(currentBackendPid(fixture.dsl));
                        secondStarted.countDown();
                        return fixture.creation.createReservedCandidateWithCreator(
                            reservation,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST);
                      }));
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseBlocking(fixture.dataSource, secondBackendPid.get(), firstBackendPid.get());
      allowFirstCommit.countDown();

      FreshTenantCreatorEvidence firstResult = first.get(10, TimeUnit.SECONDS);
      FreshTenantCreatorEvidence secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(secondResult).isEqualTo(firstResult);
      assertThat(secondResult.creationEvidence().canonicalTenantId())
          .isEqualTo(reservation.canonicalTenantId());
      assertThat(secondResult.creationEvidence().operationId())
          .isEqualTo(reservation.operationId());
      assertThat(fixture.dsl.fetchCount(RESERVATIONS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(QUALIFICATIONS)).isEqualTo(1);
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void additiveReservationMigrationPreservesPriorCompletedReceiptBytes() throws Exception {
    Fixture fixture = fixture(MigrationVersion.fromVersion("46"));
    FreshTenantCreatorEvidence before =
        fixture.inTransaction(
            () ->
                fixture.creation.createCandidateWithCreator(
                    NAMESPACE,
                    REQUEST_ID,
                    SOURCE_KEY,
                    NAME,
                    DESCRIPTION,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));
    String sourceDigestBefore =
        fixture
            .dsl
            .select(DSL.field(DSL.name("evidence_digest"), String.class))
            .from(OPERATIONS)
            .where(CREATION_REQUEST_ID.eq(REQUEST_ID))
            .fetchSingle()
            .value1();
    String creatorDigestBefore =
        fixture
            .dsl
            .select(DSL.field(DSL.name("evidence_digest"), String.class))
            .from(QUALIFICATIONS)
            .where(OPERATION_ID.eq(before.creationEvidence().operationId()))
            .fetchSingle()
            .value1();
    fixture.migrate(null);

    Fixture migrated = fixture.withFreshRepositories();
    FreshTenantCreatorEvidence after =
        migrated
            .creation
            .readCreatorQualification(
                REQUEST_ID,
                NAMESPACE,
                before.creationEvidence().requestDigest(),
                before.creationEvidence().evidenceDigest(),
                INITIATING_ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST,
                before.evidenceDigest())
            .orElseThrow();
    assertThat(after).isEqualTo(before);
    assertThat(
            migrated
                .dsl
                .select(DSL.field(DSL.name("evidence_digest"), String.class))
                .from(OPERATIONS)
                .where(CREATION_REQUEST_ID.eq(REQUEST_ID))
                .fetchSingle()
                .value1())
        .isEqualTo(sourceDigestBefore);
    assertThat(
            migrated
                .dsl
                .select(DSL.field(DSL.name("evidence_digest"), String.class))
                .from(QUALIFICATIONS)
                .where(OPERATION_ID.eq(before.creationEvidence().operationId()))
                .fetchSingle()
                .value1())
        .isEqualTo(creatorDigestBefore);
    assertThat(migrated.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(migrated.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(migrated.dsl.fetchCount(QUALIFICATIONS)).isEqualTo(1);
    assertThat(migrated.dsl.fetchCount(RESERVATIONS)).isZero();
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_tenant_reservation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Fixture fixture = new Fixture(dataSource, schema);
    fixture.migrate(target);
    return fixture.withFreshRepositories();
  }

  private int currentBackendPid(DSLContext dsl) {
    var row = dsl.fetchOne("SELECT pg_backend_pid() AS pid");
    if (row == null) {
      throw new IllegalStateException("PostgreSQL returned no backend id row");
    }
    Integer pid = row.get("pid", Integer.class);
    if (pid == null || pid <= 0) {
      throw new IllegalStateException("PostgreSQL returned an invalid backend id");
    }
    return pid;
  }

  private void awaitDatabaseBlocking(
      DriverManagerDataSource dataSource, int blockedBackendPid, int blockerBackendPid)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
      statement.setInt(1, blockerBackendPid);
      statement.setInt(2, blockedBackendPid);
      while (System.nanoTime() < deadline) {
        try (var result = statement.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            return;
          }
        }
        Thread.sleep(25L);
      }
    }
    throw new AssertionError(
        "PostgreSQL did not report the duplicate reserved creation blocked on the first writer");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to commit the first reserved creation");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting to commit reserved creation", exception);
    }
  }

  private static final class Fixture {
    private final DriverManagerDataSource dataSource;
    private final String schema;
    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private GameTenantCreationRepository creation;
    private GameTenantCreationReservationRepository reservations;

    private Fixture(DriverManagerDataSource dataSource, String schema) {
      this.dataSource = dataSource;
      this.schema = schema;
      dataSource.setSchema(schema);
      transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      dsl =
          DSL.using(new TransactionAwareDataSourceProxy(dataSource), org.jooq.SQLDialect.POSTGRES);
    }

    private void migrate(MigrationVersion target) {
      var configuration =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .table(FLYWAY_TABLE)
              .placeholders(Map.of("serviceSchema", schema))
              .locations("classpath:db/migration");
      if (target != null) {
        configuration.target(target);
      }
      configuration.load().migrate();
    }

    private Fixture withFreshRepositories() {
      GameRepository gameRepository = new GameRepository(dsl);
      creation = new GameTenantCreationRepository(dsl, gameRepository);
      reservations = new GameTenantCreationReservationRepository(dsl);
      return this;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
      return transactionTemplate.execute(status -> action.get());
    }
  }

  private static final class ReservationObserver
      implements StreamObserver<ReadFreshTenantCreationReservationResponse> {
    private ReadFreshTenantCreationReservationResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ReadFreshTenantCreationReservationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
