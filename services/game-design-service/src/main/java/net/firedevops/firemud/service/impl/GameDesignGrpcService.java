package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.dto.VersionDto;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishVersionResponse;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionRequest;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionResponse;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.RevisionService;
import net.firedevops.firemud.service.VersionService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
@RequiredArgsConstructor
public class GameDesignGrpcService extends GameDesignServiceGrpc.GameDesignServiceImplBase {
  private final PingService pingService;
  private final RevisionService revisionService;
  private final VersionService versionService;

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    responseObserver.onNext(PingResponse.newBuilder().setMessage(msg).build());
    responseObserver.onCompleted();
  }

  @Override
  public void saveRevision(
      SaveRevisionRequest request, StreamObserver<SaveRevisionResponse> responseObserver) {
    try {
      RevisionDto dto =
          new RevisionDto(
              null,
              null,
              request.getGameId(),
              request.getAuthorAccountId(),
              request.getData(),
              null);
      RevisionDto saved = revisionService.saveRevision(dto);
      responseObserver.onNext(SaveRevisionResponse.newBuilder().setRevisionId(saved.id()).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
    }
  }

  @Override
  public void publishVersion(
      PublishVersionRequest request, StreamObserver<PublishVersionResponse> responseObserver) {
    try {
      VersionDto version = versionService.publishVersion(request.getGameId(), request.getNotes());
      responseObserver.onNext(
          PublishVersionResponse.newBuilder().setVersionId(version.id()).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
    }
  }

  @Override
  public void publishScriptPatchVersion(
      PublishScriptPatchVersionRequest request,
      StreamObserver<PublishScriptPatchVersionResponse> responseObserver) {
    try {
      VersionDto version =
          versionService.publishScriptPatchVersion(
              request.getGameId(),
              request.getBaseVersionId(),
              request.getScriptPatchVersion(),
              request.getNotes());
      responseObserver.onNext(
          PublishScriptPatchVersionResponse.newBuilder().setVersionId(version.id()).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
    }
  }

  @Override
  public void listVersions(
      ListVersionsRequest request, StreamObserver<ListVersionsResponse> responseObserver) {
    try {
      var versions = versionService.listVersions(request.getGameId());
      ListVersionsResponse.Builder builder = ListVersionsResponse.newBuilder();
      versions.forEach(
          v ->
              builder.addVersions(
                  net.firedevops.firemud.gamedesign.v1.Version.newBuilder()
                      .setId(v.id())
                      .setVersionNumber(v.versionNumber())
                      .setScriptPatchVersion(
                          v.scriptPatchVersion() == null ? "" : v.scriptPatchVersion())
                      .setIsScriptOnly(v.scriptOnly())
                      .setNotes(v.notes() == null ? "" : v.notes())
                      .build()));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
    }
  }
}
