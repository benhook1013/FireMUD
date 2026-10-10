package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SQL integrity and source-writer definitions. Upstream Account capture, redeemed authorization,
 * World settlement and Game Session observation are explicitly stipulated with upstream triggers
 * disabled. New V133 guards remain enabled for acquisition and all assertions. These fixtures are
 * not genuine authenticated Account/World/Game Session producer or consumer proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionAdmissionProtectionPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void installsAdditiveHooksWithoutRemovingExistingOwnerChecks() {
    Fixture fixture = fixture();
    for (String function :
        List.of(
            "account_control_ui_hold_required_sources(text[])",
            "account_draft_authorization_source_change_guard()",
            "account_hosted_terms_disclosure_handoff_guard()")) {
      String definition =
          fixture
              .dsl()
              .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", function)
              .get(0, String.class);
      assertThat(definition)
          .contains(
              "account_ss_admission_assert_no_pending",
              "account_start_session_world_participations",
              "account_selected_owner_intake_source_read");
    }
    String hold =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT pg_get_functiondef('account_control_ui_hold_required_sources(text[])'::regprocedure)")
            .get(0, String.class);
    assertThat(hold)
        .contains(
            "account_draft_authorization_sources",
            "account_selected_publication_sources",
            "account_game_logic_intake_sources",
            "account_game_logic_intake_source_read_sources");
  }

  @Test
  void exactCurrentReadPreservesIdentityWithoutAllocatingAndWorldCommitCannotReleaseIt() {
    Fixture fixture = fixture();
    assertThat(fixture.expiry().getNano() % 1_000_000).isNotZero();
    acquire(fixture);
    Long sequence = sequenceValue(fixture);
    for (int retry = 0; retry < 2; retry++) {
      var current =
          fixture
              .transaction()
              .execute(
                  status ->
                      fixture
                          .dsl()
                          .fetchSingle(
                              "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                              fixture.protectionId(),
                              1L,
                              fixture.request()));
      assertThat(current.get("protection_id", UUID.class)).isEqualTo(fixture.protectionId());
      assertThat(current.get("request_binding_bytes", byte[].class))
          .containsExactly(fixture.request());
      OffsetDateTime retainedExpiry =
          current.get("original_lease_expires_at", OffsetDateTime.class);
      assertThat(retainedExpiry).isNotNull();
      assertThat(retainedExpiry.toInstant()).isEqualTo(fixture.expiry().toInstant());
    }
    assertThat(sequenceValue(fixture)).isEqualTo(sequence);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT account_start_session_world_participation_is_settled(?)",
                    fixture.worldId())
                .get(0, Boolean.class))
        .isTrue();
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
                                    fixture.source().key())))
        .hasMessageContaining("Original StartSession admission protection remains pending");
  }

  @Test
  void changedSubmillisecondOriginalExpiryCannotRecoverRetainedProtection() {
    Fixture fixture = fixture();
    acquire(fixture);
    var storedRowBefore =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT * FROM account_start_session_admission_protections WHERE protection_id = ?",
                fixture.protectionId());
    DateTimeFormatter expiryFormat =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    String originalExpiryText = expiryFormat.format(fixture.expiry().toInstant());
    String changedExpiryText = expiryFormat.format(fixture.expiry().plusNanos(1_000).toInstant());
    byte[] changedRequest =
        new String(fixture.request(), StandardCharsets.UTF_8)
            .replace(originalExpiryText, changedExpiryText)
            .getBytes(StandardCharsets.UTF_8);
    assertThat(changedRequest).isNotEqualTo(fixture.request());

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    changedRequest)))
        .hasMessageContaining("Original admission recovery binding differs");
    var storedRowAfter =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT * FROM account_start_session_admission_protections WHERE protection_id = ?",
                fixture.protectionId());
    assertThat(storedRowAfter).isEqualTo(storedRowBefore);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void forgedWorldHoldDigestWithoutCanonicalPrefixCannotBecomeProtection() {
    Fixture fixture = fixture();
    String holdText = new String(fixture.hold(), StandardCharsets.UTF_8);
    String digestMarker = "\"holdBindingDigest\":\"";
    int digestStart = holdText.indexOf(digestMarker) + digestMarker.length();
    int digestEnd = holdText.indexOf('"', digestStart);
    String validDigest = holdText.substring(digestStart, digestEnd);
    assertThat(validDigest).startsWith("sha256:");
    String forgedHoldText =
        holdText.substring(0, digestStart)
            + validDigest.substring("sha256:".length())
            + holdText.substring(digestEnd);
    byte[] forgedHold = forgedHoldText.getBytes(StandardCharsets.UTF_8);
    byte[] forgedRequest =
        new String(fixture.request(), StandardCharsets.UTF_8)
            .replace(base64(fixture.hold()), base64(forgedHold))
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> insertProtection(fixture, 11L, forgedHold, forgedRequest)))
        .hasMessageContaining("Admission hold identity differs from original instance and request");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void pendingAdmissionExcludesRealSourceChangeCompletionAndDisclosureDispatch() {
    Fixture fixture = fixture();
    acquire(fixture);
    UUID changeId = UUID.randomUUID();
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              insert(
                  fixture.dsl(),
                  "account_draft_authorization_source_changes",
                  "change_id",
                  changeId,
                  "binding",
                  bytes("change"),
                  "status",
                  "WAITING");
              insert(
                  fixture.dsl(),
                  "account_draft_authorization_changed_scopes",
                  "change_id",
                  changeId,
                  "source_key",
                  fixture.source().key());
            });
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_draft_authorization_source_changes SET status = 'SOURCE_COMMITTED', committed_at = clock_timestamp() WHERE change_id = ?",
                                    changeId)))
        .hasMessageContaining("Original StartSession admission protection remains pending");
    UUID handoffId = UUID.randomUUID();
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                insert(
                    fixture.dsl(),
                    "account_hosted_terms_disclosure_handoffs",
                    "handoff_id",
                    handoffId,
                    "request_id",
                    UUID.randomUUID(),
                    "kind",
                    "CATALOG",
                    "source_key",
                    fixture.source().key(),
                    "predecessor_digest",
                    "sha256:" + "a".repeat(64),
                    "candidate_digest",
                    "sha256:" + "b".repeat(64),
                    "effective_at",
                    OffsetDateTime.now().plusDays(1),
                    "binding",
                    bytes("disclosure"),
                    "binding_digest",
                    "sha256:" + hash(bytes("disclosure")),
                    "status",
                    "PREPARED"));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = 1 WHERE handoff_id = ?",
                                    handoffId)))
        .hasMessageContaining("Original StartSession admission protection remains pending");
  }

  @Test
  void rawChangedCaptureAndIncompleteChildrenCannotBecomeProtection() {
    Fixture changed = fixture();
    assertThatThrownBy(
            () ->
                changed
                    .transaction()
                    .executeWithoutResult(status -> insertProtection(changed, 99L)))
        .hasMessageContaining("retained owner bindings");
    Fixture incomplete = fixture();
    assertThatThrownBy(
            () ->
                incomplete
                    .transaction()
                    .executeWithoutResult(status -> insertProtection(incomplete, 11L)))
        .satisfies(
            failure ->
                assertDatabaseGuardFailure(
                    failure,
                    "Admission protection has incomplete or extra source children",
                    "23514"));
    assertThat(
            incomplete
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void immutableRowsAndUnprovedTerminalCannotReleaseProtection() {
    Fixture fixture = fixture();
    acquire(fixture);
    for (String table :
        List.of(
            "account_start_session_admission_protections",
            "account_start_session_admission_protection_sources")) {
      assertThatThrownBy(() -> fixture.dsl().execute("DELETE FROM " + table))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> fixture.dsl().execute("TRUNCATE " + table + " CASCADE"))
          .hasMessageContaining("append-only");
    }
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE account_start_session_admission_protection_sources SET source_evidence = ?",
                        bytes("changed")))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO account_start_session_admission_protection_settlements (protection_id, outcome, terminal_bytes, terminal_digest) VALUES (?, 'ABORTED', ?, ?)",
                        fixture.protectionId(),
                        malformedSettlementBytes(),
                        "sha256:" + hash(malformedSettlementBytes())))
        .hasMessageContaining("not the closed envelope");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void expiredOriginalCannotRecoverEvenWhenRetainedIdentityAndSourcesRemain() {
    Fixture fixture = fixture();
    acquire(fixture);
    // Stipulate passage of the immutable original expiry without changing protection:
    // rewriting the fixture row requires disabling the immutable upstream/new triggers.
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              fixture
                  .dsl()
                  .execute(
                      "UPDATE account_start_session_admission_protections SET original_lease_expires_at = date_trunc('milliseconds', clock_timestamp() - INTERVAL '1 second') WHERE protection_id = ?",
                      fixture.protectionId());
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    fixture.request())))
        .hasMessageContaining("not current");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void currentRecoveryRechecksDatabaseExpiryAfterTheCallerWaitsForOriginalCaptureLock()
      throws Exception {
    Fixture fixture = fixture(3_000L);
    acquire(fixture);
    var executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    try (Connection lock = fixture.dataSource().getConnection()) {
      lock.setAutoCommit(false);
      DSL.using(lock, SQLDialect.POSTGRES)
          .fetch(
              "SELECT control_plane_request_id FROM account_start_session_authority_captures WHERE control_plane_request_id = ? FOR UPDATE",
              fixture.requestId());
      var waiting =
          executor.submit(
              () ->
                  fixture
                      .transaction()
                      .executeWithoutResult(
                          status -> {
                            entered.countDown();
                            // The currentness producer's original-capture lock can wait before the
                            // V133
                            // boundary. V133 itself uses NOWAIT to avoid reversing source lock
                            // order.
                            fixture
                                .dsl()
                                .fetch(
                                    "SELECT control_plane_request_id FROM account_start_session_authority_captures WHERE control_plane_request_id = ? FOR UPDATE",
                                    fixture.requestId());
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    fixture.request());
                          }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (fixture
              .dsl()
              .fetchSingle("SELECT clock_timestamp() >= ?::timestamptz", fixture.expiry())
              .get(0, Boolean.class)
              .equals(false)
          && System.nanoTime() < expiryDeadline) {
        Thread.sleep(20L);
      }
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle("SELECT clock_timestamp() >= ?::timestamptz", fixture.expiry())
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(waiting.isDone()).isFalse();
      lock.commit();
      assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasStackTraceContaining("not current");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  private static void acquire(Fixture fixture) {
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              insertProtection(fixture, 11L);
              insert(
                  fixture.dsl(),
                  "account_start_session_admission_protection_sources",
                  "protection_id",
                  fixture.protectionId(),
                  "source_key",
                  fixture.source().key(),
                  "source_evidence",
                  fixture.source().canonicalBytes());
            });
  }

  private static void insertProtection(Fixture fixture, long version) {
    insertProtection(fixture, version, fixture.hold(), fixture.request());
  }

  private static void insertProtection(
      Fixture fixture, long version, byte[] holdIdentity, byte[] request) {
    insert(
        fixture.dsl(),
        "account_start_session_admission_protections",
        "protection_id",
        fixture.protectionId(),
        "control_plane_request_id",
        fixture.requestId(),
        "original_post_authorization_tuple",
        fixture.tuple(),
        "account_redemption_projection",
        fixture.projection(),
        "game_session_owner_mutation_id",
        fixture.mutationId(),
        "game_session_owner_attempt_id",
        fixture.attemptId(),
        "game_session_owner_fence",
        47L,
        "original_lease_expires_at",
        fixture.expiry(),
        "account_world_participation_id",
        fixture.worldId(),
        "account_world_participation_fence",
        1L,
        "target_namespace",
        "test",
        "canonical_tenant_id",
        fixture.tenantId(),
        "canonical_game_instance_id",
        fixture.instanceId(),
        "capture_source_version",
        version,
        "capture_source_fence",
        13L,
        "capture_sha256",
        hash(bytes("{}")),
        "world_admission_hold_identity_bytes",
        holdIdentity,
        "request_binding_bytes",
        request,
        "request_binding_digest",
        "sha256:" + hash(request));
  }

  private static Fixture fixture() {
    return fixture(60_000L);
  }

  private static Fixture fixture(long leaseMillis) {
    String schema = "ss_admission_" + UUID.randomUUID().toString().replace("-", "");
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
    OffsetDateTime now =
        dsl.fetchSingle(
                "SELECT date_trunc('milliseconds', clock_timestamp()) + INTERVAL '123 microseconds'")
            .get(0, OffsetDateTime.class);
    OffsetDateTime capturedAt = now.truncatedTo(ChronoUnit.MILLIS);
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID mutation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    UUID world = UUID.randomUUID();
    UUID issuance = UUID.randomUUID();
    String requestId = "original/" + UUID.randomUUID();
    SourceEvidence evidence =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS, "test-terms", "1", "1", null, null, bytes("terms"));
    byte[] tuple =
        json(
            Map.of(
                "preAuthorizationReservationTuple",
                Map.of(),
                "authorizationReferenceFingerprint",
                "arfp/v1/key-1/" + "a".repeat(64),
                "issuanceFence",
                "9",
                "authorityEvidenceBundle",
                Map.of()));
    byte[] projection =
        json(
            Map.of(
                "projectionSchemaId",
                "accountStartSessionRedemptionProjection",
                "projectionSchemaVersion",
                "1",
                "authorizationReferenceFingerprint",
                "arfp/v1/key-1/" + "a".repeat(64),
                "authorityEvidenceBundle",
                Map.of(),
                "issuanceOperationId",
                issuance.toString(),
                "issuanceFence",
                9L));
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            "test",
            tenant,
            "test-world",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            instance,
            UUID.randomUUID(),
            1L,
            "separate-initial-admission-identity",
            "b".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    byte[] hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
                holdRequest, UUID.randomUUID(), UUID.randomUUID())
            .canonicalBytes();
    OffsetDateTime expiry = now.plusNanos(leaseMillis * 1_000_000L);
    byte[] request =
        json(
            Map.of(
                "schema",
                "account-start-session-admission-protection-request/v1",
                "originalPostAuthorizationTupleBytesBase64",
                base64(tuple),
                "accountRedemptionProjectionBytesBase64",
                base64(projection),
                "gameSessionOwnerMutationId",
                mutation.toString(),
                "gameSessionOwnerAttemptId",
                attempt.toString(),
                "gameSessionOwnerFence",
                "47",
                "originalLeaseExpiresAt",
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(expiry.toInstant()),
                "accountWorldParticipationId",
                world.toString(),
                "accountWorldParticipationFence",
                "1",
                "worldAdmissionHoldIdentityBytesBase64",
                base64(hold)));
    Fixture fixture =
        new Fixture(
            dsl,
            transaction,
            source,
            UUID.randomUUID(),
            requestId,
            tenant,
            instance,
            mutation,
            attempt,
            world,
            expiry,
            evidence,
            tuple,
            projection,
            hold,
            request);
    byte[] snapshot =
        json(
            Map.of(
                "schema",
                "account-start-session-authority-snapshot/v1",
                "controlPlaneRequestId",
                requestId,
                "sourceVector",
                List.of(base64(evidence.canonicalBytes()))));
    transaction.executeWithoutResult(
        status -> {
          dsl.execute("SET LOCAL session_replication_role = replica");
          insert(
              dsl,
              "account_canonical_tenant_identity_claims",
              "canonical_tenant_id",
              tenant,
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
              tenant.toString());
          insert(dsl, "account_draft_authorization_source_locks", "source_key", evidence.key());
          insert(
              dsl,
              "account_start_session_authority_captures",
              "control_plane_request_id",
              requestId,
              "account_uuid",
              actor,
              "tenant_uuid",
              tenant,
              "target_owner",
              "game-session-service",
              "logging_workload_uri",
              "spiffe://firemud/ns/test/sa/logging-admin-service",
              "reservation_owner_id",
              UUID.randomUUID(),
              "reservation_claim_fence",
              7L,
              "pre_authorization_tuple",
              bytes("{}"),
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
              9L,
              "issuance_fence_source_version",
              1L,
              "captured_at",
              capturedAt,
              "snapshot_sha256",
              hash(snapshot),
              "canonical_snapshot_bytes",
              snapshot,
              "source_version",
              11L,
              "source_fence",
              13L,
              "linearization",
              "17",
              "canonical_sha256",
              hash(bytes("{}")),
              "canonical_capture_bytes",
              bytes("{}"));
          insert(
              dsl,
              "account_start_session_operator_authorizations",
              "control_plane_request_id",
              requestId,
              "pre_authorization_tuple",
              bytes("{}"),
              "mutation_digest",
              "c".repeat(64),
              "issuance_workload_uri",
              "spiffe://firemud/ns/test/sa/logging-admin-service",
              "reservation_owner_id",
              UUID.randomUUID(),
              "reservation_claim_fence",
              7L,
              "issuance_operation_id",
              issuance,
              "issuance_fence",
              9L,
              "bundle_version",
              "authorityEvidenceBundle/v1",
              "bundle_source_version",
              "11",
              "bundle_source_fence",
              "13",
              "bundle_linearization",
              "17",
              "authority_evidence_bundle",
              bytes("{}"),
              "authorization_reference_fingerprint",
              "arfp/v1/key-1/" + "a".repeat(64),
              "encrypted_response_envelope",
              bytes("synthetic"),
              "issued_at",
              now.minusSeconds(10),
              "reference_expires_at",
              now.plusSeconds(90),
              "response_envelope_expires_at",
              now.plusSeconds(120),
              "status",
              "REDEEMED",
              "redemption_redeemer_workload_uri",
              "spiffe://firemud/ns/test/sa/game-session-service",
              "redemption_owner_attempt_id",
              attempt,
              "redemption_owner_fence",
              47L,
              "redeemed_at",
              now.minusSeconds(5),
              "redemption_reference_fingerprint",
              "arfp/v1/key-1/" + "a".repeat(64),
              "redemption_authority_evidence_bundle",
              bytes("{}"));
          insert(
              dsl,
              "account_start_session_world_participations",
              "participation_id",
              world,
              "control_plane_request_id",
              requestId,
              "original_post_authorization_tuple",
              tuple,
              "target_namespace",
              "test",
              "canonical_tenant_id",
              tenant,
              "canonical_game_instance_id",
              instance,
              "game_session_owner_attempt_id",
              attempt,
              "game_session_owner_fence",
              47L,
              "preparation_input_json",
              "{}",
              "preparation_input_digest",
              "sha256:" + hash(bytes("{}")));
          insert(
              dsl,
              "account_start_session_world_participation_sources",
              "participation_id",
              world,
              "source_key",
              evidence.key(),
              "source_evidence",
              evidence.canonicalBytes());
          insert(
              dsl,
              "account_start_session_world_original_attempt_evidence",
              "participation_id",
              world,
              "target_namespace",
              "test",
              "game_session_owner_mutation_id",
              mutation,
              "game_session_owner_attempt_id",
              attempt,
              "game_session_owner_fence",
              47L,
              "original_lease_expires_at",
              expiry,
              "original_response_bytes",
              bytes("synthetic-observation"),
              "original_response_digest",
              "sha256:" + hash(bytes("synthetic-observation")));
          insert(
              dsl,
              "account_start_session_world_participation_settlements",
              "participation_id",
              world,
              "outcome",
              "COMMITTED",
              "world_execution_fence",
              1L,
              "terminal_bytes",
              bytes("synthetic-terminal"),
              "terminal_digest",
              "sha256:" + hash(bytes("synthetic-terminal")));
          dsl.execute("SET LOCAL session_replication_role = origin");
        });
    return fixture;
  }

  private static void insert(DSLContext dsl, String table, Object... fields) {
    Map<Field<?>, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < fields.length; index += 2)
      values.put(DSL.field(DSL.name((String) fields[index])), fields[index + 1]);
    try {
      dsl.insertInto(DSL.table(DSL.name(table))).set(values).execute();
    } catch (RuntimeException failure) {
      String sqlState = "unavailable";
      String constraint = "unavailable";
      StringBuilder causeTypes = new StringBuilder();
      var visited =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
      Throwable cause = failure;
      int causeCount = 0;
      while (cause != null && causeCount < 32 && visited.add(cause)) {
        if (causeTypes.length() > 0) causeTypes.append("->");
        causeTypes.append(diagnosticIdentifier(cause.getClass().getSimpleName()));
        causeCount++;
        if (cause instanceof SQLException sqlFailure && sqlFailure.getSQLState() != null) {
          sqlState = sqlFailure.getSQLState();
        }
        if (cause instanceof PSQLException postgresFailure) {
          var serverError = postgresFailure.getServerErrorMessage();
          if (serverError != null && serverError.getConstraint() != null) {
            constraint = serverError.getConstraint();
          }
        }
        cause = cause.getCause();
      }
      if (cause != null) causeTypes.append("->truncated");
      System.err.printf(
          "StartSession admission fixture insert failed: "
              + "table=%s SQLSTATE=%s constraint=%s causeTypes=%s%n",
          diagnosticIdentifier(table),
          diagnosticIdentifier(sqlState),
          diagnosticIdentifier(constraint),
          causeTypes);
      throw failure;
    }
  }

  private static String diagnosticIdentifier(String value) {
    return value != null && value.matches("[A-Za-z0-9_]+") ? value : "unavailable";
  }

  private static Long sequenceValue(Fixture fixture) {
    var sequence =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT namespace.nspname AS schema_name, relation.relname AS sequence_name "
                    + "FROM pg_class relation "
                    + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                    + "WHERE relation.oid = pg_get_serial_sequence("
                    + "format('%I.%I', current_schema(), ?), ?)::regclass",
                "account_start_session_admission_protections",
                "protection_fence");
    return fixture
        .dsl()
        .select(DSL.field(DSL.name("last_value"), Long.class))
        .from(
            DSL.table(
                DSL.name(
                    sequence.get("schema_name", String.class),
                    sequence.get("sequence_name", String.class))))
        .fetchSingle(0, Long.class);
  }

  private static void assertDatabaseGuardFailure(
      Throwable failure, String expectedMessage, String expectedSqlState) {
    var visited =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    Throwable cause = failure;
    PSQLException databaseFailure = null;
    while (cause != null && visited.add(cause)) {
      if (cause instanceof PSQLException postgresFailure) {
        databaseFailure = postgresFailure;
        break;
      }
      cause = cause.getCause();
    }
    assertThat((Throwable) databaseFailure)
        .as("the rejected fixture write must fail at PostgreSQL's database guard")
        .isNotNull();
    assertThat(databaseFailure.getSQLState()).isEqualTo(expectedSqlState);
    assertThat(databaseFailure.getServerErrorMessage()).isNotNull();
    assertThat(databaseFailure.getServerErrorMessage().getMessage()).contains(expectedMessage);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] malformedSettlementBytes() {
    return bytes(
        "{\"canonicalAccountProtectionEvidenceBytesBase64\":\"\","
            + "\"canonicalGameSessionOwnerProofBytesBase64\":\"\","
            + "\"schema\":\"account-start-session-admission-protection-settlement/v2\"}");
  }

  private static byte[] json(Map<String, Object> value) {
    return AccountControlUiAuthority.canonical(value);
  }

  private static String base64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      DriverManagerDataSource dataSource,
      UUID protectionId,
      String requestId,
      UUID tenantId,
      UUID instanceId,
      UUID mutationId,
      UUID attemptId,
      UUID worldId,
      OffsetDateTime expiry,
      SourceEvidence source,
      byte[] tuple,
      byte[] projection,
      byte[] hold,
      byte[] request) {}
}
