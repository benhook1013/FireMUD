package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import org.springframework.stereotype.Component;

@Component
public class EntityManagementClient
    extends AbstractReloadingBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {
  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, EntityManagementClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntityManagementServiceGrpc.EntityManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      String tenantId, long versionId) {
    var response =
        stub()
            .getDraftDesignDigest(
                GetDraftDesignDigestRequest.newBuilder()
                    .setTenantId(tenantId)
                    .setVersionId(String.valueOf(versionId))
                    .build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PublishParticipantDigestDto(
          "ENTITY_MANAGEMENT",
          String.valueOf(versionId),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    return new PublishParticipantDigestDto(
        "ENTITY_MANAGEMENT",
        response.getScopeValue(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }
}
