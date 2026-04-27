package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.settings.GameDesignSettingsProtoMapper;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.AppliedWorldDesignMutation;
import net.firedevops.firemud.gamedesign.v1.ApproveTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.ApproveTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.BeginPurgeVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.BeginPurgeVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.CanDeleteVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.CanDeleteVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.CompareAndSetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.CompareAndSetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.CreateTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.CreateTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.DeleteSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.DeleteSettingsDomainOverrideResponse;
import net.firedevops.firemud.gamedesign.v1.FinalizePurgeVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.FinalizePurgeVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestRequest;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesRequest;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesResponse;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetPurgeStatusRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetPurgeStatusResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusEventsRequest;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusEventsResponse;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusesResponse;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import net.firedevops.firemud.gamedesign.v1.PluginComponentPolicyDecision;
import net.firedevops.firemud.gamedesign.v1.PluginVersionStatusEventEntry;
import net.firedevops.firemud.gamedesign.v1.PublishPluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamedesign.v1.PutSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.PutSettingsDomainOverrideResponse;
import net.firedevops.firemud.gamedesign.v1.RepairPublishedVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.RepairPublishedVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.RevokePluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.RevokePluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionRequest;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionResponse;
import net.firedevops.firemud.gamedesign.v1.TemplateRemapEntry;
import net.firedevops.firemud.gamedesign.v1.TemplateRemapSet;
import net.firedevops.firemud.gamedesign.v1.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.UploadPluginBundleRequest;
import net.firedevops.firemud.gamedesign.v1.UploadPluginBundleResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class GameDesignGrpcService extends GameDesignServiceGrpc.GameDesignServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(GameDesignGrpcService.class);
  private final PingService pingService;
  private final RevisionService revisionService;
  private final VersionService versionService;
  private final LaunchDescriptorService launchDescriptorService;
  private final TemplateRemapSetService templateRemapSetService;
  private final VersionAssetArtifactService versionAssetArtifactService;
  private final SettingsAuthorityService settingsAuthorityService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is injected and not exposed")
  private final MeterRegistry meterRegistry;

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
      AdminRoleGuard.requireAdminRole();
      RevisionDto dto =
          new RevisionDto(
              null,
              request.getTenantId(),
              request.getVersionId(),
              request.getAuthorAccountId(),
              request.getData(),
              request.getRevisionKind().isBlank() ? "GENERIC" : request.getRevisionKind(),
              request.hasWorldDesignMutation()
                  ? request.getWorldDesignMutation().getLogicalRevisionId()
                  : null,
              request.hasWorldDesignMutation() ? toDto(request.getWorldDesignMutation()) : null,
              null,
              null);
      RevisionDto saved = revisionService.saveRevision(dto);
      builder.setRevisionId(saved.id());
      if (saved.appliedWorldDesignMutation() != null) {
        builder.setAppliedWorldDesignMutation(
            AppliedWorldDesignMutation.newBuilder()
                .setResult(
                    toProtoWorldDesignMutationResult(saved.appliedWorldDesignMutation().result()))
                .setAggregateId(saved.appliedWorldDesignMutation().aggregateId())
                .setDraftRevisionEpoch(saved.appliedWorldDesignMutation().draftRevisionEpoch())
                .setDraftScopeRevisionEpoch(
                    saved.appliedWorldDesignMutation().draftScopeRevisionEpoch() == null
                        ? 0L
                        : saved.appliedWorldDesignMutation().draftScopeRevisionEpoch())
                .build());
      }
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "SaveRevision", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "SaveRevision",
              saveRevisionErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "SaveRevision", ex));
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
      AdminRoleGuard.requireAdminRole();
      VersionDto version = versionService.publishVersion(request.getTenantId(), request.getNotes());
      builder.setVersionId(version.id());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PublishVersion", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PublishVersion", "INVALID_ARGUMENT", ex.getMessage()));
    } catch (PublishGateFailureException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PublishVersion", ex.failureCode().name(), ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "PublishVersion", ex));
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
      AdminRoleGuard.requireAdminRole();
      VersionDto version =
          versionService.publishScriptPatchVersion(
              request.getTenantId(),
              request.getBaseVersionId(),
              request.getScriptPatchVersion(),
              request.getNotes());
      builder.setVersionId(version.id());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PublishScriptPatchVersion",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PublishScriptPatchVersion",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (PublishGateFailureException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PublishScriptPatchVersion",
              ex.failureCode().name(),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "PublishScriptPatchVersion", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getPublishedScriptPatchVersion")
  public void getPublishedScriptPatchVersion(
      GetPublishedScriptPatchVersionRequest request,
      StreamObserver<GetPublishedScriptPatchVersionResponse> responseObserver) {
    GetPublishedScriptPatchVersionResponse.Builder builder =
        GetPublishedScriptPatchVersionResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      VersionDto version =
          versionService.getPublishedScriptPatchVersion(
              request.getTenantId(), request.getScriptPatchVersion());
      DesignControlPlaneDigestDto digest =
          versionService.getDesignControlPlaneDigestForScriptPatch(
              request.getTenantId(), request.getScriptPatchVersion());
      builder.setScriptPatch(toProtoPublishedScriptPatch(version, digest));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedScriptPatchVersion",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedScriptPatchVersion",
              scriptPatchPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetPublishedScriptPatchVersion", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.uploadPluginBundle")
  public void uploadPluginBundle(
      UploadPluginBundleRequest request,
      StreamObserver<UploadPluginBundleResponse> responseObserver) {
    UploadPluginBundleResponse.Builder builder = UploadPluginBundleResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      PublishedPluginVersionDto publication =
          versionService.uploadPluginBundle(
              request.getTenantId(), request.getBundleBytes().toByteArray(), request.getNotes());
      builder.setPublicationId(publication.id());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "UploadPluginBundle", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "UploadPluginBundle",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "UploadPluginBundle", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.publishPluginVersion")
  public void publishPluginVersion(
      PublishPluginVersionRequest request,
      StreamObserver<PublishPluginVersionResponse> responseObserver) {
    PublishPluginVersionResponse.Builder builder = PublishPluginVersionResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      PublishedPluginVersionDto publication =
          versionService.publishPluginVersion(
              request.getTenantId(),
              request.getPluginId(),
              request.getPluginVersionId(),
              request.getBaseVersionId(),
              request.getAbilitySchemaDigest(),
              request.getBundleDigest(),
              request.getManifestSchemaVersion(),
              request.getDistributionManifestHash(),
              request.getDistributionManifestPath(),
              request.getSignerKeyId(),
              request.getSignerRevoked(),
              toComponentPolicyDecision(request.getComponentPolicyDecision()),
              request.getNotes());
      builder.setPublicationId(publication.id());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PublishPluginVersion", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PublishPluginVersion",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "PublishPluginVersion", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getPublishedPluginVersion")
  public void getPublishedPluginVersion(
      GetPublishedPluginVersionRequest request,
      StreamObserver<GetPublishedPluginVersionResponse> responseObserver) {
    GetPublishedPluginVersionResponse.Builder builder =
        GetPublishedPluginVersionResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      PublishedPluginVersionDto publication =
          versionService.getPublishedPluginVersion(
              request.getTenantId(), request.getPluginId(), request.getPluginVersionId());
      builder.setPluginVersion(toProtoPublishedPluginVersion(publication));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedPluginVersion",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedPluginVersion",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetPublishedPluginVersion", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.revokePluginVersion")
  public void revokePluginVersion(
      RevokePluginVersionRequest request,
      StreamObserver<RevokePluginVersionResponse> responseObserver) {
    RevokePluginVersionResponse.Builder builder = RevokePluginVersionResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      PublishedPluginVersionDto publication =
          versionService.revokePluginVersion(
              request.getTenantId(),
              request.getPluginId(),
              request.getPluginVersionId(),
              request.getReason());
      builder.setPublicationId(publication.id());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "RevokePluginVersion", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "RevokePluginVersion",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "RevokePluginVersion", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.listPluginVersionStatuses")
  public void listPluginVersionStatuses(
      ListPluginVersionStatusesRequest request,
      StreamObserver<ListPluginVersionStatusesResponse> responseObserver) {
    ListPluginVersionStatusesResponse.Builder builder =
        ListPluginVersionStatusesResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      versionService
          .listPublishedPluginVersions(
              request.getTenantId(),
              request.getPluginId(),
              toModelVersionLifecycleState(request.getPublicationState()),
              toLocalDateTime(request.getChangedAfterMs()),
              toLocalDateTime(request.getChangedBeforeMs()),
              request.getLimit())
          .forEach(
              publication -> builder.addPluginVersions(toProtoPublishedPluginVersion(publication)));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ListPluginVersionStatuses",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ListPluginVersionStatuses",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ListPluginVersionStatuses", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.listPluginVersionStatusEvents")
  public void listPluginVersionStatusEvents(
      ListPluginVersionStatusEventsRequest request,
      StreamObserver<ListPluginVersionStatusEventsResponse> responseObserver) {
    ListPluginVersionStatusEventsResponse.Builder builder =
        ListPluginVersionStatusEventsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      versionService
          .listPluginVersionStatusEvents(
              request.getTenantId(),
              request.getPluginId(),
              request.getPluginVersionId(),
              toModelVersionLifecycleState(request.getPublicationState()),
              toLocalDateTime(request.getChangedAfterMs()),
              toLocalDateTime(request.getChangedBeforeMs()),
              request.getLimit())
          .forEach(event -> builder.addEvents(toProtoPluginVersionStatusEvent(event)));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ListPluginVersionStatusEvents",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ListPluginVersionStatusEvents",
              pluginPublicationErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ListPluginVersionStatusEvents", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.listVersions")
  public void listVersions(
      ListVersionsRequest request, StreamObserver<ListVersionsResponse> responseObserver) {
    ListVersionsResponse.Builder builder = ListVersionsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      var versions = versionService.listVersions(request.getTenantId());
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
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "ListVersions", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "ListVersions", "INVALID_ARGUMENT", ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "ListVersions", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getDesignControlPlaneDigest")
  public void getDesignControlPlaneDigest(
      GetDesignControlPlaneDigestRequest request,
      StreamObserver<GetDesignControlPlaneDigestResponse> responseObserver) {
    GetDesignControlPlaneDigestResponse.Builder builder =
        GetDesignControlPlaneDigestResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      var digest =
          request.getScopeCase() == GetDesignControlPlaneDigestRequest.ScopeCase.VERSION_ID
              ? versionService.getDesignControlPlaneDigest(
                  request.getTenantId(), request.getVersionId())
              : versionService.getDesignControlPlaneDigestForScriptPatch(
                  request.getTenantId(), request.getScriptPatchVersion());
      builder.setDigest(
          net.firedevops.firemud.gamedesign.v1.DesignControlPlaneDigest.newBuilder()
              .setTenantId(digest.tenantId())
              .setScopeValue(digest.scopeValue())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
              .build());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetDesignControlPlaneDigest",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetDesignControlPlaneDigest",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetDesignControlPlaneDigest", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getPublishedReleaseBundle")
  public void getPublishedReleaseBundle(
      GetPublishedReleaseBundleRequest request,
      StreamObserver<GetPublishedReleaseBundleResponse> responseObserver) {
    GetPublishedReleaseBundleResponse.Builder builder =
        GetPublishedReleaseBundleResponse.newBuilder();
    try {
      requireLaunchAttestationReadAccess();
      PublishedReleaseBundleDto bundle =
          versionService.getPublishedReleaseBundle(request.getTenantId(), request.getVersionId());
      PublishedReleaseBundleContract.requireSupportedSchemaForRead(bundle);
      builder.setBundle(
          net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
              .setId(bundle.id())
              .setVersionId(bundle.versionId())
              .setVersionNumber(bundle.versionNumber())
              .setAttestationSchemaVersion(bundle.attestationSchemaVersion())
              .setPublishWorkflowId(bundle.publishWorkflowId())
              .setManifestHash(bundle.manifestHash())
              .addAllRequiredManifestAssetKeys(bundle.requiredManifestAssetKeys())
              .addAllParticipantDigests(
                  bundle.participantDigests().stream()
                      .map(
                          digest ->
                              net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
                                  .setParticipantKey(digest.participantKey())
                                  .setScopeValue(digest.scopeValue())
                                  .setAppliedCommitId(
                                      digest.appliedCommitId() == null
                                          ? ""
                                          : digest.appliedCommitId())
                                  .setContentDigest(
                                      digest.contentDigest() == null ? "" : digest.contentDigest())
                                  .setDigestSchemaVersion(
                                      digest.digestSchemaVersion() == null
                                          ? 0
                                          : digest.digestSchemaVersion())
                                  .build())
                      .toList())
              .setGenerationConfigRevision(bundle.generationConfigRevision())
              .setIsScriptOnly(bundle.scriptOnly())
              .setScriptPatchVersion(
                  bundle.scriptPatchVersion() == null ? "" : bundle.scriptPatchVersion())
              .setPublishedAt(bundle.publishedAt().toString())
              .build());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedReleaseBundle",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (PublishedReleaseBundleNotFoundException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "GetPublishedReleaseBundle", "NOT_FOUND", ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedReleaseBundle",
              ex.getMessage().startsWith(PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED)
                  ? PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED
                  : "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetPublishedReleaseBundle",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetPublishedReleaseBundle", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getVersionState")
  public void getVersionState(
      GetVersionStateRequest request, StreamObserver<GetVersionStateResponse> responseObserver) {
    GetVersionStateResponse.Builder builder = GetVersionStateResponse.newBuilder();
    try {
      requireLaunchAttestationReadAccess();
      builder.setVersionState(
          toProtoVersionState(
              versionService.getVersionState(request.getTenantId(), request.getVersionId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "GetVersionState", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "GetVersionState", "INVALID_ARGUMENT", ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "GetVersionState", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.compareAndSetVersionState")
  public void compareAndSetVersionState(
      CompareAndSetVersionStateRequest request,
      StreamObserver<CompareAndSetVersionStateResponse> responseObserver) {
    CompareAndSetVersionStateResponse.Builder builder =
        CompareAndSetVersionStateResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      var state =
          versionService.compareAndSetVersionState(
              request.getTenantId(),
              request.getVersionId(),
              request.getExpectedVersionStateEpoch(),
              fromProtoVersionState(request.getNewState()),
              request.getReason());
      builder.setVersionState(toProtoVersionState(state)).setUpdated(true);
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CompareAndSetVersionState",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CompareAndSetVersionState",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "CompareAndSetVersionState", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.resolveLaunchDescriptor")
  public void resolveLaunchDescriptor(
      ResolveLaunchDescriptorRequest request,
      StreamObserver<ResolveLaunchDescriptorResponse> responseObserver) {
    ResolveLaunchDescriptorResponse.Builder builder = ResolveLaunchDescriptorResponse.newBuilder();
    try {
      requireLaunchAttestationReadAccess();
      var descriptor =
          launchDescriptorService.resolveLaunchDescriptor(
              request.getTenantId(),
              request.getGameTemplateId(),
              request.getControlPlaneRequestId(),
              request.hasRequestedScriptPatchVersion()
                  ? request.getRequestedScriptPatchVersion()
                  : null,
              request.hasSourceVersionId() ? request.getSourceVersionId() : null,
              request.hasTargetVersionId() ? request.getTargetVersionId() : null,
              request.hasRequestedRuntimeFlagsJson()
                  ? request.getRequestedRuntimeFlagsJson()
                  : null);
      builder.setLaunchDescriptor(
          net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
              .setLaunchDescriptorId(descriptor.launchDescriptorId())
              .setTenantId(descriptor.tenantId())
              .setGameTemplateId(descriptor.gameTemplateId())
              .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
              .setVersionId(descriptor.versionId())
              .setScriptPatchVersion(
                  descriptor.scriptPatchVersion() == null ? "" : descriptor.scriptPatchVersion())
              .setRuntimeFlagsJson(descriptor.runtimeFlagsJson())
              .setGenerationConfigRevision(descriptor.generationConfigRevision())
              .setVersionStateEpoch(descriptor.versionStateEpoch())
              .setReleaseBundleId(descriptor.releaseBundleId())
              .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
              .setRemapSetId(descriptor.remapSetId() == null ? "" : descriptor.remapSetId())
              .build());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ResolveLaunchDescriptor",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ResolveLaunchDescriptor",
              launchDescriptorErrorCode(ex.getMessage(), true),
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ResolveLaunchDescriptor",
              launchDescriptorErrorCode(ex.getMessage(), false),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ResolveLaunchDescriptor", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.createTemplateRemapSet")
  public void createTemplateRemapSet(
      CreateTemplateRemapSetRequest request,
      StreamObserver<CreateTemplateRemapSetResponse> responseObserver) {
    CreateTemplateRemapSetResponse.Builder builder = CreateTemplateRemapSetResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setRemapSet(
          toProto(
              templateRemapSetService.createTemplateRemapSet(
                  request.getTenantId(),
                  request.getSourceVersionId(),
                  request.getTargetVersionId(),
                  request.getCreatedReason(),
                  request.getRemapEntriesList().stream().map(this::toDto).toList())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CreateTemplateRemapSet",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CreateTemplateRemapSet",
              remapSetErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateTemplateRemapSet", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.approveTemplateRemapSet")
  public void approveTemplateRemapSet(
      ApproveTemplateRemapSetRequest request,
      StreamObserver<ApproveTemplateRemapSetResponse> responseObserver) {
    ApproveTemplateRemapSetResponse.Builder builder = ApproveTemplateRemapSetResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setRemapSet(
          toProto(
              templateRemapSetService.approveTemplateRemapSet(
                  request.getTenantId(), request.getRemapSetId(), request.getApprovalReason())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ApproveTemplateRemapSet",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ApproveTemplateRemapSet",
              remapSetErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ApproveTemplateRemapSet", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getTemplateRemapSet")
  public void getTemplateRemapSet(
      GetTemplateRemapSetRequest request,
      StreamObserver<GetTemplateRemapSetResponse> responseObserver) {
    GetTemplateRemapSetResponse.Builder builder = GetTemplateRemapSetResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setRemapSet(
          toProto(
              templateRemapSetService.getTemplateRemapSet(
                  request.getTenantId(), request.getRemapSetId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "GetTemplateRemapSet", "PERMISSION_DENIED", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetTemplateRemapSet",
              remapSetErrorCode(ex.getMessage()),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "GetTemplateRemapSet", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  private net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot toProtoVersionState(
      net.firedevops.firemud.gamedesign.dto.VersionStateDto state) {
    return net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
        .setTenantId(state.tenantId())
        .setVersionId(state.versionId())
        .setVersionState(toProtoVersionLifecycleState(state.versionState()))
        .setVersionStateEpoch(state.versionStateEpoch())
        .setUpdatedAt(state.updatedAt().toString())
        .build();
  }

  private PublishedScriptPatchVersion toProtoPublishedScriptPatch(
      VersionDto version, DesignControlPlaneDigestDto digest) {
    return PublishedScriptPatchVersion.newBuilder()
        .setTenantId(version.tenantId())
        .setScriptPatchVersion(version.scriptPatchVersion())
        .setVersionId(version.id())
        .setBaseVersionId(version.baseVersionId() == null ? 0L : version.baseVersionId())
        .setPublicationState(toProtoVersionLifecycleState(version.versionState()))
        .setControlPlaneDigest(digest.contentDigest())
        .setDigestSchemaVersion(digest.digestSchemaVersion())
        .setLastChangedAtMs(version.updatedAt().toInstant(ZoneOffset.UTC).toEpochMilli())
        .build();
  }

  private PublishedPluginVersion toProtoPublishedPluginVersion(PublishedPluginVersionDto plugin) {
    return PublishedPluginVersion.newBuilder()
        .setTenantId(plugin.tenantId())
        .setPluginId(plugin.pluginId())
        .setPluginVersionId(plugin.pluginVersionId())
        .setPublicationId(plugin.id())
        .setBaseVersionId(plugin.baseVersionId())
        .setPublicationState(toProtoVersionLifecycleState(plugin.publicationState()))
        .setAbilitySchemaDigest(plugin.abilitySchemaDigest())
        .setBundleDigest(plugin.bundleDigest())
        .setManifestSchemaVersion(plugin.manifestSchemaVersion())
        .setDistributionManifestHash(plugin.distributionManifestHash())
        .setDistributionManifestPath(plugin.distributionManifestPath())
        .setSignerKeyId(plugin.signerKeyId())
        .setSignerRevoked(plugin.signerRevoked())
        .setComponentPolicyDecision(
            toProtoComponentPolicyDecision(plugin.componentPolicyDecision()))
        .setStatusReason(plugin.statusReason())
        .setLastChangedAtMs(plugin.lastChangedAt().toInstant(ZoneOffset.UTC).toEpochMilli())
        .build();
  }

  private PluginVersionStatusEventEntry toProtoPluginVersionStatusEvent(
      net.firedevops.firemud.gamedesign.dto.PluginVersionStatusEventDto event) {
    return PluginVersionStatusEventEntry.newBuilder()
        .setEventId(event.eventId())
        .setTenantId(event.tenantId())
        .setPluginId(event.pluginId())
        .setPluginVersionId(event.pluginVersionId())
        .setPreviousPublicationState(toProtoVersionLifecycleState(event.previousPublicationState()))
        .setNewPublicationState(toProtoVersionLifecycleState(event.newPublicationState()))
        .setStatusReason(event.statusReason())
        .setObservedAtMs(event.observedAt().toEpochMilli())
        .build();
  }

  private VersionLifecycleState toProtoVersionLifecycleState(
      net.firedevops.firemud.gamedesign.model.VersionLifecycleState state) {
    return switch (state) {
      case DRAFT -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT;
      case PUBLISHED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED;
      case ACTIVE -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE;
      case FAILED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_FAILED;
      case RETIRED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_RETIRED;
      case UPLOAD_REJECTED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_UPLOAD_REJECTED;
      case SIGNATURE_VERIFIED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_SIGNATURE_VERIFIED;
      case VALIDATION_FAILED_DESIGN ->
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_VALIDATION_FAILED_DESIGN;
      case SUPERSEDED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_SUPERSEDED;
      case REVOKED_DESIGN -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_REVOKED_DESIGN;
    };
  }

  private net.firedevops.firemud.gamedesign.model.VersionLifecycleState
      toModelVersionLifecycleState(VersionLifecycleState state) {
    return switch (state) {
      case VERSION_LIFECYCLE_STATE_UNSPECIFIED -> null;
      case VERSION_LIFECYCLE_STATE_DRAFT ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT;
      case VERSION_LIFECYCLE_STATE_PUBLISHED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.PUBLISHED;
      case VERSION_LIFECYCLE_STATE_ACTIVE ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.ACTIVE;
      case VERSION_LIFECYCLE_STATE_FAILED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.FAILED;
      case VERSION_LIFECYCLE_STATE_RETIRED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.RETIRED;
      case VERSION_LIFECYCLE_STATE_UPLOAD_REJECTED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.UPLOAD_REJECTED;
      case VERSION_LIFECYCLE_STATE_SIGNATURE_VERIFIED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.SIGNATURE_VERIFIED;
      case VERSION_LIFECYCLE_STATE_VALIDATION_FAILED_DESIGN ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.VALIDATION_FAILED_DESIGN;
      case VERSION_LIFECYCLE_STATE_SUPERSEDED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.SUPERSEDED;
      case VERSION_LIFECYCLE_STATE_REVOKED_DESIGN ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.REVOKED_DESIGN;
      case UNRECOGNIZED ->
          throw new IllegalArgumentException("INVALID_ARGUMENT: unknown version state");
    };
  }

  private LocalDateTime toLocalDateTime(long epochMillis) {
    if (epochMillis <= 0L) {
      return null;
    }
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
  }

  private PluginComponentPolicyDecision toProtoComponentPolicyDecision(String decision) {
    return switch (decision) {
      case "ALLOWED" -> PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED;
      case "REPORT_ONLY" ->
          PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_REPORT_ONLY;
      case "BLOCKED" -> PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED;
      default -> PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_UNSPECIFIED;
    };
  }

  private String toComponentPolicyDecision(PluginComponentPolicyDecision decision) {
    return switch (decision) {
      case PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED -> "ALLOWED";
      case PLUGIN_COMPONENT_POLICY_DECISION_REPORT_ONLY -> "REPORT_ONLY";
      case PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED -> "BLOCKED";
      case PLUGIN_COMPONENT_POLICY_DECISION_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  private net.firedevops.firemud.gamedesign.model.VersionLifecycleState fromProtoVersionState(
      VersionLifecycleState state) {
    return switch (state) {
      case VERSION_LIFECYCLE_STATE_DRAFT ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT;
      case VERSION_LIFECYCLE_STATE_PUBLISHED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.PUBLISHED;
      case VERSION_LIFECYCLE_STATE_ACTIVE ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.ACTIVE;
      case VERSION_LIFECYCLE_STATE_FAILED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.FAILED;
      case VERSION_LIFECYCLE_STATE_RETIRED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.RETIRED;
      case VERSION_LIFECYCLE_STATE_UPLOAD_REJECTED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.UPLOAD_REJECTED;
      case VERSION_LIFECYCLE_STATE_SIGNATURE_VERIFIED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.SIGNATURE_VERIFIED;
      case VERSION_LIFECYCLE_STATE_VALIDATION_FAILED_DESIGN ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.VALIDATION_FAILED_DESIGN;
      case VERSION_LIFECYCLE_STATE_SUPERSEDED ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.SUPERSEDED;
      case VERSION_LIFECYCLE_STATE_REVOKED_DESIGN ->
          net.firedevops.firemud.gamedesign.model.VersionLifecycleState.REVOKED_DESIGN;
      case VERSION_LIFECYCLE_STATE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("INVALID_ARGUMENT: new version state is required");
    };
  }

  @Override
  @Timed(value = "gamedesignGrpc.getVersionAssetArtifactState")
  public void getVersionAssetArtifactState(
      GetVersionAssetArtifactStateRequest request,
      StreamObserver<GetVersionAssetArtifactStateResponse> responseObserver) {
    GetVersionAssetArtifactStateResponse.Builder builder =
        GetVersionAssetArtifactStateResponse.newBuilder();
    try {
      requireLaunchAttestationReadAccess();
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.getState(request.getTenantId(), request.getVersionId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetVersionAssetArtifactState",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetVersionAssetArtifactState",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetVersionAssetArtifactState", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.tombstoneVersionAssets")
  public void tombstoneVersionAssets(
      TombstoneVersionAssetsRequest request,
      StreamObserver<TombstoneVersionAssetsResponse> responseObserver) {
    TombstoneVersionAssetsResponse.Builder builder = TombstoneVersionAssetsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.tombstoneVersionAssets(
                  request.getTenantId(),
                  request.getVersionId(),
                  request.getExpectedArtifactStateEpoch(),
                  request.getTombstoneWorkflowId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "TombstoneVersionAssets",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "TombstoneVersionAssets",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "TombstoneVersionAssets", ex.getMessage(), ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "TombstoneVersionAssets", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.canDeleteVersionAssets")
  public void canDeleteVersionAssets(
      CanDeleteVersionAssetsRequest request,
      StreamObserver<CanDeleteVersionAssetsResponse> responseObserver) {
    CanDeleteVersionAssetsResponse.Builder builder = CanDeleteVersionAssetsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      var eligibility =
          versionAssetArtifactService.canDeleteVersionAssets(
              request.getTenantId(), request.getVersionId());
      builder.setEligibility(
          net.firedevops.firemud.gamedesign.v1.VersionAssetDeletionEligibility.newBuilder()
              .setTenantId(eligibility.tenantId())
              .setVersionId(eligibility.versionId())
              .setDeletable(eligibility.deletable())
              .setCurrentArtifactState(
                  ArtifactState.valueOf("ARTIFACT_STATE_" + eligibility.currentArtifactState()))
              .setCurrentStateEpoch(eligibility.currentStateEpoch())
              .setFailureCode(eligibility.failureCode() == null ? "" : eligibility.failureCode())
              .setFailureMessage(
                  eligibility.failureMessage() == null ? "" : eligibility.failureMessage())
              .build());
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CanDeleteVersionAssets",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CanDeleteVersionAssets",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "CanDeleteVersionAssets", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.beginPurgeVersionAssets")
  public void beginPurgeVersionAssets(
      BeginPurgeVersionAssetsRequest request,
      StreamObserver<BeginPurgeVersionAssetsResponse> responseObserver) {
    BeginPurgeVersionAssetsResponse.Builder builder = BeginPurgeVersionAssetsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setPurgeStatus(
          toProto(
              versionAssetArtifactService.beginPurgeVersionAssets(
                  request.getTenantId(),
                  request.getVersionId(),
                  request.getExpectedArtifactStateEpoch())));
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.getState(request.getTenantId(), request.getVersionId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "BeginPurgeVersionAssets",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "BeginPurgeVersionAssets",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "BeginPurgeVersionAssets", ex.getMessage(), ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "BeginPurgeVersionAssets", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.finalizePurgeVersionAssets")
  public void finalizePurgeVersionAssets(
      FinalizePurgeVersionAssetsRequest request,
      StreamObserver<FinalizePurgeVersionAssetsResponse> responseObserver) {
    FinalizePurgeVersionAssetsResponse.Builder builder =
        FinalizePurgeVersionAssetsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setPurgeStatus(
          toProto(
              versionAssetArtifactService.finalizePurgeVersionAssets(
                  request.getTenantId(),
                  request.getVersionId(),
                  request.getPurgeWorkflowId(),
                  request.getExpectedArtifactStateEpoch())));
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.getState(request.getTenantId(), request.getVersionId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "FinalizePurgeVersionAssets",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "FinalizePurgeVersionAssets",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "FinalizePurgeVersionAssets",
              ex.getMessage(),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "FinalizePurgeVersionAssets", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getVersionAssetPurgeStatus")
  public void getVersionAssetPurgeStatus(
      GetVersionAssetPurgeStatusRequest request,
      StreamObserver<GetVersionAssetPurgeStatusResponse> responseObserver) {
    GetVersionAssetPurgeStatusResponse.Builder builder =
        GetVersionAssetPurgeStatusResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setPurgeStatus(
          toProto(
              versionAssetArtifactService.getPurgeStatus(
                  request.getTenantId(), request.getVersionId(), request.getPurgeWorkflowId())));
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.getState(request.getTenantId(), request.getVersionId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetVersionAssetPurgeStatus",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetVersionAssetPurgeStatus",
              ex.getMessage().startsWith("PURGE_WORKFLOW_NOT_FOUND")
                  ? ex.getMessage()
                  : "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetVersionAssetPurgeStatus", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.repairPublishedVersionAssets")
  public void repairPublishedVersionAssets(
      RepairPublishedVersionAssetsRequest request,
      StreamObserver<RepairPublishedVersionAssetsResponse> responseObserver) {
    RepairPublishedVersionAssetsResponse.Builder builder =
        RepairPublishedVersionAssetsResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      builder.setArtifactState(
          toProto(
              versionAssetArtifactService.repairPublishedVersionAssets(
                  request.getTenantId(),
                  request.getVersionId(),
                  request.getExpectedArtifactStateEpoch(),
                  request.getRepairWorkflowId())));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "RepairPublishedVersionAssets",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "RepairPublishedVersionAssets",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (IllegalStateException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "RepairPublishedVersionAssets",
              ex.getMessage(),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "RepairPublishedVersionAssets", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.getScopedSettingsOverrides")
  public void getScopedSettingsOverrides(
      GetScopedSettingsOverridesRequest request,
      StreamObserver<GetScopedSettingsOverridesResponse> responseObserver) {
    GetScopedSettingsOverridesResponse.Builder builder =
        GetScopedSettingsOverridesResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      var snapshot =
          settingsAuthorityService.getScopedOverrides(
              request.getTenantId(),
              request.hasGameInstanceId() ? request.getGameInstanceId() : null);
      if (!snapshot.tenantOverrides().isEmpty()) {
        builder.setTenantOverrides(
            GameDesignSettingsProtoMapper.toProto(snapshot.tenantOverrides()));
      }
      if (!snapshot.gameInstanceOverrides().isEmpty()) {
        builder.setGameInstanceOverrides(
            GameDesignSettingsProtoMapper.toProto(snapshot.gameInstanceOverrides()));
      }
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetScopedSettingsOverrides",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetScopedSettingsOverrides",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetScopedSettingsOverrides", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.putSettingsDomainOverride")
  public void putSettingsDomainOverride(
      PutSettingsDomainOverrideRequest request,
      StreamObserver<PutSettingsDomainOverrideResponse> responseObserver) {
    PutSettingsDomainOverrideResponse.Builder builder =
        PutSettingsDomainOverrideResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      settingsAuthorityService.putDomainOverride(
          request.getTenantId(),
          request.hasGameInstanceId() ? request.getGameInstanceId() : null,
          GameDesignSettingsProtoMapper.fromProto(request.getDomain()),
          GameDesignSettingsProtoMapper.fromProto(request.getOverrides()));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PutSettingsDomainOverride",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "PutSettingsDomainOverride",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "PutSettingsDomainOverride", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamedesignGrpc.deleteSettingsDomainOverride")
  public void deleteSettingsDomainOverride(
      DeleteSettingsDomainOverrideRequest request,
      StreamObserver<DeleteSettingsDomainOverrideResponse> responseObserver) {
    DeleteSettingsDomainOverrideResponse.Builder builder =
        DeleteSettingsDomainOverrideResponse.newBuilder();
    try {
      AdminRoleGuard.requireAdminRole();
      settingsAuthorityService.deleteDomainOverride(
          request.getTenantId(),
          request.hasGameInstanceId() ? request.getGameInstanceId() : null,
          GameDesignSettingsProtoMapper.fromProto(request.getDomain()));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "DeleteSettingsDomainOverride",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "DeleteSettingsDomainOverride",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "DeleteSettingsDomainOverride", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  private net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState toProto(
      net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto state) {
    return net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
        .setTenantId(state.tenantId())
        .setVersionId(state.versionId())
        .setExportedVersionNumber(state.exportedVersionNumber())
        .setArtifactState(ArtifactState.valueOf("ARTIFACT_STATE_" + state.artifactState()))
        .setStateEpoch(state.stateEpoch())
        .setManifestHash(state.manifestHash() == null ? "" : state.manifestHash())
        .setLastWorkflowId(state.lastWorkflowId() == null ? "" : state.lastWorkflowId())
        .setLastErrorCode(state.lastErrorCode() == null ? "" : state.lastErrorCode())
        .setLastErrorMessage(state.lastErrorMessage() == null ? "" : state.lastErrorMessage())
        .setUpdatedAt(state.updatedAt().toString())
        .addAllExportedManifestAssetKeys(state.exportedManifestAssetKeys())
        .build();
  }

  private net.firedevops.firemud.gamedesign.v1.VersionAssetPurgeWorkflowStatus toProto(
      net.firedevops.firemud.gamedesign.dto.VersionAssetPurgeWorkflowStatusDto workflow) {
    return net.firedevops.firemud.gamedesign.v1.VersionAssetPurgeWorkflowStatus.newBuilder()
        .setTenantId(workflow.tenantId())
        .setVersionId(workflow.versionId())
        .setPurgeWorkflowId(workflow.purgeWorkflowId())
        .setWorkflowStatus(workflow.workflowStatus())
        .setStartedFromStateEpoch(workflow.startedFromStateEpoch())
        .setRequestedAt(workflow.requestedAt().toString())
        .setUpdatedAt(workflow.updatedAt().toString())
        .setCompletedAt(workflow.completedAt() == null ? "" : workflow.completedAt().toString())
        .setLastErrorCode(workflow.lastErrorCode() == null ? "" : workflow.lastErrorCode())
        .setLastErrorMessage(workflow.lastErrorMessage() == null ? "" : workflow.lastErrorMessage())
        .build();
  }

  private TemplateRemapSet toProto(TemplateRemapSetDto remapSet) {
    TemplateRemapSet.Builder builder =
        TemplateRemapSet.newBuilder()
            .setRemapSetId(remapSet.remapSetId())
            .setTenantId(remapSet.tenantId())
            .setSourceVersionId(remapSet.sourceVersionId())
            .setTargetVersionId(remapSet.targetVersionId())
            .setStatus(toProtoRemapStatus(remapSet.status()))
            .setCreatedReason(remapSet.createdReason())
            .setApprovalReason(remapSet.approvalReason() == null ? "" : remapSet.approvalReason())
            .setCreatedAt(remapSet.createdAt().toString())
            .setApprovedAt(remapSet.approvedAt() == null ? "" : remapSet.approvedAt().toString());
    remapSet.remapEntries().forEach(entry -> builder.addRemapEntries(toProto(entry)));
    return builder.build();
  }

  private TemplateRemapEntry toProto(TemplateRemapEntryDto entry) {
    return TemplateRemapEntry.newBuilder()
        .setMappingDomain(entry.mappingDomain())
        .setMappingType(entry.mappingType())
        .setSourceTemplateKey(entry.sourceTemplateKey())
        .setTargetTemplateKey(entry.targetTemplateKey())
        .build();
  }

  private TemplateRemapEntryDto toDto(TemplateRemapEntry entry) {
    return new TemplateRemapEntryDto(
        entry.getMappingDomain(),
        entry.getMappingType(),
        entry.getSourceTemplateKey(),
        entry.getTargetTemplateKey());
  }

  private TemplateRemapSetStatus toProtoRemapStatus(
      net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus status) {
    return switch (status) {
      case DRAFT -> TemplateRemapSetStatus.TEMPLATE_REMAP_SET_STATUS_DRAFT;
      case APPROVED -> TemplateRemapSetStatus.TEMPLATE_REMAP_SET_STATUS_APPROVED;
    };
  }

  private net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto toDto(
      WorldDesignMutationRevision request) {
    return new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto(
        request.getLogicalRevisionId(),
        request.getCommitId(),
        request.getOperation().name(),
        request.getAggregateType().name(),
        request.getAggregateId(),
        request.getExpectedDraftRevisionEpoch(),
        request.getScopeType()
                == net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType
                    .WORLD_DESIGN_SCOPE_TYPE_UNSPECIFIED
            ? ""
            : request.getScopeType().name(),
        request.getScopeId(),
        request.getExpectedDraftScopeRevisionEpoch(),
        request.getScopeMutationPolicy()
                == net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy
                    .WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED
            ? ""
            : request.getScopeMutationPolicy().name(),
        request.hasRegion()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .RegionMutationDto(
                request.getRegion().getName(),
                request.getRegion().getWeather(),
                request.getRegion().getShardId(),
                request.getRegion().getGenerationSeed(),
                request.getRegion().getGeneratorType(),
                request.getRegion().getGeneratorParams(),
                request.getRegion().getSpacingMultiplier())
            : null,
        request.hasZone()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .ZoneMutationDto(request.getZone().getName(), request.getZone().getRegionId())
            : null,
        request.hasRoom()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .RoomMutationDto(
                request.getRoom().getName(),
                request.getRoom().getDescription(),
                request.getRoom().getZoneId(),
                request.getRoom().getNameLocalizedVariantsJson(),
                request.getRoom().getDescriptionLocalizedVariantsJson())
            : null,
        request.hasRoomExit()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .RoomExitMutationDto(
                request.getRoomExit().getFromRoomId(),
                request.getRoomExit().getToRoomId(),
                request.getRoomExit().getDirection(),
                request.getRoomExit().getCost())
            : null,
        request.hasGenerationRule()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .GenerationRuleMutationDto(
                request.getGenerationRule().getName(), request.getGenerationRule().getValue())
            : null,
        request.hasWorldEntitySpawnBinding()
            ? new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                .WorldEntitySpawnBindingMutationDto(
                request.getWorldEntitySpawnBinding().getRoomId(),
                request.getWorldEntitySpawnBinding().getEntityTemplateType().name(),
                request.getWorldEntitySpawnBinding().getEntityTemplateId(),
                request.getWorldEntitySpawnBinding().getSpawnCount(),
                request.getWorldEntitySpawnBinding().getRespawnDelaySeconds())
            : null,
        request.hasWorldGenerationSubtree() ? toDto(request.getWorldGenerationSubtree()) : null);
  }

  private net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
          .WorldGenerationSubtreeMutationDto
      toDto(
          net.firedevops.firemud.worldmanagement.v1.WorldGenerationSubtreeDesignMutation request) {
    return new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
        .WorldGenerationSubtreeMutationDto(
        request.getGenerationRulesList().stream()
            .map(
                rule ->
                    new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                        .GenerationRuleMutationDto(rule.getName(), rule.getValue()))
            .toList(),
        request.getRoomsList().stream()
            .map(
                room ->
                    new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                        .GeneratedRoomMutationDto(
                        room.getClientRef(),
                        room.getName(),
                        room.getDescription(),
                        room.getZoneId(),
                        room.getNameLocalizedVariantsJson(),
                        room.getDescriptionLocalizedVariantsJson()))
            .toList(),
        request.getRoomExitsList().stream()
            .map(
                exit ->
                    new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                        .GeneratedRoomExitMutationDto(
                        exit.getFromRoomRef(),
                        exit.getToRoomRef(),
                        exit.getDirection(),
                        exit.getCost()))
            .toList(),
        request.getWorldEntitySpawnBindingsList().stream()
            .map(
                binding ->
                    new net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto
                        .GeneratedWorldEntitySpawnBindingMutationDto(
                        binding.getRoomRef(),
                        binding.getEntityTemplateType().name(),
                        binding.getEntityTemplateId(),
                        binding.getSpawnCount(),
                        binding.getRespawnDelaySeconds()))
            .toList());
  }

  private WorldDesignMutationResult toProtoWorldDesignMutationResult(String result) {
    if (result == null || result.isBlank()) {
      return WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_UNSPECIFIED;
    }
    return WorldDesignMutationResult.valueOf(result);
  }

  private String launchDescriptorErrorCode(String message, boolean allowSchemaUnsupported) {
    if (message == null || message.isBlank()) {
      return "INVALID_ARGUMENT";
    }
    if (allowSchemaUnsupported
        && message.startsWith(PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED)) {
      return PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED;
    }
    for (String prefix :
        List.of(
            "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED",
            "INVALID_TEMPLATE_CONFIGURATION",
            "SCRIPT_PATCH_OVERRIDE_CONFLICT",
            "SCRIPT_PATCH_NOT_READY",
            "RELEASE_BUNDLE_NOT_FOUND",
            "RELEASE_ATTESTATION_MISMATCH",
            "VERSION_STATE_EPOCH_STALE",
            "LAUNCH_REMAP_REQUIRED")) {
      if (message.startsWith(prefix)) {
        return prefix;
      }
    }
    return "INVALID_ARGUMENT";
  }

  private String remapSetErrorCode(String message) {
    if (message == null || message.isBlank()) {
      return "INVALID_ARGUMENT";
    }
    if (message.startsWith("NOT_FOUND:")) {
      return "NOT_FOUND";
    }
    return "INVALID_ARGUMENT";
  }

  private String scriptPatchPublicationErrorCode(String message) {
    if (message == null || message.isBlank()) {
      return "INVALID_ARGUMENT";
    }
    if (message.contains("not found")) {
      return "NOT_FOUND";
    }
    return "INVALID_ARGUMENT";
  }

  private String pluginPublicationErrorCode(String message) {
    if (message == null || message.isBlank()) {
      return "INVALID_ARGUMENT";
    }
    if (message.startsWith("NOT_FOUND:")) {
      return "NOT_FOUND";
    }
    if (message.startsWith("PLUGIN_VERSION_IMMUTABLE")) {
      return "PLUGIN_VERSION_IMMUTABLE";
    }
    return "INVALID_ARGUMENT";
  }

  private String saveRevisionErrorCode(String message) {
    if (message == null || message.isBlank()) {
      return "INVALID_ARGUMENT";
    }
    for (String prefix :
        List.of(
            "INVALID_ARGUMENT",
            "INVALID_VERSION_STATE",
            "DRAFT_WRITE_CONFLICT",
            "UNRESOLVED_REFERENCE",
            "OUT_OF_SYNC",
            "UNSUPPORTED_SCOPE")) {
      if (message.startsWith(prefix)) {
        return prefix;
      }
    }
    return "INVALID_ARGUMENT";
  }

  private void requireLaunchAttestationReadAccess() {
    if (SessionContext.isInternalService()) {
      return;
    }
    AdminRoleGuard.requireAdminRole();
  }
}
