package net.firedevops.firemud.gamesession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    props.put("grpc.port", String.valueOf(grpcPort));
    props.put("grpc.server.port", String.valueOf(grpcPort));
    props.put("grpc.enabled", "true");
    props.put("grpc.server.security.enabled", "false");
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.database.enabled", "false");
    props.put("otel.endpoint", "disabled");
    props.put("firemud.services.worldManagementService", worldEndpoint);
    props.put("firemud.services.entityManagementService", entityEndpoint);
    if (socialEndpoint != null) {
      props.put("firemud.services.socialGroupsService", socialEndpoint);
    }

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                net.firedevops.firemud.gamelogic.GameLogicServiceApplication.class)
            .run(toCommandLineArgs(props));
    return new GameLogicHolder(context, grpcPort);
  }

  public static GameSessionHolder startGameSession(
      int gameLogicPort, int accountPort, Consumer<Map<String, Object>> customizer) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-session-service");
    props.put("server.port", "0");
    props.put("grpc.port", "0");
    props.put("grpc.server.port", "0");
    props.put("grpc.enabled", "false");
    props.put("grpc.server.enabled", "false");
    props.put("game-session.dev-isolated", "false");
    props.put("spring.main.allow-bean-definition-overriding", "true");
    props.put(
        "spring.autoconfigure.exclude",
        "org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration,"
            + "org.lognet.springboot.grpc.autoconfigure.actuate.GRpcActuateAutoConfiguration");
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
    customizer.accept(props);

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                GameSessionServiceApplication.class, GameSessionTestOverrides.class)
            .run(toCommandLineArgs(props));
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port);
  }

  private static String[] toCommandLineArgs(Map<String, Object> props) {
    return props.entrySet().stream()
        .flatMap(entry -> Stream.of("--" + entry.getKey() + "=" + String.valueOf(entry.getValue())))
        .toArray(String[]::new);
  }

  @TestConfiguration
  static class GameSessionTestOverrides {
    @Bean
    @Primary
    ConflictTracker conflictTracker() {
      return key -> {};
    }

    @Bean
    @Primary
    LookCacheService lookCacheService() {
      return new LookCacheService() {
        @Override
        public void cache(
            long tenantId,
            long sessionId,
            String roomId,
            String renderedText,
            String protocolText) {}

        @Override
        public java.util.Optional<CachedLook> get(long tenantId, long sessionId) {
          return java.util.Optional.empty();
        }
      };
    }

    @Bean(name = "gameInstanceServiceImpl")
    @Primary
    GameInstanceService gameInstanceService() {
      return new GameInstanceService() {
        @Override
        public GameInstanceDto startSession(StartSessionRequest request) {
          return new GameInstanceDto(
              -1L,
              request.tenantId(),
              request.runtimeVersion(),
              request.scriptPatchVersion(),
              request.ownerAccountId(),
              "RUNNING");
        }

        @Override
        public GameInstanceDto stopSession(long sessionId) {
          return new GameInstanceDto(sessionId, 0L, "stub", null, 0L, "STOPPED");
        }

        @Override
        public GameInstanceDto restartSession(long sessionId) {
          return new GameInstanceDto(sessionId, 0L, "stub", null, 0L, "RUNNING");
        }
      };
    }
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
