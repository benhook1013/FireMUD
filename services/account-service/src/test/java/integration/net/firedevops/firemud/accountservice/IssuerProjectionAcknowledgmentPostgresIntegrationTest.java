package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository.Acknowledgment;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionAcknowledgmentService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
import net.firedevops.firemud.common.account.authority.IssuerAuthorityProjectionV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IssuerProjectionAcknowledgmentPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "issuer_ack_pg";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String OTHER_ISSUER_ID = "https://other.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final String GAME_SESSION_ID = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_GAME_SESSION_ID =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final String APPLIED_AT = "2026-10-03T00:00:00Z";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HexFormat HEX = HexFormat.of();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void acknowledgesProvedZeroBaselineWithExactBytesAndNoSourceMutation() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT) + " \n";
    SourceHistory before = sourceHistory(fixture);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);

    Acknowledgment acknowledgment = acknowledge(fixture, capture, projection);

    assertThat(capture.capturedSource().issuerAuthGeneration()).isEqualTo(1L);
    assertThat(capture.capturedSource().sourceVersion()).isEqualTo(1L);
    assertThat(capture.capturedSource().outboxSequence()).isZero();
    assertThat(capture.capturedSource().latestEvent()).isEmpty();
    assertAcknowledgment(capture, projection, acknowledgment);
    assertStoredAcknowledgment(fixture, capture, acknowledgment, projection);
    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertThat(sourceHistory(fixture)).isEqualTo(before);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  @Test
  void acknowledgesCompletePositiveCheckpointAndRetainsSourceHistory() {
    Fixture fixture = newFixture();
    IssuerGenerationAuthorityEvent sourceEvent = seedNonSequenceAlignedPositiveState(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    SourceHistory before = sourceHistory(fixture);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);

    Acknowledgment acknowledgment = acknowledge(fixture, capture, projection);

    IssuerAuthoritySnapshot captured = capture.capturedSource();
    assertThat(captured.issuerAuthGeneration()).isEqualTo(4L);
    assertThat(captured.sourceVersion()).isEqualTo(8L);
    assertThat(captured.outboxSequence()).isEqualTo(1L);
    assertThat(captured.issuerAuthGeneration()).isNotEqualTo(captured.outboxSequence() + 1L);
    assertThat(captured.latestEvent()).isPresent();
    assertSameEvent(sourceEvent, captured.latestEvent().orElseThrow());
    assertAcknowledgment(capture, projection, acknowledgment);
    assertStoredAcknowledgment(fixture, capture, acknowledgment, projection);
    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertThat(sourceHistory(fixture)).isEqualTo(before);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  @Test
  void concurrentExactAcknowledgmentsReturnOneDurableOriginalResult() throws Exception {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    SourceHistory before = sourceHistory(fixture);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Acknowledgment> first =
          executor.submit(
              () -> {
                await(start);
                return acknowledge(fixture, capture, projection);
              });
      Future<Acknowledgment> second =
          executor.submit(
              () -> {
                await(start);
                return acknowledge(fixture, capture, projection);
              });
      start.countDown();
      Acknowledgment firstResult = first.get(45, TimeUnit.SECONDS);
      Acknowledgment secondResult = second.get(45, TimeUnit.SECONDS);

      assertSameAcknowledgment(firstResult, secondResult);
      assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
      assertStoredAcknowledgment(fixture, capture, firstResult, projection);
      assertThat(sourceHistory(fixture)).isEqualTo(before);
      assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void exactRetryAfterLaterSourceAdvanceReturnsOriginalAcknowledgment() {
    Fixture fixture = newFixture();
    seedNonSequenceAlignedPositiveState(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);
    SourceHistory beforeAcknowledgment = sourceHistory(fixture);

    Acknowledgment original = acknowledge(fixture, capture, projection);
    assertThat(sourceHistory(fixture)).isEqualTo(beforeAcknowledgment);
    IssuerGenerationAuthorityEvent later =
        fixture.source().advance(ISSUER_ID, UUID.randomUUID(), 4L, 8L);
    SourceHistory afterAdvance = sourceHistory(fixture);
    Acknowledgment retry = acknowledge(fixture, capture, projection);

    assertThat(later.issuerAuthGeneration()).isEqualTo("5");
    assertThat(later.sourceVersion()).isEqualTo("9");
    assertThat(later.outboxSequence()).isEqualTo("2");
    assertSameAcknowledgment(original, retry);
    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertStoredAcknowledgment(fixture, capture, original, projection);
    assertThat(sourceHistory(fixture)).isEqualTo(afterAdvance);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  @Test
  void committedAcknowledgmentWithMissingPostCommitReadbackRecoversOnExactRetry() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);
    AtomicInteger acknowledgmentReads = new AtomicInteger();
    AccountIssuerProjectionAcknowledgmentRepository hideOneCommittedRead =
        new AccountIssuerProjectionAcknowledgmentRepository(fixture.transactionDsl()) {
          @Override
          public Optional<Acknowledgment> findByCaptureOperationId(UUID captureOperationId) {
            if (acknowledgmentReads.incrementAndGet() == 3) {
              return Optional.empty();
            }
            return super.findByCaptureOperationId(captureOperationId);
          }
        };
    AccountIssuerProjectionAcknowledgmentService service =
        fixture.acknowledgmentService(hideOneCommittedRead);
    SourceHistory before = sourceHistory(fixture);

    assertThatThrownBy(() -> acknowledge(service, capture, projection))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Post-commit issuer installation acknowledgment readback is missing");

    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    Acknowledgment original = readAcknowledgment(fixture, capture.operationId());
    assertAcknowledgment(capture, projection, original);
    Acknowledgment recovered = acknowledge(service, capture, projection);
    assertSameAcknowledgment(original, recovered);
    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertStoredAcknowledgment(fixture, capture, original, projection);
    assertThat(sourceHistory(fixture)).isEqualTo(before);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  @Test
  void staleFirstAcknowledgmentAndChangedBindingsOrProjectionEvidenceDenyWithoutInsertion() {
    Fixture staleFixture = newFixture();
    seedNonSequenceAlignedPositiveState(staleFixture);
    Receipt staleCapture =
        staleFixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String staleProjection = projectionJson(staleCapture.capturedSource(), APPLIED_AT);
    staleFixture.source().advance(ISSUER_ID, UUID.randomUUID(), 4L, 8L);
    SourceHistory staleHistory = sourceHistory(staleFixture);
    List<CaptureEvidence> staleCaptures = captureHistory(staleFixture);
    assertThatThrownBy(() -> acknowledge(staleFixture, staleCapture, staleProjection))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("captured source to remain current");
    assertThat(countAcknowledgments(staleFixture)).isZero();
    assertThat(sourceHistory(staleFixture)).isEqualTo(staleHistory);
    assertThat(captureHistory(staleFixture)).isEqualTo(staleCaptures);

    Fixture fixture = newFixture();
    IssuerGenerationAuthorityEvent sourceEvent = seedNonSequenceAlignedPositiveState(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    SourceHistory beforeDenials = sourceHistory(fixture);
    List<CaptureEvidence> capturesBeforeDenials = captureHistory(fixture);

    assertDenied(
        fixture,
        AccountIssuerAuthorityEventProducer.IssuerMismatchException.class,
        () -> acknowledge(fixture, OTHER_ISSUER_ID, GAME_SESSION_ID, capture, projection));
    assertDenied(
        fixture,
        SecurityException.class,
        () -> acknowledge(fixture, ISSUER_ID, OTHER_GAME_SESSION_ID, capture, projection));
    assertDenied(
        fixture,
        IdempotencyConflictException.class,
        () ->
            acknowledge(
                fixture,
                ISSUER_ID,
                GAME_SESSION_ID,
                UUID.randomUUID(),
                capture.requestId(),
                capture.requestDigestVersion(),
                capture.requestDigest(),
                projection));
    assertDenied(
        fixture,
        IllegalStateException.class,
        () ->
            acknowledge(
                fixture,
                ISSUER_ID,
                GAME_SESSION_ID,
                capture.operationId(),
                UUID.randomUUID(),
                capture.requestDigestVersion(),
                capture.requestDigest(),
                projection));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                ISSUER_ID,
                GAME_SESSION_ID,
                capture.operationId(),
                capture.requestId(),
                2,
                capture.requestDigest(),
                projection));
    assertDenied(
        fixture,
        IdempotencyConflictException.class,
        () ->
            acknowledge(
                fixture,
                ISSUER_ID,
                GAME_SESSION_ID,
                capture.operationId(),
                capture.requestId(),
                capture.requestDigestVersion(),
                "f".repeat(64),
                projection));

    String incompleteProjection = "{\"schemaVersion\":\"game-session-auth-issuer-projection/v1\"}";
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () -> acknowledge(fixture, capture, incompleteProjection));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                capture,
                projectionJson(
                    ISSUER_ID,
                    "5",
                    "1",
                    STREAM_KEY,
                    APPLIED_AT,
                    sealEvent(sourceEvent, UUID.randomUUID(), 5L, 8L, 1L))));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                capture,
                projectionJson(
                    ISSUER_ID,
                    "4",
                    "2",
                    STREAM_KEY,
                    APPLIED_AT,
                    sealEvent(sourceEvent, UUID.randomUUID(), 4L, 8L, 2L))));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                capture,
                projectionJson(
                    ISSUER_ID, "4", "1", STREAM_KEY + "/wrong", APPLIED_AT, sourceEvent)));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                capture,
                projectionJson(
                    ISSUER_ID,
                    "4",
                    "1",
                    STREAM_KEY,
                    APPLIED_AT,
                    sealEvent(sourceEvent, UUID.randomUUID(), 4L, 8L, 1L))));
    assertDenied(
        fixture,
        IllegalArgumentException.class,
        () ->
            acknowledge(
                fixture,
                capture,
                projectionJson(
                    ISSUER_ID,
                    "4",
                    "1",
                    STREAM_KEY,
                    APPLIED_AT,
                    sealEvent(sourceEvent, UUID.randomUUID(), 4L, 9L, 1L))));
    assertThat(countAcknowledgments(fixture)).isZero();
    assertThat(sourceHistory(fixture)).isEqualTo(beforeDenials);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBeforeDenials);

    Acknowledgment original = acknowledge(fixture, capture, projection);
    String changedAppliedAt = projectionJson(capture.capturedSource(), "2026-10-03T00:00:01Z");
    assertDenied(
        fixture,
        IdempotencyConflictException.class,
        () -> acknowledge(fixture, capture, changedAppliedAt));
    assertDenied(
        fixture,
        IdempotencyConflictException.class,
        () -> acknowledge(fixture, capture, projection + " "));
    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertStoredAcknowledgment(fixture, capture, original, projection);
    assertThat(sourceHistory(fixture)).isEqualTo(beforeDenials);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBeforeDenials);
  }

  @Test
  void ambientTransactionsAndDirectSqlGuardsRejectAcknowledgmentMutation() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    Receipt badHashCapture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    Receipt nullCapture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    Receipt longCapture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    SourceHistory before = sourceHistory(fixture);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          acknowledge(fixture, capture, projection);
                          return null;
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must own its Account transactions without an ambient transaction");
    assertThat(countAcknowledgments(fixture)).isZero();

    Acknowledgment mismatchedCapture = candidate(capture, projection);
    assertThatThrownBy(
            () ->
                insertRawAcknowledgment(
                    fixture,
                    mismatchedCapture,
                    UUID.randomUUID(),
                    mismatchedCapture.installedProjectionUtf8(),
                    mismatchedCapture.installedProjectionSha256()))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            failure ->
                assertPostgresConstraint(failure, "account_issuer_install_ack_capture_binding"));

    Acknowledgment badHashCandidate = candidate(badHashCapture, projection);
    assertSqlConstraint(
        () ->
            insertRawAcknowledgment(
                fixture,
                badHashCandidate,
                badHashCandidate.captureRequestId(),
                badHashCandidate.installedProjectionUtf8(),
                new byte[32]),
        "account_issuer_install_ack_binding_check");

    Acknowledgment nullCandidate = candidate(nullCapture, projection);
    assertThatThrownBy(
            () ->
                insertRawAcknowledgment(
                    fixture,
                    nullCandidate,
                    nullCandidate.captureRequestId(),
                    null,
                    nullCandidate.installedProjectionSha256()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("installed_projection_json");

    Acknowledgment longCandidate = candidate(longCapture, projection);
    byte[] oversized = new byte[Acknowledgment.MAX_INSTALLED_PROJECTION_UTF8_BYTES + 1];
    Arrays.fill(oversized, (byte) 'x');
    assertSqlConstraint(
        () ->
            insertRawAcknowledgment(
                fixture,
                longCandidate,
                longCandidate.captureRequestId(),
                oversized,
                longCandidate.installedProjectionSha256()),
        "account_issuer_install_ack_binding_check");

    assertThat(countAcknowledgments(fixture)).isZero();
    assertThat(sourceHistory(fixture)).isEqualTo(before);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  @Test
  void storedAcknowledgmentRejectsDirectUpdateAndDelete() {
    Fixture fixture = newFixture();
    seedIssuer(fixture);
    Receipt capture =
        fixture.captureService().capture(ISSUER_ID, GAME_SESSION_ID, UUID.randomUUID());
    String projection = projectionJson(capture.capturedSource(), APPLIED_AT);
    Acknowledgment acknowledgment = acknowledge(fixture, capture, projection);
    SourceHistory before = sourceHistory(fixture);
    List<CaptureEvidence> capturesBefore = captureHistory(fixture);

    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_issuer_projection_installation_acknowledgments "
                            + "SET request_digest_version = request_digest_version "
                            + "WHERE acknowledgment_id = ?",
                        acknowledgment.acknowledgmentId()))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            failure -> assertPostgresConstraint(failure, "account_issuer_install_ack_immutable"));
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_issuer_projection_installation_acknowledgments "
                            + "WHERE acknowledgment_id = ?",
                        acknowledgment.acknowledgmentId()))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            failure -> assertPostgresConstraint(failure, "account_issuer_install_ack_immutable"));

    assertThat(countAcknowledgments(fixture)).isEqualTo(1L);
    assertStoredAcknowledgment(fixture, capture, acknowledgment, projection);
    assertThat(sourceHistory(fixture)).isEqualTo(before);
    assertThat(captureHistory(fixture)).isEqualTo(capturesBefore);
  }

  private Fixture newFixture() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    AccountIssuerProjectionReconciliationRepository captures =
        new AccountIssuerProjectionReconciliationRepository(transactionDsl);
    AccountIssuerProjectionAcknowledgmentRepository acknowledgments =
        new AccountIssuerProjectionAcknowledgmentRepository(transactionDsl);
    AccountIssuerAuthorityEventProducer source =
        new AccountIssuerAuthorityEventProducer(
            ISSUER_ID, generations, outbox, transactionDsl, transactionManager);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        generations,
        outbox,
        captures,
        acknowledgments,
        source);
  }

  private void seedIssuer(Fixture fixture) {
    transaction(
        fixture.transaction(),
        () -> {
          fixture.generations().initializeIssuerIfAbsent(ISSUER_ID);
          return null;
        });
  }

  private IssuerGenerationAuthorityEvent seedNonSequenceAlignedPositiveState(Fixture fixture) {
    fixture
        .setupDsl()
        .execute(
            "INSERT INTO account_authority_generations "
                + "(scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES ('ISSUER', ?, NULL, NULL, 3, 7)",
            ISSUER_ID);
    UUID sourceRequestId = UUID.randomUUID();
    transaction(
        fixture.transaction(),
        () -> {
          ScopeState current = fixture.generations().read(AuthorityScope.issuer(ISSUER_ID));
          ScopeState advanced = fixture.generations().advance(current, null);
          assertThat(advanced.generation()).isEqualTo(4L);
          assertThat(advanced.sourceVersion()).isEqualTo(8L);
          appendSeedEvent(fixture, sourceRequestId, 4L, 8L);
          return null;
        });
    return fixture.source().readCurrent(ISSUER_ID).latestEvent().orElseThrow();
  }

  private void appendSeedEvent(
      Fixture fixture, UUID requestId, long generation, long sourceVersion) {
    String requestText = requestId.toString();
    String eventId = "account-issuer-authority-event-v1:" + requestText;
    fixture
        .outbox()
        .append(
            STREAM_KEY,
            requestText,
            sequence -> {
              IssuerGenerationAuthorityEvent event =
                  IssuerGenerationAuthorityEventV1Codec.seal(
                      Map.of(
                          "schemaVersion",
                          IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                          "eventType",
                          IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                          "eventId",
                          eventId,
                          "requestId",
                          requestText,
                          "issuerId",
                          ISSUER_ID,
                          "sourceScope",
                          "issuer/" + ISSUER_ID,
                          "outboxStreamKey",
                          STREAM_KEY,
                          "outboxSequence",
                          Long.toString(sequence),
                          "issuerAuthGeneration",
                          Long.toString(generation),
                          "sourceVersion",
                          Long.toString(sourceVersion)));
              return new AccountAuthorityOutboxRepository.EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
  }

  private IssuerGenerationAuthorityEvent sealEvent(
      IssuerGenerationAuthorityEvent source,
      UUID requestId,
      long generation,
      long sourceVersion,
      long sequence) {
    String requestText = requestId.toString();
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + requestText,
            "requestId",
            requestText,
            "issuerId",
            source.issuerId(),
            "sourceScope",
            source.sourceScope(),
            "outboxStreamKey",
            source.outboxStreamKey(),
            "outboxSequence",
            Long.toString(sequence),
            "issuerAuthGeneration",
            Long.toString(generation),
            "sourceVersion",
            Long.toString(sourceVersion)));
  }

  private String projectionJson(IssuerAuthoritySnapshot source, String appliedAt) {
    return projectionJson(
        source.issuerId(),
        Long.toString(source.issuerAuthGeneration()),
        Long.toString(source.outboxSequence()),
        source.outboxStreamKey(),
        appliedAt,
        source.latestEvent().orElse(null));
  }

  private String projectionJson(
      String issuerId,
      String generation,
      String sequence,
      String streamKey,
      String appliedAt,
      IssuerGenerationAuthorityEvent event) {
    Map<String, Object> projection = new LinkedHashMap<>();
    projection.put("schemaVersion", IssuerAuthorityProjectionV1Codec.SCHEMA_VERSION);
    projection.put("issuerId", issuerId);
    projection.put("lastAppliedIssuerGeneration", generation);
    projection.put("lastAppliedSourceOutboxSequence", sequence);
    projection.put("outboxStreamKey", streamKey);
    projection.put("appliedAt", appliedAt);
    if (event == null) {
      projection.put("appliedSourceEvidence", Map.of());
    } else {
      projection.put("lastAppliedSourceEventId", event.eventId());
      projection.put("lastAppliedSourceEventDigest", event.eventDigest());
      projection.put(
          "appliedSourceEvidence",
          Map.of(sequence, new String(event.canonicalJsonUtf8(), StandardCharsets.UTF_8)));
    }
    try {
      return JSON.writeValueAsString(projection);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Issuer projection test JSON could not be encoded", exception);
    }
  }

  private Acknowledgment acknowledge(Fixture fixture, Receipt capture, String projectionJson) {
    return acknowledge(fixture.acknowledgmentService(), capture, projectionJson);
  }

  private Acknowledgment acknowledge(
      AccountIssuerProjectionAcknowledgmentService service,
      Receipt capture,
      String projectionJson) {
    return acknowledge(
        service,
        ISSUER_ID,
        GAME_SESSION_ID,
        capture.operationId(),
        capture.requestId(),
        capture.requestDigestVersion(),
        capture.requestDigest(),
        projectionJson);
  }

  private Acknowledgment acknowledge(
      Fixture fixture,
      String issuerId,
      String callerIdentity,
      Receipt capture,
      String projectionJson) {
    return acknowledge(
        fixture.acknowledgmentService(),
        issuerId,
        callerIdentity,
        capture.operationId(),
        capture.requestId(),
        capture.requestDigestVersion(),
        capture.requestDigest(),
        projectionJson);
  }

  private Acknowledgment acknowledge(
      Fixture fixture,
      String issuerId,
      String callerIdentity,
      UUID operationId,
      UUID requestId,
      int captureDigestVersion,
      String captureDigest,
      String projectionJson) {
    return acknowledge(
        fixture.acknowledgmentService(),
        issuerId,
        callerIdentity,
        operationId,
        requestId,
        captureDigestVersion,
        captureDigest,
        projectionJson);
  }

  private Acknowledgment acknowledge(
      AccountIssuerProjectionAcknowledgmentService service,
      String issuerId,
      String callerIdentity,
      UUID operationId,
      UUID requestId,
      int captureDigestVersion,
      String captureDigest,
      String projectionJson) {
    return service.acknowledge(
        issuerId,
        callerIdentity,
        operationId,
        requestId,
        captureDigestVersion,
        captureDigest,
        projectionJson);
  }

  private void assertDenied(Fixture fixture, Class<? extends Throwable> expected, Runnable action) {
    long acknowledgmentsBefore = countAcknowledgments(fixture);
    assertThatThrownBy(action::run).isInstanceOf(expected);
    assertThat(countAcknowledgments(fixture)).isEqualTo(acknowledgmentsBefore);
  }

  private Acknowledgment candidate(Receipt capture, String projectionJson) {
    byte[] projectionBytes = projectionJson.getBytes(StandardCharsets.UTF_8);
    return new Acknowledgment(
        UUID.randomUUID(),
        capture.operationId(),
        capture.requestId(),
        capture.issuerId(),
        capture.callerWorkloadIdentity(),
        capture.projectionKey(),
        capture.requestDigestVersion(),
        capture.requestDigest(),
        Acknowledgment.REQUEST_DIGEST_VERSION,
        expectedAcknowledgmentDigest(capture, projectionJson),
        projectionBytes,
        sha256(projectionBytes));
  }

  private void assertAcknowledgment(
      Receipt capture, String projectionJson, Acknowledgment acknowledgment) {
    assertThat(acknowledgment.acknowledgmentId()).isNotEqualTo(NIL_UUID);
    assertThat(acknowledgment.acknowledgmentId())
        .isNotEqualTo(capture.operationId())
        .isNotEqualTo(capture.requestId());
    assertThat(acknowledgment.captureOperationId()).isEqualTo(capture.operationId());
    assertThat(acknowledgment.captureRequestId()).isEqualTo(capture.requestId());
    assertThat(acknowledgment.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(acknowledgment.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_ID);
    assertThat(acknowledgment.projectionKey()).isEqualTo(PROJECTION_KEY);
    assertThat(acknowledgment.captureRequestDigestVersion()).isEqualTo(1);
    assertThat(acknowledgment.captureRequestDigest()).isEqualTo(capture.requestDigest());
    assertThat(acknowledgment.requestDigestVersion()).isEqualTo(1);
    assertThat(acknowledgment.requestDigest())
        .isEqualTo(expectedAcknowledgmentDigest(capture, projectionJson));
    byte[] projectionBytes = projectionJson.getBytes(StandardCharsets.UTF_8);
    assertThat(acknowledgment.installedProjectionUtf8()).containsExactly(projectionBytes);
    assertThat(acknowledgment.installedProjectionSha256()).containsExactly(sha256(projectionBytes));
  }

  private String expectedAcknowledgmentDigest(Receipt capture, String projectionJson) {
    String[] fields = {
      "issuer-projection-installation-ack/v1",
      "ISSUER_PROJECTION_INSTALLATION_ACK",
      capture.issuerId(),
      capture.callerWorkloadIdentity(),
      capture.projectionKey(),
      capture.operationId().toString(),
      capture.requestId().toString(),
      Integer.toString(capture.requestDigestVersion()),
      capture.requestDigest(),
      projectionJson
    };
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.writeBytes(bytes);
    }
    return HEX.formatHex(sha256(framed.toByteArray()));
  }

  private void assertStoredAcknowledgment(
      Fixture fixture, Receipt capture, Acknowledgment expected, String projectionJson) {
    Acknowledgment repositoryReadback = readAcknowledgment(fixture, capture.operationId());
    assertSameAcknowledgment(expected, repositoryReadback);
    assertAcknowledgment(capture, projectionJson, repositoryReadback);

    Record row =
        fixture
            .setupDsl()
            .fetchOne(
                "SELECT acknowledgment_id, capture_operation_id, capture_request_id, issuer_id, "
                    + "caller_workload_identity, projection_key, capture_request_digest_version, "
                    + "capture_request_digest, request_digest_version, request_digest, "
                    + "installed_projection_json, installed_projection_sha256 "
                    + "FROM account_issuer_projection_installation_acknowledgments "
                    + "WHERE capture_operation_id = ?",
                capture.operationId());
    assertThat(row).isNotNull();
    assertThat(row.get("acknowledgment_id", UUID.class)).isEqualTo(expected.acknowledgmentId());
    assertThat(row.get("capture_operation_id", UUID.class))
        .isEqualTo(expected.captureOperationId());
    assertThat(row.get("capture_request_id", UUID.class)).isEqualTo(expected.captureRequestId());
    assertThat(row.get("issuer_id", String.class)).isEqualTo(expected.issuerId());
    assertThat(row.get("caller_workload_identity", String.class))
        .isEqualTo(expected.callerWorkloadIdentity());
    assertThat(row.get("projection_key", String.class)).isEqualTo(expected.projectionKey());
    assertThat(row.get("capture_request_digest_version", Integer.class)).isEqualTo(1);
    assertThat(HEX.formatHex(row.get("capture_request_digest", byte[].class)))
        .isEqualTo(expected.captureRequestDigest());
    assertThat(row.get("request_digest_version", Integer.class)).isEqualTo(1);
    assertThat(HEX.formatHex(row.get("request_digest", byte[].class)))
        .isEqualTo(expected.requestDigest());
    assertThat(row.get("installed_projection_json", byte[].class))
        .containsExactly(projectionJson.getBytes(StandardCharsets.UTF_8));
    assertThat(row.get("installed_projection_sha256", byte[].class))
        .containsExactly(sha256(projectionJson.getBytes(StandardCharsets.UTF_8)));
  }

  private Acknowledgment readAcknowledgment(Fixture fixture, UUID captureOperationId) {
    return transaction(
        fixture.transaction(),
        () -> fixture.acknowledgments().findByCaptureOperationId(captureOperationId).orElseThrow());
  }

  private void assertSameAcknowledgment(Acknowledgment expected, Acknowledgment actual) {
    assertThat(actual.acknowledgmentId()).isEqualTo(expected.acknowledgmentId());
    assertThat(actual.captureOperationId()).isEqualTo(expected.captureOperationId());
    assertThat(actual.captureRequestId()).isEqualTo(expected.captureRequestId());
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.callerWorkloadIdentity()).isEqualTo(expected.callerWorkloadIdentity());
    assertThat(actual.projectionKey()).isEqualTo(expected.projectionKey());
    assertThat(actual.captureRequestDigestVersion())
        .isEqualTo(expected.captureRequestDigestVersion());
    assertThat(actual.captureRequestDigest()).isEqualTo(expected.captureRequestDigest());
    assertThat(actual.requestDigestVersion()).isEqualTo(expected.requestDigestVersion());
    assertThat(actual.requestDigest()).isEqualTo(expected.requestDigest());
    assertThat(actual.installedProjectionUtf8())
        .containsExactly(expected.installedProjectionUtf8());
    assertThat(actual.installedProjectionSha256())
        .containsExactly(expected.installedProjectionSha256());
  }

  private void insertRawAcknowledgment(
      Fixture fixture,
      Acknowledgment acknowledgment,
      UUID requestId,
      byte[] installedProjection,
      byte[] installedProjectionSha256) {
    fixture
        .setupDsl()
        .execute(
            "INSERT INTO account_issuer_projection_installation_acknowledgments "
                + "(acknowledgment_id, capture_operation_id, capture_request_id, issuer_id, "
                + "caller_workload_identity, projection_key, capture_request_digest_version, "
                + "capture_request_digest, request_digest_version, request_digest, "
                + "installed_projection_json, installed_projection_sha256) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            acknowledgment.acknowledgmentId(),
            acknowledgment.captureOperationId(),
            requestId,
            acknowledgment.issuerId(),
            acknowledgment.callerWorkloadIdentity(),
            acknowledgment.projectionKey(),
            acknowledgment.captureRequestDigestVersion(),
            HEX.parseHex(acknowledgment.captureRequestDigest()),
            acknowledgment.requestDigestVersion(),
            HEX.parseHex(acknowledgment.requestDigest()),
            installedProjection,
            installedProjectionSha256);
  }

  private void assertSqlConstraint(Runnable action, String constraint) {
    assertThatThrownBy(action::run)
        .isInstanceOf(DataAccessException.class)
        .satisfies(failure -> assertPostgresConstraint(failure, constraint));
  }

  private void assertPostgresConstraint(Throwable failure, String expectedConstraint) {
    Throwable cause = failure;
    while (cause != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    PSQLException postgresFailure = (PSQLException) cause;
    assertThat(postgresFailure.getServerErrorMessage().getConstraint())
        .isEqualTo(expectedConstraint);
  }

  private SourceHistory sourceHistory(Fixture fixture) {
    Record authority =
        fixture
            .setupDsl()
            .fetchOne(
                "SELECT generation, source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                ISSUER_ID);
    List<OutboxStream> streams = new ArrayList<>();
    for (Record row :
        fixture
            .setupDsl()
            .fetch(
                "SELECT outbox_stream_key, last_sequence FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ?",
                STREAM_KEY)) {
      streams.add(
          new OutboxStream(
              row.get("outbox_stream_key", String.class), row.get("last_sequence", Long.class)));
    }
    List<OutboxEvent> events = new ArrayList<>();
    for (Record row :
        fixture
            .setupDsl()
            .fetch(
                "SELECT outbox_stream_key, outbox_sequence, request_id, event_id, event_digest, payload "
                    + "FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                    + "ORDER BY outbox_sequence",
                STREAM_KEY)) {
      events.add(
          new OutboxEvent(
              row.get("outbox_stream_key", String.class),
              row.get("outbox_sequence", Long.class),
              row.get("request_id", String.class),
              row.get("event_id", String.class),
              row.get("event_digest", String.class),
              new String(row.get("payload", byte[].class), StandardCharsets.UTF_8)));
    }
    return new SourceHistory(
        authority == null ? null : authority.get("generation", Long.class),
        authority == null ? null : authority.get("source_version", Long.class),
        streams,
        events);
  }

  private long countAcknowledgments(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_issuer_projection_installation_acknowledgments")
            .fetchOne(0, Long.class));
  }

  private List<CaptureEvidence> captureHistory(Fixture fixture) {
    List<CaptureEvidence> captures = new ArrayList<>();
    for (Record row :
        fixture
            .setupDsl()
            .fetch(
                "SELECT operation_id, request_id, issuer_id, caller_workload_identity, "
                    + "projection_key, request_digest_version, request_digest, "
                    + "issuer_auth_generation, source_version, outbox_stream_key, outbox_sequence, "
                    + "event_outbox_sequence, event_id, event_digest, event_payload "
                    + "FROM account_issuer_projection_reconciliation_receipts "
                    + "ORDER BY issuer_id, request_id")) {
      byte[] eventPayload = row.get("event_payload", byte[].class);
      captures.add(
          new CaptureEvidence(
              row.get("operation_id", UUID.class),
              row.get("request_id", UUID.class),
              row.get("issuer_id", String.class),
              row.get("caller_workload_identity", String.class),
              row.get("projection_key", String.class),
              row.get("request_digest_version", Integer.class),
              HEX.formatHex(row.get("request_digest", byte[].class)),
              row.get("issuer_auth_generation", Long.class),
              row.get("source_version", Long.class),
              row.get("outbox_stream_key", String.class),
              row.get("outbox_sequence", Long.class),
              row.get("event_outbox_sequence", Long.class),
              row.get("event_id", String.class),
              row.get("event_digest", String.class),
              eventPayload == null ? null : new String(eventPayload, StandardCharsets.UTF_8)));
    }
    return captures;
  }

  private void assertSameEvent(
      IssuerGenerationAuthorityEvent expected, IssuerGenerationAuthorityEvent actual) {
    assertThat(actual.schemaVersion()).isEqualTo(expected.schemaVersion());
    assertThat(actual.eventType()).isEqualTo(expected.eventType());
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.sourceScope()).isEqualTo(expected.sourceScope());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.issuerAuthGeneration()).isEqualTo(expected.issuerAuthGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Issuer acknowledgment PostgreSQL barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Issuer acknowledgment PostgreSQL proof was interrupted", interrupted);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountIssuerProjectionReconciliationRepository captures,
      AccountIssuerProjectionAcknowledgmentRepository acknowledgments,
      AccountIssuerAuthorityEventProducer source) {
    private AccountIssuerProjectionReconciliationService captureService() {
      return new AccountIssuerProjectionReconciliationService(
          ISSUER_ID, GAME_SESSION_ID, source, captures, transactionManager);
    }

    private AccountIssuerProjectionAcknowledgmentService acknowledgmentService() {
      return acknowledgmentService(acknowledgments);
    }

    private AccountIssuerProjectionAcknowledgmentService acknowledgmentService(
        AccountIssuerProjectionAcknowledgmentRepository repository) {
      return new AccountIssuerProjectionAcknowledgmentService(
          ISSUER_ID, GAME_SESSION_ID, captureService(), repository, transactionManager);
    }
  }

  private record OutboxStream(String streamKey, Long lastSequence) {}

  private record OutboxEvent(
      String streamKey,
      Long sequence,
      String requestId,
      String eventId,
      String eventDigest,
      String canonicalJson) {}

  private record SourceHistory(
      Long generation, Long sourceVersion, List<OutboxStream> streams, List<OutboxEvent> events) {}

  private record CaptureEvidence(
      UUID operationId,
      UUID requestId,
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      Integer requestDigestVersion,
      String requestDigest,
      Long issuerAuthGeneration,
      Long sourceVersion,
      String streamKey,
      Long sequence,
      Long eventSequence,
      String eventId,
      String eventDigest,
      String canonicalEventJson) {}
}
