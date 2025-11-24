package net.firedevops.firemud.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class DevEchoWebSocketConfig implements WebSocketConfigurer {
  private final DevEchoWebSocketHandler devEchoWebSocketHandler;

  public DevEchoWebSocketConfig(DevEchoWebSocketHandler devEchoWebSocketHandler) {
    this.devEchoWebSocketHandler = devEchoWebSocketHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(devEchoWebSocketHandler, "/dev/echo").setAllowedOrigins("*");
  }
}
