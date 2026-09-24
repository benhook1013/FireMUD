package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

class AccountGrpcServiceTest {
  private static final String WORKLOAD_NAMESPACE = "test";
  private static final String REALM_ID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String OTHER_REALM_ID = "57c58f36-c5ea-4aa8-8ef7-91a45e407f01";
  private static final GrpcPeerIdentity GAME_SESSION_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/test/sa/game-session-service",
          WORKLOAD_NAMESPACE,
          "game-session-service");

  private static PlayerExecutionContext validPlayerContext() {
    return PlayerExecutionContext.newBuilder()
        .setAccountId("10")
        .setTenantId("20")
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId("realm-state-30")
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
        .setPlayableStateNamespaceId("realm-state-30")
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
                "realm-state-30",
                "realm:30",
                40L,
                "session-1",
                "request-1"),
            new DirectTextJoinTarget(
                20L,
                UUID.fromString(REALM_ID),
                "world",
                "public",
                "realm-state-30",
                "realm:30",
                40L,
                5L,
                8L));
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
                "realm-state-30",
                "realm:30",
                40L,
                "session-1",
                "request-1"),
            new JoinPublicProductionRequest("scope-1", "request-1"));
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
  void verifyEmailLoginOtpReturnsAuthenticatedSession() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.verifyEmailLoginOtp("demo@example.com", "123456"))
        .thenReturn(new net.firedevops.firemud.accountservice.dto.AuthenticationResult(9L, "jwt"));
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
    assertEquals("9", ref.get().getAccountId());
    assertEquals("jwt", ref.get().getAuthToken());
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
  void createAccountReturnsAccountIdForGlobalRequest() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenReturn(new AccountDto(1L, "demo", "e@example.com", "player", true));
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
    assertEquals("1", ref.get().getAccountId());
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
    Mockito.when(accountService.getProfile(1L, 2L))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(
                1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetProfileResponse> ref = new AtomicReference<>();
    service.getProfile(
        GetProfileRequest.newBuilder().setTenantId("1").setAccountId("2").build(),
        new StreamObserver<GetProfileResponse>() {
          @Override
          public void onNext(GetProfileResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

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
  }

  @Test
  void getProfileRejectsZeroAccountIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetProfileResponse> ref = new AtomicReference<>();
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
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void listPresenceVisibilityPoliciesMapsPersistedPolicies() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L, 3L)))
        .thenReturn(
            Map.of(
                2L, ProfilePresenceVisibilityPolicy.PRIVATE,
                3L, ProfilePresenceVisibilityPolicy.HIDDEN_STAFF));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    service.listPresenceVisibilityPolicies(
        ListPresenceVisibilityPoliciesRequest.newBuilder()
            .setTenantId("1")
            .addAccountIds("2")
            .addAccountIds("3")
            .addAccountIds("2")
            .build(),
        observer);

    assertNotNull(observer.response());
    assertEquals(2, observer.response().getPoliciesCount());
    assertTrue(
        observer.response().getPoliciesList().stream()
            .anyMatch(
                entry -> entry.getAccountId().equals("2") && entry.getPolicy().equals("PRIVATE")));
    assertTrue(
        observer.response().getPoliciesList().stream()
            .anyMatch(
                entry ->
                    entry.getAccountId().equals("3") && entry.getPolicy().equals("HIDDEN_STAFF")));
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verify(accountService).listPresenceVisibilityPolicies(1L, List.of(2L, 3L));
  }

  @Test
  void listPresenceVisibilityPoliciesRejectsNonPositiveAccountId() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> observer = new RecordingObserver<>();

    service.listPresenceVisibilityPolicies(
        ListPresenceVisibilityPoliciesRequest.newBuilder()
            .setTenantId("1")
            .addAccountIds("0")
            .build(),
        observer);

    assertNotNull(observer.response());
    assertEquals("INVALID_ARGUMENT", observer.response().getError().getCode());
    assertEquals("accountId must be positive", observer.response().getError().getMessage());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void listPresenceVisibilityPoliciesMapsServiceRuntimeFailuresToApplicationErrors() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenThrow(new IllegalArgumentException("Tenant not found"));
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> notFoundObserver =
        new RecordingObserver<>();
    service.listPresenceVisibilityPolicies(
        ListPresenceVisibilityPoliciesRequest.newBuilder()
            .setTenantId("1")
            .addAccountIds("2")
            .build(),
        notFoundObserver);

    assertEquals("NOT_FOUND", notFoundObserver.response().getError().getCode());
    assertTrue(notFoundObserver.completed());
    assertFalse(notFoundObserver.receivedTransportError());

    Mockito.reset(accountService);
    Mockito.when(accountService.listPresenceVisibilityPolicies(1L, List.of(2L)))
        .thenThrow(new IllegalStateException("Policy lookup unavailable"));
    RecordingObserver<ListPresenceVisibilityPoliciesResponse> internalObserver =
        new RecordingObserver<>();
    service.listPresenceVisibilityPolicies(
        ListPresenceVisibilityPoliciesRequest.newBuilder()
            .setTenantId("1")
            .addAccountIds("2")
            .build(),
        internalObserver);

    assertEquals("INTERNAL", internalObserver.response().getError().getCode());
    assertTrue(internalObserver.completed());
    assertFalse(internalObserver.receivedTransportError());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 1L, true, true, 44L, "ACTIVE", 9L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantMembershipForRuntimeResponse> ref = new AtomicReference<>();
    service.getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId("2")
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
        });

    assertNotNull(ref.get());
    assertEquals("2", ref.get().getAccountId());
    assertTrue(ref.get().getMembershipExists());
    assertTrue(ref.get().getGameplayAdmissionAllowed());
    assertEquals(44L, ref.get().getMembershipVersion());
  }

  @Test
  void getTenantMembershipForRuntimePreservesMissingMembershipAndAdmissionAllowed() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 1L, false, true, 44L, "MISSING", 0L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();

    service.getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId("2")
            .setTenantId("1")
            .setRequestId("req-1")
            .build(),
        observer);

    assertNotNull(observer.response());
    assertFalse(observer.response().getMembershipExists());
    assertTrue(observer.response().getGameplayAdmissionAllowed());
    assertTrue(observer.completed());
    assertFalse(observer.receivedTransportError());
  }

  @Test
  void getTenantMembershipForRuntimeRejectsZeroTenantIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantMembershipForRuntimeResponse> ref = new AtomicReference<>();
    service.getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId("2")
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
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountService);
  }

  @Test
  void getRealmAccessGrantForRuntimeRejectsZeroAccountIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetRealmAccessGrantForRuntimeResponse> ref = new AtomicReference<>();
    service.getRealmAccessGrantForRuntime(
        GetRealmAccessGrantForRuntimeRequest.newBuilder()
            .setAccountId("0")
            .setTenantId("1")
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setRequestId("req-1")
            .build(),
        new StreamObserver<GetRealmAccessGrantForRuntimeResponse>() {
          @Override
          public void onNext(GetRealmAccessGrantForRuntimeResponse value) {
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
  void getTenantEntitlementsForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantEntitlementsForRuntime(1L, "req-2"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto(
                1L, true, true, 19L, 311L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
    service.getTenantEntitlementsForRuntime(
        GetTenantEntitlementsForRuntimeRequest.newBuilder()
            .setTenantId("1")
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
        });

    assertNotNull(ref.get());
    assertEquals("1", ref.get().getTenantId());
    assertTrue(ref.get().getGameplayAvailable());
    assertTrue(ref.get().getAllowPublicJoin());
    assertEquals(19L, ref.get().getEntitlementVersion());
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
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
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
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().hasError());
    assertEquals("ENTITLEMENT_UNAVAILABLE", ref.get().getError().getCode());
  }

  @Test
  void getTenantEntitlementsForRuntimeRejectsZeroTenantIdBeforeLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
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
        });

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
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<UpdateProfileResponse> ref = new AtomicReference<>();
    service.updateProfile(
        UpdateProfileRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
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
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void updateProfileRejectsZeroTenantIdBeforeUpdate() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<UpdateProfileResponse> ref = new AtomicReference<>();
    service.updateProfile(
        UpdateProfileRequest.newBuilder()
            .setTenantId("0")
            .setAccountId("2")
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
        });

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
