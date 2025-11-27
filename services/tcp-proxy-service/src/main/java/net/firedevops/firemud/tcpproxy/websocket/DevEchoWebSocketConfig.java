package net.firedevops.firemud.tcpproxy.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@Profile("dev")
@EnableWebSocket
public class DevEchoWebSocketConfig implements WebSocketConfigurer {
  private final TcpProxyDevEchoWebSocketHandler devEchoWebSocketHandler;

  public DevEchoWebSocketConfig(TcpProxyDevEchoWebSocketHandler devEchoWebSocketHandler) {
    this.devEchoWebSocketHandler = devEchoWebSocketHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(devEchoWebSocketHandler, "/dev/echo").setAllowedOrigins("*");
  }
}
