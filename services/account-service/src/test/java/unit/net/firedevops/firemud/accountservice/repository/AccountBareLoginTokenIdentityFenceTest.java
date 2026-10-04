package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginTokenIdentityFence;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginTokenIdentityFenceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AccountBareLoginTokenIdentityFenceTest {
  private static final UUID OPERATION_ID = UUID.fromString("018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f");
  private static final UUID ACCOUNT_UUID = UUID.fromString("018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a");
  private static final UUID TENANT_UUID = UUID.fromString("018f8f0a-4d9e-7c46-be37-8c1d0e9f7a5b");
  private static final String CONNECT_SCOPE_ID = "scope-private-login";
  private static final String REQUEST_ID = "bare-login-request-001";
  private static final String TOKEN_IDENTITY = "jti-private-delegation-001";
  private static final byte[] REQUEST_DIGEST = HexFormat.of().parseHex("12".repeat(32));
  private static final byte[] TOKEN_HASH = HexFormat.of().parseHex("34".repeat(32));

  @Test
  void keepsInitialIdentityAndAccountFenceVersionsIndependentAndCopiesBytes() {
    byte[] requestDigest = REQUEST_DIGEST.clone();
    byte[] tokenHash = TOKEN_HASH.clone();
    AccountBareLoginTokenIdentityFence evidence = capture(requestDigest, tokenHash, 4L, 9L);
    requestDigest[0]++;
    tokenHash[0]++;
    evidence.requestDigest()[1]++;
    evidence.tokenHash()[1]++;

    assertThat(evidence.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(evidence.tokenHash()).isEqualTo(TOKEN_HASH);
    assertThat(evidence.profile()).isEqualTo(AccountBareLoginTokenIdentityFence.PROFILE_NAME);
    assertThat(evidence.tokenIdentityFence()).isEqualTo(1L);
    assertThat(evidence.tokenIdentityFenceSourceVersion()).isEqualTo(1L);
    assertThat(evidence.accountIssuanceFence()).isEqualTo(4L);
    assertThat(evidence.accountIssuanceFenceSourceVersion()).isEqualTo(9L);
    assertThat(evidence.sameInitialCapture(capture(REQUEST_DIGEST, TOKEN_HASH, 4L, 9L))).isTrue();
    assertThat(evidence.toString())
        .doesNotContain(TOKEN_IDENTITY)
        .doesNotContain(HexFormat.of().formatHex(TOKEN_HASH));
  }

  @Test
  void rejectsAChangedCurrentAccountFenceAsDifferentInitialEvidence() {
    AccountBareLoginTokenIdentityFence original = capture(REQUEST_DIGEST, TOKEN_HASH, 4L, 9L);
    AccountBareLoginTokenIdentityFence changed = capture(REQUEST_DIGEST, TOKEN_HASH, 5L, 10L);

    assertThat(original.sameInitialCapture(changed)).isFalse();
  }

  @Test
  void rejectsNonInitialIdentityFenceVersionsAndMalformedTokenIdentity() {
    assertThatThrownBy(
            () ->
                new AccountBareLoginTokenIdentityFence(
                    AccountBareLoginTokenIdentityFence.SCHEMA_NAME,
                    OPERATION_ID,
                    SOURCE_OPERATION_ID,
                    17L,
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID),
                    REQUEST_ID,
                    1,
                    REQUEST_DIGEST,
                    AccountBareLoginTokenIdentityFence.PROFILE_NAME,
                    TOKEN_IDENTITY,
                    TOKEN_HASH,
                    2L,
                    1L,
                    4L,
                    9L,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> capture(REQUEST_DIGEST, TOKEN_HASH, 0L, 9L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> capture(REQUEST_DIGEST, new byte[31], 4L, 9L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void captureRequiresARealOwnerTransactionBeforeAccessingStorage() {
    DSLContext dsl = mock(DSLContext.class);
    DataSource dataSource = mock(DataSource.class);
    AccountBareLoginTokenIdentityFenceRepository repository =
        new AccountBareLoginTokenIdentityFenceRepository(dsl, dataSource);

    assertThatThrownBy(
            () ->
                repository.captureInitial(
                    exchangeIdentity(),
                    OPERATION_ID,
                    ACCOUNT_UUID,
                    REQUEST_DIGEST,
                    TOKEN_IDENTITY,
                    TOKEN_HASH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");
    verifyNoInteractions(dsl, dataSource);
  }

  private static AccountBareLoginTokenIdentityFence capture(
      byte[] requestDigest, byte[] tokenHash, long accountFence, long accountFenceSourceVersion) {
    AccountBareLoginExchangeIdentity identity = exchangeIdentity();
    return new AccountBareLoginTokenIdentityFence(
        AccountBareLoginTokenIdentityFence.SCHEMA_NAME,
        OPERATION_ID,
        identity.sourceConnectOperationId(),
        identity.accountId(),
        ACCOUNT_UUID,
        identity.tenantId(),
        AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID),
        identity.requestId(),
        1,
        requestDigest,
        AccountBareLoginTokenIdentityFence.PROFILE_NAME,
        TOKEN_IDENTITY,
        tokenHash,
        1L,
        1L,
        accountFence,
        accountFenceSourceVersion,
        Instant.parse("2026-10-04T00:00:00Z"));
  }

  private static AccountBareLoginExchangeIdentity exchangeIdentity() {
    return new AccountBareLoginExchangeIdentity(
        SOURCE_OPERATION_ID, 17L, TENANT_UUID, CONNECT_SCOPE_ID, REQUEST_ID);
  }
}
