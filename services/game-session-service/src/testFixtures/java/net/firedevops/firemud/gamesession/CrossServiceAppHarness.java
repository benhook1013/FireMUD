package net.firedevops.firemud.gamesession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;
import org.springframework.test.util.TestSocketUtils;

/** Shared bootstrap helpers for nested cross-service Spring application contexts in tests. */
public final class CrossServiceAppHarness {
  private static final String CROSS_SERVICE_TEST_JWT_SECRET =
      "stub-secret-key-for-tests-1234567890";

  private CrossServiceAppHarness() {}

  public static GameLogicHolder startGameLogic(
      String worldEndpoint, String entityEndpoint, String socialEndpoint) {
    return startGameLogic(
        TestSocketUtils.findAvailableTcpPort(),
        worldEndpoint,
        entityEndpoint,
        socialEndpoint,
        props -> {},
        new Class<?>[0]);
  }

  public static GameLogicHolder startGameLogic(
      int grpcPort, String worldEndpoint, String entityEndpoint, String socialEndpoint) {
    return startGameLogic(
        grpcPort, worldEndpoint, entityEndpoint, socialEndpoint, props -> {}, new Class<?>[0]);
  }

  public static GameLogicHolder startGameLogic(
      int grpcPort,
      String worldEndpoint,
      String entityEndpoint,
      String socialEndpoint,
      Consumer<Map<String, Object>> customizer,
      Class<?>... extraConfigClasses) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-logic-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", String.valueOf(grpcPort));
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.auth.jwt-secret", CROSS_SERVICE_TEST_JWT_SECRET);
    props.put("firemud.database.enabled", "false");
    props.put("otel.endpoint", "disabled");
    props.put("firemud.services.worldManagementService", worldEndpoint);
    props.put("firemud.services.entityManagementService", entityEndpoint);
    if (socialEndpoint != null) {
      props.put("firemud.services.socialGroupsService", socialEndpoint);
    }
    customizer.accept(props);

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                applicationClasses(
                    net.firedevops.firemud.gamelogic.GameLogicServiceApplication.class,
                    extraConfigClasses))
            .run(toCommandLineArgs(props));
    int boundGrpcPort = context.getBean(GrpcServerLifecycle.class).getPort();
    int restartGrpcPort = grpcPort == 0 ? boundGrpcPort : grpcPort;
    return new GameLogicHolder(
        context,
        boundGrpcPort,
        worldEndpoint,
        entityEndpoint,
        socialEndpoint,
        () ->
            startGameLogic(
                restartGrpcPort,
                worldEndpoint,
                entityEndpoint,
                socialEndpoint,
                customizer,
                extraConfigClasses));
  }

  public static GameSessionHolder startGameSession(
      int gameLogicPort, int accountPort, Consumer<Map<String, Object>> customizer) {
    return startGameSession(gameLogicPort, accountPort, customizer, new Class<?>[0]);
  }

  public static GameSessionHolder startGameSession(
      int gameLogicPort,
      int accountPort,
      Consumer<Map<String, Object>> customizer,
      Class<?>... extraConfigClasses) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-session-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", "0");
    props.put("spring.main.allow-bean-definition-overriding", "true");
    props.put("spring.flyway.enabled", "true");
    props.put("spring.flyway.locations", "filesystem:" + gameSessionMigrationDir());
    props.put(
        "spring.autoconfigure.exclude",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration");
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.auth.jwt-secret", CROSS_SERVICE_TEST_JWT_SECRET);
    props.put("otel.endpoint", "disabled");
    customizer.accept(props);

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                applicationClasses(
                    GameSessionServiceApplication.class,
                    combineConfigs(GameSessionTestOverrides.class, extraConfigClasses)))
            .run(toCommandLineArgs(props));
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port);
  }

  private static Class<?>[] applicationClasses(Class<?> primary, Class<?>... extras) {
    List<Class<?>> classes = new ArrayList<>();
    classes.add(primary);
    classes.addAll(Arrays.asList(extras));
    return classes.toArray(Class<?>[]::new);
  }

  private static Class<?>[] combineConfigs(Class<?> first, Class<?>... extras) {
    List<Class<?>> classes = new ArrayList<>();
    classes.add(first);
    classes.addAll(Arrays.asList(extras));
    return classes.toArray(Class<?>[]::new);
  }

  private static String[] toCommandLineArgs(Map<String, Object> props) {
    return props.entrySet().stream()
        .flatMap(entry -> Stream.of("--" + entry.getKey() + "=" + String.valueOf(entry.getValue())))
        .toArray(String[]::new);
  }

  private static String gameSessionMigrationDir() {
    return resolveModuleMigrationDir("game-session-service").toString();
  }

  private static Path resolveModuleMigrationDir(String moduleName) {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path candidate =
          current
              .resolve("services")
              .resolve(moduleName)
              .resolve("src/main/resources/db/migration");
      if (candidate.toFile().exists()) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not resolve migration directory for " + moduleName);
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
    GrpcServiceDiscoverer grpcServiceDiscoverer() {
      return org.mockito.Mockito.mock(GrpcServiceDiscoverer.class);
    }

    @Bean
    @Primary
    ModerationPolicyClient moderationPolicyClient() {
      ModerationPolicyClient client = org.mockito.Mockito.mock(ModerationPolicyClient.class);
      org.mockito.Mockito.when(
              client.evaluateGameplayAdmission(
                  org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(
              net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                  .setAllowed(true)
                  .build());
      return client;
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

    @Bean
    @Primary
    ScreenBufferService screenBufferService() {
      return new ScreenBufferService() {
        private final Map<String, BufferedScreen> buffers = new ConcurrentHashMap<>();

        @Override
        public void append(
            long tenantId,
            long gameInstanceId,
            long characterId,
            java.util.List<BufferedEntry> entries) {
          java.util.List<BufferedEntry> filtered =
              entries == null
                  ? java.util.List.of()
                  : entries.stream().filter(entry -> !entry.text().isBlank()).toList();
          if (filtered.isEmpty()) {
            return;
          }
          String key = tenantId + ":" + gameInstanceId + ":" + characterId;
          BufferedScreen previous = buffers.get(key);
          java.util.List<BufferedEntry> combined = new java.util.ArrayList<>();
          if (previous != null) {
            combined.addAll(previous.entries());
          }
          combined.addAll(filtered);
          int messages = combined.size();
          int lines = combined.stream().mapToInt(BufferedEntry::lineCount).sum();
          buffers.put(
              key, new BufferedScreen(combined, messages, lines, System.currentTimeMillis()));
        }

        @Override
        public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
          return Optional.ofNullable(
              buffers.get(tenantId + ":" + gameInstanceId + ":" + characterId));
        }

        @Override
        public void clear(long tenantId, long gameInstanceId, long characterId) {
          buffers.remove(tenantId + ":" + gameInstanceId + ":" + characterId);
        }
      };
    }

    @Bean(name = "gameInstanceServiceImpl")
    @Primary
    GameInstanceService gameInstanceService() {
      return new GameInstanceService() {
        @Override
        public GameInstanceDto startSession(
            StartSessionRequest request, boolean replaceExistingFirst) {
          return new GameInstanceDto(
              -1L,
              request.tenantId(),
              "stub-template-" + request.gameTemplateId(),
              null,
              request.gameTemplateId(),
              null,
              null,
              null,
              null,
              null,
              request.ownerAccountId(),
              "RUNNING");
        }

        @Override
        public GameInstanceDto stopSession(long sessionId) {
          return new GameInstanceDto(
              sessionId, 0L, "stub", null, null, null, null, null, null, null, 0L, "STOPPED");
        }

        @Override
        public GameInstanceDto restartSession(long sessionId) {
          return new GameInstanceDto(
              sessionId, 0L, "stub", null, null, null, null, null, null, null, 0L, "RUNNING");
        }
      };
    }
  }

  public static final class GameLogicHolder {
    private final ConfigurableApplicationContext context;
    private final int grpcPort;
    private final String worldEndpoint;
    private final String entityEndpoint;
    private final String socialEndpoint;
    private final Supplier<GameLogicHolder> restartAction;

    GameLogicHolder(
        ConfigurableApplicationContext context,
        int grpcPort,
        String worldEndpoint,
        String entityEndpoint,
        String socialEndpoint,
        Supplier<GameLogicHolder> restartAction) {
      this.context = context;
      this.grpcPort = grpcPort;
      this.worldEndpoint = worldEndpoint;
      this.entityEndpoint = entityEndpoint;
      this.socialEndpoint = socialEndpoint;
      this.restartAction = restartAction;
    }

    public int grpcPort() {
      return grpcPort;
    }

    public GameLogicHolder restart() {
      context.close();
      return restartAction.get();
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
