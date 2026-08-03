package net.firedevops.firemud.test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Shared reactive app bootstrap helpers for integration and cross-service tests. */
public final class ReactiveTestApplicationSupport {

  private ReactiveTestApplicationSupport() {}

  public static ReactiveAppHolder startReactiveApp(
      Map<String, ?> properties, Class<?>... applicationClasses) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    resolved.put("server.port", "0");
    resolved.put("spring.main.web-application-type", "reactive");
    // Reactive test apps in this repo do not use service discovery, and letting Spring Cloud
    // infer local host metadata can hang startup under heavier parallel test load.
    resolved.put("spring.cloud.discovery.enabled", "false");
    resolved.put("spring.cloud.inetutils.timeout-seconds", "1");
    resolved.put("spring.cloud.inetutils.default-hostname", "localhost");
    resolved.put("spring.cloud.inetutils.default-ip-address", "127.0.0.1");
    resolved.putAll(properties);
    ConfigurableApplicationContext context =
        Objects.requireNonNull(
            new SpringApplicationBuilder(applicationClasses)
                .properties(toPropertyArray(resolved))
                .run(),
            "Spring application context");
    int port =
        Objects.requireNonNull(
                ((WebServerApplicationContext) context).getWebServer(), "Reactive test web server")
            .getPort();
    return new ReactiveAppHolder(context, port);
  }

  private static String[] toPropertyArray(Map<String, ?> properties) {
    return properties.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
        .toArray(String[]::new);
  }

  @SuppressFBWarnings(
      value = "EI",
      justification = "Test app holder intentionally exposes the live Spring context.")
  public record ReactiveAppHolder(ConfigurableApplicationContext context, int port)
      implements AutoCloseable {
    public String websocketUrl() {
      return websocketUrl("/ws/game");
    }

    public String websocketUrl(String path) {
      return "ws://localhost:" + port + path;
    }

    @Override
    public void close() {
      context.close();
    }
  }
}
