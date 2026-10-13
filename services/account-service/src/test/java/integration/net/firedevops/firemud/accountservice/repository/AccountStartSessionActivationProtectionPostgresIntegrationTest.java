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
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
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
 * Runs V1003/V1004 against the actual Account PostgreSQL schema and assembled source-writer guard.
 * The integrity and currentness fixtures seed related owner rows with upstream producer triggers
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
  void lifecycleUpgradePreservesPendingProtectionAndBlocksInvalidSettlement() throws Exception {
    Fixture fixture = fixture("1003");
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

    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_regprocedure('account_ss_activation_settlement_lifecycle_guard()')::TEXT")
                .get(0, String.class))
        .isNull();
    String retainedHistoryQuery =
        "SELECT installed_rank || ':' || version || ':' || script || ':' "
            + "|| checksum || ':' || success FROM flyway_schema_history "
            + "WHERE version IS NOT NULL AND version <> '1004' ORDER BY installed_rank";
    var installedHistory = fixture.dsl().fetch(retainedHistoryQuery).getValues(0, String.class);
    String protectionRowsQuery =
        "SELECT row_to_json(p)::TEXT FROM "
            + "account_start_session_activation_protections p ORDER BY activation_protection_id";
    String sourceRowsQuery =
        "SELECT row_to_json(s)::TEXT FROM "
            + "account_start_session_activation_protection_sources s ORDER BY activation_protection_id, source_key";
    var pendingRows = fixture.dsl().fetch(protectionRowsQuery).getValues(0, String.class);
    var retainedSources = fixture.dsl().fetch(sourceRowsQuery).getValues(0, String.class);
    assertThat(pendingRows).hasSize(1);
    assertThat(retainedSources).hasSize(2);

    Flyway upgrade = activationFlyway(fixture.dataSource(), "1004");
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(fixture.dsl().fetch(retainedHistoryQuery).getValues(0, String.class))
        .isEqualTo(installedHistory);
    assertThat(fixture.dsl().fetch(protectionRowsQuery).getValues(0, String.class))
        .isEqualTo(pendingRows);
    assertThat(fixture.dsl().fetch(sourceRowsQuery).getValues(0, String.class))
        .isEqualTo(retainedSources);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$'")
                .get(0, Integer.class))
        .isEqualTo(1004);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_regprocedure('account_ss_activation_settlement_lifecycle_guard()')::TEXT")
                .get(0, String.class))
        .isNotNull();

    fixture
        .dsl()
        .fetch(
            "SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - clock_timestamp()))))",
            current.expiryText());

    // This SQL receipt retains a typed closed ACTIVE lifecycle fixture. Upstream owner rows remain
    // stipulated; this is storage integrity proof, not authenticated World production.
    // The original lease has expired before settlement insertion.
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

    Map<String, Object> validResult = readJson(activationResultBytes);
    byte[] activeBytes =
        Base64.getDecoder().decode((String) validResult.get("lifecycleEvidenceBytesBase64"));
    java.util.List<String> invalidLifecycleCarriers = new java.util.ArrayList<>();
    invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(bytes("synthetic ACTIVE")));
    invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(bytes("{")));
    invalidLifecycleCarriers.add(
        Base64.getEncoder().encodeToString(new byte[] {(byte) 0xc3, 0x28}));
    invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(bytes("null")));
    String activeJson = new String(activeBytes, StandardCharsets.UTF_8);
    for (String schemaVersion : new String[] {"1.0", "1e0", "1e+0"}) {
      byte[] invalidVersion =
          bytes(activeJson.replace("\"schemaVersion\":1", "\"schemaVersion\":" + schemaVersion));
      assertThatThrownBy(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(invalidVersion))
          .isInstanceOf(IllegalArgumentException.class);
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(invalidVersion));
    }
    for (String duplicate :
        new String[] {
          activeJson.replace("\"schema\":", "\"schema\":\"wrong/v1\",\"schema\":"),
          activeJson.replace(
              "\"lifecycleStatus\":", "\"lifecycleStatus\":\"PREPARING\",\"lifecycleStatus\":"),
          activeJson.replace("\"schemaVersion\":1", "\"schemaVersion\":0,\"schemaVersion\":1")
        }) {
      byte[] duplicateBytes = bytes(duplicate);
      assertThatThrownBy(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(duplicateBytes))
          .isInstanceOf(IllegalArgumentException.class);
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(duplicateBytes));
    }
    invalidLifecycleCarriers.add("%%%");
    invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(activeBytes) + "\n");
    invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(bytes("[]")));
    for (String field :
        new String[] {
          "schema",
          "lifecycleStatus",
          "lifecycleEpoch",
          "rowVersion",
          "runtimeRoomInstanceId",
          "captureId",
          "graphSha256",
          "preparationInputDigest",
          "launchBindingBytesBase64",
          "startLocation",
          "operationalRegionAssignments",
          "request"
        }) {
      Map<String, Object> missing = readJson(activeBytes);
      missing.remove(field);
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(jsonBytes(missing)));
      Map<String, Object> wrongType = readJson(activeBytes);
      wrongType.put(field, 1);
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(jsonBytes(wrongType)));
    }
    for (Map.Entry<String, Object> change :
        Map.<String, Object>ofEntries(
                Map.entry("schema", "wrong/v1"),
                Map.entry("lifecycleStatus", "PREPARING"),
                Map.entry("lifecycleEpoch", "0"),
                Map.entry("rowVersion", "01"),
                Map.entry("runtimeRoomInstanceId", "9223372036854775808"),
                Map.entry("captureId", UUID.randomUUID().toString()),
                Map.entry("graphSha256", "a".repeat(64)),
                Map.entry("preparationInputDigest", "sha256:" + "a".repeat(64)),
                Map.entry("launchBindingBytesBase64", "e30="),
                Map.entry("startLocation", Map.of()),
                Map.entry("operationalRegionAssignments", Map.of()),
                Map.entry("extra", true))
            .entrySet()) {
      Map<String, Object> changed = readJson(activeBytes);
      changed.put(change.getKey(), change.getValue());
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(jsonBytes(changed)));
    }
    for (String field :
        new String[] {
          "canonicalGameInstanceId",
          "controlPlaneRequestId",
          "readRequestId",
          "schemaVersion",
          "publicProduction",
          "extra"
        }) {
      Map<String, Object> changed = readJson(activeBytes);
      @SuppressWarnings("unchecked")
      Map<String, Object> changedLifecycleRequest = (Map<String, Object>) changed.get("request");
      changedLifecycleRequest.put(
          field,
          "canonicalGameInstanceId".equals(field) ? UUID.randomUUID().toString() : "changed");
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(jsonBytes(changed)));
    }
    for (String status : new String[] {"FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED"}) {
      Map<String, Object> changed = readJson(activeBytes);
      changed.put("lifecycleStatus", status);
      invalidLifecycleCarriers.add(Base64.getEncoder().encodeToString(jsonBytes(changed)));
    }
    for (String carrier : invalidLifecycleCarriers) {
      Map<String, Object> invalidResult = readJson(activationResultBytes);
      invalidResult.put("lifecycleEvidenceBytesBase64", carrier);
      assertSettlementInsertRejected(
          fixture,
          id,
          protectionFence,
          activationRequestId,
          accountEvidenceBytes,
          jsonBytes(invalidResult));
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table("account_start_session_activation_protection_settlements")))
          .isZero();
      assertThatThrownBy(
              () ->
                  fixture
                      .transaction()
                      .executeWithoutResult(
                          status ->
                              fixture
                                  .dsl()
                                  .execute(
                                      "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
                                      sourceKey)))
          .hasMessageContaining("Original StartSession activation protection remains pending");
    }

    Map<String, Object> invalidTerminalCode = readJson(activationResultBytes);
    invalidTerminalCode.put("terminalCode", "FENCED_ABORT");
    assertSettlementInsertRejected(
        fixture,
        id,
        protectionFence,
        activationRequestId,
        accountEvidenceBytes,
        jsonBytes(invalidTerminalCode));
    Map<String, Object> invalidOutcome = readJson(activationResultBytes);
    invalidOutcome.put("outcome", "ABORTED");
    invalidOutcome.put("terminalCode", "FENCED_ABORT");
    assertSettlementInsertRejected(
        fixture,
        id,
        protectionFence,
        activationRequestId,
        accountEvidenceBytes,
        jsonBytes(invalidOutcome));

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

  @Test
  void abortedSettlementPreservesTerminalCodeFenceWithoutRequiringActiveLifecycle()
      throws Exception {
    Fixture fixture = fixture();
    CurrentnessCase current = currentnessCase(fixture, Instant.now().plusSeconds(60));
    acquireProtection(fixture, current);
    var stored =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT activation_protection_id, activation_protection_fence, acquired_at "
                    + "FROM account_start_session_activation_protections");
    UUID id = stored.get("activation_protection_id", UUID.class);
    long fence = stored.get("activation_protection_fence", Long.class);
    Map<String, Object> request = readJson(current.requestBytes());
    UUID activationId = UUID.fromString((String) request.get("worldActivationRequestId"));
    var sources =
        fixture
            .dsl()
            .fetch(
                "SELECT source_key, source_evidence FROM "
                    + "account_start_session_activation_protection_sources "
                    + "ORDER BY account_publication_authorization_source_sort_key(source_key)");
    byte[] evidence =
        accountEvidence(
            id,
            fence,
            stored.get("acquired_at", OffsetDateTime.class),
            current.requestBytes(),
            bytes("{}"),
            sources.stream()
                .map(row -> row.get("source_evidence", byte[].class))
                .toArray(byte[][]::new));
    Map<String, Object> result =
        readJson(
            activationResult(
                activationId,
                Base64.getDecoder().decode((String) request.get("preparingEvidenceBytesBase64")),
                "ABORTED"));
    result.put("lifecycleEvidenceBytesBase64", null);
    result.put("terminalCode", "");
    assertSettlementInsertRejected(
        fixture, id, fence, activationId, evidence, jsonBytes(result), "ABORTED");
    result.put("terminalCode", null);
    assertSettlementInsertRejected(
        fixture, id, fence, activationId, evidence, jsonBytes(result), "ABORTED");
    result.put("terminalCode", "FENCED_ABORT");
    byte[] terminal = terminalBytes(id, fence, activationId, evidence, jsonBytes(result));
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
                    fence,
                    "world_activation_request_id",
                    activationId,
                    "world_activation_fence",
                    29L,
                    "outcome",
                    "ABORTED",
                    "account_protection_evidence_bytes",
                    evidence,
                    "terminal_bytes",
                    terminal,
                    "terminal_digest",
                    "sha256:" + hash(terminal)));
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                fixture
                    .dsl()
                    .execute(
                        "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
                        sources.get(0).get("source_key", String.class)));
  }

  private static void assertSettlementInsertRejected(
      Fixture fixture,
      UUID protectionId,
      long protectionFence,
      UUID activationRequestId,
      byte[] protectionEvidenceBytes,
      byte[] activationResultBytes) {
    assertSettlementInsertRejected(
        fixture,
        protectionId,
        protectionFence,
        activationRequestId,
        protectionEvidenceBytes,
        activationResultBytes,
        "COMMITTED");
  }

  private static void assertSettlementInsertRejected(
      Fixture fixture,
      UUID protectionId,
      long protectionFence,
      UUID activationRequestId,
      byte[] protectionEvidenceBytes,
      byte[] activationResultBytes,
      String outcome) {
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
                                outcome,
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
                      sqlFailure ->
                          assertThat(sqlFailure.getSQLState())
                              .as(sqlFailure.getMessage())
                              .isEqualTo("23514"));
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
    Map<String, Object> active = readJson(preparingEvidence);
    active.put("lifecycleStatus", "ACTIVE");
    active.put("lifecycleEpoch", "4");
    active.put("rowVersion", "1");
    @SuppressWarnings("unchecked")
    Map<String, Object> activeRequest = (Map<String, Object>) active.get("request");
    activeRequest.put("readRequestId", UUID.randomUUID().toString());
    byte[] activeBytes = jsonBytes(active);
    assertThat(WorldCanonicalInstanceLifecycleEvidence.fromStored(activeBytes).lifecycleStatus())
        .isEqualTo("ACTIVE");
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
    result.put("lifecycleEvidenceBytesBase64", Base64.getEncoder().encodeToString(activeBytes));
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
                      sqlFailure ->
                          assertThat(sqlFailure.getSQLState())
                              .as(sqlFailure.getMessage())
                              .isEqualTo("23514"));
            });
  }

  private static CurrentnessCase currentnessCase(Fixture fixture, Instant originalLease)
      throws Exception {
    return currentnessCase(fixture, originalLease, 0);
  }

  private static CurrentnessCase currentnessCase(
      Fixture fixture, Instant originalLease, int padding) throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("PREPARING", 3L);
    UUID tenantId = lifecycle.request().canonicalTenantId();
    UUID gameInstanceId = lifecycle.request().canonicalGameInstanceId();
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
    String requestId = lifecycle.request().controlPlaneRequestId();
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
        new WorldCanonicalInstanceLifecycleEvidence(
                lifecycle.request(),
                lifecycle.launchBinding(),
                lifecycle.startLocation(),
                lifecycle.runtimeRoomInstanceId(),
                "PREPARING",
                lifecycle.lifecycleEpoch(),
                lifecycle.rowVersion(),
                lifecycle.captureId(),
                lifecycle.graphSha256(),
                preparationDigest,
                lifecycle.operationalRegionAssignments())
            .canonicalBytes();
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
    return fixture("latest");
  }

  private static Fixture fixture(String target) {
    String schema = "ss_activation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    activationFlyway(source, target).migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    String requestId = "activation-test/" + UUID.randomUUID();
    return new Fixture(dsl, transaction, requestId, source);
  }

  private static Flyway activationFlyway(DriverManagerDataSource source, String target) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(source.getSchema())
        .defaultSchema(source.getSchema())
        .placeholders(Map.of("serviceSchema", source.getSchema()))
        .locations("classpath:db/migration")
        .target(target)
        .load();
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

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      String requestId,
      DriverManagerDataSource dataSource) {}

  private record CurrentnessCase(
      byte[] requestBytes,
      byte[] operationIdentityBytes,
      String operationIdentityDigest,
      UUID ownerAttemptId,
      String expiryText) {}
}
