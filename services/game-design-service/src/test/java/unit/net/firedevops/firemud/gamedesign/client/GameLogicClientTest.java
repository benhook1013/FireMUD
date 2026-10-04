package net.firedevops.firemud.gamedesign.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;
import org.junit.jupiter.api.Test;

class GameLogicClientTest {

  @Test
  void fullVersionDigestReadPreservesDedicatedAbilitySchemaDigest() throws Exception {
    var stub = mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant-1", "7", "request-1");
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(
            GetDraftDesignDigestResponse.newBuilder()
                .setTenantId(binding.tenantId())
                .setVersionId(binding.versionId())
                .setAppliedCommitId("commit-7")
                .setContentDigest("logic-aggregate-digest")
                .setDigestSchemaVersion(1)
                .setAbilitySchemaDigest("ability-schema-digest")
                .build());
    var client = newTestClient(stub);

    var digest = client.getDraftDesignDigestForVersion(binding);

    assertEquals("logic-aggregate-digest", digest.contentDigest());
    assertEquals("ability-schema-digest", digest.abilitySchemaDigest());
  }

  @Test
  void fullVersionDigestReadKeepsAbsentAbilitySchemaDigestAbsent() throws Exception {
    var stub = mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant-1", "7", "request-1");
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(
            GetDraftDesignDigestResponse.newBuilder()
                .setTenantId(binding.tenantId())
                .setVersionId(binding.versionId())
                .setAppliedCommitId("commit-7")
                .setContentDigest("logic-aggregate-digest")
                .setDigestSchemaVersion(1)
                .build());
    var client = newTestClient(stub);

    var digest = client.getDraftDesignDigestForVersion(binding);

    assertNull(digest.abilitySchemaDigest());
  }

  private TestGameLogicClient newTestClient(GameLogicServiceGrpc.GameLogicServiceBlockingStub stub)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    var client =
        new TestGameLogicClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            stub);
    client.initialize();
    return client;
  }

  private static final class TestGameLogicClient extends GameLogicClient {
    private final GameLogicServiceGrpc.GameLogicServiceBlockingStub stub;

    private TestGameLogicClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        GameLogicServiceGrpc.GameLogicServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    private void initialize() throws Exception {
      initReloadingClient();
    }

    @Override
    protected GameLogicServiceGrpc.GameLogicServiceBlockingStub buildStub(ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
