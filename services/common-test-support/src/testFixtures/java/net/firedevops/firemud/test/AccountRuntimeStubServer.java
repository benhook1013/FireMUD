package net.firedevops.firemud.test;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import net.firedevops.firemud.account.v1.RuntimeAuthorityTuple;
import net.firedevops.firemud.account.v1.RuntimeMembershipBaseline;
import net.firedevops.firemud.account.v1.RuntimeOutboxCheckpoint;
import net.firedevops.firemud.account.v1.RuntimeOutboxSourceEvidence;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.account.AccountProfileJson;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Shared fake Account runtime authority for cross-service gameplay tests. */
public final class AccountRuntimeStubServer extends AccountServiceGrpc.AccountServiceImplBase
    implements AutoCloseable {
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final String DEFAULT_ACCOUNT_UUID = "a7e0feac-60ab-4fd1-9002-0ad38d585db0";
  private static final String MEMBERSHIP_EVENT_ID = "00000000-0000-0000-0000-000000000099";
  private static final String MEMBERSHIP_EVENT_REQUEST_ID = "runtime-membership-request";
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
  private final AtomicReference<String> defaultAccountUuid =
      new AtomicReference<>(DEFAULT_ACCOUNT_UUID);
  private final AtomicLong nextConnectScopeId = new AtomicLong(1L);
  private final Map<String, String> accountUuidsByEmail = new ConcurrentHashMap<>();
  private final Map<String, StubProfile> profilesByAccountUuid = new ConcurrentHashMap<>();
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

  public void setDefaultAccountUuid(String accountUuid) {
    defaultAccountUuid.set(requireCanonicalAccountUuid(accountUuid));
  }

  public void mapAccountUuid(String email, String accountUuid) {
    accountUuidsByEmail.put(
        EmailCanonicalization.normalize(email), requireCanonicalAccountUuid(accountUuid));
  }

  public void setPresenceVisibilityPolicy(String accountUuid, String visibilityPolicy) {
    String canonicalAccountUuid = requireCanonicalAccountUuid(accountUuid);
    profilesByAccountUuid.compute(
        canonicalAccountUuid,
        (ignored, current) ->
            (current == null ? StubProfile.defaultFor(canonicalAccountUuid) : current)
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
    profilesByAccountUuid.clear();
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
    String accountUuid = accountUuidsByEmail.getOrDefault(canonicalEmail, defaultAccountUuid.get());
    profilesByAccountUuid.computeIfAbsent(accountUuid, StubProfile::defaultFor);
    responseObserver.onNext(
        AuthenticateResponse.newBuilder()
            .setAccountId(accountUuid)
            .setAuthToken("stub-token-" + accountUuid)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void getTenantMembershipForRuntime(
      GetTenantMembershipForRuntimeRequest request,
      StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
    boolean exists = membershipExists.get();
    String accountUuid = requireCanonicalAccountUuid(request.getPlayerContext().getAccountId());
    String tenantSelector = request.getPlayerContext().getTenantId();
    String lifecycle = membershipLifecycleState.get();
    boolean admitted = gameplayAdmissionAllowed.get();
    GetTenantMembershipForRuntimeResponse response;
    if (exists && admitted && "ACTIVE".equals(lifecycle)) {
      response =
          completeMembershipSnapshot(
              accountUuid, tenantSelector, request.getPlayerContext().getRequestId(), lifecycle);
    } else if (exists && !admitted && "INACTIVE".equals(lifecycle)) {
      response =
          completeMembershipSnapshot(
              accountUuid, tenantSelector, request.getPlayerContext().getRequestId(), lifecycle);
    } else if (!exists && !admitted && "MISSING".equals(lifecycle)) {
      response =
          completeMembershipSnapshot(
              accountUuid, tenantSelector, request.getPlayerContext().getRequestId(), lifecycle);
    } else {
      response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setAccountId(accountUuid)
              .setTenantId(syntheticTenantUuid(tenantSelector))
              .setRequestAccountId(accountUuid)
              .setRequestTenantId(tenantSelector)
              .setRequestId(request.getPlayerContext().getRequestId())
              .setMembershipExists(exists)
              .setMembershipLifecycleState(lifecycle)
              .setGameplayAdmissionAllowed(admitted)
              .setEvaluatedAt(Instant.now().toString())
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private static GetTenantMembershipForRuntimeResponse completeMembershipSnapshot(
      String accountUuid, String tenantSelector, String requestId, String lifecycle) {
    boolean exists = !"MISSING".equals(lifecycle);
    boolean admitted = "ACTIVE".equals(lifecycle);
    String tenantUuid = syntheticTenantUuid(tenantSelector);
    String membershipStream =
        AUTHORITY_STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
    List<RuntimeOutboxCheckpoint> checkpoints =
        new ArrayList<>(
            List.of(
                checkpoint(AUTHORITY_STREAM_PREFIX + "account/" + accountUuid, "0"),
                checkpoint(AUTHORITY_STREAM_PREFIX + "issuer/" + ACCOUNT_ISSUER, "0"),
                checkpoint(membershipStream, exists ? "1" : "0"),
                checkpoint(AUTHORITY_STREAM_PREFIX + "tenant/" + tenantUuid, "0")));
    checkpoints.sort(
        (first, second) -> first.getOutboxStreamKey().compareTo(second.getOutboxStreamKey()));
    RuntimeAuthorityTuple tuple =
        RuntimeAuthorityTuple.newBuilder()
            .setIssuerAuthGeneration("1")
            .setAccountAuthorityGeneration("1")
            .putTenantAuthorityGeneration(tenantUuid, "1")
            .putMembershipAuthorityGeneration(tenantUuid, "1")
            .build();
    RuntimeMembershipBaseline baseline =
        RuntimeMembershipBaseline.newBuilder()
            .setMembershipLifecycleState(lifecycle)
            .putMembershipVersion(tenantUuid, "1")
            .setMembershipAuthorityGeneration("1")
            .build();
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(tenantUuid)
            .setRequestAccountId(accountUuid)
            .setRequestTenantId(tenantSelector)
            .setRequestId(requestId)
            .setAuthorityAvailability("AVAILABLE")
            .setMembershipExists(exists)
            .setMembershipLifecycleState(lifecycle)
            .setGameplayAdmissionAllowed(admitted)
            .putMembershipVersion(tenantUuid, "1")
            .setMembershipAuthorityGeneration("1")
            .setMembershipBaseline(baseline)
            .setAuthorityTuple(tuple)
            .setIssuanceFence("1")
            .addAllOutboxCheckpoints(checkpoints)
            .setEvaluatedAt(Instant.now().toString());
    if (admitted) {
      response.addRoles("player");
    }
    if (exists) {
      MembershipAuthorityEventV1Codec.MembershipEvent event =
          MembershipAuthorityEventV1Codec.seal(
              Map.ofEntries(
                  Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                  Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                  Map.entry("eventId", MEMBERSHIP_EVENT_ID),
                  Map.entry("requestId", MEMBERSHIP_EVENT_REQUEST_ID),
                  Map.entry("outboxStreamKey", membershipStream),
                  Map.entry("outboxSequence", "1"),
                  Map.entry("sourceScope", "membership/" + accountUuid + "/" + tenantUuid),
                  Map.entry("accountId", accountUuid),
                  Map.entry("tenantId", tenantUuid),
                  Map.entry("membershipExists", true),
                  Map.entry("membershipLifecycleState", lifecycle),
                  Map.entry("membershipVersion", Map.of(tenantUuid, "1")),
                  Map.entry("membershipAuthorityGeneration", "1"),
                  Map.entry(
                      "authorityTuple",
                      Map.of(
                          "issuerAuthGeneration", "1",
                          "accountAuthorityGeneration", "1",
                          "tenantAuthorityGeneration", Map.of(tenantUuid, "1"),
                          "membershipAuthorityGeneration", Map.of(tenantUuid, "1"),
                          "privateRealmGrantVersions", List.of())),
                  Map.entry("issuanceFence", "1"),
                  Map.entry("roles", admitted ? List.of("player") : List.of()),
                  Map.entry("gameplayAdmissionAllowed", admitted),
                  Map.entry("callerBoundAuthorityInvalidated", false)));
      response.addOutboxSourceEvidence(
          RuntimeOutboxSourceEvidence.newBuilder()
              .setOutboxStreamKey(membershipStream)
              .setOutboxSequence("1")
              .setEventId(event.eventId())
              .setEventDigest(event.eventDigest())
              .setCanonicalEventJson(event.canonicalJson()));
    }
    return response.build();
  }

  private static RuntimeOutboxCheckpoint checkpoint(String streamKey, String sequence) {
    return RuntimeOutboxCheckpoint.newBuilder()
        .setOutboxStreamKey(streamKey)
        .setOutboxSequence(sequence)
        .build();
  }

  private static String requireCanonicalAccountUuid(String selector) {
    if (selector == null) {
      throw new IllegalArgumentException("Account UUID must be canonical and non-nil");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(selector);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Account UUID must be canonical and non-nil", exception);
    }
    if ((parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L)
        || !parsed.toString().equals(selector)) {
      throw new IllegalArgumentException("Account UUID must be canonical and non-nil");
    }
    return selector;
  }

  private static String syntheticTenantUuid(String selector) {
    // This stable UUID-shaped key is only a legacy synthetic-tenant test fixture. It is not
    // evidence of an Account-owned tenant identity or an authenticated membership snapshot.
    long value = Long.parseLong(selector);
    if (value <= 0L || !Long.toString(value).equals(selector)) {
      throw new IllegalArgumentException("synthetic tenant fixture selector must be canonical");
    }
    return "00000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", value);
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
            .setEvaluatedAt(Instant.now().toString())
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
            .setEvaluatedAt(Instant.now().toString())
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
    try {
      String accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      StubProfile profile =
          profilesByAccountUuid.computeIfAbsent(accountUuid, StubProfile::defaultFor);
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
      String accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      AccountProfileJson profile =
          AccountProfileJson.parse(request.getProfileJson(), StubProfile.DEFAULT_VISIBILITY_POLICY);
      StubProfile existing =
          profilesByAccountUuid.computeIfAbsent(accountUuid, StubProfile::defaultFor);
      profilesByAccountUuid.put(
          accountUuid,
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

    private static StubProfile defaultFor(String accountUuid) {
      return new StubProfile("Demo-" + accountUuid, null, DEFAULT_VISIBILITY_POLICY);
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
