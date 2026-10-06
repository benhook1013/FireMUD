package net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountGameplayDelegationPendingIdentityTest {
  private static final UUID OPERATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TOKEN_JTI = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String DIGEST = "ab".repeat(32);

  @Test
  void preservesAllIdentityFieldsAndAcceptsTheExistingWorkloadBoundary() {
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            OPERATION_ID,
            REQUEST_ID,
            ACCOUNT_ID,
            "x".repeat(256),
            CALLER_CONTEXT_ID,
            DIGEST,
            TOKEN_JTI,
            10,
            11,
            12);

    assertEquals(OPERATION_ID, identity.operationId());
    assertEquals(REQUEST_ID, identity.requestId());
    assertEquals(ACCOUNT_ID, identity.accountId());
    assertEquals("x".repeat(256), identity.callerWorkload());
    assertEquals(CALLER_CONTEXT_ID, identity.callerContextId());
    assertEquals(DIGEST, identity.requestDigest());
    assertEquals(TOKEN_JTI, identity.tokenJti());
    assertEquals(10, identity.issuedAtEpochSecond());
    assertEquals(11, identity.notBeforeEpochSecond());
    assertEquals(12, identity.expiresAtEpochSecond());
  }

  @Test
  void requiresTheExistingIdentityUuidFields() {
    assertThrows(NullPointerException.class, () -> identity(null, REQUEST_ID, ACCOUNT_ID, "game"));
    assertThrows(
        NullPointerException.class, () -> identity(OPERATION_ID, null, ACCOUNT_ID, "game"));
    assertThrows(
        NullPointerException.class, () -> identity(OPERATION_ID, REQUEST_ID, null, "game"));
    assertThrows(
        NullPointerException.class,
        () ->
            new AccountGameplayDelegationPendingIdentity(
                OPERATION_ID, REQUEST_ID, ACCOUNT_ID, "game", null, DIGEST, TOKEN_JTI, 10, 11, 12));
    assertThrows(
        NullPointerException.class,
        () ->
            new AccountGameplayDelegationPendingIdentity(
                OPERATION_ID,
                REQUEST_ID,
                ACCOUNT_ID,
                "game",
                CALLER_CONTEXT_ID,
                DIGEST,
                null,
                10,
                11,
                12));
  }

  @Test
  void requiresTheExistingDigestAndCallerWorkloadShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> identity(OPERATION_ID, REQUEST_ID, ACCOUNT_ID, "game", null));
    assertThrows(
        IllegalArgumentException.class, () -> identity(OPERATION_ID, REQUEST_ID, ACCOUNT_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> identity(OPERATION_ID, REQUEST_ID, ACCOUNT_ID, "x".repeat(257)));
  }

  private static AccountGameplayDelegationPendingIdentity identity(
      UUID operationId, UUID requestId, UUID accountId, String callerWorkload) {
    return identity(operationId, requestId, accountId, callerWorkload, DIGEST);
  }

  private static AccountGameplayDelegationPendingIdentity identity(
      UUID operationId, UUID requestId, UUID accountId, String callerWorkload, String digest) {
    return new AccountGameplayDelegationPendingIdentity(
        operationId,
        requestId,
        accountId,
        callerWorkload,
        CALLER_CONTEXT_ID,
        digest,
        TOKEN_JTI,
        10,
        11,
        12);
  }
}
