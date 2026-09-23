package net.firedevops.firemud.accountservice.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
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
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final String ROUTING_AUTHORITY_UNAVAILABLE_MESSAGE =
      "Gameplay routing authority unavailable; retry later";

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
    try {
      var response = callStub().listGameplayWorlds(ListGameplayWorldsRequest.getDefaultInstance());
      if (response.hasError()) {
        throw new IllegalStateException(
            "Gameplay world discovery failed: " + response.getError().getCode());
      }
      return response.getWorldsList();
    } catch (StatusRuntimeException ex) {
      throw routingAuthorityUnavailable(ex);
    }
  }

  public List<GameplayRealm> listGameplayRealms(String worldSlug) {
    try {
      var response =
          callStub()
              .listGameplayRealms(
                  ListGameplayRealmsRequest.newBuilder().setWorldSlug(worldSlug).build());
      if (response.hasError()) {
        throw new IllegalStateException(
            "Gameplay realm discovery failed: " + response.getError().getCode());
      }
      return response.getRealmsList();
    } catch (StatusRuntimeException ex) {
      throw routingAuthorityUnavailable(ex);
    }
  }

  public GameplayAdmissionPointer getAdmissionPointer(
      long tenantId, String worldSlug, String realmSlug) {
    try {
      var response =
          callStub()
              .getAdmissionPointer(
                  GetAdmissionPointerRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setRealmSlug(realmSlug)
                      .setWorldSlug(worldSlug)
                      .build());
      if (response.hasError()) {
        throw new IllegalStateException(
            "Admission pointer lookup failed: " + response.getError().getCode());
      }
      return response.getAdmissionPointer();
    } catch (StatusRuntimeException ex) {
      throw routingAuthorityUnavailable(ex);
    }
  }

  private GameSessionServiceGrpc.GameSessionServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private static AuthenticationException routingAuthorityUnavailable(StatusRuntimeException ex) {
    Status.Code code = ex.getStatus().getCode();
    if (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) {
      throw ex;
    }
    return new AuthenticationException(
        "AUTH_UNAVAILABLE", ROUTING_AUTHORITY_UNAVAILABLE_MESSAGE, ex);
  }
}
