package net.firedevops.firemud.test;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
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
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.account.AccountProfileJson;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Shared fake Account runtime authority for cross-service gameplay tests. */
public final class AccountRuntimeStubServer extends AccountServiceGrpc.AccountServiceImplBase
    implements AutoCloseable {
  private static final String EVALUATED_AT = "2026-03-30T00:00:00Z";
  private static final Set<String> IMPLEMENTED_RUNTIME_METHODS =
      Set.of(
          "Ping",
          "Authenticate",
          "GetTenantMembershipForRuntime",
          "GetRealmAccessGrantForRuntime",
          "GetTenantEntitlementsForRuntime",
          "IssueDirectTextConnectScope",
          "JoinPublicProductionMembership");

  private final Server server;
  private final List<AuthenticateRequest> authenticateRequests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean membershipExists = new AtomicBoolean(true);
  private final AtomicBoolean gameplayAdmissionAllowed = new AtomicBoolean(true);
  private final AtomicReference<String> membershipLifecycleState = new AtomicReference<>("ACTIVE");
  private final AtomicBoolean gameplayAvailable = new AtomicBoolean(true);
  private final AtomicBoolean allowPublicJoin = new AtomicBoolean(true);
  private final AtomicBoolean realmAccessGranted = new AtomicBoolean(true);
  private final AtomicLong defaultAccountId = new AtomicLong(1L);
  private final AtomicLong nextConnectScopeId = new AtomicLong(1L);
  private final Map<String, Long> accountIdsByEmail = new ConcurrentHashMap<>();
  private final Map<Long, StubProfile> profilesByAccountId = new ConcurrentHashMap<>();
  private final Map<String, StubConnectScope> connectScopesById = new ConcurrentHashMap<>();

  public AccountRuntimeStubServer(int port) throws IOException {
    this.server = NettyServerBuilder.forPort(port).addService(this).build().start();
  }

  public static Set<String> implementedRuntimeMethodNames() {
    return IMPLEMENTED_RUNTIME_METHODS;
  }

  public int port() {
    return server.getPort();
  }

  public List<AuthenticateRequest> capturedAuthenticateRequests() {
    return List.copyOf(authenticateRequests);
  }

  public void setDefaultAccountId(long accountId) {
    defaultAccountId.set(accountId);
  }

  public void mapAccountId(String email, long accountId) {
    accountIdsByEmail.put(EmailCanonicalization.normalize(email), accountId);
  }

  public void setPresenceVisibilityPolicy(long accountId, String visibilityPolicy) {
    profilesByAccountId.compute(
        accountId,
        (ignored, current) ->
            (current == null ? StubProfile.defaultFor(accountId) : current)
                .withPresenceVisibilityPolicy(visibilityPolicy));
  }

  public void setGameplayAdmissionAllowed(boolean allowed) {
    gameplayAdmissionAllowed.set(allowed);
  }

  public void setMembershipExists(boolean exists) {
    if (!exists) {
      gameplayAdmissionAllowed.set(false);
    }
    membershipExists.set(exists);
    membershipLifecycleState.set(exists ? "ACTIVE" : "MISSING");
  }

  public void allowGameplayAdmission() {
    setMembershipExists(true);
    setGameplayAdmissionAllowed(true);
  }

  public void denyGameplayAdmission() {
    setMembershipExists(true);
    setGameplayAdmissionAllowed(false);
    membershipLifecycleState.set("INACTIVE");
  }

  public void setGameplayAvailable(boolean available) {
    gameplayAvailable.set(available);
  }

  public void setAllowPublicJoin(boolean allowed) {
    allowPublicJoin.set(allowed);
  }

  public void setRealmAccessGranted(boolean granted) {
    realmAccessGranted.set(granted);
  }

  public void resetRuntimeState() {
    authenticateRequests.clear();
    membershipExists.set(true);
    gameplayAdmissionAllowed.set(true);
    membershipLifecycleState.set("ACTIVE");
    gameplayAvailable.set(true);
    allowPublicJoin.set(true);
    realmAccessGranted.set(true);
    profilesByAccountId.clear();
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    responseObserver.onNext(PingResponse.newBuilder().setMessage("ok").build());
    responseObserver.onCompleted();
  }

  @Override
  public void authenticate(
      AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    authenticateRequests.add(request);
    String canonicalEmail = EmailCanonicalization.normalize(request.getEmail());
    long accountId = accountIdsByEmail.getOrDefault(canonicalEmail, defaultAccountId.get());
    profilesByAccountId.computeIfAbsent(accountId, StubProfile::defaultFor);
    responseObserver.onNext(
        AuthenticateResponse.newBuilder()
            .setAccountId(Long.toString(accountId))
            .setAuthToken("stub-token-" + accountId)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void getTenantMembershipForRuntime(
      GetTenantMembershipForRuntimeRequest request,
      StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
    boolean exists = membershipExists.get();
    responseObserver.onNext(
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(request.getAccountId())
            .setTenantId(request.getTenantId())
            .setMembershipExists(exists)
            .setMembershipLifecycleState(membershipLifecycleState.get())
            .setGameplayAdmissionAllowed(gameplayAdmissionAllowed.get())
            .setMembershipVersion(exists ? 1L : 0L)
            .setEvaluatedAt(EVALUATED_AT)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void getRealmAccessGrantForRuntime(
      GetRealmAccessGrantForRuntimeRequest request,
      StreamObserver<GetRealmAccessGrantForRuntimeResponse> responseObserver) {
    responseObserver.onNext(
        GetRealmAccessGrantForRuntimeResponse.newBuilder()
            .setAccountId(request.getAccountId())
            .setTenantId(request.getTenantId())
            .setWorldSlug(request.getWorldSlug())
            .setRealmSlug(request.getRealmSlug())
            .setGranted(realmAccessGranted.get())
            .setGrantVersion(1L)
            .setEvaluatedAt(EVALUATED_AT)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void getTenantEntitlementsForRuntime(
      GetTenantEntitlementsForRuntimeRequest request,
      StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
    responseObserver.onNext(
        GetTenantEntitlementsForRuntimeResponse.newBuilder()
            .setTenantId(request.getTenantId())
            .setGameplayAvailable(gameplayAvailable.get())
            .setAllowPublicJoin(allowPublicJoin.get())
            .setEntitlementVersion(1L)
            .setTenantBillingSequence(1L)
            .setEvaluatedAt(EVALUATED_AT)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void issueDirectTextConnectScope(
      IssueDirectTextConnectScopeRequest request,
      StreamObserver<IssueDirectTextConnectScopeResponse> responseObserver) {
    PlayerExecutionContext caller = request.getPlayerContext();
    if (caller.getAccountId().isBlank()
        || caller.getTenantId().isBlank()
        || caller.getRealmId().isBlank()
        || caller.getGameInstanceId().isBlank()
        || caller.getPlayableStateNamespaceId().isBlank()
        || caller.getPlayableStateScope().isBlank()
        || request.getTenantId().isBlank()
        || request.getRealmId().isBlank()
        || request.getGameInstanceId().isBlank()
        || request.getWorldSlug().isBlank()
        || request.getRealmSlug().isBlank()
        || request.getPlayableStateNamespaceId().isBlank()
        || request.getPlayableStateScope().isBlank()
        || !caller.getTenantId().equals(request.getTenantId())
        || !caller.getRealmId().equals(request.getRealmId())
        || !caller.getGameInstanceId().equals(request.getGameInstanceId())
        || !caller.getPlayableStateNamespaceId().equals(request.getPlayableStateNamespaceId())
        || !caller.getPlayableStateScope().equals(request.getPlayableStateScope())) {
      responseObserver.onNext(
          IssueDirectTextConnectScopeResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage("Direct-text connect scope request is incomplete"))
              .build());
    } else {
      String scopeId = "stub-connect-scope-" + nextConnectScopeId.getAndIncrement();
      Instant expiresAt = Instant.now().plusSeconds(300);
      connectScopesById.put(
          scopeId,
          new StubConnectScope(
              caller.getAccountId(),
              request.getTenantId(),
              request.getRealmId(),
              request.getGameInstanceId(),
              request.getPlayableStateNamespaceId(),
              request.getPlayableStateScope(),
              expiresAt));
      responseObserver.onNext(
          IssueDirectTextConnectScopeResponse.newBuilder()
              .setConnectScopeId(scopeId)
              .setConnectScopeExpiresAt(expiresAt.toString())
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  public void joinPublicProductionMembership(
      JoinPublicProductionMembershipRequest request,
      StreamObserver<JoinPublicProductionMembershipResponse> responseObserver) {
    PlayerExecutionContext caller = request.getPlayerContext();
    StubConnectScope scope = connectScopesById.get(request.getConnectScopeId());
    if (request.getConnectScopeId().isBlank()
        || request.getRequestId().isBlank()
        || !request.getRequestId().equals(caller.getRequestId())
        || caller.getAccountId().isBlank()
        || caller.getTenantId().isBlank()
        || scope == null
        || !scope.expiresAt().isAfter(Instant.now())
        || !scope.accountId().equals(caller.getAccountId())
        || !scope.tenantId().equals(caller.getTenantId())
        || !scope.realmId().equals(caller.getRealmId())
        || !scope.gameInstanceId().equals(caller.getGameInstanceId())
        || !scope.playableStateNamespaceId().equals(caller.getPlayableStateNamespaceId())
        || !scope.playableStateScope().equals(caller.getPlayableStateScope())) {
      responseObserver.onNext(
          JoinPublicProductionMembershipResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage("Direct-text JOIN request is incomplete"))
              .build());
    } else if (!allowPublicJoin.get()) {
      responseObserver.onNext(
          JoinPublicProductionMembershipResponse.newBuilder()
              .setOutcomeCode("PUBLIC_PRODUCTION_ADMISSION_DENIED")
              .setAccountId(caller.getAccountId())
              .setTenantId(caller.getTenantId())
              .build());
    } else {
      membershipExists.set(true);
      gameplayAdmissionAllowed.set(true);
      responseObserver.onNext(
          JoinPublicProductionMembershipResponse.newBuilder()
              .setSuccess(true)
              .setOutcomeCode("JOINED")
              .setAccountId(caller.getAccountId())
              .setTenantId(caller.getTenantId())
              .setMembershipId(
                  "stub-membership-" + caller.getAccountId() + "-" + caller.getTenantId())
              .setMembershipVersion(1L)
              .setMembershipAuthorityGeneration(1L)
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  public void getProfile(
      GetProfileRequest request, StreamObserver<GetProfileResponse> responseObserver) {
    long accountId = Long.parseLong(request.getAccountId());
    StubProfile profile = profilesByAccountId.computeIfAbsent(accountId, StubProfile::defaultFor);
    try {
      responseObserver.onNext(
          GetProfileResponse.newBuilder()
              .setProfileJson(
                  new AccountProfileJson(
                          profile.displayName(), profile.bio(), profile.presenceVisibilityPolicy())
                      .toJson())
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(GetProfileResponse.newBuilder().build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> responseObserver) {
    try {
      long accountId = Long.parseLong(request.getAccountId());
      AccountProfileJson profile =
          AccountProfileJson.parse(request.getProfileJson(), StubProfile.DEFAULT_VISIBILITY_POLICY);
      StubProfile existing =
          profilesByAccountId.computeIfAbsent(accountId, StubProfile::defaultFor);
      profilesByAccountId.put(
          accountId,
          new StubProfile(
              profile.displayName(),
              profile.bio(),
              profile.presenceVisibilityPolicy() == null
                  ? existing.presenceVisibilityPolicy()
                  : profile.presenceVisibilityPolicy()));
      responseObserver.onNext(UpdateProfileResponse.newBuilder().setSuccess(true).build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(UpdateProfileResponse.newBuilder().setSuccess(false).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void close() {
    server.shutdownNow();
  }

  private record StubProfile(String displayName, String bio, String presenceVisibilityPolicy) {
    private static final String DEFAULT_VISIBILITY_POLICY = "FRIENDS_ONLY";

    private static StubProfile defaultFor(long accountId) {
      return new StubProfile("Demo-" + accountId, null, DEFAULT_VISIBILITY_POLICY);
    }

    private StubProfile withPresenceVisibilityPolicy(String visibilityPolicy) {
      return new StubProfile(
          displayName,
          bio,
          visibilityPolicy == null ? DEFAULT_VISIBILITY_POLICY : visibilityPolicy);
    }
  }

  private record StubConnectScope(
      String accountId,
      String tenantId,
      String realmId,
      String gameInstanceId,
      String playableStateNamespaceId,
      String playableStateScope,
      Instant expiresAt) {}
}
