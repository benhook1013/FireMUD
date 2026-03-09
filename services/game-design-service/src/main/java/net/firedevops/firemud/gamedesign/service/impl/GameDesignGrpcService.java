package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
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
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
@RequiredArgsConstructor
public class GameDesignGrpcService extends GameDesignServiceGrpc.GameDesignServiceImplBase {
  private final PingService pingService;
  private final RevisionService revisionService;
  private final VersionService versionService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is injected and not exposed")
  private final MeterRegistry meterRegistry;

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  @Override
  @Timed(value = "gamedesignGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    responseObserver.onNext(PingResponse.newBuilder().setMessage(msg).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.saveRevision")
  public void saveRevision(
      SaveRevisionRequest request, StreamObserver<SaveRevisionResponse> responseObserver) {
    SaveRevisionResponse.Builder builder = SaveRevisionResponse.newBuilder();
    try {
      RevisionDto dto =
          new RevisionDto(
              null, request.getTenantId(), request.getAuthorAccountId(), request.getData(), null);
      RevisionDto saved = revisionService.saveRevision(dto);
      builder.setRevisionId(saved.id());
    } catch (IllegalArgumentException ex) {
      builder.setError(error("INVALID_ARGUMENT", ex.getMessage()));
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
      return;
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.publishVersion")
  public void publishVersion(
      PublishVersionRequest request, StreamObserver<PublishVersionResponse> responseObserver) {
    PublishVersionResponse.Builder builder = PublishVersionResponse.newBuilder();
    try {
      VersionDto version = versionService.publishVersion(request.getTenantId(), request.getNotes());
      builder.setVersionId(version.id());
    } catch (IllegalArgumentException ex) {
      builder.setError(error("INVALID_ARGUMENT", ex.getMessage()));
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
      return;
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.publishScriptPatchVersion")
  public void publishScriptPatchVersion(
      PublishScriptPatchVersionRequest request,
      StreamObserver<PublishScriptPatchVersionResponse> responseObserver) {
    PublishScriptPatchVersionResponse.Builder builder =
        PublishScriptPatchVersionResponse.newBuilder();
    try {
      VersionDto version =
          versionService.publishScriptPatchVersion(
              request.getTenantId(),
              request.getBaseVersionId(),
              request.getScriptPatchVersion(),
              request.getNotes());
      builder.setVersionId(version.id());
    } catch (IllegalArgumentException ex) {
      builder.setError(error("INVALID_ARGUMENT", ex.getMessage()));
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed").withCause(ex).asRuntimeException());
      return;
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.listVersions")
  public void listVersions(
      ListVersionsRequest request, StreamObserver<ListVersionsResponse> responseObserver) {
    try {
      var versions = versionService.listVersions(request.getTenantId());
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
