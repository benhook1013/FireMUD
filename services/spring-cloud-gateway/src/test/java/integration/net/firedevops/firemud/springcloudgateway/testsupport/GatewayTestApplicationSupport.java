package net.firedevops.firemud.springcloudgateway.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Shared reactive app bootstrap helpers for gateway integration tests. */
public final class GatewayTestApplicationSupport {

  private GatewayTestApplicationSupport() {}

  public static ReactiveAppHolder startReactiveApp(
      Map<String, ?> properties, Class<?>... applicationClasses) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    resolved.put("server.port", "0");
    resolved.put("spring.main.web-application-type", "reactive");
    resolved.putAll(properties);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(applicationClasses)
            .properties(toPropertyArray(resolved))
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new ReactiveAppHolder(context, port);
  }

  private static String[] toPropertyArray(Map<String, ?> properties) {
    return properties.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
        .toArray(String[]::new);
  }

  public record ReactiveAppHolder(ConfigurableApplicationContext context, int port)
      implements AutoCloseable {
    public String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    @Override
    public void close() {
      context.close();
    }
  }
}
