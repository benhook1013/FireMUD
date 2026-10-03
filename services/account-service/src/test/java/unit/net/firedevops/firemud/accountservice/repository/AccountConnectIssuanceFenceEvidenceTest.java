package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import org.junit.jupiter.api.Test;

class AccountConnectIssuanceFenceEvidenceTest {
  private static final UUID OPERATION_ID = UUID.fromString("018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a");
  private static final UUID ACCOUNT_UUID = UUID.fromString("018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f");
  private static final UUID TENANT_UUID = UUID.fromString("018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a");
  private static final String CONNECT_SCOPE_HASH = "sha256:" + "a".repeat(64);
  private static final String REQUEST_ID = "requête-雪-001";
  private static final byte[] REQUEST_DIGEST = HexFormat.of().parseHex("64".repeat(32));
  private static final String EXPECTED_DIGEST =
      "91271de72c31addc5d3b88be733a18537c314be833671798bc903c1677c2b70d";

  @Test
  void reproducesIndependentUnicodeAndLargeCounterVector() {
    AccountConnectIssuanceFenceEvidence evidence =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            CONNECT_SCOPE_HASH,
            REQUEST_ID,
            REQUEST_DIGEST,
            9_007_199_254_740_993L,
            Long.MAX_VALUE);

    assertThat(HexFormat.of().formatHex(evidence.digest())).isEqualTo(EXPECTED_DIGEST);
    assertThat(evidence.issuanceFence()).isEqualTo(9_007_199_254_740_993L);
    assertThat(evidence.fenceSourceVersion()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void changesDigestWhenAnyBoundFieldChanges() {
    byte[] expected = HexFormat.of().parseHex(EXPECTED_DIGEST);

    assertDifferentDigest(
        UUID.fromString("018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0b"),
        ACCOUNT_UUID,
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        UUID.fromString("018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e50"),
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        UUID.fromString("018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f40"),
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        "sha256:" + "b".repeat(64),
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        "requête-雪-002",
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    byte[] changedRequestDigest = REQUEST_DIGEST.clone();
    changedRequestDigest[0]++;
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        changedRequestDigest,
        9_007_199_254_740_993L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_992L,
        Long.MAX_VALUE,
        expected);
    assertDifferentDigest(
        OPERATION_ID,
        ACCOUNT_UUID,
        TENANT_UUID,
        CONNECT_SCOPE_HASH,
        REQUEST_ID,
        REQUEST_DIGEST,
        9_007_199_254_740_993L,
        Long.MAX_VALUE - 1,
        expected);
  }

  @Test
  void copiesDigestInputsAndAccessorsAndRejectsMalformedUnicode() {
    byte[] mutableRequestDigest = REQUEST_DIGEST.clone();
    AccountConnectIssuanceFenceEvidence evidence =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            CONNECT_SCOPE_HASH,
            REQUEST_ID,
            mutableRequestDigest,
            9_007_199_254_740_993L,
            Long.MAX_VALUE);
    mutableRequestDigest[0]++;
    byte[] returnedRequestDigest = evidence.requestDigest();
    returnedRequestDigest[1]++;
    byte[] returnedDigest = evidence.digest();
    returnedDigest[2]++;

    assertThat(evidence.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(HexFormat.of().formatHex(evidence.digest())).isEqualTo(EXPECTED_DIGEST);
    assertThatThrownBy(
            () ->
                AccountConnectIssuanceFenceEvidence.capture(
                    OPERATION_ID,
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    CONNECT_SCOPE_HASH,
                    "invalid-\uD800",
                    REQUEST_DIGEST,
                    1L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertDifferentDigest(
      UUID operationId,
      UUID accountUuid,
      UUID tenantUuid,
      String connectScopeHash,
      String requestId,
      byte[] requestDigest,
      long issuanceFence,
      long fenceSourceVersion,
      byte[] expected) {
    byte[] actual =
        AccountConnectIssuanceFenceEvidence.calculateDigest(
            AccountConnectIssuanceFenceEvidence.SCHEMA_NAME,
            operationId,
            accountUuid,
            tenantUuid,
            connectScopeHash,
            requestId,
            requestDigest,
            issuanceFence,
            fenceSourceVersion);
    assertThat(actual).isNotEqualTo(expected);
  }
}
