package net.firedevops.firemud.gamesession.test.stubs;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import org.junit.jupiter.api.Test;

class GameDesignStubServerTest {

  @Test
  void returnsTheReleaseBundleIdentityUsedByGameplayFixtures() throws Exception {
    try (GameDesignStubServer server = new GameDesignStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        GetPublishedReleaseBundleResponse response =
            GameDesignServiceGrpc.newBlockingStub(channel)
                .getPublishedReleaseBundle(
                    GetPublishedReleaseBundleRequest.newBuilder()
                        .setTenantId("1")
                        .setVersionId(7L)
                        .build());

        assertThat(response.getBundle().getId())
            .isEqualTo(GameInstanceTestFixtures.PUBLISHED_RELEASE_BUNDLE_ID);
        assertThat(response.getBundle().getVersionId()).isEqualTo(7L);
        assertThat(response.getBundle().getCommandDefinitionsList()).isEmpty();
        assertThat(server.publishedReleaseBundleRequests()).isEqualTo(1);
      } finally {
        channel.shutdownNow();
      }
    }
  }
}
