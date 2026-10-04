package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;

public final class WorldManagementStubServer implements AutoCloseable {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private final Server server;
  private final int port;
  private final AtomicReference<StatusRuntimeException> nextFailure = new AtomicReference<>();
  private final Map<String, AcquireInitialAdmissionBindHoldRequest> admissionBindRequests =
      new HashMap<>();
  private final Map<String, InitialAdmissionBindHold> admissionBindHolds = new HashMap<>();

  public WorldManagementStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new WorldManagementServiceGrpc.WorldManagementServiceImplBase() {
                  @Override
                  public void acquireInitialAdmissionBindHold(
                      AcquireInitialAdmissionBindHoldRequest request,
                      StreamObserver<AcquireInitialAdmissionBindHoldResponse> responseObserver) {
                    try {
                      responseObserver.onNext(issueInitialAdmissionBindHold(request));
                      responseObserver.onCompleted();
                    } catch (StatusRuntimeException failure) {
                      responseObserver.onError(failure);
                    }
                  }

                  @Override
                  public void getRoomSnapshot(
                      GetRoomSnapshotRequest request,
                      StreamObserver<GetRoomSnapshotResponse> responseObserver) {
                    StatusRuntimeException failure = nextFailure.getAndSet(null);
                    if (failure != null) {
                      responseObserver.onError(failure);
                      return;
                    }
                    try {
                      RoomSnapshot snapshot =
                          LookTestFixtures.sampleRoomSnapshot(
                              request.getRoomInstance().getRoomInstanceId());
                      RoomSnapshot.Builder scopedSnapshot = snapshot.toBuilder();
                      if (!request.getRoomInstance().getTenantId().isBlank()) {
                        scopedSnapshot.setTenantId(request.getRoomInstance().getTenantId());
                      } else if (!request.getTenantId().isBlank()) {
                        scopedSnapshot.setTenantId(request.getTenantId());
                      }
                      if (!request.getRoomInstance().getGameInstanceId().isBlank()) {
                        scopedSnapshot.setGameInstanceId(
                            request.getRoomInstance().getGameInstanceId());
                      }
                      responseObserver.onNext(
                          GetRoomSnapshotResponse.newBuilder().setSnapshot(scopedSnapshot).build());
                      responseObserver.onCompleted();
                    } catch (IllegalArgumentException ex) {
                      responseObserver.onError(
                          Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
                    }
                  }
                })
            .build()
            .start();
    this.port = server.getPort();
  }

  /** Issues an exact, idempotent hold response for this test World owner stub. */
  private synchronized AcquireInitialAdmissionBindHoldResponse issueInitialAdmissionBindHold(
      AcquireInitialAdmissionBindHoldRequest request) {
    validateInitialAdmissionBindRequest(request);
    String key = request.getTenantId() + ":" + request.getInitialAdmissionRequestId();
    AcquireInitialAdmissionBindHoldRequest existingRequest = admissionBindRequests.get(key);
    if (existingRequest != null && !existingRequest.equals(request)) {
      throw Status.ALREADY_EXISTS
          .withDescription("initial admission request identity was reused with a different tuple")
          .asRuntimeException();
    }
    InitialAdmissionBindHold hold = admissionBindHolds.get(key);
    if (hold == null) {
      hold =
          InitialAdmissionBindHold.newBuilder()
              .setHoldId(UUID.randomUUID().toString())
              .setHoldFence(UUID.randomUUID().toString())
              .setTenantId(request.getTenantId())
              .setRealmUuid(request.getRealmUuid())
              .setPlayableStateNamespaceUuid(request.getPlayableStateNamespaceUuid())
              .setPlayableStateScope(request.getPlayableStateScope())
              .setGameInstanceId(request.getGameInstanceId())
              .setVersionId(request.getVersionId())
              .setActiveLifecycleEpoch(request.getExpectedActiveLifecycleEpoch())
              .setInitialAdmissionRequestId(request.getInitialAdmissionRequestId())
              .setRequestDigest(request.getRequestDigest())
              .setExpectedNoPriorPointer(request.getExpectedNoPriorPointer())
              .setExpectedCatalogRevision(request.getExpectedCatalogRevision())
              .setStatus(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING)
              .build();
      admissionBindRequests.put(key, request);
      admissionBindHolds.put(key, hold);
    }
    return AcquireInitialAdmissionBindHoldResponse.newBuilder().setHold(hold).build();
  }

  public synchronized void resetInitialAdmissionBindHold(
      long tenantId,
      String requestId,
      String requestDigest,
      String gameInstanceId,
      String versionId,
      long activeLifecycleEpoch,
      String holdId,
      String holdFence) {
    String key = tenantId + ":" + requestId;
    AcquireInitialAdmissionBindHoldRequest request = admissionBindRequests.get(key);
    InitialAdmissionBindHold hold = admissionBindHolds.get(key);
    if (request == null || hold == null) {
      if (request == null && hold == null) {
        return;
      }
      throw new IllegalStateException(
          "test World stub has incomplete initial admission hold state");
    }
    if (!Long.toString(tenantId).equals(request.getTenantId())
        || !requestId.equals(request.getInitialAdmissionRequestId())
        || !request.getTenantId().equals(hold.getTenantId())
        || !request.getRealmUuid().equals(hold.getRealmUuid())
        || !request.getPlayableStateNamespaceUuid().equals(hold.getPlayableStateNamespaceUuid())
        || request.getPlayableStateScope() != hold.getPlayableStateScope()
        || !request.getGameInstanceId().equals(hold.getGameInstanceId())
        || !request.getVersionId().equals(hold.getVersionId())
        || request.getExpectedActiveLifecycleEpoch() != hold.getActiveLifecycleEpoch()
        || !request.getInitialAdmissionRequestId().equals(hold.getInitialAdmissionRequestId())
        || !request.getRequestDigest().equals(hold.getRequestDigest())
        || request.getExpectedNoPriorPointer() != hold.getExpectedNoPriorPointer()
        || request.getExpectedCatalogRevision() != hold.getExpectedCatalogRevision()
        || hold.getStatus()
            != InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING
        || (requestDigest != null && !requestDigest.equals(request.getRequestDigest()))
        || (gameInstanceId != null && !gameInstanceId.equals(request.getGameInstanceId()))
        || (versionId != null && !versionId.equals(request.getVersionId()))
        || (activeLifecycleEpoch > 0L
            && activeLifecycleEpoch != request.getExpectedActiveLifecycleEpoch())
        || (holdId != null && !holdId.equals(hold.getHoldId()))
        || (holdFence != null && !holdFence.equals(hold.getHoldFence()))) {
      throw new IllegalStateException("test World stub hold does not match the owned reset tuple");
    }
    admissionBindRequests.remove(key);
    admissionBindHolds.remove(key);
  }

  private static void validateInitialAdmissionBindRequest(
      AcquireInitialAdmissionBindHoldRequest request) {
    if (request.getTenantId().isBlank()
        || request.getGameInstanceId().isBlank()
        || request.getVersionId().isBlank()
        || request.getInitialAdmissionRequestId().isBlank()
        || request.getInitialAdmissionRequestId().length() > 128
        || !SHA_256.matcher(request.getRequestDigest()).matches()
        || !isCanonicalUuid(request.getRealmUuid())
        || !isCanonicalUuid(request.getPlayableStateNamespaceUuid())
        || request.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !request.getExpectedNoPriorPointer()
        || request.getExpectedCatalogRevision() <= 0L
        || request.getExpectedActiveLifecycleEpoch() <= 0L
        || !isPositiveLong(request.getTenantId())
        || !isPositiveLong(request.getGameInstanceId())
        || !isPositiveLong(request.getVersionId())) {
      throw Status.INVALID_ARGUMENT
          .withDescription("initial admission hold tuple is incomplete or unsupported")
          .asRuntimeException();
    }
  }

  private static boolean isPositiveLong(String value) {
    try {
      return Long.parseLong(value) > 0L && Long.toString(Long.parseLong(value)).equals(value);
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public void triggerNotFound(String description) {
    nextFailure.set(Status.NOT_FOUND.withDescription(description).asRuntimeException());
  }

  public void resetFailures() {
    nextFailure.set(null);
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
