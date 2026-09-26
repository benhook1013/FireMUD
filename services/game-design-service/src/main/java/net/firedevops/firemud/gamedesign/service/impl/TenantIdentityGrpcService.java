package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

/** Authenticated read of Game Design's own retained tenant identity provenance. */
@GrpcService
public class TenantIdentityGrpcService
    extends TenantIdentityServiceGrpc.TenantIdentityServiceImplBase {
  private final GameRepository gameRepository;
  private final String workloadNamespace;

  public TenantIdentityGrpcService(
      GameRepository gameRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.gameRepository = gameRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveLegacyGameTenantIdentity(
      ResolveLegacyGameTenantIdentityRequest request,
      StreamObserver<ResolveLegacyGameTenantIdentityResponse> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || workloadNamespace == null
        || workloadNamespace.isBlank()
        || !peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service")) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Account workload identity is required")
              .asRuntimeException());
      return;
    }

    String sourceKey = request.getLegacyGameTenantId();
    if (sourceKey.isBlank() || sourceKey.length() > 36) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Exact legacy Game Design tenant key is required")
              .asRuntimeException());
      return;
    }

    Optional<GameTenantIdentity> resolved;
    try {
      resolved = gameRepository.findTenantIdentityByLegacyTenantId(sourceKey);
    } catch (IllegalStateException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity provenance is invalid")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No Game Design tenant identity for exact source key")
              .asRuntimeException());
      return;
    }
    GameTenantIdentity identity = resolved.orElseThrow();
    if (identity.canonicalTenantId() == null
        || identity.sourceGameId() == null
        || identity.sourceGameId() <= 0
        || identity.provenanceKind() == null
        || !sourceKey.equals(identity.sourceLegacyTenantId())) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity provenance is incomplete")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(
        ResolveLegacyGameTenantIdentityResponse.newBuilder()
            .setCanonicalTenantId(identity.canonicalTenantId().toString())
            .setSourceLegacyGameTenantId(identity.sourceLegacyTenantId())
            .setSourceGameRowId(identity.sourceGameId())
            .setProvenanceKind(identity.provenanceKind().name())
            .build());
    responseObserver.onCompleted();
  }
}
