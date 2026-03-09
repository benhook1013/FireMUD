package net.firedevops.firemud.gamesession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.TestSocketUtils;

/** Shared bootstrap helpers for nested cross-service Spring application contexts in tests. */
public final class CrossServiceAppHarness {
  private CrossServiceAppHarness() {}

  public static GameLogicHolder startGameLogic(
      String worldEndpoint, String entityEndpoint, String socialEndpoint) {
    int grpcPort = TestSocketUtils.findAvailableTcpPort();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-logic-service");
    props.put("server.port", "0");
    props.put("grpc.server.port", String.valueOf(grpcPort));
    props.put("grpc.server.security.enabled", "false");
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
    props.put("firemud.services.worldManagementService", worldEndpoint);
    props.put("firemud.services.entityManagementService", entityEndpoint);
    if (socialEndpoint != null) {
      props.put("firemud.services.socialGroupsService", socialEndpoint);
    }

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                net.firedevops.firemud.gamelogic.GameLogicServiceApplication.class)
            .properties(props)
            .run();
    return new GameLogicHolder(context, grpcPort);
  }

  public static GameSessionHolder startGameSession(
      int gameLogicPort, int accountPort, Consumer<Map<String, Object>> customizer) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-session-service");
    props.put("server.port", "0");
    props.put("grpc.server.port", "0");
    props.put("grpc.server.enabled", "false");
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
    customizer.accept(props);

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameSessionServiceApplication.class).properties(props).run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port);
  }

  public static final class GameLogicHolder {
    private final ConfigurableApplicationContext context;
    private final int grpcPort;

    GameLogicHolder(ConfigurableApplicationContext context, int grpcPort) {
      this.context = context;
      this.grpcPort = grpcPort;
    }

    public int grpcPort() {
      return grpcPort;
    }

    public void close() {
      context.close();
    }
  }

  public static class GameSessionHolder {
    private final ConfigurableApplicationContext context;
    private final int port;

    GameSessionHolder(ConfigurableApplicationContext context, int port) {
      this.context = context;
      this.port = port;
    }

    public int port() {
      return port;
    }

    public <T> T bean(Class<T> type) {
      return context.getBean(type);
    }

    public void close() {
      context.close();
    }
  }
}
