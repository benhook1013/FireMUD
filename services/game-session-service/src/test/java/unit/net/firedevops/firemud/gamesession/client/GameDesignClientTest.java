package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Field;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameDesignClientTest {

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void rejectsNonpositiveBaseVersionAsInvalidArgumentWithoutCallingGameDesign(long baseVersionId)
      throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GameDesignClient client = newClient(stub);

    GetPublishedScriptPatchVersionResponse response =
        client.getPublishedScriptPatchVersion(1L, "patch-1", baseVersionId);

    assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(response.getError().getMessage())
        .isEqualTo("base_version_id must be positive for script-patch publication lookup");
    verifyNoInteractions(stub);
  }

  @Test
  void legacyNumericLaunchResolveFailsClosedWithoutStubOrChannelInteraction() throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignClient client = newClient(stub, channelFactory);

    ResolveLaunchDescriptorResponse response = client.resolveLaunchDescriptor(1L, 9L, "cp-1");

    assertLaunchBindingRequired(response);
    verifyNoInteractions(stub, channelFactory);
  }

  @Test
  void legacyNumericReplacementLaunchResolveFailsClosedWithoutStubOrChannelInteraction()
      throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignClient client = newClient(stub, channelFactory);

    ResolveLaunchDescriptorResponse response =
        client.resolveLaunchDescriptor(1L, 9L, "cp-1", 7L, 8L);

    assertLaunchBindingRequired(response);
    verifyNoInteractions(stub, channelFactory);
  }

  private static void assertLaunchBindingRequired(ResolveLaunchDescriptorResponse response) {
    assertThat(response.getError().getCode()).isEqualTo("AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED");
    assertThat(response.getError().getMessage())
        .contains("Canonical authored-world source binding is required");
  }

  private static GameDesignClient newClient(
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub) throws Exception {
    return newClient(stub, mock(GrpcChannelFactory.class));
  }

  private static GameDesignClient newClient(
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub,
      GrpcChannelFactory channelFactory)
      throws Exception {
    GameDesignClient client =
        new GameDesignClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }
}
