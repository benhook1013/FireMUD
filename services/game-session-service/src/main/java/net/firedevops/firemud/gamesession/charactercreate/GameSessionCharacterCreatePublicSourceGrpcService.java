package net.firedevops.firemud.gamesession.charactercreate;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;

/**
 * Unregistered purpose-specific certificate boundary. Closed request bounds are checked before
 * owner work; full owner proofs are bounded response messages.
 */
public final class GameSessionCharacterCreatePublicSourceGrpcService
    extends CharacterCreatePublicSourceServiceGrpc.CharacterCreatePublicSourceServiceImplBase {
  private final GameSessionCharacterCreatePublicSourceService source;
  private final String namespace;

  public GameSessionCharacterCreatePublicSourceGrpcService(
      GameSessionCharacterCreatePublicSourceService source, String namespace) {
    this.source = Objects.requireNonNull(source);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed GS namespace required");
    this.namespace = namespace;
  }

  @Override
  public void resolveCharacterCreatePublicTarget(
      ResolveCharacterCreatePublicTargetRequest request,
      StreamObserver<ResolveCharacterCreatePublicTargetResponse> observer) {
    try {
      requirePeer("account-service");
      GameSessionCharacterCreatePublicSourceService.outsideSql();
      GameSessionCharacterCreatePublicSourceService.requireResolveRequest(request, namespace);
      var result = source.resolve(request);
      if (result.getSerializedSize()
              > GameSessionCharacterCreatePublicSourceService.MAX_TARGET_RESPONSE_BYTES
          || !result.getRequest().equals(request)
          || result.getSchemaVersion() != 1) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Exact bounded public target response required")
            .asRuntimeException();
      }
      observer.onNext(result);
      observer.onCompleted();
    } catch (StatusRuntimeException status) {
      observer.onError(status);
    } catch (IllegalArgumentException invalid) {
      observer.onError(GameSessionCharacterCreatePublicSourceService.invalid());
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException storage) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Public target owner storage unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact public creation target unavailable")
              .asRuntimeException());
    }
  }

  @Override
  public void readCharacterCreatePublicSource(
      ReadCharacterCreatePublicSourceRequest request,
      StreamObserver<ReadCharacterCreatePublicSourceResponse> observer) {
    try {
      requirePeer("account-service");
      GameSessionCharacterCreatePublicSourceService.outsideSql();
      GameSessionCharacterCreatePublicSourceService.requireRequest(request, namespace);
      var result = source.read(request);
      if (result.getSerializedSize()
              > GameSessionCharacterCreatePublicSourceService.MAX_RESPONSE_BYTES
          || !result.getRequest().equals(request)
          || result.getSchemaVersion() != 1) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Exact bounded source response required")
            .asRuntimeException();
      }
      observer.onNext(result);
      observer.onCompleted();
    } catch (StatusRuntimeException status) {
      observer.onError(status);
    } catch (IllegalArgumentException invalid) {
      observer.onError(GameSessionCharacterCreatePublicSourceService.invalid());
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException storage) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Creation source owner storage unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact creation source unavailable")
              .asRuntimeException());
    }
  }

  @Override
  public void readPlayerCreatedRosterSource(
      ReadPlayerCreatedRosterSourceRequest request,
      StreamObserver<ReadPlayerCreatedRosterSourceResponse> observer) {
    try {
      requirePeer("entity-management-service");
      GameSessionCharacterCreatePublicSourceService.outsideSql();
      GameSessionCharacterCreatePublicSourceService.requireRosterRequest(request, namespace);
      var result = source.readPlayerCreatedRosterSource(request);
      if (result.getSerializedSize()
              > GameSessionCharacterCreatePublicSourceService.MAX_RESPONSE_BYTES
          || !result.getUnknownFields().asMap().isEmpty()
          || !result.getRequest().equals(request)
          || result.getSchemaVersion() != 1) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Exact bounded roster source response required")
            .asRuntimeException();
      }
      observer.onNext(result);
      observer.onCompleted();
    } catch (StatusRuntimeException status) {
      observer.onError(status);
    } catch (IllegalArgumentException invalid) {
      observer.onError(GameSessionCharacterCreatePublicSourceService.invalid());
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException storage) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Roster source owner storage unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact PLAYER_CREATED roster source unavailable")
              .asRuntimeException());
    }
  }

  private void requirePeer(String service) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null)
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload mTLS identity required")
          .asRuntimeException();
    if (!peer.isService(service)
        || !peer.isInNamespace(namespace)
        || !peer.uri().equals("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "account-service".equals(service)
                  ? "Exact same-namespace Account workload required"
                  : "Exact same-namespace Entity Management workload required")
          .asRuntimeException();
    }
  }
}
