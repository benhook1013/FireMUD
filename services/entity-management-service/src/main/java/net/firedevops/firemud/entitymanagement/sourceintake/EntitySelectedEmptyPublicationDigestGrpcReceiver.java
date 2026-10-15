package net.firedevops.firemud.entitymanagement.sourceintake;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService.EntityDraftDesignDigest;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Unregistered native receiver for the selected-empty Entity publication digest path. */
public final class EntitySelectedEmptyPublicationDigestGrpcReceiver
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(EntitySelectedEmptyPublicationDigestGrpcReceiver.class);

  private final EntitySelectedEmptyPublicationDigestService digestService;
  private final PublicationReadGuard publicationReadGuard;
  private final MeterRegistry meterRegistry;

  public EntitySelectedEmptyPublicationDigestGrpcReceiver(
      EntitySelectedEmptyPublicationDigestService digestService,
      String configuredNamespace,
      MeterRegistry meterRegistry) {
    this.digestService = Objects.requireNonNull(digestService, "digestService");
    this.publicationReadGuard = PublicationReadGuard.configured(configuredNamespace);
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
  }

  @Override
  public void getDraftDesignDigest(
      GetDraftDesignDigestRequest request,
      StreamObserver<GetDraftDesignDigestResponse> responseObserver) {
    try {
      requirePublicationRead();
      if (request.getScopeCase() != GetDraftDesignDigestRequest.ScopeCase.VERSION_ID) {
        responseObserver.onNext(
            error(
                "UNSUPPORTED_SCOPE",
                "Entity selected-empty publication supports version_id scope only"));
        responseObserver.onCompleted();
        return;
      }
      PublicationDigestRequestBinding binding =
          PublicationDigestRequestBinding.forScope(
              PublicationDigestRequestBinding.ScopeKind.FULL_VERSION,
              request.getTenantId(),
              request.getVersionId(),
              request.getBaseVersionId(),
              request.getScriptPatchVersion(),
              request.getPublishRequestId());
      binding.validateSupplied(request.getDerivedWorkflowIdentity(), request.getRequestDigest());

      EntityDraftDesignDigest digest = digestService.getDraftDesignDigest(binding);
      binding.requireOwnerScope(digest.tenantId(), digest.scopeValue());
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(binding.tenantId())
              .setVersionId(binding.versionId())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
              .build());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException denied) {
      responseObserver.onNext(error("PERMISSION_DENIED", denied.getMessage()));
      responseObserver.onCompleted();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onNext(error("INVALID_ARGUMENT", invalid.getMessage()));
      responseObserver.onCompleted();
    } catch (IllegalStateException unavailable) {
      responseObserver.onNext(error("FAILED_PRECONDITION", unavailable.getMessage()));
      responseObserver.onCompleted();
    } catch (RuntimeException failure) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(meterRegistry, LOGGER, "GetDraftDesignDigest", failure))
              .build());
      responseObserver.onCompleted();
    }
  }

  private void requirePublicationRead() {
    if (publicationReadGuard == null) {
      throw new AdminAuthorizationException("Publication read authorization is not configured");
    }
    publicationReadGuard.requirePublicationRead(
        PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD);
  }

  private GetDraftDesignDigestResponse error(String code, String message) {
    return GetDraftDesignDigestResponse.newBuilder()
        .setError(GrpcAppErrors.error(meterRegistry, LOGGER, "GetDraftDesignDigest", code, message))
        .build();
  }
}
