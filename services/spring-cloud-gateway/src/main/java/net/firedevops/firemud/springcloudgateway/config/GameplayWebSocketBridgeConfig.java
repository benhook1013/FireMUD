package net.firedevops.firemud.springcloudgateway.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.filter.VerifiedTcpProxyOrigin;
import net.firedevops.firemud.springcloudgateway.websocket.AuthenticatedGameplayWebSocketClient;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketBridgeHandler;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.WebHandler;
import reactor.netty.http.client.HttpClient;

@Configuration
public class GameplayWebSocketBridgeConfig {
  private static final String AUTHENTICATED_UPSTREAM_URL_PROPERTY =
      "firemud.gateway.gameplay.bridge.authenticated-upstream-url";

  @Bean
  @Primary
  ReactorNettyWebSocketClient gameplayWebSocketClient() {
    HttpClient httpClient =
        HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)
            .responseTimeout(Duration.ofSeconds(1));
    return new ReactorNettyWebSocketClient(httpClient);
  }

  @Bean
  GameplayWebSocketBridgeHandler gameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient gameplayWebSocketClient,
      GameplayWebSocketBridgeProperties properties,
      RuntimeIdentity runtimeIdentity,
      GameplayWebSocketObservability gameplayWebSocketObservability,
      ObjectProvider<AuthenticatedGameplayWebSocketClient> authenticatedClient) {
    return new GameplayWebSocketBridgeHandler(
        gameplayWebSocketClient,
        properties,
        runtimeIdentity,
        gameplayWebSocketObservability,
        authenticatedClient.getIfAvailable());
  }

  @Bean(initMethod = "initialize", destroyMethod = "close")
  @Conditional(AuthenticatedUpstreamConfigured.class)
  AuthenticatedGameplayWebSocketClient authenticatedGameplayWebSocketClient(
      @Value("${firemud.grpc.workload-namespace:}") String namespace,
      @Value("${firemud.gateway.gameplay.bridge.authenticated-upstream-url}") String upstream,
      @Value(
              "${firemud.gateway.gameplay.bridge.tls.cert-chain:${FIREMUD_GATEWAY_GAMEPLAY_BRIDGE_TLS_CERT_CHAIN_PATH:}}")
          String certificate,
      @Value(
              "${firemud.gateway.gameplay.bridge.tls.private-key:${FIREMUD_GATEWAY_GAMEPLAY_BRIDGE_TLS_PRIVATE_KEY_PATH:}}")
          String privateKey,
      @Value(
              "${firemud.gateway.gameplay.bridge.tls.ca-cert:${FIREMUD_GATEWAY_GAMEPLAY_BRIDGE_TLS_CA_CERT_PATH:}}")
          String caCertificate) {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return new AuthenticatedGameplayWebSocketClient(tls, namespace, upstream);
  }

  @Bean
  HandlerMapping gameplayWebSocketBridgeMapping(
      GameplayWebSocketBridgeHandler gameplayWebSocketBridgeHandler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    HandshakeWebSocketService upgrade = new HandshakeWebSocketService();
    WebHandler bridge =
        exchange ->
            upgrade.handleRequest(
                exchange, VerifiedTcpProxyOrigin.bind(exchange, gameplayWebSocketBridgeHandler));
    mapping.setUrlMap(Map.of("/ws/game", bridge, "/ws/game/**", bridge));
    return mapping;
  }

  @Bean
  @ConditionalOnMissingBean(WebSocketHandlerAdapter.class)
  WebSocketHandlerAdapter gameplayWebSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }

  static final class AuthenticatedUpstreamConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String upstream = context.getEnvironment().getProperty(AUTHENTICATED_UPSTREAM_URL_PROPERTY);
      return StringUtils.hasText(upstream) && !"false".equalsIgnoreCase(upstream.trim());
    }
  }
}
