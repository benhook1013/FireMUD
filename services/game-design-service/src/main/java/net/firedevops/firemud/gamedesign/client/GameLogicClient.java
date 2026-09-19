package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import org.springframework.stereotype.Component;

@Component
public class GameLogicClient
    extends AbstractReloadingBlockingGrpcClient<GameLogicServiceGrpc.GameLogicServiceBlockingStub> {
  public GameLogicClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameLogicClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicServiceGrpc.GameLogicServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      PublicationDigestRequestBinding binding) {
    var response =
        stub()
            .getDraftDesignDigest(
                GetDraftDesignDigestRequest.newBuilder()
                    .setTenantId(binding.tenantId())
                    .setVersionId(requireFullVersionId(binding))
                    .setPublishRequestId(binding.publishRequestId())
                    .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
                    .setRequestDigest(binding.requestDigest())
                    .build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PublishParticipantDigestDto(
          "GAME_LOGIC",
          binding.versionId(),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    if (!binding.tenantId().equals(response.getTenantId())
        || !response.hasVersionId()
        || !binding.versionId().equals(response.getVersionId())
        || !response.getBaseVersionId().isEmpty()) {
      return new PublishParticipantDigestDto(
          "GAME_LOGIC",
          binding.versionId(),
          null,
          null,
          null,
          "RESPONSE_BINDING_MISMATCH",
          "owner returned tenant or typed full-version scope that does not match request");
    }
    return new PublishParticipantDigestDto(
        "GAME_LOGIC",
        response.getVersionId(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }

  private String requireFullVersionId(PublicationDigestRequestBinding binding) {
    if (binding.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("full-version binding required");
    }
    return binding.versionId();
  }
}
