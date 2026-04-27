package net.firedevops.firemud.test;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipRequest;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;

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
          "EnsurePublicProductionPlayerMembership",
          "GetTenantEntitlementsForRuntime");

  private final Server server;
  private final List<AuthenticateRequest> authenticateRequests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean gameplayAdmissionAllowed = new AtomicBoolean(true);
  private final AtomicBoolean gameplayAvailable = new AtomicBoolean(true);
  private final AtomicBoolean realmAccessGranted = new AtomicBoolean(true);
  private final AtomicLong defaultAccountId = new AtomicLong(1L);
  private final Map<String, Long> accountIdsByUsername = new ConcurrentHashMap<>();

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

  public void mapAccountId(String username, long accountId) {
    accountIdsByUsername.put(username, accountId);
  }

  public void setGameplayAdmissionAllowed(boolean allowed) {
    gameplayAdmissionAllowed.set(allowed);
  }

  public void allowGameplayAdmission() {
    setGameplayAdmissionAllowed(true);
  }

  public void denyGameplayAdmission() {
    setGameplayAdmissionAllowed(false);
  }

  public void setGameplayAvailable(boolean available) {
    gameplayAvailable.set(available);
  }

  public void setRealmAccessGranted(boolean granted) {
    realmAccessGranted.set(granted);
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
    long accountId =
        accountIdsByUsername.getOrDefault(request.getUsername(), defaultAccountId.get());
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
    responseObserver.onNext(
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(request.getAccountId())
            .setTenantId(request.getTenantId())
            .setGameplayAdmissionAllowed(gameplayAdmissionAllowed.get())
            .setMembershipVersion(1L)
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
  public void ensurePublicProductionPlayerMembership(
      EnsurePublicProductionPlayerMembershipRequest request,
      StreamObserver<EnsurePublicProductionPlayerMembershipResponse> responseObserver) {
    boolean allowed = gameplayAdmissionAllowed.get();
    responseObserver.onNext(
        EnsurePublicProductionPlayerMembershipResponse.newBuilder()
            .setAccountId(request.getAccountId())
            .setTenantId(request.getTenantId())
            .setRealmSlug(request.getRealmSlug())
            .setGameplayAdmissionAllowed(allowed)
            .setMembershipVersion(1L)
            .setCreated(allowed)
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
            .setEntitlementVersion(1L)
            .setTenantBillingSequence(1L)
            .setEvaluatedAt(EVALUATED_AT)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void close() {
    server.shutdownNow();
  }
}
