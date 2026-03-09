package net.firedevops.firemud.springcloudgateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import net.firedevops.firemud.springcloudgateway.websocket.DevEchoWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
@Profile({"dev", "test"})
public class DevWebSocketConfig {

  @Bean
  public DevEchoWebSocketHandler devEchoWebSocketHandler(MeterRegistry meterRegistry) {
    return new DevEchoWebSocketHandler(meterRegistry);
  }

  @Bean
  public HandlerMapping devWebSocketMapping(DevEchoWebSocketHandler devEchoWebSocketHandler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    mapping.setUrlMap(Map.of("/dev/echo", devEchoWebSocketHandler));
    return mapping;
  }

  @Bean
  public WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}
