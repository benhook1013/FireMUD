package net.firedevops.firemud.accountservice.service.session;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical V1003 repository proof over controlled upstream rows; no mTLS owner authority is
 * inferred.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionAdmissionProtectionRepositoryPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String NAMESPACE = "test";
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACCOUNT = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final Instant LEASE = Instant.parse("2030-01-01T00:05:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void exactHistoricalRepositoryReadReconstructsProtectionAndTerminalBytes() {
    String schema = "ss_admission_repo_" + UUID.randomUUID().toString().replace("-", "");
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
    Fixture fixture = fixture();
    var settlement =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
            fixture.protection(), fixture.terminal());

    transaction.executeWithoutResult(
        status -> {
          dsl.execute("SET LOCAL session_replication_role = replica");
          insertCapture(dsl, fixture);
          dsl.execute(
              "INSERT INTO account_start_session_admission_protections "
                  + "(protection_id, protection_fence, control_plane_request_id, original_post_authorization_tuple, "
                  + "account_redemption_projection, game_session_owner_mutation_id, game_session_owner_attempt_id, "
                  + "game_session_owner_fence, original_lease_expires_at, account_world_participation_id, "
                  + "account_world_participation_fence, target_namespace, canonical_tenant_id, canonical_game_instance_id, "
                  + "capture_source_version, capture_source_fence, capture_sha256, world_admission_hold_identity_bytes, "
                  + "request_binding_bytes, request_binding_digest) OVERRIDING SYSTEM VALUE "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              fixture.protection().accountProtectionId(),
              fixture.protection().accountProtectionFence(),
              fixture.request().originalTuple().controlPlaneRequestId(),
              fixture.request().originalPostAuthorizationTupleBytes(),
              fixture.request().accountRedemptionProjectionBytes(),
              fixture.request().gameSessionOwnerMutationId(),
              fixture.request().gameSessionOwnerAttemptId(),
              fixture.request().gameSessionOwnerFence(),
              OffsetDateTime.ofInstant(fixture.request().originalLeaseExpiresAt(), ZoneOffset.UTC),
              fixture.request().accountWorldParticipationId(),
              fixture.request().accountWorldParticipationFence(),
              NAMESPACE,
              TENANT,
              fixture.request().worldAdmissionHoldIdentity().request().canonicalGameInstanceId(),
              1L,
              1L,
              digestHex(fixture.captureReference()),
              fixture.request().worldAdmissionHoldIdentityBytes(),
              fixture.request().canonicalBytes(),
              digest(fixture.request().canonicalBytes()));
          for (SourceEvidence evidence : fixture.protection().sourceEvidenceVector()) {
            dsl.execute(
                "INSERT INTO account_start_session_admission_protection_sources "
                    + "(protection_id, source_key, source_evidence) VALUES (?, ?, ?)",
                fixture.protection().accountProtectionId(),
                evidence.key(),
                evidence.canonicalBytes());
          }
          dsl.execute(
              "INSERT INTO account_start_session_admission_protection_settlements "
                  + "(protection_id, outcome, terminal_bytes, terminal_digest) VALUES (?, ?, ?, ?)",
              fixture.protection().accountProtectionId(),
              settlement.outcome().name(),
              settlement.canonicalBytes(),
              settlement.digest());
        });

    var repository = new AccountStartSessionAdmissionProtectionRepository(dsl);
    StoredProtection protection =
        transaction.execute(
            status ->
                repository
                    .findHistoricalExact(
                        fixture.protection().accountProtectionId(),
                        fixture.protection().accountProtectionFence())
                    .orElseThrow());
    StoredSettlement storedSettlement =
        transaction.execute(
            status ->
                repository
                    .findSettlementExact(
                        fixture.protection().accountProtectionId(),
                        fixture.protection().accountProtectionFence())
                    .orElseThrow());

    assertThat(protection.evidence().canonicalBytes())
        .containsExactly(fixture.protection().canonicalBytes());
    assertThat(protection.producerTransactionId()).isPositive();
    assertThat(storedSettlement.protection().canonicalBytes())
        .containsExactly(fixture.protection().canonicalBytes());
    assertThat(storedSettlement.canonicalBytes()).containsExactly(settlement.canonicalBytes());
    assertThat(storedSettlement.digest()).isEqualTo(settlement.digest());
    assertThat(storedSettlement.outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(storedSettlement.terminal().holdIdentity().canonicalBytes())
        .containsExactly(fixture.request().worldAdmissionHoldIdentity().canonicalBytes());
  }

  @Test
  void javaRepositoryAcquiresAndReadsUnderEnabledV1003SourceWriterGuards() {
    String schema = "ss_admission_java_repo_" + UUID.randomUUID().toString().replace("-", "");
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
    DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    CurrentFixture fixture = seedControlledCurrentOwnerRows(dsl, transaction);

    var participationRepository = new AccountStartSessionWorldParticipationRepository(dsl);
    var attemptRepository = new AccountStartSessionWorldOriginalAttemptEvidenceRepository(dsl);
    var admissionRepository = new AccountStartSessionAdmissionProtectionRepository(dsl);
    var retainedParticipation =
        transaction.execute(
            ignored ->
                participationRepository
                    .findHistoricalForOriginalTuple(
                        fixture.request().originalPostAuthorizationTupleBytes())
                    .orElseThrow());
    assertThat(retainedParticipation.sources()).containsExactly(fixture.source());
    var retainedAttempt =
        transaction.execute(
            ignored ->
                attemptRepository
                    .findCurrentExact(retainedParticipation, fixture.attemptRequest())
                    .orElseThrow());
    assertThat(retainedAttempt.result().request()).isEqualTo(fixture.attemptRequest());
    assertThat(retainedAttempt.result().accountRedemptionProjection())
        .containsExactly(fixture.request().accountRedemptionProjectionBytes());

    StoredProtection committed =
        transaction.execute(
            ignored ->
                admissionRepository.acquireOrReadExact(
                    fixture.request(), fixture.capture(), retainedParticipation));
    StoredProtection replayed =
        transaction.execute(
            ignored ->
                admissionRepository.acquireOrReadExact(
                    fixture.request(), fixture.capture(), retainedParticipation));
    StoredProtection readback =
        transaction.execute(
            ignored ->
                admissionRepository
                    .findCurrentExact(
                        committed.evidence().accountProtectionId(),
                        committed.evidence().accountProtectionFence(),
                        fixture.request().canonicalBytes())
                    .orElseThrow());

    assertThat(replayed.evidence().canonicalBytes())
        .containsExactly(committed.evidence().canonicalBytes());
    assertThat(readback.evidence().canonicalBytes())
        .containsExactly(committed.evidence().canonicalBytes());
    assertThat(readback.producerTransactionId()).isPositive();
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isEqualTo(1L);

    // Direct installed admission-hook proof after canonical source-row locking. The older
    // World participation remains pending; this is not full writer causation after World
    // settlement.
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      var lockedKeys =
                          dsl.fetch(
                                  "SELECT source_key FROM account_draft_authorization_source_locks "
                                      + "WHERE source_key = ANY(ARRAY[?]::text[]) "
                                      + "ORDER BY account_publication_authorization_source_sort_key(source_key) "
                                      + "FOR UPDATE",
                                  fixture.source().key())
                              .getValues(0, String.class);
                      assertThat(lockedKeys).containsExactly(fixture.source().key());
                      dsl.execute(
                          "SELECT account_ss_admission_assert_no_pending(ARRAY[?]::text[])",
                          fixture.source().key());
                    }))
        .hasMessageContaining("Original StartSession admission protection remains pending");

    AccountStartSessionAdmissionProtectionRequest changedRequest =
        AccountStartSessionAdmissionProtectionRequest.create(
            fixture.request().originalPostAuthorizationTupleBytes(),
            fixture.request().accountRedemptionProjectionBytes(),
            fixture.request().gameSessionOwnerMutationId(),
            fixture.request().gameSessionOwnerAttemptId(),
            fixture.request().gameSessionOwnerFence(),
            fixture.request().originalLeaseExpiresAt().plusMillis(1),
            fixture.request().accountWorldParticipationId(),
            fixture.request().accountWorldParticipationFence(),
            fixture.request().worldAdmissionHoldIdentity());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        admissionRepository.findCurrentExact(
                            committed.evidence().accountProtectionId(),
                            committed.evidence().accountProtectionFence(),
                            changedRequest.canonicalBytes())))
        .hasMessageContaining("Original admission recovery binding differs");
  }

  private static CurrentFixture seedControlledCurrentOwnerRows(
      DSLContext dsl, TransactionTemplate transaction) {
    Instant databaseNow =
        dsl.fetchSingle("SELECT clock_timestamp()")
            .get(0, OffsetDateTime.class)
            .toInstant()
            .truncatedTo(ChronoUnit.MICROS);
    Instant capturedInstant = databaseNow.truncatedTo(ChronoUnit.MILLIS);
    String capturedAt =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(capturedInstant);
    Instant referenceExpiry = databaseNow.plusSeconds(240L).truncatedTo(ChronoUnit.MICROS);
    Instant originalLeaseExpiry = databaseNow.plusSeconds(60L).truncatedTo(ChronoUnit.MICROS);
    String controlPlaneRequestId = "admission-current-" + UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    UUID reservationOwnerId = UUID.randomUUID();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID ownerMutationId = UUID.randomUUID();
    UUID participationId = UUID.randomUUID();
    UUID controlUiOperationId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    long ownerFence = 47L;

    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            controlPlaneRequestId,
            ACCOUNT,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, ACCOUNT),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "TEST_ONLY controlled V1003 owner facts"));
    byte[] bundleBytes =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new StartSessionAuthorityEvidenceBundleFixture.Values(
                operationId,
                "sha256:" + "a".repeat(64),
                "1",
                "CURRENT",
                databaseNow.minusSeconds(1L).truncatedTo(ChronoUnit.MILLIS),
                referenceExpiry.truncatedTo(ChronoUnit.MILLIS),
                1L,
                1L,
                1L,
                1L,
                1L,
                1L,
                tokenJti,
                "1",
                "1"));
    var authorityBundle = StartSessionAuthorityEvidenceBundle.decode(bundleBytes);
    var bundleReference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "1", "1", "1");
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/test/sa/logging-admin-service",
            "arfp/v1/test-key/" + "d".repeat(64),
            reservationOwnerId,
            7L,
            bundleBytes,
            bundleReference);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "controlled-world",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            gameInstanceId,
            UUID.randomUUID(),
            3L,
            tuple.controlPlaneRequestId(),
            "e".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null);
    HoldIdentity hold = new HoldIdentity(holdRequest, UUID.randomUUID(), UUID.randomUUID());
    SourceEvidence sourceEvidence =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            "controlled-currentness-source",
            "1",
            "1",
            null,
            null,
            bytes("controlled upstream source evidence"));
    byte[] preTupleBytes = preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    byte[] tupleBytes = tuple.canonicalBytes();
    byte[] projection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    byte[] sourceVectorEvidence = bytes("controlled upstream source vector");
    byte[] signerReceipt = bytes("controlled upstream signer receipt");
    String tokenHash = digestHex(bytes("controlled test token hash"));
    long sourceVersion = Long.parseLong(bundleReference.sourceVersion());
    long sourceFence = Long.parseLong(bundleReference.sourceFence());
    String linearization = bundleReference.linearization();

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", "account-start-session-authority-snapshot/v1");
    snapshot.put("controlPlaneRequestId", tuple.controlPlaneRequestId());
    snapshot.put("preAuthorizationTuple", Base64.getEncoder().encodeToString(preTupleBytes));
    snapshot.put("mutationDigest", tuple.mutationDigest());
    snapshot.put("accountId", ACCOUNT.toString());
    snapshot.put("tenantId", TENANT.toString());
    snapshot.put("targetOwner", "game-session-service");
    snapshot.put("loggingWorkloadUri", tuple.authenticatedWorkloadIdentity());
    snapshot.put("reservationOwnerId", reservationOwnerId.toString());
    snapshot.put("reservationClaimFence", Long.toString(tuple.reservationClaimFence()));
    snapshot.put("controlUiOperationId", controlUiOperationId.toString());
    snapshot.put("controlUiTokenJti", tokenJti.toString());
    snapshot.put("controlUiTokenHash", tokenHash);
    snapshot.put("controlUiSignerReceipt", Base64.getEncoder().encodeToString(signerReceipt));
    snapshot.put("controlUiSignerReceiptSha256", digestHex(signerReceipt));
    snapshot.put("sourceVectorEvidence", Base64.getEncoder().encodeToString(sourceVectorEvidence));
    snapshot.put(
        "sourceVector",
        List.of(Base64.getEncoder().encodeToString(sourceEvidence.canonicalBytes())));
    snapshot.put("outboxCheckpoints", Map.of());
    snapshot.put("authorityTuple", authorityBundle.authorityTuple());
    snapshot.put("membershipVersion", authorityBundle.membershipVersion());
    snapshot.put("accountIdentitySource", "controlled-account-identity-source");
    snapshot.put("issuanceFence", tuple.issuanceFence());
    snapshot.put("issuanceFenceSourceVersion", bundleReference.sourceVersion());
    byte[] snapshotBytes = canonicalJson(snapshot);
    AccountStartSessionAuthorityCapture capture =
        AccountStartSessionAuthorityCapture.create(
            tuple.controlPlaneRequestId(),
            sourceVersion,
            sourceFence,
            linearization,
            capturedAt,
            snapshotBytes);

    var attemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            UUID.randomUUID(), NAMESPACE, tupleBytes, ownerAttemptId, ownerMutationId, ownerFence);
    var attemptResult =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            attemptRequest, originalLeaseExpiry, projection);
    byte[] attemptResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(attemptResult).toByteArray();

    CurrentFixture currentFixture =
        transaction.execute(
            status -> {
              dsl.execute("SET LOCAL session_replication_role = replica");
              dsl.execute(
                  "INSERT INTO account_canonical_tenant_identity_claims "
                      + "(canonical_tenant_id, identity_kind, source_operation_id, "
                      + "source_account_legacy_tenant_id, source_target_namespace, "
                      + "source_game_row_id, source_game_tenant_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  TENANT,
                  "APPROVED_RETAINED",
                  UUID.randomUUID(),
                  1L,
                  NAMESPACE,
                  1L,
                  TENANT.toString());
              dsl.execute(
                  "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)",
                  sourceEvidence.key());
              dsl.execute(
                  "INSERT INTO account_start_session_authority_captures "
                      + "(control_plane_request_id, account_uuid, tenant_uuid, target_owner, "
                      + "logging_workload_uri, reservation_owner_id, reservation_claim_fence, "
                      + "pre_authorization_tuple, mutation_digest, control_ui_operation_id, "
                      + "control_ui_token_jti, control_ui_token_hash, "
                      + "control_ui_signer_receipt_sha256, issuance_fence, "
                      + "issuance_fence_source_version, captured_at, snapshot_sha256, "
                      + "canonical_snapshot_bytes, source_version, source_fence, linearization, "
                      + "canonical_sha256, canonical_capture_bytes) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?)",
                  tuple.controlPlaneRequestId(),
                  ACCOUNT,
                  TENANT,
                  "game-session-service",
                  tuple.authenticatedWorkloadIdentity(),
                  reservationOwnerId,
                  tuple.reservationClaimFence(),
                  preTupleBytes,
                  tuple.mutationDigest(),
                  controlUiOperationId,
                  tokenJti,
                  tokenHash,
                  digestHex(signerReceipt),
                  Long.parseLong(tuple.issuanceFence()),
                  sourceVersion,
                  OffsetDateTime.ofInstant(capturedInstant, ZoneOffset.UTC),
                  capture.snapshotSha256(),
                  capture.snapshotBytes(),
                  capture.sourceVersion(),
                  capture.sourceFence(),
                  capture.linearization(),
                  capture.canonicalSha256(),
                  capture.canonicalBytes());
              dsl.execute(
                  "INSERT INTO account_start_session_operator_authorizations "
                      + "(control_plane_request_id, pre_authorization_tuple, mutation_digest, "
                      + "issuance_workload_uri, reservation_owner_id, reservation_claim_fence, "
                      + "issuance_operation_id, issuance_fence, bundle_version, bundle_source_version, "
                      + "bundle_source_fence, bundle_linearization, authority_evidence_bundle, "
                      + "authorization_reference_fingerprint, encrypted_response_envelope, issued_at, "
                      + "reference_expires_at, response_envelope_expires_at, status, "
                      + "redemption_redeemer_workload_uri, redemption_owner_attempt_id, "
                      + "redemption_owner_fence, redeemed_at, redemption_reference_fingerprint, "
                      + "redemption_authority_evidence_bundle) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?)",
                  tuple.controlPlaneRequestId(),
                  preTupleBytes,
                  tuple.mutationDigest(),
                  tuple.authenticatedWorkloadIdentity(),
                  tuple.reservationOwnerId(),
                  tuple.reservationClaimFence(),
                  authorityBundle.issuanceOperationId(),
                  Long.parseLong(authorityBundle.issuanceFence()),
                  bundleReference.bundleVersion(),
                  bundleReference.sourceVersion(),
                  bundleReference.sourceFence(),
                  bundleReference.linearization(),
                  bundleBytes,
                  tuple.authorizationReferenceFingerprint(),
                  bytes("controlled owner response envelope"),
                  OffsetDateTime.ofInstant(databaseNow.minusSeconds(2L), ZoneOffset.UTC),
                  OffsetDateTime.ofInstant(referenceExpiry, ZoneOffset.UTC),
                  OffsetDateTime.ofInstant(referenceExpiry.plusSeconds(30L), ZoneOffset.UTC),
                  "REDEEMED",
                  "spiffe://firemud/ns/test/sa/game-session-service",
                  ownerAttemptId,
                  ownerFence,
                  OffsetDateTime.ofInstant(databaseNow.minusSeconds(1L), ZoneOffset.UTC),
                  tuple.authorizationReferenceFingerprint(),
                  bundleBytes);
              dsl.execute(
                  "INSERT INTO account_start_session_world_participations "
                      + "(participation_id, control_plane_request_id, "
                      + "original_post_authorization_tuple, target_namespace, canonical_tenant_id, "
                      + "canonical_game_instance_id, game_session_owner_attempt_id, "
                      + "game_session_owner_fence, preparation_input_json, preparation_input_digest) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  participationId,
                  tuple.controlPlaneRequestId(),
                  tupleBytes,
                  NAMESPACE,
                  TENANT,
                  gameInstanceId,
                  ownerAttemptId,
                  ownerFence,
                  "{}",
                  digest(bytes("{}")));
              long participationFence =
                  dsl.fetchSingle(
                          "SELECT participation_fence FROM account_start_session_world_participations "
                              + "WHERE participation_id = ?",
                          participationId)
                      .get(0, Long.class);
              dsl.execute(
                  "INSERT INTO account_start_session_world_participation_sources "
                      + "(participation_id, source_key, source_evidence) VALUES (?, ?, ?)",
                  participationId,
                  sourceEvidence.key(),
                  sourceEvidence.canonicalBytes());
              dsl.execute(
                  "INSERT INTO account_start_session_world_original_attempt_evidence "
                      + "(participation_id, target_namespace, game_session_owner_attempt_id, "
                      + "game_session_owner_mutation_id, game_session_owner_fence, "
                      + "original_lease_expires_at, original_response_bytes, original_response_digest) "
                      + "VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?)",
                  participationId,
                  NAMESPACE,
                  ownerAttemptId,
                  ownerMutationId,
                  ownerFence,
                  OffsetDateTime.ofInstant(originalLeaseExpiry, ZoneOffset.UTC),
                  attemptResponse,
                  digest(attemptResponse));
              var request =
                  AccountStartSessionAdmissionProtectionRequest.create(
                      tupleBytes,
                      projection,
                      ownerMutationId,
                      ownerAttemptId,
                      ownerFence,
                      originalLeaseExpiry,
                      participationId,
                      participationFence,
                      hold);
              dsl.execute("SET LOCAL session_replication_role = origin");
              return new CurrentFixture(request, capture, sourceEvidence, attemptRequest);
            });

    return currentFixture;
  }

  private static void insertCapture(DSLContext dsl, Fixture fixture) {
    byte[] snapshot = bytes("{}");
    dsl.execute(
        "INSERT INTO account_start_session_authority_captures "
            + "(control_plane_request_id, account_uuid, tenant_uuid, target_owner, logging_workload_uri, "
            + "reservation_owner_id, reservation_claim_fence, pre_authorization_tuple, mutation_digest, "
            + "control_ui_operation_id, control_ui_token_jti, control_ui_token_hash, "
            + "control_ui_signer_receipt_sha256, issuance_fence, issuance_fence_source_version, captured_at, "
            + "snapshot_sha256, canonical_snapshot_bytes, source_version, source_fence, linearization, "
            + "canonical_sha256, canonical_capture_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?)",
        fixture.request().originalTuple().controlPlaneRequestId(),
        ACCOUNT,
        TENANT,
        "game-session-service",
        "spiffe://firemud/ns/test/sa/logging-admin-service",
        UUID.randomUUID(),
        5L,
        fixture
            .request()
            .originalTuple()
            .preAuthorizationTuple()
            .canonicalJson()
            .getBytes(StandardCharsets.UTF_8),
        "a".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64),
        "c".repeat(64),
        1L,
        1L,
        OffsetDateTime.parse("2030-01-01T00:00:00Z"),
        digestHex(snapshot),
        snapshot,
        1L,
        1L,
        "1",
        digestHex(fixture.captureReference()),
        fixture.captureReference());
  }

  private static Fixture fixture() {
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "admission-repository-" + UUID.randomUUID(),
            ACCOUNT,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, UUID.randomUUID()),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "TEST_ONLY exact stored Account admission protection"));
    byte[] bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            pre,
            new StartSessionAuthorityEvidenceBundleFixture.Values(
                UUID.randomUUID(),
                "sha256:" + "a".repeat(64),
                "1",
                "CURRENT",
                Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2030-01-01T00:04:00Z"),
                1L,
                1L,
                1L,
                1L,
                1L,
                1L,
                UUID.randomUUID(),
                "1",
                "1"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/test/sa/logging-admin-service",
            "arfp/v1/test-key/" + "d".repeat(64),
            UUID.randomUUID(),
            1L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "1", "1", "1"));
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            UUID.randomUUID(),
            3L,
            tuple.controlPlaneRequestId(),
            "e".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, UUID.randomUUID(), UUID.randomUUID());
    UUID mutation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    UUID participation = UUID.randomUUID();
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            mutation,
            attempt,
            7L,
            LEASE,
            participation,
            11L,
            hold);
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            TENANT.toString(),
            "1",
            "1",
            null,
            null,
            bytes("controlled-account-source"));
    byte[] captureReference = captureReference(tuple, bytes("{}"));
    var evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            UUID.randomUUID(),
            23L,
            captureReference,
            digestHex(captureReference),
            List.of(source));
    var terminal =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            1L,
            31L,
            "sha256:" + "f".repeat(64),
            false,
            Instant.parse("2030-01-01T00:01:00Z"));
    return new Fixture(request, evidence, terminal, captureReference);
  }

  private static byte[] captureReference(
      StartSessionPostAuthorizationExecutionTuple tuple, byte[] snapshot) {
    Map<String, Object> reference =
        Map.of(
            "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2030-01-01T00:00:00.000Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", "1",
                    "sourceFence", "1",
                    "linearization", "1"),
            "snapshotSha256", digestHex(snapshot));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(reference));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] canonicalJson(Map<String, ?> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digestHex(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(byte[] value) {
    return "sha256:" + digestHex(value);
  }

  private record Fixture(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof terminal,
      byte[] captureReference) {
    private Fixture {
      captureReference = captureReference.clone();
    }

    @Override
    public byte[] captureReference() {
      return captureReference.clone();
    }
  }

  private record CurrentFixture(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      SourceEvidence source,
      OriginalStartSessionCurrentAttemptEvidence.Request attemptRequest) {}
}
