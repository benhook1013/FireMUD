package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

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

  private record CaptureFixture(Record row, AccountStartSessionAuthorityCapture capture) {}
}
