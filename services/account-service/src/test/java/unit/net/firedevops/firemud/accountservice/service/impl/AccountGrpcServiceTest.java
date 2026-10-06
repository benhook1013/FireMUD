package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
import net.firedevops.firemud.account.v1.ExportAccountRequest;
import net.firedevops.firemud.account.v1.ExportAccountResponse;
import net.firedevops.firemud.account.v1.ExportTenantDataRequest;
import net.firedevops.firemud.account.v1.ExportTenantDataResponse;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesRequest;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.account.v1.VerifyEmailLoginOtpRequest;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class AccountGrpcServiceTest {
  private static final String WORKLOAD_NAMESPACE = "test";
  private static final String ACCOUNT_UUID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String OTHER_ACCOUNT_UUID = "a2e1342e-a139-49c6-a460-c8e25f6697ae";
  private static final String REALM_ID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String OTHER_REALM_ID = "57c58f36-c5ea-4aa8-8ef7-91a45e407f01";
  private static final String PLAYABLE_STATE_NAMESPACE_ID = "c6ed6a44-c7e7-4f18-81fc-078a74e67c07";
  private static final GrpcPeerIdentity GAME_SESSION_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/test/sa/game-session-service",
          WORKLOAD_NAMESPACE,
          "game-session-service");
  private static final GrpcPeerIdentity SOCIAL_GROUPS_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/test/sa/social-groups-service",
          WORKLOAD_NAMESPACE,
          "social-groups-service");
  private static final GrpcPeerIdentity WORLD_MANAGEMENT_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/test/sa/world-management-service",
          WORKLOAD_NAMESPACE,
          "world-management-service");

  private static PlayerExecutionContext validPlayerContext() {
    return PlayerExecutionContext.newBuilder()
        .setAccountId("10")
        .setTenantId("20")
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
        .setPlayableStateScope("realm:30")
        .setGameInstanceId("40")
        .setSessionId("session-1")
        .setRequestId("request-1")
        .build();
  }

  private static IssueDirectTextConnectScopeRequest validScopeRequest() {
    return IssueDirectTextConnectScopeRequest.newBuilder()
        .setPlayerContext(validPlayerContext())
        .setTenantId("20")
        .setRealmId(REALM_ID)
        .setWorldSlug("world")
        .setRealmSlug("public")
        .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
        .setPlayableStateScope("realm:30")
        .setGameInstanceId("40")
        .setCatalogRevision(5)
        .setPointerVersion(8)
        .build();
  }

  private static JoinPublicProductionMembershipRequest validJoinRequest() {
    return JoinPublicProductionMembershipRequest.newBuilder()
        .setPlayerContext(validPlayerContext())
        .setConnectScopeId("scope-1")
        .setRequestId("request-1")
        .build();
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.ROOT;
    if (peer != null) {
      context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    }
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<PingResponse>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void issueDirectTextConnectScopeAcceptsExactGameSessionPeerAndTypedTarget() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(
            accountService.issueDirectTextConnectScope(
                Mockito.any(DirectTextCallerContext.class),
                Mockito.any(DirectTextJoinTarget.class)))
        .thenReturn(new DirectTextJoinScope("scope-1", "2026-09-24T12:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<IssueDirectTextConnectScopeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () -> service.issueDirectTextConnectScope(validScopeRequest(), observer));

    assertTrue(observer.completed());
    assertEquals("scope-1", observer.response().getConnectScopeId());
    assertEquals("2026-09-24T12:00:00Z", observer.response().getConnectScopeExpiresAt());
    Mockito.verify(accountService)
        .issueDirectTextConnectScope(
            new DirectTextCallerContext(
                10L,
                20L,
                UUID.fromString(REALM_ID),
                PLAYABLE_STATE_NAMESPACE_ID,
                "realm:30",
                40L,
                "session-1",
                "request-1"),
            new DirectTextJoinTarget(
                20L,
                UUID.fromString(REALM_ID),
                "world",
                "public",
                PLAYABLE_STATE_NAMESPACE_ID,
                "realm:30",
                40L,
                5L,
                8L));
  }

  @Test
  void passwordResetMissingSourceIsSafeUnavailableWhileInvalidTokenMappingIsUnchanged() {
    var owner = Mockito.mock(AccountService.class);
    var service = new AccountGrpcService(Mockito.mock(PingService.class), owner);
    var request =
        net.firedevops.firemud.account.v1.CompletePasswordResetRequest.newBuilder()
            .setToken("retained-reset-token")
            .setNewPassword("new-password")
            .build();
    Mockito.doThrow(
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException())
        .when(owner)
        .completePasswordReset(Mockito.any());
    var unavailable =
        new RecordingObserver<net.firedevops.firemud.account.v1.CompletePasswordResetResponse>();
    service.completePasswordReset(request, unavailable);
    assertTrue(unavailable.completed());
    assertFalse(unavailable.response().getSuccess());
    assertEquals("AUTH_UNAVAILABLE", unavailable.response().getError().getCode());
    assertEquals(
        "Account authority is unavailable", unavailable.response().getError().getMessage());
    Mockito.doThrow(new IllegalArgumentException("Invalid token"))
        .when(owner)
        .completePasswordReset(Mockito.any());
    var invalid =
        new RecordingObserver<net.firedevops.firemud.account.v1.CompletePasswordResetResponse>();
    service.completePasswordReset(request, invalid);
    assertTrue(invalid.completed());
    assertFalse(invalid.response().getSuccess());
    assertEquals("INVALID_ARGUMENT", invalid.response().getError().getCode());
  }

  @Test
  void verifyEmailMapsMissingSourceToSafeUnavailableAndPreservesOtherExceptions() {
    var owner = Mockito.mock(AccountService.class);
    var service = new AccountGrpcService(Mockito.mock(PingService.class), owner);
    var request =
        net.firedevops.firemud.account.v1.VerifyEmailRequest.newBuilder()
            .setToken("retained-verification-token")
            .build();
    Mockito.doThrow(
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException())
        .when(owner)
        .verifyEmail(Mockito.any());
    var unavailable =
        new RecordingObserver<net.firedevops.firemud.account.v1.VerifyEmailResponse>();
    service.verifyEmail(request, unavailable);
    assertTrue(unavailable.completed());
    assertFalse(unavailable.response().getSuccess());
    assertEquals("AUTH_UNAVAILABLE", unavailable.response().getError().getCode());
    assertEquals(
        "Account authority is unavailable", unavailable.response().getError().getMessage());

    Mockito.doThrow(new IllegalArgumentException("Invalid token"))
        .when(owner)
        .verifyEmail(Mockito.any());
    var invalid = new RecordingObserver<net.firedevops.firemud.account.v1.VerifyEmailResponse>();
    service.verifyEmail(request, invalid);
    assertTrue(invalid.completed());
    assertFalse(invalid.response().getSuccess());
    assertEquals("INVALID_ARGUMENT", invalid.response().getError().getCode());
  }

  @Test
  void joinPublicProductionMembershipPreservesStoredFailureOutcome() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(
            accountService.joinPublicProductionFromGameSession(
                Mockito.any(DirectTextCallerContext.class),
                Mockito.any(JoinPublicProductionRequest.class)))
        .thenReturn(
            new JoinPublicProductionResult(
                false, "ENTITLEMENT_UNAVAILABLE", 10L, 20L, 0L, 0L, 0L, true));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<JoinPublicProductionMembershipResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () -> service.joinPublicProductionMembership(validJoinRequest(), observer));

    assertTrue(observer.completed());
    assertFalse(observer.response().getSuccess());
    assertEquals("ENTITLEMENT_UNAVAILABLE", observer.response().getOutcomeCode());
    assertEquals("10", observer.response().getAccountId());
    assertEquals("20", observer.response().getTenantId());
    assertEquals("0", observer.response().getMembershipId());
    assertTrue(observer.response().getReplayed());
    assertFalse(observer.response().hasError());
    Mockito.verify(accountService)
        .joinPublicProductionFromGameSession(
            new DirectTextCallerContext(
                10L,
                20L,
                UUID.fromString(REALM_ID),
                PLAYABLE_STATE_NAMESPACE_ID,
                "realm:30",
                40L,
                "session-1",
                "request-1"),
            new JoinPublicProductionRequest("scope-1", "request-1"));
  }

  @Test
  void issueDirectTextConnectScopeMapsUnexpectedFailureToRetryableUnavailable() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(
            accountService.issueDirectTextConnectScope(
                Mockito.any(DirectTextCallerContext.class),
                Mockito.any(DirectTextJoinTarget.class)))
        .thenThrow(new IllegalStateException("private backend detail"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<IssueDirectTextConnectScopeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () -> service.issueDirectTextConnectScope(validScopeRequest(), observer));

    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
  }

  @Test
  void joinPublicProductionMembershipMapsUnexpectedFailureToRetryableUnavailable() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(
            accountService.joinPublicProductionFromGameSession(
                Mockito.any(DirectTextCallerContext.class),
                Mockito.any(JoinPublicProductionRequest.class)))
        .thenThrow(new IllegalStateException("private backend detail"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<JoinPublicProductionMembershipResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () -> service.joinPublicProductionMembership(validJoinRequest(), observer));

    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
  }

  @Test
  void protectedDirectTextMethodsRejectAbsentWrongSharedAndCrossNamespacePeers() {
    List<GrpcPeerIdentity> rejectedPeers =
        java.util.Arrays.asList(
            null,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/world-management-service",
                "test",
                "world-management-service"),
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/account-service", "test", "account-service"),
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other/sa/game-session-service",
                "other",
                "game-session-service"));

    for (GrpcPeerIdentity peer : rejectedPeers) {
      PingService pingService = Mockito.mock(PingService.class);
      AccountService accountService = Mockito.mock(AccountService.class);
      AccountGrpcService service =
          new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
      RecordingObserver<IssueDirectTextConnectScopeResponse> issueObserver =
          new RecordingObserver<>();
      RecordingObserver<JoinPublicProductionMembershipResponse> joinObserver =
          new RecordingObserver<>();

      withPeer(peer, () -> service.issueDirectTextConnectScope(validScopeRequest(), issueObserver));
      withPeer(
          peer, () -> service.joinPublicProductionMembership(validJoinRequest(), joinObserver));

      assertEquals("PERMISSION_DENIED", issueObserver.response().getError().getCode());
      assertEquals("PERMISSION_DENIED", joinObserver.response().getError().getCode());
      Mockito.verifyNoInteractions(accountService);
    }
  }

  @Test
  void directTextMethodsRejectMalformedOrMismatchedTypedContextBeforeServiceCall() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    IssueDirectTextConnectScopeRequest mismatchedTarget =
        validScopeRequest().toBuilder().setRealmId(OTHER_REALM_ID).build();
    PlayerExecutionContext malformedContext =
        validPlayerContext().toBuilder().setAccountId("NaN").build();
    JoinPublicProductionMembershipRequest mismatchedJoin =
        validJoinRequest().toBuilder().setRequestId("request-2").build();
    RecordingObserver<IssueDirectTextConnectScopeResponse> targetObserver =
        new RecordingObserver<>();
    RecordingObserver<JoinPublicProductionMembershipResponse> malformedObserver =
        new RecordingObserver<>();
    RecordingObserver<JoinPublicProductionMembershipResponse> requestIdObserver =
        new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () -> service.issueDirectTextConnectScope(mismatchedTarget, targetObserver));
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.joinPublicProductionMembership(
                validJoinRequest().toBuilder().setPlayerContext(malformedContext).build(),
                malformedObserver));
    withPeer(
        GAME_SESSION_PEER,
        () -> service.joinPublicProductionMembership(mismatchedJoin, requestIdObserver));

    assertEquals("INVALID_ARGUMENT", targetObserver.response().getError().getCode());
    assertEquals("INVALID_ARGUMENT", malformedObserver.response().getError().getCode());
    assertEquals("INVALID_ARGUMENT", requestIdObserver.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void joinRejectsRequestIdLongerThanThePublicContractBeforeCallingAccountService() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    String requestId = "j".repeat(JoinPublicProductionRequest.MAX_REQUEST_ID_LENGTH + 1);
    PlayerExecutionContext playerContext =
        validPlayerContext().toBuilder().setRequestId(requestId).build();
    JoinPublicProductionMembershipRequest request =
        validJoinRequest().toBuilder()
            .setPlayerContext(playerContext)
            .setRequestId(requestId)
            .build();
    RecordingObserver<JoinPublicProductionMembershipResponse> observer = new RecordingObserver<>();

    withPeer(GAME_SESSION_PEER, () -> service.joinPublicProductionMembership(request, observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void issueDirectTextConnectScopeRejectsBlankRequestIdBeforeServiceCall(String requestId) {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<IssueDirectTextConnectScopeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.issueDirectTextConnectScope(
                validScopeRequest().toBuilder()
                    .setPlayerContext(validPlayerContext().toBuilder().setRequestId(requestId))
                    .build(),
                observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"30", "not-a-uuid", "4C4B57D8-E3A2-48FE-9977-E7DF0FDCE901"})
  void directTextScopeRejectsNoncanonicalRealmIdsAtBothIngressFields(String realmId) {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<IssueDirectTextConnectScopeResponse> targetObserver =
        new RecordingObserver<>();
    RecordingObserver<IssueDirectTextConnectScopeResponse> contextObserver =
        new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.issueDirectTextConnectScope(
                validScopeRequest().toBuilder().setRealmId(realmId).build(), targetObserver));
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.issueDirectTextConnectScope(
                validScopeRequest().toBuilder()
                    .setPlayerContext(validPlayerContext().toBuilder().setRealmId(realmId).build())
                    .build(),
                contextObserver));

    assertEquals("INVALID_ARGUMENT", targetObserver.response().getError().getCode());
    assertEquals("INVALID_ARGUMENT", contextObserver.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "shared-live",
        "C6ED6A44-C7E7-4F18-81FC-078A74E67C07",
        "c6ed6a44c7e74f1881fc078a74e67c07"
      })
  void directTextMethodsRejectNoncanonicalPlayableStateNamespaceIdsAtIngress(String namespaceId) {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    PlayerExecutionContext malformedContext =
        validPlayerContext().toBuilder().setPlayableStateNamespaceId(namespaceId).build();
    RecordingObserver<IssueDirectTextConnectScopeResponse> scopeObserver =
        new RecordingObserver<>();
    RecordingObserver<JoinPublicProductionMembershipResponse> joinObserver =
        new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.issueDirectTextConnectScope(
                validScopeRequest().toBuilder().setPlayerContext(malformedContext).build(),
                scopeObserver));
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.joinPublicProductionMembership(
                validJoinRequest().toBuilder().setPlayerContext(malformedContext).build(),
                joinObserver));

    assertEquals("INVALID_ARGUMENT", scopeObserver.response().getError().getCode());
    assertEquals("INVALID_ARGUMENT", joinObserver.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void authenticateFailureReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(
            accountService.authenticateForGameplay(
                Mockito.eq("demo@example.com"), Mockito.eq("bad")))
        .thenThrow(
            new AuthenticationException(
                AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<AuthenticateResponse> ref = new AtomicReference<>();
    service.authenticate(
        AuthenticateRequest.newBuilder().setEmail("demo@example.com").setPassword("bad").build(),
        new StreamObserver<AuthenticateResponse>() {
          @Override
          public void onNext(AuthenticateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, ref.get().getError().getCode());
    Mockito.verify(accountService).authenticateForGameplay("demo@example.com", "bad");
  }

  @Test
  void authenticateConvertsIllegalStateToBoundedAuthorityUnavailableError() {
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.authenticateForGameplay("demo@example.com", "password"))
        .thenThrow(new IllegalStateException("private provenance detail"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AccountGrpcService service =
        new AccountGrpcService(Mockito.mock(PingService.class), accountService, registry);
    RecordingObserver<AuthenticateResponse> observer = new RecordingObserver<>();

    service.authenticate(
        AuthenticateRequest.newBuilder()
            .setEmail("demo@example.com")
            .setPassword("password")
            .build(),
        observer);

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    assertEquals(
        1.0, registry.get("grpc.app_error").tag("code", "AUTH_UNAVAILABLE").counter().count());
  }

  @Test
  void requestEmailLoginOtpDispatchesNeutralChallengeRequest() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<RequestEmailLoginOtpResponse> ref = new AtomicReference<>();
    service.requestEmailLoginOtp(
        RequestEmailLoginOtpRequest.newBuilder().setEmail("demo@example.com").build(),
        new StreamObserver<RequestEmailLoginOtpResponse>() {
          @Override
          public void onNext(RequestEmailLoginOtpResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().getAccepted());
    Mockito.verify(accountService).requestEmailLoginOtp("demo@example.com");
  }

  @Test
  void requestEmailLoginOtpRejectsBlankEmailBeforeCallingAccountService() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<RequestEmailLoginOtpResponse> observer = new RecordingObserver<>();

    service.requestEmailLoginOtp(
        RequestEmailLoginOtpRequest.newBuilder().setEmail("   ").build(), observer);

    assertNotNull(observer.response());
    assertFalse(observer.response().getAccepted());
    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertEquals("email must not be blank", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void verifyEmailLoginOtpReturnsAuthenticatedSession() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.verifyEmailLoginOtp("demo@example.com", "123456"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
                ACCOUNT_UUID, "jwt"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<AuthenticateResponse> ref = new AtomicReference<>();
    service.verifyEmailLoginOtp(
        VerifyEmailLoginOtpRequest.newBuilder()
            .setEmail("demo@example.com")
            .setCode("123456")
            .build(),
        new StreamObserver<AuthenticateResponse>() {
          @Override
          public void onNext(AuthenticateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(ACCOUNT_UUID, ref.get().getAccountId());
    assertEquals("jwt", ref.get().getAuthToken());
  }

  @Test
  void authenticateReturnsCanonicalUuid() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.authenticateForGameplay("demo@example.com", "password"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.AuthenticationResult(
                ACCOUNT_UUID, "jwt"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<AuthenticateResponse> observer = new RecordingObserver<>();

    service.authenticate(
        AuthenticateRequest.newBuilder()
            .setEmail("demo@example.com")
            .setPassword("password")
            .build(),
        observer);

    assertEquals(ACCOUNT_UUID, observer.response().getAccountId());
    assertEquals("jwt", observer.response().getAuthToken());
    assertTrue(observer.completed());
  }

  @Test
  void verifyEmailLoginOtpReturnsInvalidCredentialsAsApplicationError() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.verifyEmailLoginOtp("demo@example.com", "123456"))
        .thenThrow(
            new AuthenticationException(
                AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<AuthenticateResponse> observer = new RecordingObserver<>();

    service.verifyEmailLoginOtp(
        VerifyEmailLoginOtpRequest.newBuilder()
            .setEmail("demo@example.com")
            .setCode("123456")
            .build(),
        observer);

    assertNotNull(observer.response());
    assertEquals(
        AuthenticationErrorCodes.INVALID_CREDENTIALS, observer.response().getError().getCode());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @Test
  void verifyEmailLoginOtpConvertsIllegalStateToBoundedAuthorityUnavailableError() {
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.verifyEmailLoginOtp("demo@example.com", "123456"))
        .thenThrow(new IllegalStateException("private provenance detail"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AccountGrpcService service =
        new AccountGrpcService(Mockito.mock(PingService.class), accountService, registry);
    RecordingObserver<AuthenticateResponse> observer = new RecordingObserver<>();

    service.verifyEmailLoginOtp(
        VerifyEmailLoginOtpRequest.newBuilder()
            .setEmail("demo@example.com")
            .setCode("123456")
            .build(),
        observer);

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    assertEquals(
        1.0, registry.get("grpc.app_error").tag("code", "AUTH_UNAVAILABLE").counter().count());
  }

  @Test
  void createAccountValidationErrorReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenThrow(new IllegalArgumentException("bad"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<CreateAccountResponse> ref = new AtomicReference<>();
    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setUsername("demo")
            .setEmail("e@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<CreateAccountResponse>() {
          @Override
          public void onNext(CreateAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void createAccountConflictReturnsApplicationError() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenThrow(new AccountAlreadyExistsException(new RuntimeException("duplicate")));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<CreateAccountResponse> observer = new RecordingObserver<>();

    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setUsername("demo")
            .setEmail("demo@example.com")
            .setPassword("pass")
            .build(),
        observer);

    assertNotNull(observer.response());
    assertEquals("ALREADY_EXISTS", observer.response().getError().getCode());
    assertEquals("Account already exists", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @Test
  void createAccountInternalFailureReturnsBoundedTransportError() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenThrow(new IllegalStateException("private backend detail"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    AtomicInteger nextCalls = new AtomicInteger();
    AtomicInteger errorCalls = new AtomicInteger();
    AtomicInteger completedCalls = new AtomicInteger();
    AtomicReference<Throwable> transportError = new AtomicReference<>();

    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setUsername("demo")
            .setEmail("demo@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<CreateAccountResponse>() {
          @Override
          public void onNext(CreateAccountResponse value) {
            nextCalls.incrementAndGet();
          }

          @Override
          public void onError(Throwable throwable) {
            errorCalls.incrementAndGet();
            transportError.set(throwable);
          }

          @Override
          public void onCompleted() {
            completedCalls.incrementAndGet();
          }
        });

    assertEquals(0, nextCalls.get());
    assertEquals(1, errorCalls.get());
    assertEquals(0, completedCalls.get());
    assertEquals(Status.Code.INTERNAL, Status.fromThrowable(transportError.get()).getCode());
    assertEquals(
        "Account creation failed", Status.fromThrowable(transportError.get()).getDescription());
    assertFalse(
        Status.fromThrowable(transportError.get())
            .getDescription()
            .contains("private backend detail"));
  }

  @Test
  void createAccountReturnsAccountIdForGlobalRequest() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenReturn(
            new AccountDto(
                "4cae05e8-7a6b-4b14-9d44-665e3eec450b", "demo", "e@example.com", "player", true));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<CreateAccountResponse> ref = new AtomicReference<>();
    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setUsername("demo")
            .setEmail("e@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<CreateAccountResponse>() {
          @Override
          public void onNext(CreateAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("4cae05e8-7a6b-4b14-9d44-665e3eec450b", ref.get().getAccountId());
    org.mockito.ArgumentCaptor<net.firedevops.firemud.accountservice.dto.CreateAccountRequest>
        captor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.dto.CreateAccountRequest.class);
    Mockito.verify(accountService).createAccount(captor.capture());
    assertEquals("demo", captor.getValue().username());
    assertEquals("e@example.com", captor.getValue().email());
  }

  @Test
  void getProfileReturnsProfile() throws Exception {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.getProfile(1L, 2L))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetProfileResponse> ref = new AtomicReference<>();
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build(),
                new StreamObserver<GetProfileResponse>() {
                  @Override
                  public void onNext(GetProfileResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertEquals(
        "demo",
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(ref.get().getProfileJson())
            .get("displayName")
            .asText());
    assertEquals(
        "PRIVATE",
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(ref.get().getProfileJson())
            .path("presenceVisibilityPolicy")
            .asText());
    Mockito.verify(accountService).resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID));
  }

  @Test
  void getProfileRejectsZeroAccountIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetProfileResponse> ref = new AtomicReference<>();
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId("0").build(),
                new StreamObserver<GetProfileResponse>() {
                  @Override
                  public void onNext(GetProfileResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be a canonical non-nil UUID", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsMismatchedCallerSubjectBeforeStorageResolution() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetProfileResponse> observer = new RecordingObserver<>();
    SessionContext.setContext(OTHER_ACCOUNT_UUID, List.of("player"), Map.of());

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build(),
                observer));

    assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
    Mockito.verify(accountService, Mockito.never())
        .resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID));
    Mockito.verify(accountService, Mockito.never()).getProfile(1L, 2L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resolution", "profile"})
  void getProfileBoundsUnprovedIdentityAndUnavailableAuthority(String failureStage) {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    if ("resolution".equals(failureStage)) {
      Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
          .thenThrow(new IllegalStateException("private Account source-row provenance detail"));
    } else {
      Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
          .thenReturn(2L);
      Mockito.when(accountService.getProfile(1L, 2L))
          .thenThrow(new DataAccessResourceFailureException("private Account database detail"));
    }
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<GetProfileResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build(),
                observer));

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @ParameterizedTest
  @ValueSource(strings = {"resolution", "profile"})
  void updateProfileBoundsUnprovedIdentityAndUnavailableAuthority(String failureStage) {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    if ("resolution".equals(failureStage)) {
      Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
          .thenThrow(new IllegalStateException("private Account source-row provenance detail"));
    } else {
      Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
          .thenReturn(2L);
      Mockito.when(accountService.updateProfile(Mockito.any()))
          .thenThrow(new DataAccessResourceFailureException("private Account database detail"));
    }
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<UpdateProfileResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("1")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(
                        "{\"displayName\":\"demo\",\"presenceVisibilityPolicy\":\"PRIVATE\"}")
                    .build(),
                observer));

    assertFalse(observer.response().getSuccess());
    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertEquals(
        "Account authority unavailable; retry later", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @Test
  void profileMethodsPreserveMissingAccountOutcome() {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenThrow(new IllegalArgumentException("Account not found"));
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<GetProfileResponse> getObserver = new RecordingObserver<>();
    RecordingObserver<UpdateProfileResponse> updateObserver = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () -> {
          service.getProfile(
              GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build(),
              getObserver);
          service.updateProfile(
              UpdateProfileRequest.newBuilder()
                  .setTenantId("1")
                  .setAccountId(ACCOUNT_UUID)
                  .setProfileJson("{}")
                  .build(),
              updateObserver);
        });

    assertEquals("NOT_FOUND", getObserver.response().getError().getCode());
    assertEquals("NOT_FOUND", updateObserver.response().getError().getCode());
    assertTrue(getObserver.completed());
    assertTrue(updateObserver.completed());
    Mockito.verify(accountService, Mockito.never()).getProfile(1L, 2L);
    Mockito.verify(accountService, Mockito.never()).updateProfile(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "42",
        "1-1-1-1-1",
        "00000000-0000-0000-0000-000000000000",
        "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B"
      })
  void profileMethodsRejectNoncanonicalAccountUuidBeforeResolution(String accountUuid) {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<GetProfileResponse> getObserver = new RecordingObserver<>();
    RecordingObserver<UpdateProfileResponse> updateObserver = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () -> {
          service.getProfile(
              GetProfileRequest.newBuilder().setTenantId("1").setAccountId(accountUuid).build(),
              getObserver);
          service.updateProfile(
              UpdateProfileRequest.newBuilder()
                  .setTenantId("1")
                  .setAccountId(accountUuid)
                  .setProfileJson("{}")
                  .build(),
              updateObserver);
        });

    assertEquals("INVALID_ARGUMENT", getObserver.response().getError().getCode());
    assertEquals("INVALID_ARGUMENT", updateObserver.response().getError().getCode());
    assertTrue(getObserver.completed());
    assertTrue(updateObserver.completed());
    Mockito.verifyNoInteractions(accountService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{", "null", "[]", "{\"presenceVisibilityPolicy\":\"UNKNOWN\"}"})
  void updateProfileRejectsMalformedJsonAndPolicyWithInvalidArgument(String profileJson) {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<UpdateProfileResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("1")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(profileJson)
                    .build(),
                observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verify(accountService, Mockito.never()).updateProfile(Mockito.any());
  }

  @Test
  void updateProfilePreservesCallerSubjectPermissionDenial() {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    SessionContext.setContext(OTHER_ACCOUNT_UUID, List.of("player"), Map.of());
    RecordingObserver<UpdateProfileResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("1")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson("{}")
                    .build(),
                observer));

    assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
    assertTrue(observer.completed());
    Mockito.verifyNoInteractions(accountService);
  }

  private static ListPresenceVisibilityPoliciesRequest presencePolicyRequest(
      String... accountUuids) {
    ListPresenceVisibilityPoliciesRequest.Builder builder =
        ListPresenceVisibilityPoliciesRequest.newBuilder().setTenantId("1");
    for (String accountUuid : accountUuids) {
      builder.addAccountIds(accountUuid);
    }
    return builder.build();
  }

  @Test
  void listPresenceVisibilityPoliciesResolvesAndReturnsCanonicalUuidKeysForSocialPeer() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(OTHER_ACCOUNT_UUID)))
        .thenReturn(3L);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L, 3L)))
        .thenReturn(
            Map.of(
                2L, ProfilePresenceVisibilityPolicy.PRIVATE,
                3L, ProfilePresenceVisibilityPolicy.HIDDEN_STAFF));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID, OTHER_ACCOUNT_UUID, ACCOUNT_UUID), observer));

    assertNotNull(observer.response());
    assertEquals(2, observer.response().getPoliciesCount());
    assertEquals(
        java.util.Set.of(ACCOUNT_UUID, OTHER_ACCOUNT_UUID),
        observer.response().getPoliciesList().stream()
            .map(entry -> entry.getAccountId())
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(
        observer.response().getPoliciesList().stream()
            .anyMatch(
                entry ->
                    entry.getAccountId().equals(ACCOUNT_UUID)
                        && entry.getPolicy().equals("PRIVATE")));
    assertTrue(
        observer.response().getPoliciesList().stream()
            .anyMatch(
                entry ->
                    entry.getAccountId().equals(OTHER_ACCOUNT_UUID)
                        && entry.getPolicy().equals("HIDDEN_STAFF")));
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verify(accountService).resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID));
    Mockito.verify(accountService).resolveAccountStorageId(UUID.fromString(OTHER_ACCOUNT_UUID));
    Mockito.verify(accountService).listPresenceVisibilityPolicies(1L, List.of(2L, 3L));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2",
        "0",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B"
      })
  void listPresenceVisibilityPoliciesRejectsNonCanonicalAccountUuidBeforeResolution(
      String accountId) {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID, accountId), observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsOverLimitBeforeResolution() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();
    String[] accountUuids =
        java.util.stream.LongStream.rangeClosed(1L, 101L)
            .mapToObj(value -> new UUID(0L, value).toString())
            .toArray(String[]::new);

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(presencePolicyRequest(accountUuids), observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertTrue(observer.completed());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void listPresenceVisibilityPoliciesRequiresExactSocialWorkloadPeer() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    List<GrpcPeerIdentity> deniedPeers =
        List.of(
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/account-service", "test", "account-service"),
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other/sa/social-groups-service",
                "other",
                "social-groups-service"),
            GAME_SESSION_PEER);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> noPeerObserver =
        new RecordingObserver<>();

    service.listPresenceVisibilityPolicies(presencePolicyRequest(ACCOUNT_UUID), noPeerObserver);
    for (GrpcPeerIdentity peer : deniedPeers) {
      RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer =
          new RecordingObserver<>();
      withPeer(
          peer,
          () ->
              service.listPresenceVisibilityPolicies(
                  presencePolicyRequest(ACCOUNT_UUID), observer));
      assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
      assertTrue(observer.completed());
    }

    assertEquals("PERMISSION_DENIED", noPeerObserver.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void listPresenceVisibilityPoliciesOmitsUnknownAndUnprovenSubjects() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    String unprovenAccountUuid = "00000000-0000-4000-8000-000000000003";
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(OTHER_ACCOUNT_UUID)))
        .thenThrow(new IllegalArgumentException("Account not found"));
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(unprovenAccountUuid)))
        .thenThrow(new IllegalStateException("Account identity provenance is unavailable"));
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenReturn(Map.of(2L, ProfilePresenceVisibilityPolicy.FRIENDS_ONLY));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID, OTHER_ACCOUNT_UUID, unprovenAccountUuid),
                observer));

    assertEquals(1, observer.response().getPoliciesCount());
    assertEquals(ACCOUNT_UUID, observer.response().getPolicies(0).getAccountId());
    assertEquals("FRIENDS_ONLY", observer.response().getPolicies(0).getPolicy());
    Mockito.verify(accountService).listPresenceVisibilityPolicies(1L, List.of(2L));
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsAliasedResolverRowsWithoutPolicyRead() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(OTHER_ACCOUNT_UUID)))
        .thenReturn(2L);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID, OTHER_ACCOUNT_UUID), observer));

    assertEquals("INTERNAL", observer.response().getError().getCode());
    assertEquals(0, observer.response().getPoliciesCount());
    Mockito.verify(accountService, Mockito.never())
        .listPresenceVisibilityPolicies(Mockito.anyLong(), Mockito.anyList());
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsUnexpectedStorageRowsWithoutPolicyResults() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenReturn(Map.of(3L, ProfilePresenceVisibilityPolicy.PRIVATE));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(presencePolicyRequest(ACCOUNT_UUID), observer));

    assertEquals("INTERNAL", observer.response().getError().getCode());
    assertEquals(0, observer.response().getPoliciesCount());
  }

  @Test
  void listPresenceVisibilityPoliciesMapsServiceRuntimeFailuresToApplicationErrors() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenThrow(new IllegalArgumentException("Tenant not found"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> notFoundObserver =
        new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID), notFoundObserver));

    assertEquals("NOT_FOUND", notFoundObserver.response().getError().getCode());
    assertTrue(notFoundObserver.completed());
    assertFalse(notFoundObserver.receivedTransportError());

    Mockito.reset(accountService);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenThrow(new IllegalStateException("Policy lookup unavailable"));
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> internalObserver =
        new RecordingObserver<>();
    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.listPresenceVisibilityPolicies(
                presencePolicyRequest(ACCOUNT_UUID), internalObserver));

    assertEquals("INTERNAL", internalObserver.response().getError().getCode());
    assertTrue(internalObserver.completed());
    assertFalse(internalObserver.receivedTransportError());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 1L, true, true, 44L, "ACTIVE", 9L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetTenantMembershipForRuntimeResponse> ref = new AtomicReference<>();
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("1")
                    .setRequestId("req-1")
                    .build(),
                new StreamObserver<GetTenantMembershipForRuntimeResponse>() {
                  @Override
                  public void onNext(GetTenantMembershipForRuntimeResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals(ACCOUNT_UUID, ref.get().getAccountId());
    assertTrue(ref.get().getMembershipExists());
    assertTrue(ref.get().getGameplayAdmissionAllowed());
    assertEquals(44L, ref.get().getMembershipVersion());
    Mockito.verify(accountService).resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID));
    Mockito.verify(accountService).getTenantMembershipForRuntime(2L, 1L, "req-1");
  }

  @Test
  void getTenantMembershipForRuntimePreservesMissingMembershipAndAdmissionAllowed() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 1L, false, true, 44L, "MISSING", 0L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("1")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertNotNull(observer.response());
    assertEquals(ACCOUNT_UUID, observer.response().getAccountId());
    assertFalse(observer.response().getMembershipExists());
    assertTrue(observer.response().getGameplayAdmissionAllowed());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @Test
  void getTenantMembershipForRuntimeRejectsZeroTenantIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetTenantMembershipForRuntimeResponse> ref = new AtomicReference<>();
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("0")
                    .setRequestId("req-1")
                    .build(),
                new StreamObserver<GetTenantMembershipForRuntimeResponse>() {
                  @Override
                  public void onNext(GetTenantMembershipForRuntimeResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "4CAe05e8-7a6b-4b14-9d44-665e3eec450b"
      })
  void getTenantMembershipForRuntimeRejectsNonCanonicalAccountId(String accountId) {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(accountId)
                    .setTenantId("1")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertTrue(observer.completed());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getTenantMembershipForRuntimeRejectsMissingGameSessionPeer() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    service.getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("1")
            .setRequestId("req-1")
            .build(),
        observer);

    assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
    assertTrue(observer.completed());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getTenantMembershipForRuntimeFailsClosedWhenPrivateAccountDoesNotCorrelate() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                3L, 1L, true, true, 44L, "ACTIVE", 9L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("1")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertTrue(observer.completed());
  }

  @Test
  void getTenantMembershipForRuntimeFailsClosedWhenPrivateTenantDoesNotCorrelate() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 3L, true, true, 44L, "ACTIVE", 9L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("1")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
    assertTrue(observer.completed());
  }

  @Test
  void getRealmAccessGrantForRuntimeRejectsNumericAccountIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetRealmAccessGrantForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId("42")
                    .setTenantId("1")
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertEquals(
        "accountId must be a canonical non-nil UUID", observer.response().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getRealmAccessGrantForRuntimeRequiresExactGameSessionPeer() {
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetRealmAccessGrantForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("1")
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getRealmAccessGrantForRuntimeResolvesUuidAndEchoesItAfterCorrelatedRead() {
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(42L);
    Mockito.when(accountService.getRealmAccessGrantForRuntime(42L, 7L, "demo", "preview", "req-1"))
        .thenReturn(
            new RealmAccessGrantResult(
                42L, 7L, "demo", "preview", true, 3L, Instant.now().toString()));
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetRealmAccessGrantForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("7")
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals(ACCOUNT_UUID, observer.response().getAccountId());
    assertEquals("7", observer.response().getTenantId());
    assertEquals("demo", observer.response().getWorldSlug());
    assertEquals("preview", observer.response().getRealmSlug());
    assertEquals(3L, observer.response().getGrantVersion());
    assertTrue(observer.response().getGranted());
    Mockito.verify(accountService).resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID));
  }

  @Test
  void getRealmAccessGrantForRuntimeFailsUnavailableOnMismatchedPrivateOwnerEvidence() {
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(42L);
    Mockito.when(accountService.getRealmAccessGrantForRuntime(42L, 7L, "demo", "preview", "req-1"))
        .thenReturn(
            new RealmAccessGrantResult(
                43L, 7L, "demo", "preview", true, 3L, Instant.now().toString()));
    AccountGrpcService service =
        new AccountGrpcService(
            Mockito.mock(PingService.class), accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetRealmAccessGrantForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId(ACCOUNT_UUID)
                    .setTenantId("7")
                    .setWorldSlug("demo")
                    .setRealmSlug("preview")
                    .setRequestId("req-1")
                    .build(),
                observer));

    assertEquals("AUTH_UNAVAILABLE", observer.response().getError().getCode());
  }

  @Test
  void getTenantEntitlementsForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantEntitlementsForRuntime(1L, "req-2"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto(
                1L, true, true, 19L, 311L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    RecordingObserver<GetTenantEntitlementsForRuntimeResponse> observer = new RecordingObserver<>();
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantEntitlementsForRuntime(
                GetTenantEntitlementsForRuntimeRequest.newBuilder()
                    .setTenantId("1")
                    .setRequestId("req-2")
                    .build(),
                observer));

    assertNotNull(observer.response());
    assertEquals("1", observer.response().getTenantId());
    assertTrue(observer.response().getGameplayAvailable());
    assertTrue(observer.response().getAllowPublicJoin());
    assertEquals(19L, observer.response().getEntitlementVersion());
    assertTrue(observer.completed());
    Mockito.verify(accountService).getTenantEntitlementsForRuntime(1L, "req-2");
  }

  @Test
  void getTenantEntitlementsForRuntimeAllowsWorldManagementPeer() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantEntitlementsForRuntime(1L, "req-world"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto(
                1L, true, true, 19L, 311L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    RecordingObserver<GetTenantEntitlementsForRuntimeResponse> observer = new RecordingObserver<>();

    withPeer(
        WORLD_MANAGEMENT_PEER,
        () ->
            service.getTenantEntitlementsForRuntime(
                GetTenantEntitlementsForRuntimeRequest.newBuilder()
                    .setTenantId("1")
                    .setRequestId("req-world")
                    .build(),
                observer));

    assertEquals("1", observer.response().getTenantId());
    assertTrue(observer.completed());
    Mockito.verify(accountService).getTenantEntitlementsForRuntime(1L, "req-world");
  }

  @Test
  void getTenantEntitlementsForRuntimeRejectsAbsentWrongAndCrossNamespacePeers() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    List<GrpcPeerIdentity> rejectedPeers =
        java.util.Arrays.asList(
            null,
            SOCIAL_GROUPS_PEER,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other/sa/game-session-service",
                "other",
                "game-session-service"),
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other/sa/world-management-service",
                "other",
                "world-management-service"));

    RecordingObserver<GetTenantEntitlementsForRuntimeResponse> absentPeerObserver =
        new RecordingObserver<>();
    service.getTenantEntitlementsForRuntime(validEntitlementRequest(), absentPeerObserver);
    assertEquals("PERMISSION_DENIED", absentPeerObserver.response().getError().getCode());
    for (GrpcPeerIdentity peer : rejectedPeers) {
      RecordingObserver<GetTenantEntitlementsForRuntimeResponse> observer =
          new RecordingObserver<>();
      withPeer(
          peer, () -> service.getTenantEntitlementsForRuntime(validEntitlementRequest(), observer));
      assertEquals("PERMISSION_DENIED", observer.response().getError().getCode());
      assertTrue(observer.completed());
    }
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getTenantEntitlementsForRuntimeRejectsMalformedTenantOrRequestBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);
    List<GetTenantEntitlementsForRuntimeRequest> malformedRequests =
        List.of(
            GetTenantEntitlementsForRuntimeRequest.newBuilder()
                .setTenantId("0")
                .setRequestId("req-1")
                .build(),
            GetTenantEntitlementsForRuntimeRequest.newBuilder()
                .setTenantId("1")
                .setRequestId(" ")
                .build(),
            GetTenantEntitlementsForRuntimeRequest.newBuilder()
                .setTenantId("1")
                .setRequestId("x".repeat(129))
                .build());

    for (GetTenantEntitlementsForRuntimeRequest request : malformedRequests) {
      RecordingObserver<GetTenantEntitlementsForRuntimeResponse> observer =
          new RecordingObserver<>();
      withPeer(GAME_SESSION_PEER, () -> service.getTenantEntitlementsForRuntime(request, observer));
      assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
      assertTrue(observer.completed());
    }
    Mockito.verifyNoInteractions(accountService);
  }

  private static GetTenantEntitlementsForRuntimeRequest validEntitlementRequest() {
    return GetTenantEntitlementsForRuntimeRequest.newBuilder()
        .setTenantId("1")
        .setRequestId("req-2")
        .build();
  }

  @Test
  void getTenantEntitlementsForRuntimeReturnsUnavailableAuthorityError() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantEntitlementsForRuntime(1L, "req-ambiguous"))
        .thenThrow(
            new AuthenticationException(
                "ENTITLEMENT_UNAVAILABLE",
                "Tenant entitlement authority is missing or ambiguous; retry later"));
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantEntitlementsForRuntime(
                GetTenantEntitlementsForRuntimeRequest.newBuilder()
                    .setTenantId("1")
                    .setRequestId("req-ambiguous")
                    .build(),
                new StreamObserver<GetTenantEntitlementsForRuntimeResponse>() {
                  @Override
                  public void onNext(GetTenantEntitlementsForRuntimeResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertTrue(ref.get().hasError());
    assertEquals("ENTITLEMENT_UNAVAILABLE", ref.get().getError().getCode());
  }

  @Test
  void getTenantEntitlementsForRuntimeRejectsZeroTenantIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
    withPeer(
        GAME_SESSION_PEER,
        () ->
            service.getTenantEntitlementsForRuntime(
                GetTenantEntitlementsForRuntimeRequest.newBuilder()
                    .setTenantId("0")
                    .setRequestId("req-2")
                    .build(),
                new StreamObserver<GetTenantEntitlementsForRuntimeResponse>() {
                  @Override
                  public void onNext(GetTenantEntitlementsForRuntimeResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileErrorReturnsDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.updateProfile(Mockito.any()))
        .thenThrow(new IllegalArgumentException("bad"));
    Mockito.when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<UpdateProfileResponse> ref = new AtomicReference<>();
    SessionContext.setContext(ACCOUNT_UUID, List.of("player"), Map.of());
    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("1")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(
                        "{\"displayName\":\"demo\",\"bio\":\"bio\",\"presenceVisibilityPolicy\":\"PRIVATE\"}")
                    .build(),
                new StreamObserver<UpdateProfileResponse>() {
                  @Override
                  public void onNext(UpdateProfileResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void updateProfileRejectsZeroTenantIdBeforeUpdate() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(pingService, accountService, null, WORKLOAD_NAMESPACE);

    AtomicReference<UpdateProfileResponse> ref = new AtomicReference<>();
    withPeer(
        SOCIAL_GROUPS_PEER,
        () ->
            service.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("0")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(
                        "{\"displayName\":\"demo\",\"bio\":\"bio\",\"presenceVisibilityPolicy\":\"PRIVATE\"}")
                    .build(),
                new StreamObserver<UpdateProfileResponse>() {
                  @Override
                  public void onNext(UpdateProfileResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void exportAccountErrorReturnsDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.exportAccountData(2L))
        .thenThrow(new IllegalArgumentException("missing"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<ExportAccountResponse> ref = new AtomicReference<>();
    service.exportAccount(
        ExportAccountRequest.newBuilder().setAccountId("2").build(),
        new StreamObserver<ExportAccountResponse>() {
          @Override
          public void onNext(ExportAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void exportAccountRejectsZeroAccountIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<ExportAccountResponse> ref = new AtomicReference<>();
    service.exportAccount(
        ExportAccountRequest.newBuilder().setAccountId("0").build(),
        new StreamObserver<ExportAccountResponse>() {
          @Override
          public void onNext(ExportAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void exportTenantDataRejectsZeroTenantIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<ExportTenantDataResponse> ref = new AtomicReference<>();
    service.exportTenantData(
        ExportTenantDataRequest.newBuilder().setTenantId("0").setAccountId("2").build(),
        new StreamObserver<ExportTenantDataResponse>() {
          @Override
          public void onNext(ExportTenantDataResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<DeleteAccountResponse> ref = new AtomicReference<>();
    service.deleteAccount(
        DeleteAccountRequest.newBuilder().setAccountId("2").build(),
        new StreamObserver<DeleteAccountResponse>() {
          @Override
          public void onNext(DeleteAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().getSuccess());
  }

  @Test
  void deleteAccountRejectsZeroAccountIdBeforeDelete() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<DeleteAccountResponse> ref = new AtomicReference<>();
    service.deleteAccount(
        DeleteAccountRequest.newBuilder().setAccountId("0").build(),
        new StreamObserver<DeleteAccountResponse>() {
          @Override
          public void onNext(DeleteAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void linkExternalAccountSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<net.firedevops.firemud.account.v1.LinkExternalAccountResponse> ref =
        new AtomicReference<>();
    service.linkExternalAccount(
        net.firedevops.firemud.account.v1.LinkExternalAccountRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setProvider("google")
            .setExternalId("abc")
            .build(),
        new StreamObserver<net.firedevops.firemud.account.v1.LinkExternalAccountResponse>() {
          @Override
          public void onNext(net.firedevops.firemud.account.v1.LinkExternalAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().getSuccess());
  }

  @Test
  void linkExternalAccountRejectsZeroTenantIdBeforeLink() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<net.firedevops.firemud.account.v1.LinkExternalAccountResponse> ref =
        new AtomicReference<>();
    service.linkExternalAccount(
        net.firedevops.firemud.account.v1.LinkExternalAccountRequest.newBuilder()
            .setTenantId("0")
            .setAccountId("2")
            .setProvider("google")
            .setExternalId("abc")
            .build(),
        new StreamObserver<net.firedevops.firemud.account.v1.LinkExternalAccountResponse>() {
          @Override
          public void onNext(net.firedevops.firemud.account.v1.LinkExternalAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void requestEmailVerificationRejectsZeroAccountIdBeforeDispatch() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<net.firedevops.firemud.account.v1.RequestEmailVerificationResponse> ref =
        new AtomicReference<>();
    service.requestEmailVerification(
        net.firedevops.firemud.account.v1.RequestEmailVerificationRequest.newBuilder()
            .setAccountId("0")
            .build(),
        new StreamObserver<net.firedevops.firemud.account.v1.RequestEmailVerificationResponse>() {
          @Override
          public void onNext(
              net.firedevops.firemud.account.v1.RequestEmailVerificationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountRequiresAdminRole() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    SessionContext.setContext("1", List.of("player"), Map.of());
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<DeleteAccountResponse> ref = new AtomicReference<>();
    service.deleteAccount(
        DeleteAccountRequest.newBuilder().setAccountId("2").build(),
        new StreamObserver<DeleteAccountResponse>() {
          @Override
          public void onNext(DeleteAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(false, ref.get().getSuccess());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T response;
    private boolean receivedTransportError;
    private boolean completed;

    @Override
    public void onNext(T value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      receivedTransportError = true;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private T response() {
      return response;
    }

    private boolean receivedTransportError() {
      return receivedTransportError;
    }

    private boolean completed() {
      return completed;
    }
  }
}
