package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Field;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs V1003 against the actual Account PostgreSQL schema and assembled source-writer guard. The
 * integrity and currentness fixtures seed related owner rows with upstream producer triggers
 * disabled. Protection acquisition, child completeness, settlement and source-writer guards remain
 * enabled; they prove SQL currentness/exclusion/immutability only, not authenticated
 * activation-protection production.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionActivationProtectionPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void databaseOwnsProtectionIdentityAndPendingProtectionBlocksSourceWriters() throws Exception {
    Fixture fixture = fixture();
    CurrentnessCase current = currentnessCase(fixture, Instant.now().plusSeconds(10), 135500);
    byte[] requestBinding = current.requestBytes();
    Map<String, Object> request = readJson(requestBinding);
    UUID activationRequestId = UUID.fromString((String) request.get("worldActivationRequestId"));
    byte[] preparingEvidence =
        Base64.getDecoder().decode((String) request.get("preparingEvidenceBytesBase64"));
    byte[] captureReference = bytes("{}");
    String operationDigest = current.operationIdentityDigest();
    assertThat(
            Base64.getDecoder()
                .decode((String) request.get("accountRedemptionProjectionBytesBase64"))
                .length)
        .isGreaterThan(135168)
        .isLessThanOrEqualTo(262144);
    acquireProtection(fixture, current);
    var sources =
        fixture
            .dsl()
            .fetch(
                "SELECT source_key, source_evidence FROM account_start_session_activation_protection_sources "
                    + "ORDER BY account_publication_authorization_source_sort_key(source_key)");
    assertThat(sources).hasSize(2);
    String sourceKey = sources.get(0).get("source_key", String.class);
    byte[][] sourceEvidenceBytes =
        sources.stream()
            .map(row -> row.get("source_evidence", byte[].class))
            .toArray(byte[][]::new);

    var stored =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT activation_protection_id, activation_protection_fence, acquired_at "
                    + "FROM account_start_session_activation_protections "
                    + "WHERE operation_identity_digest = ?",
                operationDigest);
    UUID id = stored.get("activation_protection_id", UUID.class);
    assertThat(id).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(stored.get("activation_protection_fence", Long.class)).isPositive();
    assertThat(stored.get("acquired_at", OffsetDateTime.class)).isNotNull();

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_start_session_activation_protections "
                                        + "SET acquired_at = acquired_at "
                                        + "WHERE activation_protection_id = ?",
                                    id)))
        .hasMessageContaining("immutable; terminal settlement is separate");

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT account_control_ui_hold_required_sources("
                                        + "ARRAY[?]::text[])",
                                    sourceKey)))
        .hasMessageContaining("Original StartSession activation protection remains pending");

    fixture
        .dsl()
        .fetch(
            "SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - clock_timestamp()))))",
            current.expiryText());

    // This synthetic SQL receipt proves only that V1003 permits a source writer after an immutable
    // terminal-shaped row is committed. It is not a typed receipt or authenticated World producer
    // proof. The original lease has expired before settlement insertion.
    OffsetDateTime retainedAcquiredAt = stored.get("acquired_at", OffsetDateTime.class);
    long protectionFence = stored.get("activation_protection_fence", Long.class);
    byte[] accountEvidenceBytes =
        accountEvidence(
            id,
            protectionFence,
            retainedAcquiredAt,
            requestBinding,
            captureReference,
            sourceEvidenceBytes);
    byte[] activationResultBytes =
        activationResult(activationRequestId, preparingEvidence, "COMMITTED");

    Map<String, Object> changedCapture = readJson(accountEvidenceBytes);
    byte[] wrongCapture = bytes("substituted capture reference");
    changedCapture.put(
        "originalSourceCaptureReferenceBytesBase64",
        Base64.getEncoder().encodeToString(wrongCapture));
    changedCapture.put("originalSourceCaptureReferenceSha256", hash(wrongCapture));
    assertSettlementInsertRejected(
        fixture,
        id,
        protectionFence,
        activationRequestId,
        accountEvidenceBytes(changedCapture),
        activationResultBytes);

    Map<String, Object> changedRequest = readJson(accountEvidenceBytes);
    changedRequest.put(
        "requestBytesBase64",
        Base64.getEncoder().encodeToString(bytes("{\"schema\":\"substituted-request/v1\"}")));
    assertSettlementInsertRejected(
        fixture,
        id,
        protectionFence,
        activationRequestId,
        accountEvidenceBytes(changedRequest),
        activationResultBytes);

    Map<String, Object> changedSources = readJson(accountEvidenceBytes);
    changedSources.put(
        "sourceEvidenceVector",
        java.util.List.of(Base64.getEncoder().encodeToString(bytes("substituted source"))));
    assertSettlementInsertRejected(
        fixture,
        id,
        protectionFence,
        activationRequestId,
        accountEvidenceBytes(changedSources),
        activationResultBytes);

    byte[] terminalBytes =
        terminalBytes(
            id, protectionFence, activationRequestId, accountEvidenceBytes, activationResultBytes);
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                insert(
                    fixture.dsl(),
                    "account_start_session_activation_protection_settlements",
                    "activation_protection_id",
                    id,
                    "activation_protection_fence",
                    protectionFence,
                    "world_activation_request_id",
                    activationRequestId,
                    "world_activation_fence",
                    29L,
                    "outcome",
                    "COMMITTED",
                    "account_protection_evidence_bytes",
                    accountEvidenceBytes,
                    "terminal_bytes",
                    terminalBytes,
                    "terminal_digest",
                    "sha256:" + hash(terminalBytes)));

    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                fixture
                    .dsl()
                    .execute(
                        "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
                        sourceKey));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_start_session_activation_protection_settlements "
                                        + "SET outcome = outcome WHERE activation_protection_id = ?",
                                    id)))
        .hasMessageContaining("activation settlement is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "DELETE FROM account_start_session_activation_protection_settlements "
                                        + "WHERE activation_protection_id = ?",
                                    id)))
        .hasMessageContaining("activation settlement is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "TRUNCATE account_start_session_activation_protection_settlements")))
        .hasMessageContaining("activation settlement is immutable");

    for (String guard :
        new String[] {
          "account_control_ui_hold_required_sources(text[])",
          "account_draft_authorization_source_change_guard()",
          "account_hosted_terms_disclosure_handoff_guard()"
        }) {
      String guardDefinition =
          fixture
              .dsl()
              .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", guard)
              .get(0, String.class);
      assertThat(guardDefinition)
          .contains("account_ss_admission_assert_no_pending")
          .contains("account_start_session_world_participations");
    }
    String pendingProtectionDefinition =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT pg_get_functiondef('account_ss_admission_assert_no_pending(text[])'::regprocedure)")
            .get(0, String.class);
    assertThat(pendingProtectionDefinition)
        .contains("account_start_session_admission_protection_sources")
        .contains("account_ss_admission_is_settled")
        .contains("account_start_session_activation_protection_settlements");
  }

  @Test
  void currentnessRequiresCommittedPreparationExactAttemptAndUnexpiredLease() throws Exception {
    Fixture fixture = fixture();
    CurrentnessCase valid = currentnessCase(fixture, Instant.now().plusSeconds(60));
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                fixture
                    .dsl()
                    .execute(
                        "SELECT account_ss_activation_assert_current(?, ?, ?, FALSE)",
                        valid.requestBytes(),
                        valid.operationIdentityBytes(),
                        valid.operationIdentityDigest()));

    Map<String, Object> changedProjectionRequest =
        JSON.readValue(valid.requestBytes(), new tools.jackson.core.type.TypeReference<>() {});
    byte[] originalTuple =
        Base64.getDecoder()
            .decode(
                (String) changedProjectionRequest.get("originalPostAuthorizationTupleBytesBase64"));
    byte[] differentSpelling =
        (" " + new String(originalTuple, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    changedProjectionRequest.put(
        "accountRedemptionProjectionBytesBase64",
        Base64.getEncoder().encodeToString(differentSpelling));
    byte[] changedProjection = jsonBytes(changedProjectionRequest);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT account_ss_activation_assert_current(?, ?, ?, FALSE)",
                                    changedProjection,
                                    valid.operationIdentityBytes(),
                                    valid.operationIdentityDigest())))
        .hasMessageContaining(
            "Activation protection differs from original Account redemption or capture");

    byte[] changedAttempt =
        activationRequest(valid, UUID.randomUUID().toString(), valid.expiryText(), "PREPARING");
    assertCurrentnessRejected(fixture, changedAttempt, valid.operationIdentityBytes());

    byte[] changedWorldState =
        activationRequest(valid, valid.ownerAttemptId().toString(), valid.expiryText(), "ACTIVE");
    assertCurrentnessRejected(fixture, changedWorldState, valid.operationIdentityBytes());

    Fixture expiredFixture = fixture();
    CurrentnessCase expired = currentnessCase(expiredFixture, Instant.now().minusSeconds(1));
    assertCurrentnessRejected(
        expiredFixture, expired.requestBytes(), expired.operationIdentityBytes());
  }

  private static void assertSettlementInsertRejected(
      Fixture fixture,
      UUID protectionId,
      long protectionFence,
      UUID activationRequestId,
      byte[] protectionEvidenceBytes,
      byte[] activationResultBytes) {
    byte[] terminalBytes =
        terminalBytes(
            protectionId,
            protectionFence,
            activationRequestId,
            protectionEvidenceBytes,
            activationResultBytes);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            insert(
                                fixture.dsl(),
                                "account_start_session_activation_protection_settlements",
                                "activation_protection_id",
                                protectionId,
                                "activation_protection_fence",
                                protectionFence,
                                "world_activation_request_id",
                                activationRequestId,
                                "world_activation_fence",
                                29L,
                                "outcome",
                                "COMMITTED",
                                "account_protection_evidence_bytes",
                                protectionEvidenceBytes,
                                "terminal_bytes",
                                terminalBytes,
                                "terminal_digest",
                                "sha256:" + hash(terminalBytes))))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable postgresFailure = failure;
              while (postgresFailure.getCause() != null) {
                postgresFailure = postgresFailure.getCause();
              }
              assertThat(postgresFailure)
                  .isInstanceOfSatisfying(
                      org.postgresql.util.PSQLException.class,
                      sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("23514"));
            });
  }

  private static byte[] accountEvidence(
      UUID protectionId,
      long protectionFence,
      OffsetDateTime acquiredAt,
      byte[] requestBytes,
      byte[] captureReference,
      byte[]... sourceEvidence)
      throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", "account-start-session-activation-protection-evidence/v1");
    value.put("requestBytesBase64", Base64.getEncoder().encodeToString(requestBytes));
    value.put("accountProtectionId", protectionId.toString());
    value.put("accountProtectionFence", Long.toString(protectionFence));
    value.put("acquiredAt", timestamp(acquiredAt));
    value.put(
        "originalSourceCaptureReferenceBytesBase64",
        Base64.getEncoder().encodeToString(captureReference));
    value.put("originalSourceCaptureReferenceSha256", hash(captureReference));
    value.put(
        "sourceEvidenceVector",
        java.util.Arrays.stream(sourceEvidence).map(Base64.getEncoder()::encodeToString).toList());
    return jsonBytes(value);
  }

  private static byte[] accountEvidenceBytes(Map<String, Object> value) throws Exception {
    return jsonBytes(value);
  }

  private static byte[] activationResult(
      UUID activationRequestId, byte[] preparingEvidence, String outcome) throws Exception {
    Map<String, Object> preparing = readJson(preparingEvidence);
    @SuppressWarnings("unchecked")
    Map<String, Object> lifecycleRequest = (Map<String, Object>) preparing.get("request");
    lifecycleRequest.remove("readRequestId");
    Map<String, Object> activationRequest = new LinkedHashMap<>();
    activationRequest.put("schema", "world-canonical-instance-activation-request/v1");
    activationRequest.put("activationRequestId", activationRequestId.toString());
    activationRequest.put("preparingEvidence", preparing);
    byte[] activationRequestBytes = jsonBytes(activationRequest);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "world-canonical-instance-activation-result/v1");
    result.put("activationRequestId", activationRequestId.toString());
    result.put("requestDigest", "sha256:" + hash(activationRequestBytes));
    result.put("requestBytesBase64", Base64.getEncoder().encodeToString(activationRequestBytes));
    result.put(
        "preparingEvidenceBytesBase64", Base64.getEncoder().encodeToString(preparingEvidence));
    result.put("outcome", outcome);
    result.put("terminalCode", "COMMITTED".equals(outcome) ? null : "FENCED_ABORT");
    result.put(
        "lifecycleEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(bytes("synthetic ACTIVE")));
    return jsonBytes(result);
  }

  private static byte[] terminalBytes(
      UUID protectionId,
      long protectionFence,
      UUID activationRequestId,
      byte[] protectionEvidenceBytes,
      byte[] activationResultBytes) {
    Map<String, Object> terminal = new LinkedHashMap<>();
    terminal.put("schema", "world-original-start-session-activation-terminal-evidence/v1");
    terminal.put("activationRequestId", activationRequestId.toString());
    terminal.put("accountProtectionId", protectionId.toString());
    terminal.put("accountProtectionFence", Long.toString(protectionFence));
    terminal.put("worldActivationFence", "29");
    terminal.put(
        "accountProtectionEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(protectionEvidenceBytes));
    terminal.put(
        "activationResultBytesBase64", Base64.getEncoder().encodeToString(activationResultBytes));
    terminal.put("activationResultSha256", hash(activationResultBytes));
    try {
      return jsonBytes(terminal);
    } catch (Exception impossible) {
      throw new IllegalStateException("Synthetic terminal receipt must be canonical", impossible);
    }
  }

  private static Map<String, Object> readJson(byte[] bytes) throws Exception {
    return JSON.readValue(bytes, new tools.jackson.core.type.TypeReference<>() {});
  }

  private static String timestamp(OffsetDateTime value) {
    return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC)
        .format(value.toInstant());
  }

  private static void assertCurrentnessRejected(
      Fixture fixture, byte[] requestBytes, byte[] operationIdentityBytes) {
    String operationDigest = "sha256:" + hash(operationIdentityBytes);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT account_ss_activation_assert_current(?, ?, ?, FALSE)",
                                    requestBytes,
                                    operationIdentityBytes,
                                    operationDigest)))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable postgresFailure = failure;
              while (postgresFailure.getCause() != null) {
                postgresFailure = postgresFailure.getCause();
              }
              assertThat(postgresFailure)
                  .isInstanceOfSatisfying(
                      org.postgresql.util.PSQLException.class,
                      sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("23514"));
            });
  }

  private static CurrentnessCase currentnessCase(Fixture fixture, Instant originalLease)
      throws Exception {
    return currentnessCase(fixture, originalLease, 0);
  }

  private static CurrentnessCase currentnessCase(
      Fixture fixture, Instant originalLease, int padding) throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    UUID participationId = UUID.randomUUID();
    UUID ownerMutationId = UUID.randomUUID();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID activationRequestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID issuanceOperationId = UUID.randomUUID();
    long ownerFence = 11L;
    long issuanceFence = 9L;
    long sourceVersion = 13L;
    long sourceFence = 17L;
    String requestId = "original-activation/" + UUID.randomUUID();
    String fingerprint = "arfp/v1/activation-test/" + "a".repeat(64);
    OffsetDateTime dbNow =
        fixture.dsl().fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class);
    Instant normalizedNow = dbNow.toInstant().truncatedTo(ChronoUnit.MICROS);
    Instant normalizedLease = originalLease.truncatedTo(ChronoUnit.MICROS);
    Instant issuedAt = normalizedNow.minusSeconds(10);
    Instant redeemedAt = normalizedNow.minusSeconds(5);
    Instant referenceExpiry = normalizedNow.plusSeconds(180);
    Instant responseExpiry = normalizedNow.plusSeconds(220);
    String expiryText =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(normalizedLease);

    Map<String, Object> preAuthorizationValue =
        Map.of(
            "scope",
            Map.of("targetNamespace", "test", "tenantId", tenantId.toString()),
            "fixturePadding",
            "x".repeat(Math.min(6000, padding)));
    byte[] preAuthorization = jsonBytes(preAuthorizationValue);
    Map<String, Object> bundleValue =
        Map.of(
            "issuanceOperationIdentity",
            Map.of("issuanceOperationId", issuanceOperationId.toString()),
            "fixturePadding",
            "x".repeat(padding - Math.min(6000, padding)));
    byte[] authorityBundle = jsonBytes(bundleValue);
    byte[] tuple =
        jsonBytes(
            Map.of(
                "controlPlaneRequestId",
                requestId,
                "preAuthorizationReservationTuple",
                preAuthorizationValue,
                "authorizationReferenceFingerprint",
                fingerprint,
                "issuanceFence",
                Long.toString(issuanceFence),
                "authorityEvidenceBundle",
                bundleValue));
    // Current-use integrity preserves the complete stipulated original tuple without scalarizing
    // it.
    byte[] projection = tuple.clone();
    // Java String ordering is UTF-16: the supplementary key precedes the BMP private-use key.
    var sourceEvidence =
        java.util.List.of(
            new SourceEvidence(
                SourceKind.HOSTED_TERMS,
                tenantId + "-terms-\uD800\uDC00",
                "2",
                "17",
                null,
                null,
                bytes("supplementary source")),
            new SourceEvidence(
                SourceKind.HOSTED_TERMS,
                tenantId + "-terms-\uE000",
                "2",
                "17",
                null,
                null,
                bytes("BMP source")));
    byte[] snapshot =
        jsonBytes(
            Map.of(
                "schema",
                "account-start-session-authority-snapshot/v1",
                "controlPlaneRequestId",
                requestId,
                "sourceVector",
                sourceEvidence.stream()
                    .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
                    .toList()));
    byte[] captureReference = bytes("{}");
    String captureDigest = hash(captureReference);
    String snapshotDigest = hash(snapshot);
    byte[] attemptBytes = bytes("synthetic-current-original-attempt");
    String preparationInput = "{}";
    String preparationDigest = "sha256:" + hash(bytes(preparationInput));

    byte[] worldPreparing =
        jsonBytes(
            Map.of(
                "schema",
                "world-canonical-instance-lifecycle-evidence/v1",
                "request",
                Map.of(
                    "controlPlaneRequestId",
                    requestId,
                    "targetNamespace",
                    "test",
                    "canonicalTenantId",
                    tenantId.toString(),
                    "canonicalGameInstanceId",
                    gameInstanceId.toString()),
                "lifecycleStatus",
                "PREPARING"));
    byte[] launchRead = bytes("synthetic exact STARTING read");
    byte[] operationIdentity = bytes("activation operation identity");
    String operationDigest = "sha256:" + hash(operationIdentity);
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture
                  .dsl()
                  .execute(
                      "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)",
                      sourceEvidence.get(0).key());
              fixture
                  .dsl()
                  .execute(
                      "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)",
                      sourceEvidence.get(1).key());
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              insert(
                  fixture.dsl(),
                  "account_canonical_tenant_identity_claims",
                  "canonical_tenant_id",
                  tenantId,
                  "identity_kind",
                  "APPROVED_RETAINED",
                  "source_operation_id",
                  UUID.randomUUID(),
                  "source_account_legacy_tenant_id",
                  1L,
                  "source_target_namespace",
                  "test",
                  "source_game_row_id",
                  1L,
                  "source_game_tenant_key",
                  tenantId.toString());
              insert(
                  fixture.dsl(),
                  "account_start_session_authority_captures",
                  "control_plane_request_id",
                  requestId,
                  "account_uuid",
                  accountId,
                  "tenant_uuid",
                  tenantId,
                  "target_owner",
                  "game-session-service",
                  "logging_workload_uri",
                  "spiffe://firemud/ns/test/sa/logging-admin-service",
                  "reservation_owner_id",
                  UUID.randomUUID(),
                  "reservation_claim_fence",
                  7L,
                  "pre_authorization_tuple",
                  preAuthorization,
                  "mutation_digest",
                  "c".repeat(64),
                  "control_ui_operation_id",
                  UUID.randomUUID(),
                  "control_ui_token_jti",
                  UUID.randomUUID(),
                  "control_ui_token_hash",
                  "d".repeat(64),
                  "control_ui_signer_receipt_sha256",
                  "e".repeat(64),
                  "issuance_fence",
                  issuanceFence,
                  "issuance_fence_source_version",
                  sourceVersion,
                  "captured_at",
                  OffsetDateTime.ofInstant(
                      normalizedNow.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC),
                  "snapshot_sha256",
                  snapshotDigest,
                  "canonical_snapshot_bytes",
                  snapshot,
                  "source_version",
                  sourceVersion,
                  "source_fence",
                  sourceFence,
                  "linearization",
                  "19",
                  "canonical_sha256",
                  captureDigest,
                  "canonical_capture_bytes",
                  captureReference);
              insert(
                  fixture.dsl(),
                  "account_start_session_operator_authorizations",
                  "control_plane_request_id",
                  requestId,
                  "pre_authorization_tuple",
                  preAuthorization,
                  "mutation_digest",
                  "c".repeat(64),
                  "issuance_workload_uri",
                  "spiffe://firemud/ns/test/sa/logging-admin-service",
                  "reservation_owner_id",
                  UUID.randomUUID(),
                  "reservation_claim_fence",
                  7L,
                  "issuance_operation_id",
                  issuanceOperationId,
                  "issuance_fence",
                  issuanceFence,
                  "bundle_version",
                  "authorityEvidenceBundle/v1",
                  "bundle_source_version",
                  Long.toString(sourceVersion),
                  "bundle_source_fence",
                  Long.toString(sourceFence),
                  "bundle_linearization",
                  "19",
                  "authority_evidence_bundle",
                  authorityBundle,
                  "authorization_reference_fingerprint",
                  fingerprint,
                  "encrypted_response_envelope",
                  bytes("synthetic response"),
                  "issued_at",
                  OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC),
                  "reference_expires_at",
                  OffsetDateTime.ofInstant(referenceExpiry, ZoneOffset.UTC),
                  "response_envelope_expires_at",
                  OffsetDateTime.ofInstant(responseExpiry, ZoneOffset.UTC),
                  "status",
                  "REDEEMED",
                  "redemption_redeemer_workload_uri",
                  "spiffe://firemud/ns/test/sa/game-session-service",
                  "redemption_owner_attempt_id",
                  ownerAttemptId,
                  "redemption_owner_fence",
                  ownerFence,
                  "redeemed_at",
                  OffsetDateTime.ofInstant(redeemedAt, ZoneOffset.UTC),
                  "redemption_reference_fingerprint",
                  fingerprint,
                  "redemption_authority_evidence_bundle",
                  authorityBundle);
              insert(
                  fixture.dsl(),
                  "account_start_session_world_participations",
                  "participation_id",
                  participationId,
                  "control_plane_request_id",
                  requestId,
                  "original_post_authorization_tuple",
                  tuple,
                  "target_namespace",
                  "test",
                  "canonical_tenant_id",
                  tenantId,
                  "canonical_game_instance_id",
                  gameInstanceId,
                  "game_session_owner_attempt_id",
                  ownerAttemptId,
                  "game_session_owner_fence",
                  ownerFence,
                  "preparation_input_json",
                  preparationInput,
                  "preparation_input_digest",
                  preparationDigest);
              for (SourceEvidence source : sourceEvidence) {
                insert(
                    fixture.dsl(),
                    "account_start_session_world_participation_sources",
                    "participation_id",
                    participationId,
                    "source_key",
                    source.key(),
                    "source_evidence",
                    source.canonicalBytes());
              }
              insert(
                  fixture.dsl(),
                  "account_start_session_world_original_attempt_evidence",
                  "participation_id",
                  participationId,
                  "target_namespace",
                  "test",
                  "game_session_owner_mutation_id",
                  ownerMutationId,
                  "game_session_owner_attempt_id",
                  ownerAttemptId,
                  "game_session_owner_fence",
                  ownerFence,
                  "original_lease_expires_at",
                  OffsetDateTime.ofInstant(normalizedLease, ZoneOffset.UTC),
                  "original_response_bytes",
                  attemptBytes,
                  "original_response_digest",
                  "sha256:" + hash(attemptBytes));
              insert(
                  fixture.dsl(),
                  "account_start_session_world_participation_settlements",
                  "participation_id",
                  participationId,
                  "outcome",
                  "COMMITTED",
                  "world_execution_fence",
                  3L,
                  "terminal_bytes",
                  bytes("synthetic committed preparation"),
                  "terminal_digest",
                  "sha256:" + hash(bytes("synthetic committed preparation")));
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    byte[] requestBytes =
        activationRequest(
            tuple,
            projection,
            ownerMutationId,
            ownerAttemptId,
            ownerFence,
            expiryText,
            participationId,
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT participation_fence FROM account_start_session_world_participations "
                        + "WHERE participation_id = ?",
                    participationId)
                .get("participation_fence", Long.class),
            launchRead,
            activationRequestId,
            worldPreparing);
    return new CurrentnessCase(
        requestBytes, operationIdentity, operationDigest, ownerAttemptId, expiryText);
  }

  private static void acquireProtection(Fixture fixture, CurrentnessCase current) {
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture
                  .dsl()
                  .execute(
                      """
          INSERT INTO account_start_session_activation_protections(
              control_plane_request_id, account_world_participation_id, account_world_participation_fence,
              original_post_authorization_tuple, account_redemption_projection,
              game_session_owner_mutation_id, game_session_owner_attempt_id, game_session_owner_fence,
              original_lease_expires_at, target_namespace, canonical_tenant_id, canonical_game_instance_id,
              world_activation_request_id, game_session_launch_read_evidence, world_preparing_evidence,
              capture_source_version, capture_source_fence, capture_sha256,
              capture_reference_bytes, capture_reference_sha256, operation_identity_bytes,
              operation_identity_digest, request_binding_bytes, request_binding_digest)
          SELECT w.control_plane_request_id, w.participation_id, w.participation_fence,
              w.original_post_authorization_tuple, w.original_post_authorization_tuple,
              o.game_session_owner_mutation_id, o.game_session_owner_attempt_id, o.game_session_owner_fence,
              o.original_lease_expires_at, w.target_namespace, w.canonical_tenant_id, w.canonical_game_instance_id,
              (r.value->>'worldActivationRequestId')::uuid,
              decode(r.value->>'gameSessionLaunchReadEvidenceBytesBase64', 'base64'),
              decode(r.value->>'preparingEvidenceBytesBase64', 'base64'),
              c.source_version, c.source_fence, c.canonical_sha256,
              c.canonical_capture_bytes, c.canonical_sha256, ?, ?, ?, ?
          FROM account_start_session_world_participations w
          JOIN account_start_session_world_original_attempt_evidence o USING(participation_id)
          JOIN account_start_session_authority_captures c USING(control_plane_request_id)
          CROSS JOIN (SELECT convert_from(?::bytea, 'UTF8')::jsonb AS value) r
          WHERE w.participation_id = (r.value->>'accountWorldParticipationId')::uuid
          """,
                      current.operationIdentityBytes(),
                      current.operationIdentityDigest(),
                      current.requestBytes(),
                      "sha256:" + hash(current.requestBytes()),
                      current.requestBytes());
              fixture
                  .dsl()
                  .execute(
                      """
          INSERT INTO account_start_session_activation_protection_sources(
              activation_protection_id, source_key, source_evidence)
          SELECT p.activation_protection_id, s.source_key, s.source_evidence
          FROM account_start_session_activation_protections p
          JOIN account_start_session_world_participation_sources s
              ON s.participation_id = p.account_world_participation_id
          WHERE p.operation_identity_digest = ?
          """,
                      current.operationIdentityDigest());
            });
  }

  private static byte[] activationRequest(
      CurrentnessCase current, String ownerAttemptId, String expiry, String worldStatus)
      throws Exception {
    Map<String, Object> root =
        JSON.readValue(current.requestBytes(), new tools.jackson.core.type.TypeReference<>() {});
    root.put("gameSessionOwnerAttemptId", ownerAttemptId);
    root.put("originalLeaseExpiresAt", expiry);
    byte[] preparing =
        Base64.getDecoder().decode((String) root.get("preparingEvidenceBytesBase64"));
    Map<String, Object> world =
        JSON.readValue(preparing, new tools.jackson.core.type.TypeReference<>() {});
    world.put("lifecycleStatus", worldStatus);
    root.put("preparingEvidenceBytesBase64", Base64.getEncoder().encodeToString(jsonBytes(world)));
    return jsonBytes(root);
  }

  private static byte[] activationRequest(
      byte[] tuple,
      byte[] projection,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      String expiry,
      UUID participationId,
      long participationFence,
      byte[] launchRead,
      UUID activationRequestId,
      byte[] preparingEvidence)
      throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", "account-start-session-activation-protection-request/v1");
    value.put(
        "originalPostAuthorizationTupleBytesBase64", Base64.getEncoder().encodeToString(tuple));
    value.put(
        "accountRedemptionProjectionBytesBase64", Base64.getEncoder().encodeToString(projection));
    value.put("gameSessionOwnerMutationId", ownerMutationId.toString());
    value.put("gameSessionOwnerAttemptId", ownerAttemptId.toString());
    value.put("gameSessionOwnerFence", Long.toString(ownerFence));
    value.put("originalLeaseExpiresAt", expiry);
    value.put("accountWorldParticipationId", participationId.toString());
    value.put("accountWorldParticipationFence", Long.toString(participationFence));
    value.put(
        "gameSessionLaunchReadEvidenceBytesBase64", Base64.getEncoder().encodeToString(launchRead));
    value.put("worldActivationRequestId", activationRequestId.toString());
    value.put(
        "preparingEvidenceBytesBase64", Base64.getEncoder().encodeToString(preparingEvidence));
    byte[] result = jsonBytes(value);
    return result;
  }

  private static byte[] jsonBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static Fixture fixture() {
    String schema = "ss_activation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    String requestId = "activation-test/" + UUID.randomUUID();
    return new Fixture(dsl, transaction, requestId);
  }

  private static void insert(DSLContext dsl, String table, Object... fields) {
    Map<Field<?>, Object> values = new java.util.LinkedHashMap<>();
    for (int index = 0; index < fields.length; index += 2) {
      values.put(DSL.field(DSL.name((String) fields[index])), fields[index + 1]);
    }
    dsl.insertInto(DSL.table(DSL.name(table))).set(values).execute();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record Fixture(DSLContext dsl, TransactionTemplate transaction, String requestId) {}

  private record CurrentnessCase(
      byte[] requestBytes,
      byte[] operationIdentityBytes,
      String operationIdentityDigest,
      UUID ownerAttemptId,
      String expiryText) {}
}
