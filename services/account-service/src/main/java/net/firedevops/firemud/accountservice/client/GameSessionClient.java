package net.firedevops.firemud.accountservice.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
import net.firedevops.firemud.gamesession.v1.GameplayWorld;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsRequest;
import net.firedevops.firemud.gamesession.v1.ListGameplayWorldsRequest;
import org.springframework.stereotype.Component;

@Component
public class GameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionServiceGrpc.GameSessionServiceBlockingStub> {
  public GameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameSessionClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionServiceGrpc.GameSessionServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public List<GameplayWorld> listGameplayWorlds() {
    var response = stub().listGameplayWorlds(ListGameplayWorldsRequest.getDefaultInstance());
    if (response.hasError()) {
      throw new IllegalStateException(
          "Gameplay world discovery failed: " + response.getError().getCode());
    }
    return response.getWorldsList();
  }

  public List<GameplayRealm> listGameplayRealms(String worldSlug) {
    var response =
        stub()
            .listGameplayRealms(
                ListGameplayRealmsRequest.newBuilder().setWorldSlug(worldSlug).build());
    if (response.hasError()) {
      throw new IllegalStateException(
          "Gameplay realm discovery failed: " + response.getError().getCode());
    }
    return response.getRealmsList();
  }

  public GameplayAdmissionPointer getAdmissionPointer(String worldSlug, String realmSlug) {
    var response =
        stub()
            .getAdmissionPointer(
                GetAdmissionPointerRequest.newBuilder()
                    .setWorldSlug(worldSlug)
                    .setRealmSlug(realmSlug)
                    .build());
    if (response.hasError()) {
      throw new IllegalStateException(
          "Admission pointer lookup failed: " + response.getError().getCode());
    }
    return response.getAdmissionPointer();
  }
}
