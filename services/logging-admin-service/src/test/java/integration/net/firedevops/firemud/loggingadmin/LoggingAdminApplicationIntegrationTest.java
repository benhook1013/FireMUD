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
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
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
  void concurrentExactRetriesCreateOneProjectionAndReturnTheSameIdentifiers() throws Exception {
    String eventId = "4b7d9ee5-62f5-44dd-8b35-dcf6490d7404";
    CreateLogEventRequest request = tenantAuditRequest(eventId, "{\"accountId\":94}");
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
  void v3BackfillsRetainedV2ReceiptsAndKeepsLegacyTenantLogWritesValid() {
    String schema = "logging_admin_migration_" + UUID.randomUUID().toString().replace("-", "");
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
          UUID.fromString("30000000-0000-4000-8000-000000000002"),
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
          "2025-03-01T12:00:01.123456");
      assertBackfilledReceipt(
          schema,
          "30000000-0000-4000-8000-000000000002",
          "tenant",
          73L,
          "10000000-0000-4000-8000-000000000002",
          "MINIMIZED",
          "NON_REPLAYABLE",
          "2025-03-01T12:00:02.987654");
      assertBackfilledReceipt(
          schema,
          "40000000-0000-4000-8000-000000000001",
          "platform",
          null,
          "20000000-0000-4000-8000-000000000001",
          "COMMITTED",
          "ACCEPTED",
          "2025-03-01T12:00:03.123456");
      assertBackfilledReceipt(
          schema,
          "40000000-0000-4000-8000-000000000002",
          "platform",
          null,
          "20000000-0000-4000-8000-000000000002",
          "MINIMIZED",
          "NON_REPLAYABLE",
          "2025-03-01T12:00:04.987654");

      dsl.execute(
          "INSERT INTO "
              + schema
              + ".log_events (tenant_id, type, message, timestamp) VALUES (?, ?, ?, ?)",
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
              + ".log_events (scope, tenant_id, tenant_key, audit_event_id, type, message) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
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

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO "
                          + schema
                          + ".log_events (scope, tenant_id, tenant_key, audit_event_id, type, message) "
                          + "VALUES ('tenant', 73, 74, '50000000-0000-4000-8000-000000000001', "
                          + "'ACCOUNT_AUDIT', 'mismatched key')"))
          .isInstanceOf(DataAccessException.class);
    } finally {
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
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
      String timestamp) {
    var receipt =
        dsl.fetchOne(
            "SELECT receipt.scope, receipt.tenant_id, receipt.tenant_key, receipt.audit_event_id, "
                + "receipt.receipt_id, receipt.producer_service, receipt.event_type, "
                + "receipt.occurred_at_seconds, receipt.occurred_at_nanos, receipt.schema_version, "
                + "receipt.payload_digest_version, receipt.payload, receipt.payload_digest, "
                + "receipt.status, receipt.outcome, receipt.log_event_id, event.id AS projection_id, "
                + "event.type, event.message, event.timestamp, event.account_id FROM "
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
    assertThat(receipt.get("payload_digest", String.class))
        .isEqualTo(digest("{\"retained\":true}".getBytes(StandardCharsets.UTF_8)));
    assertThat(receipt.get("status", String.class)).isEqualTo(status);
    assertThat(receipt.get("outcome", String.class)).isEqualTo(outcome);
    assertThat(receipt.get("log_event_id", Long.class))
        .isEqualTo(receipt.get("projection_id", Long.class));
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
    ByteString payloadBytes = ByteString.copyFrom(payload, StandardCharsets.UTF_8);
    return new CreateLogEventRequest(
        scope,
        tenantId,
        eventId,
        "account-service",
        "ACCOUNT_AUDIT_INTEGRATION_TEST",
        Instant.parse("2026-09-24T00:00:00Z"),
        1,
        payloadBytes,
        1,
        digest(payloadBytes.toByteArray()));
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
            "logging-admin-test", Map.of("globalRoles", java.util.List.of("platformAdmin")));
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
        "logging-admin-test",
        Map.of(
            "accountId", "42",
            "globalRoles", java.util.List.of("platformAdmin"),
            "scopedRoles", Map.of(Long.toString(tenantId), java.util.List.of("tenantAdmin"))));
  }
}
