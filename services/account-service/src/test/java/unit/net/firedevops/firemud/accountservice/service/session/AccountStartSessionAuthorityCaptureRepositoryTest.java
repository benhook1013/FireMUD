package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.Snapshot;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountStartSessionAuthorityCaptureRepositoryTest {
  private static final String REQUEST_ID = "capture-repository-unit";
  private static final String CAPTURED_AT = "2026-10-10T12:34:56.789Z";
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CONTROL_UI_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CONTROL_UI_TOKEN_JTI =
      UUID.fromString("55555555-5555-4555-8555-555555555555");

  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      field(name("control_plane_request_id"), SQLDataType.VARCHAR);
  private static final Field<UUID> ACCOUNT_UUID = field(name("account_uuid"), SQLDataType.UUID);
  private static final Field<UUID> TENANT_UUID = field(name("tenant_uuid"), SQLDataType.UUID);
  private static final Field<String> TARGET_OWNER =
      field(name("target_owner"), SQLDataType.VARCHAR);
  private static final Field<String> LOGGING_WORKLOAD_URI =
      field(name("logging_workload_uri"), SQLDataType.VARCHAR);
  private static final Field<UUID> RESERVATION_OWNER_UUID =
      field(name("reservation_owner_id"), SQLDataType.UUID);
  private static final Field<Long> RESERVATION_CLAIM_FENCE =
      field(name("reservation_claim_fence"), SQLDataType.BIGINT);
  private static final Field<String> MUTATION_DIGEST =
      field(name("mutation_digest"), SQLDataType.VARCHAR);
  private static final Field<UUID> CONTROL_UI_OPERATION_UUID =
      field(name("control_ui_operation_id"), SQLDataType.UUID);
  private static final Field<UUID> CONTROL_UI_TOKEN_UUID =
      field(name("control_ui_token_jti"), SQLDataType.UUID);
  private static final Field<String> CONTROL_UI_TOKEN_HASH =
      field(name("control_ui_token_hash"), SQLDataType.VARCHAR);
  private static final Field<String> SIGNER_RECEIPT_SHA256 =
      field(name("control_ui_signer_receipt_sha256"), SQLDataType.VARCHAR);
  private static final Field<Long> ISSUANCE_FENCE =
      field(name("issuance_fence"), SQLDataType.BIGINT);
  private static final Field<Long> ISSUANCE_FENCE_SOURCE_VERSION =
      field(name("issuance_fence_source_version"), SQLDataType.BIGINT);
  private static final Field<String> CAPTURED_AT_FIELD =
      field(name("captured_at"), SQLDataType.VARCHAR);
  private static final Field<String> SNAPSHOT_SHA256 =
      field(name("snapshot_sha256"), SQLDataType.VARCHAR);
  private static final Field<byte[]> SNAPSHOT_BYTES =
      field(name("canonical_snapshot_bytes"), SQLDataType.VARBINARY);
  private static final Field<Long> SOURCE_VERSION =
      field(name("source_version"), SQLDataType.BIGINT);
  private static final Field<Long> SOURCE_FENCE = field(name("source_fence"), SQLDataType.BIGINT);
  private static final Field<String> LINEARIZATION =
      field(name("linearization"), SQLDataType.VARCHAR);
  private static final Field<String> CANONICAL_SHA256 =
      field(name("canonical_sha256"), SQLDataType.VARCHAR);
  private static final Field<byte[]> CANONICAL_BYTES =
      field(name("canonical_capture_bytes"), SQLDataType.VARBINARY);
  private static final Field<byte[]> PRE_AUTHORIZATION_TUPLE =
      field(name("pre_authorization_tuple"), SQLDataType.VARBINARY);
  private static final Field<UUID> ISSUANCE_REQUEST_ID =
      field(name("request_id"), SQLDataType.UUID);
  private static final Field<UUID> ISSUANCE_OPERATION_ID =
      field(name("operation_id"), SQLDataType.UUID);
  private static final Field<UUID> ISSUANCE_TOKEN_JTI = field(name("token_jti"), SQLDataType.UUID);
  private static final Field<UUID> ISSUANCE_CALLER_CONTEXT_ID =
      field(name("caller_context_id"), SQLDataType.UUID);
  private static final Field<String> ISSUANCE_CALLER_WORKLOAD =
      field(name("caller_workload"), SQLDataType.VARCHAR);
  private static final Field<String> ISSUANCE_REQUEST_MAC_KEY_ID =
      field(name("request_mac_key_id"), SQLDataType.VARCHAR);
  private static final Field<String> ISSUANCE_REQUEST_DIGEST =
      field(name("request_digest"), SQLDataType.VARCHAR);
  private static final Field<String> ISSUANCE_STATUS = field(name("status"), SQLDataType.VARCHAR);
  private static final Field<String> ISSUANCE_TOKEN_HASH =
      field(name("token_hash"), SQLDataType.VARCHAR);
  private static final Field<byte[]> ISSUANCE_CLAIMS =
      field(name("claims_payload"), SQLDataType.VARBINARY);
  private static final Field<byte[]> ISSUANCE_SOURCES =
      field(name("source_payload"), SQLDataType.VARBINARY);
  private static final Field<byte[]> ISSUANCE_BUNDLE =
      field(name("bundle_payload"), SQLDataType.VARBINARY);
  private static final Field<byte[]> ISSUANCE_SIGNER_RECEIPT =
      field(name("signer_receipt"), SQLDataType.VARBINARY);
  private static final Field<byte[]> ISSUANCE_PENDING_REGISTRY =
      field(name("pending_registry"), SQLDataType.VARBINARY);
  private static final Field<byte[]> ISSUANCE_ACTIVE_REGISTRY =
      field(name("active_registry"), SQLDataType.VARBINARY);
  private static final Field<Long> ISSUANCE_CREATED_AT =
      field(name("issued_at_epoch_second"), SQLDataType.BIGINT);
  private static final Field<Long> ISSUANCE_EXPIRES_AT =
      field(name("expires_at_epoch_second"), SQLDataType.BIGINT);
  private static final Field<OffsetDateTime> ISSUANCE_RECOVERY_EXPIRES_AT =
      field(name("recovery_expires_at"), SQLDataType.OFFSETDATETIME);

  private static final String NAMESPACE = "gameplay";
  private static final String LOGGING_PEER_URI =
      "spiffe://firemud/ns/gameplay/sa/logging-admin-service";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/gameplay/sa/world-management-service";

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void decodesCanonicalSnapshotUuidsFromTypedJooqUuidColumns() {
    CaptureFixture fixture = fixture(ACCOUNT_ID.toString());

    assertThat(fixture.row().get(ACCOUNT_UUID, UUID.class)).isEqualTo(ACCOUNT_ID);
    assertThat(fixture.row().get(TENANT_UUID, UUID.class)).isEqualTo(TENANT_ID);
    assertThat(decode(fixture.row()).sameStoredValue(fixture.capture())).isTrue();
  }

  @Test
  void rejectsWrongMissingAndMalformedSnapshotUuidEvidence() {
    assertUnavailable(fixture("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").row());
    assertUnavailable(fixture("not-a-uuid").row());
    assertUnavailable(fixture(null).row());

    CaptureFixture missingStoredUuid = fixture(ACCOUNT_ID.toString());
    missingStoredUuid.row().setValue(ACCOUNT_UUID, (UUID) null);
    assertUnavailable(missingStoredUuid.row());
  }

  @Test
  void worldReceivingLookupAcceptsWorldPeerAndRetainedLoggingIssuer() {
    WorldLookupFixture fixture = worldLookupFixture(true);
    beginOwnerTransaction();

    AccountStartSessionAuthorityCapture observed =
        withPeer(WORLD_PEER_URI, NAMESPACE, "world-management-service")
            .call(
                () ->
                    fixture
                        .repository()
                        .lockReadExactCurrentFromWorldReceiving(
                            fixture.current(),
                            fixture.tuple(),
                            LOGGING_PEER_URI,
                            RESERVATION_OWNER_ID,
                            7L,
                            WORLD_PEER_URI));

    assertThat(observed.sameStoredValue(fixture.capture())).isTrue();
    verify(fixture.dsl(), times(1))
        .fetchOne(startsWith("SELECT control_plane_request_id"), any(Object[].class));
    verifyNoMoreInteractions(fixture.dsl());
  }

  @Test
  void worldReceivingLookupRejectsWrongPeerAndNamespaceBeforeStorage() {
    WorldLookupFixture fixture = worldLookupFixture(true);
    beginOwnerTransaction();

    assertUnavailable(
        () ->
            withPeer(LOGGING_PEER_URI, NAMESPACE, "logging-admin-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrentFromWorldReceiving(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L,
                                WORLD_PEER_URI)));
    assertUnavailable(
        () ->
            withPeer(
                    "spiffe://firemud/ns/other/sa/world-management-service",
                    "other",
                    "world-management-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrentFromWorldReceiving(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L,
                                WORLD_PEER_URI)));

    verifyNoInteractions(fixture.dsl());
  }

  @Test
  void worldReceivingLookupRejectsAmbientEndUserContextBeforeStorage() {
    WorldLookupFixture fixture = worldLookupFixture(true);
    beginOwnerTransaction();
    SessionContext.setContext("account-user", List.of("tenantAdmin"), Map.of());

    assertUnavailable(
        () ->
            withPeer(WORLD_PEER_URI, NAMESPACE, "world-management-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrentFromWorldReceiving(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L,
                                WORLD_PEER_URI)));

    verifyNoInteractions(fixture.dsl());
  }

  @Test
  void worldReceivingLookupDoesNotCreateMissingOriginalCapture() {
    WorldLookupFixture fixture = worldLookupFixture(false);
    beginOwnerTransaction();

    assertUnavailable(
        () ->
            withPeer(WORLD_PEER_URI, NAMESPACE, "world-management-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrentFromWorldReceiving(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L,
                                WORLD_PEER_URI)));

    verify(fixture.dsl(), times(1))
        .fetchOne(startsWith("SELECT control_plane_request_id"), any(Object[].class));
    verifyNoMoreInteractions(fixture.dsl());
  }

  @Test
  void existingExactCurrentReadStillRequiresLoggingPeer() {
    WorldLookupFixture fixture = worldLookupFixture(true);
    beginOwnerTransaction();

    assertUnavailable(
        () ->
            withPeer(WORLD_PEER_URI, NAMESPACE, "world-management-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrent(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L)));
    assertThat(
            withPeer(LOGGING_PEER_URI, NAMESPACE, "logging-admin-service")
                .call(
                    () ->
                        fixture
                            .repository()
                            .lockReadExactCurrent(
                                fixture.current(),
                                fixture.tuple(),
                                LOGGING_PEER_URI,
                                RESERVATION_OWNER_ID,
                                7L))
                .sameStoredValue(fixture.capture()))
        .isTrue();
  }

  private static CaptureFixture fixture(String snapshotAccountId) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", "account-start-session-authority-snapshot/v1");
    snapshot.put(
        "preAuthorizationTuple",
        java.util.Base64.getEncoder()
            .encodeToString("canonical tuple".getBytes(StandardCharsets.UTF_8)));
    snapshot.put("mutationDigest", "a".repeat(64));
    if (snapshotAccountId != null) snapshot.put("accountId", snapshotAccountId);
    snapshot.put("tenantId", TENANT_ID.toString());
    snapshot.put("targetOwner", "game-session-service");
    snapshot.put("loggingWorkloadUri", "spiffe://firemud/ns/gameplay/sa/logging-admin-service");
    snapshot.put("reservationOwnerId", RESERVATION_OWNER_ID.toString());
    snapshot.put("reservationClaimFence", "7");
    snapshot.put("controlUiOperationId", CONTROL_UI_OPERATION_ID.toString());
    snapshot.put("controlUiTokenJti", CONTROL_UI_TOKEN_JTI.toString());
    snapshot.put("controlUiTokenHash", "b".repeat(64));
    snapshot.put("controlUiSignerReceiptSha256", "c".repeat(64));
    snapshot.put("issuanceFence", "9");
    snapshot.put("issuanceFenceSourceVersion", "10");

    byte[] snapshotBytes = AccountControlUiAuthority.canonical(snapshot);
    AccountStartSessionAuthorityCapture capture =
        AccountStartSessionAuthorityCapture.create(
            REQUEST_ID, 11L, 13L, "17", CAPTURED_AT, snapshotBytes);

    Record row =
        DSL.using(SQLDialect.POSTGRES)
            .newRecord(
                CONTROL_PLANE_REQUEST_ID,
                ACCOUNT_UUID,
                TENANT_UUID,
                TARGET_OWNER,
                LOGGING_WORKLOAD_URI,
                RESERVATION_OWNER_UUID,
                RESERVATION_CLAIM_FENCE,
                MUTATION_DIGEST,
                CONTROL_UI_OPERATION_UUID,
                CONTROL_UI_TOKEN_UUID,
                CONTROL_UI_TOKEN_HASH,
                SIGNER_RECEIPT_SHA256,
                ISSUANCE_FENCE,
                ISSUANCE_FENCE_SOURCE_VERSION,
                CAPTURED_AT_FIELD,
                SNAPSHOT_SHA256,
                SNAPSHOT_BYTES,
                SOURCE_VERSION,
                SOURCE_FENCE,
                LINEARIZATION,
                CANONICAL_SHA256,
                CANONICAL_BYTES);
    row.setValue(CONTROL_PLANE_REQUEST_ID, REQUEST_ID);
    row.setValue(ACCOUNT_UUID, ACCOUNT_ID);
    row.setValue(TENANT_UUID, TENANT_ID);
    row.setValue(TARGET_OWNER, "game-session-service");
    row.setValue(LOGGING_WORKLOAD_URI, "spiffe://firemud/ns/gameplay/sa/logging-admin-service");
    row.setValue(RESERVATION_OWNER_UUID, RESERVATION_OWNER_ID);
    row.setValue(RESERVATION_CLAIM_FENCE, 7L);
    row.setValue(MUTATION_DIGEST, "a".repeat(64));
    row.setValue(CONTROL_UI_OPERATION_UUID, CONTROL_UI_OPERATION_ID);
    row.setValue(CONTROL_UI_TOKEN_UUID, CONTROL_UI_TOKEN_JTI);
    row.setValue(CONTROL_UI_TOKEN_HASH, "b".repeat(64));
    row.setValue(SIGNER_RECEIPT_SHA256, "c".repeat(64));
    row.setValue(ISSUANCE_FENCE, 9L);
    row.setValue(ISSUANCE_FENCE_SOURCE_VERSION, 10L);
    row.setValue(CAPTURED_AT_FIELD, CAPTURED_AT);
    row.setValue(SNAPSHOT_SHA256, capture.snapshotSha256());
    row.setValue(SNAPSHOT_BYTES, capture.snapshotBytes());
    row.setValue(SOURCE_VERSION, 11L);
    row.setValue(SOURCE_FENCE, 13L);
    row.setValue(LINEARIZATION, "17");
    row.setValue(CANONICAL_SHA256, capture.canonicalSha256());
    row.setValue(CANONICAL_BYTES, capture.canonicalBytes());
    return new CaptureFixture(row, capture);
  }

  private static AccountStartSessionAuthorityCapture decode(Record row) {
    try {
      var method =
          AccountStartSessionAuthorityCaptureRepository.class.getDeclaredMethod(
              "decode", Record.class);
      method.setAccessible(true);
      return (AccountStartSessionAuthorityCapture)
          method.invoke(
              new AccountStartSessionAuthorityCaptureRepository(DSL.using(SQLDialect.POSTGRES)),
              row);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new AssertionError("Unexpected capture decode failure", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not invoke capture decoder", failure);
    }
  }

  private static void assertUnavailable(Record row) {
    assertThatThrownBy(() -> decode(row))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account StartSession source capture is unavailable");
  }

  private static void assertUnavailable(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account StartSession source capture is unavailable");
  }

  private static WorldLookupFixture worldLookupFixture(boolean capturePresent) {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    Snapshot source = sourceSnapshot();
    var stored = issuance(tuple);
    CaptureFixture capture = worldCapture(tuple, source, stored);
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenReturn(capturePresent ? capture.row() : null);
    return new WorldLookupFixture(
        dsl,
        new AccountStartSessionAuthorityCaptureRepository(dsl),
        new AccountControlUiActorService.Current(stored, source),
        tuple,
        capture.capture());
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(71L, ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "capture lookup test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(REQUEST_ID, ACCOUNT_ID, action);
  }

  private static Snapshot sourceSnapshot() {
    SourceEvidence sourceEvidence =
        new SourceEvidence(
            SourceKind.TENANT,
            TENANT_ID.toString(),
            "3",
            "4",
            null,
            null,
            "exact tenant source".getBytes(StandardCharsets.UTF_8));
    try {
      var constructor =
          Snapshot.class.getDeclaredConstructor(
              UUID.class,
              UUID.class,
              Map.class,
              Map.class,
              long.class,
              long.class,
              List.class,
              byte[].class,
              List.class,
              Map.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          ACCOUNT_ID,
          TENANT_ID,
          Map.of("issuerAuthGeneration", 1L),
          Map.of(TENANT_ID.toString(), 2L),
          9L,
          10L,
          List.of(sourceEvidence),
          "exact source vector".getBytes(StandardCharsets.UTF_8),
          List.of(),
          Map.of("sourceRowId", 17L, "provenance", "canonical", "sourceNumericId", 17L));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not construct the captured Account source fixture", failure);
    }
  }

  private static AccountControlUiIssuanceRepository.Stored issuance(
      StartSessionPreAuthorizationReservationTuple tuple) {
    Record row =
        DSL.using(SQLDialect.POSTGRES)
            .newRecord(
                ISSUANCE_REQUEST_ID,
                ISSUANCE_OPERATION_ID,
                ISSUANCE_TOKEN_JTI,
                ACCOUNT_UUID,
                TENANT_UUID,
                ISSUANCE_CALLER_CONTEXT_ID,
                ISSUANCE_CALLER_WORKLOAD,
                ISSUANCE_REQUEST_MAC_KEY_ID,
                ISSUANCE_REQUEST_DIGEST,
                ISSUANCE_STATUS,
                ISSUANCE_TOKEN_HASH,
                ISSUANCE_CLAIMS,
                ISSUANCE_SOURCES,
                ISSUANCE_BUNDLE,
                ISSUANCE_SIGNER_RECEIPT,
                ISSUANCE_PENDING_REGISTRY,
                ISSUANCE_ACTIVE_REGISTRY,
                ISSUANCE_CREATED_AT,
                ISSUANCE_EXPIRES_AT,
                ISSUANCE_RECOVERY_EXPIRES_AT);
    row.setValue(ISSUANCE_REQUEST_ID, CONTROL_UI_OPERATION_ID);
    row.setValue(ISSUANCE_OPERATION_ID, CONTROL_UI_OPERATION_ID);
    row.setValue(ISSUANCE_TOKEN_JTI, CONTROL_UI_TOKEN_JTI);
    row.setValue(ACCOUNT_UUID, tuple.actor().accountId());
    row.setValue(TENANT_UUID, tuple.action().scope().tenantId());
    row.setValue(
        ISSUANCE_CALLER_CONTEXT_ID, UUID.fromString("66666666-6666-4666-8666-666666666666"));
    row.setValue(ISSUANCE_CALLER_WORKLOAD, LOGGING_PEER_URI);
    row.setValue(ISSUANCE_REQUEST_MAC_KEY_ID, "capture-test-key");
    row.setValue(ISSUANCE_REQUEST_DIGEST, "request-digest");
    row.setValue(ISSUANCE_STATUS, "COMMITTED");
    row.setValue(ISSUANCE_TOKEN_HASH, "b".repeat(64));
    row.setValue(ISSUANCE_CLAIMS, "claims".getBytes(StandardCharsets.UTF_8));
    row.setValue(ISSUANCE_SOURCES, "sources".getBytes(StandardCharsets.UTF_8));
    row.setValue(ISSUANCE_BUNDLE, "bundle".getBytes(StandardCharsets.UTF_8));
    row.setValue(
        ISSUANCE_SIGNER_RECEIPT, "original signer receipt".getBytes(StandardCharsets.UTF_8));
    row.setValue(ISSUANCE_PENDING_REGISTRY, "pending".getBytes(StandardCharsets.UTF_8));
    row.setValue(ISSUANCE_ACTIVE_REGISTRY, "active".getBytes(StandardCharsets.UTF_8));
    row.setValue(ISSUANCE_CREATED_AT, 1_800_000_000L);
    row.setValue(ISSUANCE_EXPIRES_AT, 1_900_000_000L);
    row.setValue(ISSUANCE_RECOVERY_EXPIRES_AT, OffsetDateTime.parse("2040-01-01T00:00:00Z"));
    return new AccountControlUiIssuanceRepository.Stored(row);
  }

  private static CaptureFixture worldCapture(
      StartSessionPreAuthorizationReservationTuple tuple,
      Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored) {
    byte[] tupleBytes = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", "account-start-session-authority-snapshot/v1");
    snapshot.put("controlPlaneRequestId", tuple.controlPlaneRequestId());
    snapshot.put("preAuthorizationTuple", Base64.getEncoder().encodeToString(tupleBytes));
    snapshot.put("mutationDigest", tuple.mutationDigest());
    snapshot.put("accountId", stored.accountId.toString());
    snapshot.put("tenantId", stored.tenantId.toString());
    snapshot.put("targetOwner", tuple.targetOwner());
    snapshot.put("loggingWorkloadUri", LOGGING_PEER_URI);
    snapshot.put("reservationOwnerId", RESERVATION_OWNER_ID.toString());
    snapshot.put("reservationClaimFence", "7");
    snapshot.put("controlUiOperationId", stored.operationId.toString());
    snapshot.put("controlUiTokenJti", stored.jti.toString());
    snapshot.put("controlUiTokenHash", stored.tokenHash);
    snapshot.put(
        "controlUiSignerReceipt", Base64.getEncoder().encodeToString(stored.signerReceipt));
    snapshot.put("controlUiSignerReceiptSha256", sha256(stored.signerReceipt));
    snapshot.put("sourceVectorEvidence", Base64.getEncoder().encodeToString(source.evidence()));
    snapshot.put(
        "sourceVector",
        source.sources().stream()
            .map(value -> Base64.getEncoder().encodeToString(value.canonicalBytes()))
            .toList());
    snapshot.put("outboxCheckpoints", source.outboxCheckpoints());
    snapshot.put("authorityTuple", source.authorityTuple());
    snapshot.put("membershipVersion", source.membershipVersion());
    snapshot.put("accountIdentitySource", source.accountIdentitySource());
    snapshot.put("issuanceFence", Long.toString(source.issuanceFence()));
    snapshot.put("issuanceFenceSourceVersion", Long.toString(source.issuanceFenceSourceVersion()));
    byte[] snapshotBytes = AccountControlUiAuthority.canonical(Map.copyOf(snapshot));
    AccountStartSessionAuthorityCapture capture =
        AccountStartSessionAuthorityCapture.create(
            tuple.controlPlaneRequestId(), 11L, 13L, "17", CAPTURED_AT, snapshotBytes);
    Record row =
        DSL.using(SQLDialect.POSTGRES)
            .newRecord(
                CONTROL_PLANE_REQUEST_ID,
                ACCOUNT_UUID,
                TENANT_UUID,
                TARGET_OWNER,
                LOGGING_WORKLOAD_URI,
                RESERVATION_OWNER_UUID,
                RESERVATION_CLAIM_FENCE,
                PRE_AUTHORIZATION_TUPLE,
                MUTATION_DIGEST,
                CONTROL_UI_OPERATION_UUID,
                CONTROL_UI_TOKEN_UUID,
                CONTROL_UI_TOKEN_HASH,
                SIGNER_RECEIPT_SHA256,
                ISSUANCE_FENCE,
                ISSUANCE_FENCE_SOURCE_VERSION,
                CAPTURED_AT_FIELD,
                SNAPSHOT_SHA256,
                SNAPSHOT_BYTES,
                SOURCE_VERSION,
                SOURCE_FENCE,
                LINEARIZATION,
                CANONICAL_SHA256,
                CANONICAL_BYTES);
    row.setValue(CONTROL_PLANE_REQUEST_ID, tuple.controlPlaneRequestId());
    row.setValue(ACCOUNT_UUID, tuple.actor().accountId());
    row.setValue(TENANT_UUID, tuple.action().scope().tenantId());
    row.setValue(TARGET_OWNER, tuple.targetOwner());
    row.setValue(LOGGING_WORKLOAD_URI, LOGGING_PEER_URI);
    row.setValue(RESERVATION_OWNER_UUID, RESERVATION_OWNER_ID);
    row.setValue(RESERVATION_CLAIM_FENCE, 7L);
    row.setValue(PRE_AUTHORIZATION_TUPLE, tupleBytes);
    row.setValue(MUTATION_DIGEST, tuple.mutationDigest());
    row.setValue(CONTROL_UI_OPERATION_UUID, stored.operationId);
    row.setValue(CONTROL_UI_TOKEN_UUID, stored.jti);
    row.setValue(CONTROL_UI_TOKEN_HASH, stored.tokenHash);
    row.setValue(SIGNER_RECEIPT_SHA256, sha256(stored.signerReceipt));
    row.setValue(ISSUANCE_FENCE, source.issuanceFence());
    row.setValue(ISSUANCE_FENCE_SOURCE_VERSION, source.issuanceFenceSourceVersion());
    row.setValue(CAPTURED_AT_FIELD, CAPTURED_AT);
    row.setValue(SNAPSHOT_SHA256, capture.snapshotSha256());
    row.setValue(SNAPSHOT_BYTES, capture.snapshotBytes());
    row.setValue(SOURCE_VERSION, capture.sourceVersion());
    row.setValue(SOURCE_FENCE, capture.sourceFence());
    row.setValue(LINEARIZATION, capture.linearization());
    row.setValue(CANONICAL_SHA256, capture.canonicalSha256());
    row.setValue(CANONICAL_BYTES, capture.canonicalBytes());
    return new CaptureFixture(row, capture);
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required", impossible);
    }
  }

  private static void beginOwnerTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  private static PeerContext withPeer(String uri, String namespace, String service) {
    return new PeerContext(new GrpcPeerIdentity(uri, namespace, service));
  }

  private record CaptureFixture(Record row, AccountStartSessionAuthorityCapture capture) {}

  private record WorldLookupFixture(
      DSLContext dsl,
      AccountStartSessionAuthorityCaptureRepository repository,
      AccountControlUiActorService.Current current,
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountStartSessionAuthorityCapture capture) {}

  private record PeerContext(GrpcPeerIdentity peer) {
    <T> T call(Supplier<T> action) {
      Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
      Context previous = context.attach();
      try {
        return action.get();
      } finally {
        context.detach(previous);
      }
    }
  }
}
