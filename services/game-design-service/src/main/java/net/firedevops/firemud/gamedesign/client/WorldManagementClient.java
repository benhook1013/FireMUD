package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.springframework.stereotype.Component;

@Component
public class WorldManagementClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub> {
  public WorldManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, WorldManagementClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected WorldManagementServiceGrpc.WorldManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        WorldManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
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
          "WORLD_MANAGEMENT",
          String.valueOf(versionId),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    return new PublishParticipantDigestDto(
        "WORLD_MANAGEMENT",
        response.getScopeValue(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }
}
