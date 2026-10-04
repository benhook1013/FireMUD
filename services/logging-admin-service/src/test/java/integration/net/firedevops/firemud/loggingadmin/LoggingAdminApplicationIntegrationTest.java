package net.firedevops.firemud.loggingadmin;

import static net.firedevops.firemud.loggingadmin.jooq.tables.AccountAuditReceipts.ACCOUNT_AUDIT_RECEIPTS;
import static net.firedevops.firemud.loggingadmin.jooq.tables.LogEvents.LOG_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.client.AccountClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.AccountAuditReceiptsRecord;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.LogEventsRecord;
import net.firedevops.firemud.loggingadmin.service.AuditStorageUnavailableException;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = LoggingAdminServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "spring.grpc.server.port=0",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
class LoggingAdminApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String ACCOUNT_UUID = "550e8400-e29b-41d4-a716-446655440000";
  private static final JwtUtil JWT_UTIL =
      new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 3600000L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "logging_admin_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;

  @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

  @MockitoBean private AccountClient accountClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private LogEventService logEventService;
  @Autowired private LogQueryService logQueryService;

  @Test
  void accountAuditReceiptAndProjectionShareStableIdentityAcrossRetryAndConflict() {
    String eventId = "82a6f475-0baa-4fd0-a86b-580f286a9940";
    String payload = "{\"accountId\":91,\"auditMarker\":\"tenant-retry-proof\"}";
    CreateLogEventRequest request = tenantAuditRequest(eventId, payload);

    var accepted = logEventService.createLogEvent(request);
    var duplicate = logEventService.createLogEvent(request);
    var conflict =
        logEventService.createLogEvent(tenantAuditRequest(eventId, "{\"changed\":true}"));

    assertThat(accepted.status()).isEqualTo(AccountAuditReceiptStatus.COMMITTED);
    assertThat(accepted.outcome()).isEqualTo(AccountAuditReceiptOutcome.ACCEPTED);
    assertThat(duplicate.outcome()).isEqualTo(AccountAuditReceiptOutcome.DUPLICATE);
    assertThat(duplicate.receiptId()).isEqualTo(accepted.receiptId());
    assertThat(duplicate.logEventId()).isEqualTo(accepted.logEventId());
    assertThat(conflict.status()).isEqualTo(AccountAuditReceiptStatus.CONFLICT);
    assertThat(conflict.outcome()).isEqualTo(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT);
    assertThat(conflict.logEventId()).isEqualTo(accepted.logEventId());

    LogEventsRecord projection =
        dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(accepted.logEventId())).fetchOne();
    AccountAuditReceiptsRecord receipt =
        dsl.selectFrom(ACCOUNT_AUDIT_RECEIPTS)
            .where(ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId))
            .fetchOne();
    assertThat(projection).isNotNull();
    assertThat(projection.getScope()).isEqualTo("tenant");
    assertThat(projection.getTenantId()).isEqualTo(42L);
    assertThat(projection.getAuditEventId()).isEqualTo(eventId);
    assertThat(projection.getType()).isEqualTo("ACCOUNT_AUDIT");
    assertThat(projection.getMessage()).isEqualTo("Account audit event " + eventId);
    assertThat(projection.getMessage()).doesNotContain("accountId", "tenant-retry-proof");
    assertThat(receipt).isNotNull();
    assertThat(receipt.getLogEventId()).isEqualTo(projection.getId());
    assertThat(receipt.getPayload()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
    assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isOne();
    assertThat(
            dsl.fetchCount(
                ACCOUNT_AUDIT_RECEIPTS, ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId)))
        .isOne();
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(42L, eventId)))
        .containsExactly("Account audit event " + eventId);
  }

  @Test
  void canonicalUuidReceiptIsIsolatedFromRetainedNumericIdentityAndMinimizesSafely() {
    String eventId = "a4e2f840-e844-46a7-8d30-8d17a220ce44";
    UUID tenantUuid = UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21");
    String payload = "{\"accountId\":95,\"auditMarker\":\"uuid-retry-proof\"}";
    CreateLogEventRequest numericRequest = tenantAuditRequest(eventId, payload);
    CreateLogEventRequest uuidRequest = uuidTenantAuditRequest(eventId, tenantUuid, payload);

    var retainedNumeric = logEventService.createLogEvent(numericRequest);
    var acceptedUuid = logEventService.createLogEvent(uuidRequest);
    var duplicateUuid = logEventService.createLogEvent(uuidRequest);
    var conflictUuid =
        logEventService.createLogEvent(
            uuidTenantAuditRequest(eventId, tenantUuid, "{\"changed\":true}"));
    var readback = logEventService.readLogEventReceipt(uuidRequest);

    assertThat(acceptedUuid.tenantIdentityVersion()).isEqualTo(2);
    assertThat(acceptedUuid.tenantId()).isNull();
    assertThat(acceptedUuid.tenantUuid()).isEqualTo(tenantUuid);
    assertThat(duplicateUuid.outcome()).isEqualTo(AccountAuditReceiptOutcome.DUPLICATE);
    assertThat(duplicateUuid.receiptId()).isEqualTo(acceptedUuid.receiptId());
    assertThat(conflictUuid.status()).isEqualTo(AccountAuditReceiptStatus.CONFLICT);
    assertThat(readback.tenantUuid()).isEqualTo(tenantUuid);
    assertThat(readback.logEventId()).isEqualTo(acceptedUuid.logEventId());
    assertThat(acceptedUuid.logEventId()).isNotEqualTo(retainedNumeric.logEventId());

    LogEventsRecord numericProjection =
        dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(retainedNumeric.logEventId())).fetchOne();
    LogEventsRecord uuidProjection =
        dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(acceptedUuid.logEventId())).fetchOne();
    AccountAuditReceiptsRecord uuidReceipt =
        dsl.selectFrom(ACCOUNT_AUDIT_RECEIPTS)
            .where(ACCOUNT_AUDIT_RECEIPTS.RECEIPT_ID.eq(UUID.fromString(acceptedUuid.receiptId())))
            .fetchOne();
    assertThat(numericProjection.getTenantId()).isEqualTo(42L);
    assertThat(numericProjection.getTenantKey()).isEqualTo(42L);
    assertThat(numericProjection.getTenantIdentityVersion()).isEqualTo(1);
    assertThat(numericProjection.getTenantUuid()).isNull();
    assertThat(uuidProjection.getTenantId()).isNull();
    assertThat(uuidProjection.getTenantKey()).isNull();
    assertThat(uuidProjection.getTenantIdentityVersion()).isEqualTo(2);
    assertThat(uuidProjection.getTenantUuid()).isEqualTo(tenantUuid);
    assertThat(uuidProjection.getMessage()).isEqualTo("Account audit event " + eventId);
    assertThat(uuidProjection.getMessage()).doesNotContain("accountId", "uuid-retry-proof");
    assertThat(uuidReceipt.getTenantId()).isNull();
    assertThat(uuidReceipt.getTenantKey()).isNull();
    assertThat(uuidReceipt.getTenantUuid()).isEqualTo(tenantUuid);
    assertThat(uuidReceipt.getPayload()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
    assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isEqualTo(2);
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(42L, eventId)))
        .containsExactly("Account audit event " + eventId);

    dsl.update(ACCOUNT_AUDIT_RECEIPTS)
        .set(ACCOUNT_AUDIT_RECEIPTS.PAYLOAD, (byte[]) null)
        .set(ACCOUNT_AUDIT_RECEIPTS.STATUS, "MINIMIZED")
        .set(ACCOUNT_AUDIT_RECEIPTS.OUTCOME, "NON_REPLAYABLE")
        .where(ACCOUNT_AUDIT_RECEIPTS.RECEIPT_ID.eq(UUID.fromString(acceptedUuid.receiptId())))
        .execute();

    var minimizedReadback = logEventService.readLogEventReceipt(uuidRequest);
    assertThat(minimizedReadback.tenantIdentityVersion()).isEqualTo(2);
    assertThat(minimizedReadback.tenantUuid()).isEqualTo(tenantUuid);
    assertThat(minimizedReadback.status()).isEqualTo(AccountAuditReceiptStatus.MINIMIZED);
    assertThat(minimizedReadback.outcome()).isEqualTo(AccountAuditReceiptOutcome.NON_REPLAYABLE);
    assertThat(uuidProjection.getMessage()).doesNotContain(payload);
  }

  @Test
  void malformedPersistedIdentityCannotBypassDatabaseChecksOrGrowProjectionRows() {
    int before = dsl.fetchCount(LOG_EVENTS);

    assertThatThrownBy(
            () ->
                insertMalformedProjection(
                    "b7abbe36-8a74-4527-b0d7-c3117de24001", null, 42L, null, 42L))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_log_events_scope_tenant");
    assertThatThrownBy(
            () ->
                insertMalformedProjection(
                    "b7abbe36-8a74-4527-b0d7-c3117de24002", 1, 42L, null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_log_events_scope_tenant");
    assertThatThrownBy(
            () ->
                insertMalformedProjection(
                    "b7abbe36-8a74-4527-b0d7-c3117de24003", 2, null, new UUID(0L, 0L), null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_log_events_scope_tenant");
    assertThatThrownBy(() -> insertMalformedProjection(null, null, 42L, null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_log_events_scope_tenant");

    assertThat(dsl.fetchCount(LOG_EVENTS)).isEqualTo(before);
  }

  private void insertMalformedProjection(
      String eventId, Integer identityVersion, Long tenantId, UUID tenantUuid, Long tenantKey) {
    dsl.insertInto(LOG_EVENTS)
        .set(LOG_EVENTS.SCOPE, "tenant")
        .set(LOG_EVENTS.TENANT_IDENTITY_VERSION, identityVersion)
        .set(LOG_EVENTS.TENANT_ID, tenantId)
        .set(LOG_EVENTS.TENANT_KEY, tenantKey)
        .set(LOG_EVENTS.TENANT_UUID, tenantUuid)
        .set(LOG_EVENTS.AUDIT_EVENT_ID, eventId)
        .set(LOG_EVENTS.TYPE, "ACCOUNT_AUDIT")
        .set(LOG_EVENTS.MESSAGE, "Account audit event " + eventId)
        .set(LOG_EVENTS.TIMESTAMP, LocalDateTime.now())
        .execute();
  }

  @Test
  void auditEventIdHasDistinctPlatformAndTenantReceiptIdentities() {
    String eventId = "9a25cba0-b6f3-40c2-82ec-4ffcad2f2081";
    String payload = "{\"accountId\":96,\"auditMarker\":\"scoped-identity-proof\"}";
    List<CreateLogEventRequest> requests =
        List.of(
            platformAuditRequest(eventId, payload),
            auditRequest(AccountAuditScope.TENANT, 42L, eventId, payload),
            auditRequest(AccountAuditScope.TENANT, 43L, eventId, payload));
    List<AccountAuditReceiptDto> accepted =
        requests.stream().map(logEventService::createLogEvent).toList();

    assertThat(accepted)
        .extracting(AccountAuditReceiptDto::outcome)
        .containsOnly(AccountAuditReceiptOutcome.ACCEPTED);
    assertThat(accepted.stream().map(AccountAuditReceiptDto::receiptId).toList())
        .doesNotHaveDuplicates();
    assertThat(accepted.stream().map(AccountAuditReceiptDto::logEventId).toList())
        .doesNotHaveDuplicates();

    for (int index = 0; index < requests.size(); index++) {
      CreateLogEventRequest request = requests.get(index);
      AccountAuditReceiptDto original = accepted.get(index);

      assertDuplicateOf(logEventService.createLogEvent(request), original);
      assertDuplicateOf(logEventService.readLogEventReceipt(request), original);

      CreateLogEventRequest changedPayload =
          auditRequest(
              request.scope(), request.tenantId(), request.auditEventId(), "{\"changed\":true}");
      assertConflictWithOriginal(logEventService.createLogEvent(changedPayload), original);

      CreateLogEventRequest changedMetadata =
          withOccurredAt(request, request.occurredAt().plusSeconds(1));
      assertConflictWithOriginal(logEventService.createLogEvent(changedMetadata), original);

      LogEventsRecord projection =
          dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(original.logEventId())).fetchOne();
      AccountAuditReceiptsRecord receipt =
          dsl.selectFrom(ACCOUNT_AUDIT_RECEIPTS)
              .where(ACCOUNT_AUDIT_RECEIPTS.RECEIPT_ID.eq(UUID.fromString(original.receiptId())))
              .fetchOne();
      assertThat(projection).isNotNull();
      assertThat(projection.getScope()).isEqualTo(request.scope().databaseValue());
      assertThat(projection.getTenantId()).isEqualTo(request.tenantId());
      assertThat(projection.getAuditEventId()).isEqualTo(eventId);
      assertThat(receipt).isNotNull();
      assertThat(receipt.getScope()).isEqualTo(request.scope().databaseValue());
      assertThat(receipt.getTenantId()).isEqualTo(request.tenantId());
      assertThat(receipt.getLogEventId()).isEqualTo(original.logEventId());
    }

    assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isEqualTo(3);
    assertThat(
            dsl.fetchCount(
                ACCOUNT_AUDIT_RECEIPTS, ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId)))
        .isEqualTo(3);
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(42L, eventId)))
        .containsExactly("Account audit event " + eventId);
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(43L, eventId)))
        .containsExactly("Account audit event " + eventId);
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(44L, eventId))).isEmpty();
  }

  @Test
  void platformAuditProjectionHasNoTenantAndIsExcludedFromTenantQuery() {
    String eventId = "41613d4b-3e66-4c9a-9f1c-7e02f54d0c34";
    String marker = "platform-only-marker";
    String payload = "{\"accountId\":92,\"auditMarker\":\"" + marker + "\"}";
    CreateLogEventRequest request = platformAuditRequest(eventId, payload);

    var accepted = logEventService.createLogEvent(request);

    LogEventsRecord projection =
        dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(accepted.logEventId())).fetchOne();
    assertThat(projection).isNotNull();
    assertThat(projection.getScope()).isEqualTo("platform");
    assertThat(projection.getTenantId()).isNull();
    assertThat(projection.getAuditEventId()).isEqualTo(eventId);
    assertThat(projection.getMessage()).isEqualTo("Account audit event " + eventId);
    assertThat(projection.getMessage()).doesNotContain(marker);
    assertThat(logQueryService.queryLogs(new QueryLogsRequest(42L, eventId))).isEmpty();
  }

  @Test
  void receiptFailureRollsBackTheAlreadyInsertedAuditProjection() {
    String eventId = "f15e1f7a-f2ee-4f90-b83d-77695bcac86c";
    dsl.execute(
        "CREATE OR REPLACE FUNCTION fail_test_account_audit_receipt() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.audit_event_id = '"
            + eventId
            + "' THEN RAISE EXCEPTION 'injected receipt insert failure'; END IF; RETURN NEW; END $$");
    dsl.execute(
        "CREATE TRIGGER fail_test_account_audit_receipt BEFORE INSERT ON account_audit_receipts "
            + "FOR EACH ROW EXECUTE FUNCTION fail_test_account_audit_receipt()");
    try {
      assertThatThrownBy(
              () ->
                  logEventService.createLogEvent(tenantAuditRequest(eventId, "{\"accountId\":93}")))
          .isInstanceOf(AuditStorageUnavailableException.class);
      assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isZero();
      assertThat(
              dsl.fetchCount(
                  ACCOUNT_AUDIT_RECEIPTS, ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId)))
          .isZero();
    } finally {
      dsl.execute(
          "DROP TRIGGER IF EXISTS fail_test_account_audit_receipt ON account_audit_receipts");
      dsl.execute("DROP FUNCTION IF EXISTS fail_test_account_audit_receipt()");
    }
  }

  @Test
  void canonicalUuidReceiptFailureRollsBackItsProjection() {
    String eventId = "58d4fb8a-0910-42ab-8af1-80cf8c81b26a";
    UUID tenantUuid = UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21");
    dsl.execute(
        "CREATE OR REPLACE FUNCTION fail_test_account_audit_receipt() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.audit_event_id = '"
            + eventId
            + "' THEN RAISE EXCEPTION 'injected receipt insert failure'; END IF; RETURN NEW; END $$");
    dsl.execute(
        "CREATE TRIGGER fail_test_account_audit_receipt BEFORE INSERT ON account_audit_receipts "
            + "FOR EACH ROW EXECUTE FUNCTION fail_test_account_audit_receipt()");
    try {
      assertThatThrownBy(
              () ->
                  logEventService.createLogEvent(
                      uuidTenantAuditRequest(
                          eventId, tenantUuid, "{\"auditMarker\":\"rollback\"}")))
          .isInstanceOf(AuditStorageUnavailableException.class);
      assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isZero();
      assertThat(
              dsl.fetchCount(
                  ACCOUNT_AUDIT_RECEIPTS, ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId)))
          .isZero();
    } finally {
      dsl.execute(
          "DROP TRIGGER IF EXISTS fail_test_account_audit_receipt ON account_audit_receipts");
      dsl.execute("DROP FUNCTION IF EXISTS fail_test_account_audit_receipt()");
    }
  }

  @Test
  void concurrentExactRetriesCreateOneProjectionAndReturnTheSameIdentifiers() throws Exception {
    String eventId = "4b7d9ee5-62f5-44dd-8b35-dcf6490d7404";
    CreateLogEventRequest request =
        uuidTenantAuditRequest(
            eventId, UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21"), "{\"accountId\":94}");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AccountAuditReceiptDto> first = executor.submit(() -> createAfter(start, request));
      Future<AccountAuditReceiptDto> second = executor.submit(() -> createAfter(start, request));
      start.countDown();
      var firstResult = first.get(20, TimeUnit.SECONDS);
      var secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult.logEventId()).isEqualTo(secondResult.logEventId());
      assertThat(firstResult.receiptId()).isEqualTo(secondResult.receiptId());
      assertThat(java.util.List.of(firstResult.outcome(), secondResult.outcome()))
          .containsExactlyInAnyOrder(
              AccountAuditReceiptOutcome.ACCEPTED, AccountAuditReceiptOutcome.DUPLICATE);
      assertThat(dsl.fetchCount(LOG_EVENTS, LOG_EVENTS.AUDIT_EVENT_ID.eq(eventId))).isOne();
      assertThat(
              dsl.fetchCount(
                  ACCOUNT_AUDIT_RECEIPTS, ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(eventId)))
          .isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void v3ThroughV6PreserveRetainedRowsAndEnforceNewReceiptAndProjectionChecks() {
    String schema = "logging_admin_migration_" + UUID.randomUUID().toString().replace("-", "");
    UUID retainedInvalidReceiptId = UUID.fromString("30000000-0000-4000-8000-000000000002");
    String retainedInvalidDigest = "sha256:" + "g".repeat(64);
    dsl.execute("CREATE SCHEMA " + schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .target(MigrationVersion.fromVersion("2"))
          .load()
          .migrate();

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (tenant_id, type, message, timestamp, account_id) "
              + "VALUES (?, ?, ?, ?, ?)",
          73L,
          "PAYMENT",
          "legacy payment log",
          LocalDateTime.of(2025, 3, 1, 12, 0),
          12L);
      seedV2Receipt(
          schema,
          UUID.fromString("30000000-0000-4000-8000-000000000001"),
          "tenant",
          73L,
          "10000000-0000-4000-8000-000000000001",
          Instant.parse("2025-03-01T12:00:01.123456Z"),
          false);
      seedV2Receipt(
          schema,
          retainedInvalidReceiptId,
          "tenant",
          73L,
          "10000000-0000-4000-8000-000000000002",
          Instant.parse("2025-03-01T12:00:02.987654Z"),
          true);
      seedV2Receipt(
          schema,
          UUID.fromString("40000000-0000-4000-8000-000000000001"),
          "platform",
          null,
          "20000000-0000-4000-8000-000000000001",
          Instant.parse("2025-03-01T12:00:03.123456Z"),
          false);
      seedV2Receipt(
          schema,
          UUID.fromString("40000000-0000-4000-8000-000000000002"),
          "platform",
          null,
          "20000000-0000-4000-8000-000000000002",
          Instant.parse("2025-03-01T12:00:04.987654Z"),
          true);

      dsl.execute(
          "UPDATE "
              + schema
              + ".account_audit_receipts SET payload_digest = ? WHERE receipt_id = ?",
          retainedInvalidDigest,
          retainedInvalidReceiptId);
      assertThat(
              dsl.fetchSingle(
                      "SELECT payload_digest FROM "
                          + schema
                          + ".account_audit_receipts WHERE receipt_id = ?",
                      retainedInvalidReceiptId)
                  .get(0, String.class))
          .isEqualTo(retainedInvalidDigest);

      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .target(MigrationVersion.fromVersion("3"))
          .load()
          .migrate();

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (scope, tenant_id, tenant_key, audit_event_id, type, message, timestamp) "
              + "VALUES ('tenant', 0, 0, '90000000-0000-4000-8000-000000000001', "
              + "'ACCOUNT_AUDIT', 'retained zero tenant projection', TIMESTAMP '2025-03-01 15:00:00'), "
              + "('tenant', -9, -9, '90000000-0000-4000-8000-000000000002', "
              + "'ACCOUNT_AUDIT', 'retained negative tenant projection', TIMESTAMP '2025-03-01 15:00:01'), "
              + "('tenant', 0, 0, NULL, 'PAYMENT', 'retained zero tenant legacy row', "
              + "TIMESTAMP '2025-03-01 15:00:02'), "
              + "('tenant', -9, 0, NULL, 'PAYMENT', 'retained negative tenant legacy row', "
              + "TIMESTAMP '2025-03-01 15:00:03')");

      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .load()
          .migrate();

      assertThat(
              dsl.fetchSingle(
                      "SELECT COUNT(*) FROM " + schema + ".log_events WHERE message = ?",
                      "legacy payment log")
                  .get(0, Integer.class))
          .isEqualTo(1);
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_key FROM " + schema + ".log_events WHERE message = ?",
                      "legacy payment log")
                  .get(0, Long.class))
          .isEqualTo(0L);
      assertThat(
              dsl.fetch(
                  "SELECT tenant_id, tenant_key, audit_event_id, type, message, timestamp "
                      + "FROM "
                      + schema
                      + ".log_events WHERE message IN (?, ?, ?, ?) ORDER BY message",
                  "retained negative tenant legacy row",
                  "retained negative tenant projection",
                  "retained zero tenant legacy row",
                  "retained zero tenant projection"))
          .extracting(
              row ->
                  List.of(
                      row.get("tenant_id", Long.class),
                      row.get("tenant_key", Long.class),
                      row.get("audit_event_id", String.class) == null
                          ? "<null>"
                          : row.get("audit_event_id", String.class),
                      row.get("type", String.class),
                      row.get("message", String.class),
                      row.get("timestamp", LocalDateTime.class)))
          .containsExactly(
              List.of(
                  -9L,
                  0L,
                  "<null>",
                  "PAYMENT",
                  "retained negative tenant legacy row",
                  LocalDateTime.of(2025, 3, 1, 15, 0, 3)),
              List.of(
                  -9L,
                  -9L,
                  "90000000-0000-4000-8000-000000000002",
                  "ACCOUNT_AUDIT",
                  "retained negative tenant projection",
                  LocalDateTime.of(2025, 3, 1, 15, 0, 1)),
              List.of(
                  0L,
                  0L,
                  "<null>",
                  "PAYMENT",
                  "retained zero tenant legacy row",
                  LocalDateTime.of(2025, 3, 1, 15, 0, 2)),
              List.of(
                  0L,
                  0L,
                  "90000000-0000-4000-8000-000000000001",
                  "ACCOUNT_AUDIT",
                  "retained zero tenant projection",
                  LocalDateTime.of(2025, 3, 1, 15, 0)));
      assertThat(
              dsl.fetch(
                  "SELECT tenant_identity_version, tenant_uuid FROM "
                      + schema
                      + ".log_events WHERE message IN (?, ?) ORDER BY message",
                  "retained negative tenant projection",
                  "retained zero tenant projection"))
          .allSatisfy(
              row -> {
                assertThat(row.get("tenant_identity_version", Integer.class)).isEqualTo(1);
                assertThat(row.get("tenant_uuid", UUID.class)).isNull();
              });
      assertThat(
              dsl.fetchSingle("SELECT COUNT(*) FROM " + schema + ".account_audit_receipts")
                  .get(0, Integer.class))
          .isEqualTo(4);
      assertThat(
              dsl.fetchSingle(
                      "SELECT COUNT(*) FROM "
                          + schema
                          + ".account_audit_receipts AS receipt LEFT JOIN "
                          + schema
                          + ".log_events AS event ON event.id = receipt.log_event_id "
                          + "WHERE event.id IS NULL")
                  .get(0, Integer.class))
          .isEqualTo(0);
      assertBackfilledReceipt(
          schema,
          "30000000-0000-4000-8000-000000000001",
          "tenant",
          73L,
          "10000000-0000-4000-8000-000000000001",
          "COMMITTED",
          "ACCEPTED",
          digest("{\"retained\":true}".getBytes(StandardCharsets.UTF_8)),
          "2025-03-01T12:00:01.123456");
      assertBackfilledReceipt(
          schema,
          "30000000-0000-4000-8000-000000000002",
          "tenant",
          73L,
          "10000000-0000-4000-8000-000000000002",
          "MINIMIZED",
          "NON_REPLAYABLE",
          retainedInvalidDigest,
          "2025-03-01T12:00:02.987654");
      assertBackfilledReceipt(
          schema,
          "40000000-0000-4000-8000-000000000001",
          "platform",
          null,
          "20000000-0000-4000-8000-000000000001",
          "COMMITTED",
          "ACCEPTED",
          digest("{\"retained\":true}".getBytes(StandardCharsets.UTF_8)),
          "2025-03-01T12:00:03.123456");
      assertBackfilledReceipt(
          schema,
          "40000000-0000-4000-8000-000000000002",
          "platform",
          null,
          "20000000-0000-4000-8000-000000000002",
          "MINIMIZED",
          "NON_REPLAYABLE",
          digest("{\"retained\":true}".getBytes(StandardCharsets.UTF_8)),
          "2025-03-01T12:00:04.987654");

      assertThat(
              dsl.fetchSingle(
                      "SELECT payload_digest FROM "
                          + schema
                          + ".account_audit_receipts WHERE receipt_id = ?",
                      retainedInvalidReceiptId)
                  .get(0, String.class))
          .isEqualTo(retainedInvalidDigest);
      assertThat(
              dsl.fetchSingle(
                      "SELECT convalidated FROM pg_constraint "
                          + "WHERE conrelid = to_regclass(?) AND conname = ?",
                      schema + ".account_audit_receipts",
                      "chk_account_audit_receipt_digest_format")
                  .get(0, Boolean.class))
          .isFalse();
      assertThat(
              dsl.fetchSingle(
                      "SELECT convalidated FROM pg_constraint "
                          + "WHERE conrelid = to_regclass(?) AND conname = ?",
                      schema + ".log_events",
                      "chk_log_events_tenant_audit_positive_tenant_id")
                  .get(0, Boolean.class))
          .isFalse();

      for (String invalidDigest : List.of("sha256:" + "A".repeat(64), "sha256:" + "g".repeat(64))) {
        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "UPDATE "
                            + schema
                            + ".account_audit_receipts SET payload_digest = ? "
                            + "WHERE receipt_id = ?",
                        invalidDigest,
                        retainedInvalidReceiptId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_account_audit_receipt_digest_format");
      }
      assertThat(
              dsl.fetchSingle(
                      "SELECT payload_digest FROM "
                          + schema
                          + ".account_audit_receipts WHERE receipt_id = ?",
                      retainedInvalidReceiptId)
                  .get(0, String.class))
          .isEqualTo(retainedInvalidDigest);

      byte[] validPayload = "{\"afterMigration\":true}".getBytes(StandardCharsets.UTF_8);
      assertThatThrownBy(
              () ->
                  insertMigrationReceipt(
                      schema,
                      UUID.fromString("80000000-0000-4000-8000-000000000001"),
                      "70000000-0000-4000-8000-000000000001",
                      "sha256:" + "A".repeat(64),
                      validPayload,
                      "COMMITTED",
                      "ACCEPTED"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("chk_account_audit_receipt_digest_format");
      assertThatThrownBy(
              () ->
                  insertMigrationReceipt(
                      schema,
                      UUID.fromString("80000000-0000-4000-8000-000000000002"),
                      "70000000-0000-4000-8000-000000000002",
                      "sha256:" + "g".repeat(64),
                      validPayload,
                      "COMMITTED",
                      "ACCEPTED"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("chk_account_audit_receipt_digest_format");

      insertMigrationReceipt(
          schema,
          UUID.fromString("80000000-0000-4000-8000-000000000003"),
          "70000000-0000-4000-8000-000000000003",
          digest(validPayload),
          validPayload,
          "COMMITTED",
          "ACCEPTED");
      byte[] minimizedPayload = "retained digest evidence".getBytes(StandardCharsets.UTF_8);
      insertMigrationReceipt(
          schema,
          UUID.fromString("80000000-0000-4000-8000-000000000004"),
          "70000000-0000-4000-8000-000000000004",
          digest(minimizedPayload),
          null,
          "MINIMIZED",
          "NON_REPLAYABLE");
      assertThat(
              dsl.fetchSingle(
                      "SELECT status FROM "
                          + schema
                          + ".account_audit_receipts WHERE receipt_id = ?",
                      UUID.fromString("80000000-0000-4000-8000-000000000003"))
                  .get(0, String.class))
          .isEqualTo("COMMITTED");
      var minimizedReceipt =
          dsl.fetchSingle(
              "SELECT status, outcome, payload IS NULL AS payload_is_null FROM "
                  + schema
                  + ".account_audit_receipts WHERE receipt_id = ?",
              UUID.fromString("80000000-0000-4000-8000-000000000004"));
      assertThat(minimizedReceipt.get("status", String.class)).isEqualTo("MINIMIZED");
      assertThat(minimizedReceipt.get("outcome", String.class)).isEqualTo("NON_REPLAYABLE");
      assertThat(minimizedReceipt.get("payload_is_null", Boolean.class)).isTrue();

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, tenant_uuid, "
              + "type, message, timestamp) VALUES ('tenant', ?, 0, NULL, NULL, ?, ?, ?)",
          84L,
          "PAYMENT",
          "legacy insert",
          LocalDateTime.of(2025, 3, 1, 13, 0));
      Long legacyLogId =
          dsl.fetchSingle(
                  "SELECT id FROM " + schema + ".log_events WHERE message = ?", "legacy insert")
              .get("id", Long.class);
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_key FROM " + schema + ".log_events WHERE id = ?", legacyLogId)
                  .get(0, Long.class))
          .isEqualTo(0L);

      dsl.execute(
          "UPDATE " + schema + ".log_events SET tenant_id = ? WHERE id = ?", 85L, legacyLogId);
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_key FROM " + schema + ".log_events WHERE id = ?", legacyLogId)
                  .get(0, Long.class))
          .isEqualTo(0L);

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, tenant_uuid, "
              + "audit_event_id, type, message) VALUES (?, ?, ?, 1, NULL, ?, ?, ?)",
          "tenant",
          86L,
          86L,
          "60000000-0000-4000-8000-000000000001",
          "ACCOUNT_AUDIT",
          "Account audit event 60000000-0000-4000-8000-000000000001");
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_key FROM " + schema + ".log_events WHERE audit_event_id = ?",
                      "60000000-0000-4000-8000-000000000001")
                  .get(0, Long.class))
          .isEqualTo(86L);

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, tenant_uuid, "
              + "audit_event_id, type, message) "
              + "VALUES ('platform', NULL, 0, 1, NULL, '60000000-0000-4000-8000-000000000002', "
              + "'ACCOUNT_AUDIT', 'Account audit event 60000000-0000-4000-8000-000000000002')");
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_id, tenant_key FROM "
                          + schema
                          + ".log_events WHERE audit_event_id = ?",
                      "60000000-0000-4000-8000-000000000002")
                  .intoArray())
          .containsExactly(null, 0L);

      for (long invalidTenantId : List.of(0L, -9L)) {
        String eventId =
            invalidTenantId == 0
                ? "60000000-0000-4000-8000-000000000003"
                : "60000000-0000-4000-8000-000000000004";
        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "INSERT INTO "
                            + schema
                            + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, "
                            + "tenant_uuid, audit_event_id, type, message) "
                            + "VALUES ('tenant', ?, ?, 1, NULL, ?, 'ACCOUNT_AUDIT', 'invalid tenant projection')",
                        invalidTenantId,
                        invalidTenantId,
                        eventId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_log_events_scope_tenant");
        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "UPDATE "
                            + schema
                            + ".log_events SET tenant_id = ?, tenant_key = ? "
                            + "WHERE audit_event_id = ?",
                        invalidTenantId,
                        invalidTenantId,
                        "60000000-0000-4000-8000-000000000001"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_log_events_scope_tenant");
      }
      assertThat(
              dsl.fetchSingle(
                      "SELECT tenant_id, tenant_key FROM "
                          + schema
                          + ".log_events WHERE audit_event_id = ?",
                      "60000000-0000-4000-8000-000000000001")
                  .intoArray())
          .containsExactly(86L, 86L);

      for (long legacyTenantId : List.of(0L, -9L)) {
        dsl.execute(
            "INSERT INTO "
                + schema
                + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, tenant_uuid, "
                + "type, message) VALUES ('tenant', ?, 0, NULL, NULL, 'PAYMENT', "
                + "'new non-audit legacy row')",
            legacyTenantId);
      }

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO "
                          + schema
                          + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, "
                          + "tenant_uuid, audit_event_id, type, message) "
                          + "VALUES ('tenant', 73, 74, 1, NULL, '50000000-0000-4000-8000-000000000001', "
                          + "'ACCOUNT_AUDIT', 'mismatched key')"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("chk_log_events_scope_tenant");
    } finally {
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private void insertMigrationReceipt(
      String schema,
      UUID receiptId,
      String eventId,
      String payloadDigest,
      byte[] payload,
      String status,
      String outcome) {
    Instant occurredAt = Instant.parse("2025-03-01T14:00:00.123456Z");
    dsl.execute(
        "WITH projection AS ("
            + "INSERT INTO "
            + schema
            + ".log_events (scope, tenant_id, tenant_key, tenant_identity_version, tenant_uuid, "
            + "audit_event_id, type, message, timestamp) "
            + "VALUES (?, ?, ?, 1, NULL, ?, ?, ?, ?) RETURNING id) "
            + "INSERT INTO "
            + schema
            + ".account_audit_receipts (receipt_id, scope, tenant_id, tenant_key, "
            + "tenant_identity_version, tenant_uuid, audit_event_id, producer_service, event_type, "
            + "occurred_at_seconds, occurred_at_nanos, schema_version, "
            + "payload_digest_version, payload_digest, payload, status, outcome, log_event_id) "
            + "SELECT ?, ?, ?, ?, 1, NULL, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS BYTEA), ?, ?, projection.id "
            + "FROM projection",
        "tenant",
        73L,
        73L,
        eventId,
        "ACCOUNT_AUDIT",
        "Account audit event " + eventId,
        LocalDateTime.of(2025, 3, 1, 14, 0, 0, 123456000),
        receiptId,
        "tenant",
        73L,
        73L,
        eventId,
        "account-service",
        "ACCOUNT_REGISTRATION",
        occurredAt.getEpochSecond(),
        occurredAt.getNano(),
        1,
        1,
        payloadDigest,
        payload,
        status,
        outcome);
  }

  private void seedV2Receipt(
      String schema,
      UUID receiptId,
      String scope,
      Long tenantId,
      String eventId,
      Instant occurredAt,
      boolean minimized) {
    byte[] originalPayload = "{\"retained\":true}".getBytes(StandardCharsets.UTF_8);
    long tenantKey = tenantId == null ? 0L : tenantId.longValue();
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_audit_receipts (receipt_id, scope, tenant_id, tenant_key, audit_event_id, "
            + "producer_service, event_type, occurred_at_seconds, occurred_at_nanos, schema_version, "
            + "payload_digest_version, payload_digest, payload, status, outcome) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        receiptId,
        scope,
        tenantId,
        tenantKey,
        eventId,
        "account-service",
        "ACCOUNT_REGISTRATION",
        occurredAt.getEpochSecond(),
        occurredAt.getNano(),
        1,
        1,
        digest(originalPayload),
        minimized ? null : originalPayload,
        minimized ? "MINIMIZED" : "COMMITTED",
        minimized ? "NON_REPLAYABLE" : "ACCEPTED");
  }

  private void assertBackfilledReceipt(
      String schema,
      String receiptId,
      String scope,
      Long tenantId,
      String eventId,
      String status,
      String outcome,
      String expectedPayloadDigest,
      String timestamp) {
    var receipt =
        dsl.fetchOne(
            "SELECT receipt.scope, receipt.tenant_id, receipt.tenant_key, "
                + "receipt.tenant_identity_version, receipt.tenant_uuid, receipt.audit_event_id, "
                + "receipt.receipt_id, receipt.producer_service, receipt.event_type, "
                + "receipt.occurred_at_seconds, receipt.occurred_at_nanos, receipt.schema_version, "
                + "receipt.payload_digest_version, receipt.payload, receipt.payload_digest, "
                + "receipt.status, receipt.outcome, receipt.log_event_id, event.id AS projection_id, "
                + "event.tenant_identity_version AS projection_identity_version, "
                + "event.tenant_uuid AS projection_tenant_uuid, event.type, event.message, "
                + "event.timestamp, event.account_id FROM "
                + schema
                + ".account_audit_receipts AS receipt JOIN "
                + schema
                + ".log_events AS event ON event.id = receipt.log_event_id "
                + "WHERE receipt.audit_event_id = ?",
            eventId);
    assertThat(receipt).isNotNull();
    assertThat(receipt.get("scope", String.class)).isEqualTo(scope);
    assertThat(receipt.get("tenant_id", Long.class)).isEqualTo(tenantId);
    assertThat(receipt.get("tenant_key", Long.class)).isEqualTo(tenantId == null ? 0L : tenantId);
    assertThat(receipt.get("tenant_identity_version", Integer.class)).isEqualTo(1);
    assertThat(receipt.get("tenant_uuid", UUID.class)).isNull();
    assertThat(receipt.get("audit_event_id", String.class)).isEqualTo(eventId);
    assertThat(receipt.get("receipt_id", UUID.class)).isEqualTo(UUID.fromString(receiptId));
    assertThat(receipt.get("producer_service", String.class)).isEqualTo("account-service");
    assertThat(receipt.get("event_type", String.class)).isEqualTo("ACCOUNT_REGISTRATION");
    Instant occurredAt = Instant.parse(timestamp + "Z");
    assertThat(receipt.get("occurred_at_seconds", Long.class))
        .isEqualTo(occurredAt.getEpochSecond());
    assertThat(receipt.get("occurred_at_nanos", Integer.class)).isEqualTo(occurredAt.getNano());
    assertThat(receipt.get("schema_version", Integer.class)).isEqualTo(1);
    assertThat(receipt.get("payload_digest_version", Integer.class)).isEqualTo(1);
    assertThat(receipt.get("payload_digest", String.class)).isEqualTo(expectedPayloadDigest);
    assertThat(receipt.get("status", String.class)).isEqualTo(status);
    assertThat(receipt.get("outcome", String.class)).isEqualTo(outcome);
    assertThat(receipt.get("log_event_id", Long.class))
        .isEqualTo(receipt.get("projection_id", Long.class));
    assertThat(receipt.get("projection_identity_version", Integer.class)).isEqualTo(1);
    assertThat(receipt.get("projection_tenant_uuid", UUID.class)).isNull();
    assertThat(receipt.get("type", String.class)).isEqualTo("ACCOUNT_AUDIT");
    assertThat(receipt.get("message", String.class)).isEqualTo("Account audit event " + eventId);
    assertThat(receipt.get("message", String.class)).doesNotContain("retained");
    assertThat(receipt.get("timestamp", LocalDateTime.class))
        .isEqualTo(LocalDateTime.parse(timestamp));
    assertThat(receipt.get("account_id", Long.class)).isNull();
    assertThat(
            dsl.fetchSingle(
                    "SELECT COUNT(*) FROM " + schema + ".log_events WHERE audit_event_id = ?",
                    eventId)
                .get(0, Integer.class))
        .isEqualTo(1);
    if ("MINIMIZED".equals(status)) {
      assertThat(receipt.get("payload", byte[].class)).isNull();
    } else {
      assertThat(receipt.get("payload", byte[].class))
          .isEqualTo("{\"retained\":true}".getBytes(StandardCharsets.UTF_8));
    }
  }

  private AccountAuditReceiptDto createAfter(CountDownLatch start, CreateLogEventRequest request) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent audit test did not start");
      }
      return logEventService.createLogEvent(request);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent audit test was interrupted", ex);
    }
  }

  private static CreateLogEventRequest tenantAuditRequest(String eventId, String payload) {
    return auditRequest(AccountAuditScope.TENANT, 42L, eventId, payload);
  }

  private static CreateLogEventRequest platformAuditRequest(String eventId, String payload) {
    return auditRequest(AccountAuditScope.PLATFORM, null, eventId, payload);
  }

  private static CreateLogEventRequest auditRequest(
      AccountAuditScope scope, Long tenantId, String eventId, String payload) {
    return auditRequest(scope, tenantId, eventId, payload, Instant.parse("2026-09-24T00:00:00Z"));
  }

  private static CreateLogEventRequest auditRequest(
      AccountAuditScope scope, Long tenantId, String eventId, String payload, Instant occurredAt) {
    ByteString payloadBytes = ByteString.copyFrom(payload, StandardCharsets.UTF_8);
    return new CreateLogEventRequest(
        scope,
        1,
        tenantId,
        null,
        eventId,
        "account-service",
        "ACCOUNT_AUDIT_INTEGRATION_TEST",
        occurredAt,
        1,
        payloadBytes,
        1,
        digest(payloadBytes.toByteArray()));
  }

  private static CreateLogEventRequest uuidTenantAuditRequest(
      String eventId, UUID tenantUuid, String payload) {
    ByteString payloadBytes = ByteString.copyFrom(payload, StandardCharsets.UTF_8);
    return new CreateLogEventRequest(
        AccountAuditScope.TENANT,
        2,
        null,
        tenantUuid,
        eventId,
        "account-service",
        "ACCOUNT_AUDIT_INTEGRATION_TEST",
        Instant.parse("2026-09-24T00:00:00Z"),
        1,
        payloadBytes,
        1,
        digest(payloadBytes.toByteArray()));
  }

  private static CreateLogEventRequest withOccurredAt(
      CreateLogEventRequest request, Instant occurredAt) {
    return auditRequest(
        request.scope(),
        request.tenantId(),
        request.auditEventId(),
        new String(request.payload().toByteArray(), StandardCharsets.UTF_8),
        occurredAt);
  }

  private static void assertDuplicateOf(
      AccountAuditReceiptDto actual, AccountAuditReceiptDto expected) {
    assertThat(actual.status()).isEqualTo(AccountAuditReceiptStatus.COMMITTED);
    assertThat(actual.outcome()).isEqualTo(AccountAuditReceiptOutcome.DUPLICATE);
    assertThat(actual.receiptId()).isEqualTo(expected.receiptId());
    assertThat(actual.logEventId()).isEqualTo(expected.logEventId());
    assertThat(actual.scope()).isEqualTo(expected.scope());
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
  }

  private static void assertConflictWithOriginal(
      AccountAuditReceiptDto actual, AccountAuditReceiptDto expected) {
    assertThat(actual.status()).isEqualTo(AccountAuditReceiptStatus.CONFLICT);
    assertThat(actual.outcome()).isEqualTo(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT);
    assertThat(actual.receiptId()).isEqualTo(expected.receiptId());
    assertThat(actual.logEventId()).isEqualTo(expected.logEventId());
    assertThat(actual.scope()).isEqualTo(expected.scope());
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
  }

  private static String digest(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  @Test
  void pingEndpointReturnsPong() throws Exception {
    String token =
        JWT_UTIL.generateToken(
            ACCOUNT_UUID,
            Map.of("accountId", ACCOUNT_UUID, "globalRoles", java.util.List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ping"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("pong");
  }

  @Test
  void publicReportPersistenceControllerMappingIsAbsent() {
    assertThat(
            requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()))
        .noneMatch(
            pattern ->
                pattern.equals("/reports")
                    || pattern.startsWith("/reports/")
                    || pattern.equals("/admin/reports")
                    || pattern.startsWith("/admin/reports/"));
  }

  @Test
  void publicTargetOnlyAdmissionPointerWriteMappingsAreAbsent() {
    assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
        .noneMatch(
            mapping ->
                (mapping.getMethodsCondition().getMethods().isEmpty()
                        || mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST))
                    && mapping.getPatternValues().stream()
                        .anyMatch(
                            path ->
                                path.equals("/admission-pointers")
                                    || path.equals("/admission-pointers/cutover")
                                    || path.equals("/admission-pointers/version-upgrades")));
  }

  @Test
  void externallyGatedOperatorWriteMappingsRemainMapped() {
    for (String path :
        new String[] {
          "/feature-flags/toggle",
          "/moderation/actions",
          "/tick-remediation/pause",
          "/tick-remediation/resume"
        }) {
      assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
          .as("path %s", path)
          .anyMatch(
              mapping ->
                  mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                      && mapping.getPatternValues().contains(path));
    }
  }

  @Test
  void mappedAdmissionPointerPostUsesCanonicalMethodNotAllowedEnvelope() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admission-pointers"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAdminToken(1L))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.body()).contains("\"status\":\"ERROR\"");
    assertThat(response.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
    assertThat(response.body()).contains("\"message\":\"Request method is not allowed\"");
  }

  @Test
  void unmappedAdmissionPointerWriteFamiliesUseCanonicalNotFoundEnvelope() throws Exception {
    for (String path :
        new String[] {"/admission-pointers/cutover", "/admission-pointers/version-upgrades"}) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAdminToken(1L))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();

      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).as("path %s", path).isEqualTo(404);
      assertThat(response.body()).as("path %s", path).contains("\"status\":\"ERROR\"");
      assertThat(response.body()).as("path %s", path).contains("\"code\":\"NOT_FOUND\"");
      assertThat(response.body())
          .as("path %s", path)
          .contains("\"message\":\"Resource not found\"");
    }
  }

  @Test
  void unmappedPostReportFamiliesUseCanonicalNotFoundEnvelope() throws Exception {
    for (String path :
        new String[] {"/reports", "/reports/123", "/admin/reports", "/admin/reports/123"}) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAdminToken(1L))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();

      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).as("path %s", path).isEqualTo(404);
      assertThat(response.body()).as("path %s", path).contains("\"status\":\"ERROR\"");
      assertThat(response.body()).as("path %s", path).contains("\"code\":\"NOT_FOUND\"");
      assertThat(response.body())
          .as("path %s", path)
          .contains("\"message\":\"Resource not found\"");
    }
  }

  @Test
  void remoteFollowupsRejectMalformedPointerVersionWithInvalidArgumentEnvelope() throws Exception {
    String token = tenantAdminToken(1L);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/remote-followups/1?pointerVersion=abc"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"pointerVersion");
  }

  private String tenantAdminToken(long tenantId) {
    return JWT_UTIL.generateToken(
        ACCOUNT_UUID,
        Map.of(
            "accountId", ACCOUNT_UUID,
            "globalRoles", java.util.List.of("platformAdmin"),
            "scopedRoles", Map.of(Long.toString(tenantId), java.util.List.of("tenantAdmin"))));
  }
}
