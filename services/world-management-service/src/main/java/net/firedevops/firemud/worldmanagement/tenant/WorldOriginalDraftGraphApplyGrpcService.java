package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalDraftGraphApplyServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Unregistered Game Design-only original graph apply/recovery construction adapter. */
public final class WorldOriginalDraftGraphApplyGrpcService
    extends WorldOriginalDraftGraphApplyServiceGrpc.WorldOriginalDraftGraphApplyServiceImplBase {
  private static final int MAX_DIAGNOSTIC_CAUSES = 8;
  private static final int MAX_DIAGNOSTIC_FRAMES_PER_CAUSE = 8;
  private static final Logger LOGGER =
      LoggerFactory.getLogger(WorldOriginalDraftGraphApplyGrpcService.class);

  private final WorldOriginalDraftGraphApplicationService applications;
  private final String namespace;

  public WorldOriginalDraftGraphApplyGrpcService(
      WorldOriginalDraftGraphApplicationService applications, String namespace) {
    this.applications = Objects.requireNonNull(applications, "applications");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical World namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void applyOriginalDraftGraph(
      ApplyOriginalDraftGraphRequest wire,
      StreamObserver<ApplyOriginalDraftGraphResponse> observer) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      deny(
          observer,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Game Design workload required");
      return;
    }
    try {
      var request = WorldOriginalDraftGraphApplyGrpcCodec.fromRequest(wire);
      if (!namespace.equals(request.targetNamespace())) {
        deny(observer, Status.PERMISSION_DENIED, "World original apply namespace mismatch");
        return;
      }
      var result = applications.apply(namespace, request.originalAccountBinding());
      var response =
          WorldOriginalDraftGraphApplyGrpcCodec.toResponse(request, result.ownerReadback());
      observer.onNext(response);
      observer.onCompleted();
    } catch (IllegalArgumentException invalid) {
      deny(observer, Status.INVALID_ARGUMENT, "Canonical original World apply evidence required");
    } catch (SecurityException denied) {
      deny(observer, Status.PERMISSION_DENIED, "World original apply authorization denied");
    } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
      deny(
          observer,
          Status.FAILED_PRECONDITION,
          "World original apply conflicts with retained evidence");
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      deny(observer, Status.UNAVAILABLE, "World original apply storage unavailable");
    } catch (RuntimeException failure) {
      LOGGER.error(
          "World original graph application failed to establish committed evidence; "
              + "failureType={} trace={}",
          failure.getClass().getName(),
          diagnosticTrace(failure));
      deny(
          observer,
          Status.FAILED_PRECONDITION,
          "World original apply could not establish committed evidence");
    }
  }

  private static void deny(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }

  private static String diagnosticTrace(Throwable failure) {
    var trace = new StringBuilder();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    int causeCount = 0;
    while (current != null && causeCount < MAX_DIAGNOSTIC_CAUSES && seen.add(current)) {
      if (causeCount > 0) trace.append(" <- cause ");
      trace.append(current.getClass().getName()).append(" at [");
      StackTraceElement[] frames = current.getStackTrace();
      int frameCount = Math.min(frames.length, MAX_DIAGNOSTIC_FRAMES_PER_CAUSE);
      for (int index = 0; index < frameCount; index++) {
        if (index > 0) trace.append(", ");
        StackTraceElement frame = frames[index];
        trace
            .append(frame.getClassName())
            .append('.')
            .append(frame.getMethodName())
            .append('(')
            .append(frame.getFileName() == null ? "unknown" : frame.getFileName())
            .append(':')
            .append(frame.getLineNumber())
            .append(')');
      }
      trace.append(']');
      causeCount++;
      current = current.getCause();
    }
    if (current != null) trace.append(" <- cause chain truncated");
    return trace.toString();
  }
}
