package net.firedevops.firemud.gamesession.testsupport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.CrossServiceAppHarness;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared gameplay-oriented cross-service bootstrap fixture above the lower-level app harness. */
public final class GameplayCrossServiceStack implements AutoCloseable {
  private final AccountRuntimeStubServer accountStub;
  private final WorldManagementStubServer worldStub;
  private final EntityManagementStubServer entityStub;
  private final SocialGroupsStubServer socialStub;
  private final net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse
      baselineRoomEntities;
  private CrossServiceAppHarness.GameLogicHolder gameLogic;
  private final CrossServiceAppHarness.GameSessionHolder gameSession;

  private GameplayCrossServiceStack(
      AccountRuntimeStubServer accountStub,
      WorldManagementStubServer worldStub,
      EntityManagementStubServer entityStub,
      SocialGroupsStubServer socialStub,
      net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse baselineRoomEntities,
      CrossServiceAppHarness.GameLogicHolder gameLogic,
      CrossServiceAppHarness.GameSessionHolder gameSession) {
    this.accountStub = accountStub;
    this.worldStub = worldStub;
    this.entityStub = entityStub;
    this.socialStub = socialStub;
    this.baselineRoomEntities = baselineRoomEntities;
    this.gameLogic = gameLogic;
    this.gameSession = gameSession;
  }

  public static Builder builder() {
    return new Builder();
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Test stack intentionally exposes mutable stubs for scenario control.")
  public AccountRuntimeStubServer accountStub() {
    return accountStub;
  }

  public WorldManagementStubServer worldStub() {
    return worldStub;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Test stack intentionally exposes mutable stubs for scenario control.")
  public EntityManagementStubServer entityStub() {
    return entityStub;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Test stack intentionally exposes mutable stubs for scenario control.")
  public SocialGroupsStubServer socialStub() {
    return socialStub;
  }

  public CrossServiceAppHarness.GameLogicHolder gameLogic() {
    return gameLogic;
  }

  public CrossServiceAppHarness.GameSessionHolder gameSession() {
    return gameSession;
  }

  public int gameSessionPort() {
    return gameSession.port();
  }

  public synchronized CrossServiceAppHarness.GameLogicHolder restartGameLogic() {
    gameLogic = gameLogic.restart();
    return gameLogic;
  }

  public <T> T gameSessionBean(Class<T> type) {
    return gameSession.bean(type);
  }

  public JdbcTemplate jdbc() {
    return new JdbcTemplate(gameSession.bean(DataSource.class));
  }

  public void clearRedis() {
    gameSession
        .bean(StringRedisTemplate.class)
        .getConnectionFactory()
        .getConnection()
        .serverCommands()
        .flushAll();
  }

  public void resetScenarioState() {
    accountStub.resetRuntimeState();
    worldStub.resetFailures();
    if (baselineRoomEntities == null) {
      entityStub.resetRoomEntities();
    } else {
      entityStub.setRoomEntities(baselineRoomEntities);
    }
    entityStub.resetItemState();
    if (socialStub != null) {
      socialStub.resetState();
    }
  }

  public void clearScreenBuffers(long tenantId, long gameInstanceId, long... characterIds) {
    ScreenBufferService screenBufferService = gameSession.bean(ScreenBufferService.class);
    for (long characterId : characterIds) {
      screenBufferService.clear(tenantId, gameInstanceId, characterId);
    }
  }

  public long freshGameplayBaseline(
      long tenantId,
      long gameplayInstanceId,
      long ownerAccountId,
      long gameTemplateId,
      long... characterIds) {
    resetScenarioState();
    clearRedis();
    JdbcTemplate jdbc = jdbc();
    GameInstanceTestFixtures.ensureGameInstancesTable(jdbc);
    jdbc.update("DELETE FROM game_instances");
    if (characterIds.length > 0) {
      clearScreenBuffers(tenantId, gameplayInstanceId, characterIds);
    }
    return GameInstanceTestFixtures.insertRunningGameInstance(
        jdbc, tenantId, ownerAccountId, gameTemplateId);
  }

  public void seedLiveSession(SessionContext context) {
    gameSession.bean(SessionContextService.class).save(context);
  }

  public long insertRunningGameInstance(
      long tenantId, long accountId, long gameTemplateId, boolean clearExisting) {
    JdbcTemplate jdbc = jdbc();
    GameInstanceTestFixtures.ensureGameInstancesTable(jdbc);
    if (clearExisting) {
      clearRedis();
      jdbc.update("DELETE FROM game_instances");
    }
    return GameInstanceTestFixtures.insertRunningGameInstance(
        jdbc, tenantId, accountId, gameTemplateId);
  }

  @Override
  public synchronized void close() {
    gameSession.close();
    gameLogic.close();
    if (socialStub != null) {
      socialStub.close();
    }
    entityStub.close();
    worldStub.close();
    accountStub.close();
  }

  public static final class Builder {
    private String postgresHost;
    private int postgresPort;
    private String postgresDatabase;
    private String postgresUsername;
    private String postgresPassword;
    private String redisHost;
    private int redisPort;
    private long defaultAccountId = 7L;
    private String defaultRoomId = LookTestFixtures.ROOM_ID;
    private net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse initialRoomEntities;
    private boolean includeSocial;
    private final Map<String, Long> accountMappings = new LinkedHashMap<>();
    private final Map<String, Object> gameLogicProps = new LinkedHashMap<>();
    private final Map<String, Object> gameSessionProps = new LinkedHashMap<>();
    private Class<?>[] gameLogicConfigs = new Class<?>[0];
    private Class<?>[] gameSessionConfigs = new Class<?>[0];

    private Builder() {}

    public Builder withPostgres(
        String host, int port, String database, String username, String password) {
      this.postgresHost = host;
      this.postgresPort = port;
      this.postgresDatabase = database;
      this.postgresUsername = username;
      this.postgresPassword = password;
      return this;
    }

    public Builder withRedis(String host, int port) {
      this.redisHost = host;
      this.redisPort = port;
      return this;
    }

    public Builder withDefaultAccountId(long accountId) {
      this.defaultAccountId = accountId;
      return this;
    }

    public Builder mapAccountId(String username, long accountId) {
      this.accountMappings.put(username, accountId);
      return this;
    }

    public Builder withRoomId(String roomId) {
      this.defaultRoomId = roomId;
      return this;
    }

    public Builder withInitialRoomEntities(
        net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse roomEntities) {
      this.initialRoomEntities = roomEntities == null ? null : roomEntities.toBuilder().build();
      return this;
    }

    public Builder withSocialEnabled(boolean includeSocial) {
      this.includeSocial = includeSocial;
      return this;
    }

    public Builder withGameLogicProps(Map<String, Object> props) {
      this.gameLogicProps.putAll(props);
      return this;
    }

    public Builder withGameSessionProps(Map<String, Object> props) {
      this.gameSessionProps.putAll(props);
      return this;
    }

    public Builder withGameLogicConfigs(Class<?>... configs) {
      this.gameLogicConfigs = configs == null ? new Class<?>[0] : configs;
      return this;
    }

    public Builder withGameSessionConfigs(Class<?>... configs) {
      this.gameSessionConfigs = configs == null ? new Class<?>[0] : configs;
      return this;
    }

    public GameplayCrossServiceStack start() throws IOException {
      requireConfigured();
      AccountRuntimeStubServer accountStub = new AccountRuntimeStubServer(0);
      accountStub.setDefaultAccountId(defaultAccountId);
      accountMappings.forEach(accountStub::mapAccountId);

      WorldManagementStubServer worldStub = new WorldManagementStubServer(0);
      EntityManagementStubServer entityStub = new EntityManagementStubServer(0);
      if (initialRoomEntities != null) {
        entityStub.setRoomEntities(initialRoomEntities);
      }
      SocialGroupsStubServer socialStub = includeSocial ? new SocialGroupsStubServer(0) : null;

      CrossServiceAppHarness.GameLogicHolder gameLogic =
          CrossServiceAppHarness.startGameLogic(
              0,
              worldStub.endpoint(),
              entityStub.endpoint(),
              socialStub == null ? null : socialStub.endpoint(),
              props -> props.putAll(gameLogicProps),
              gameLogicConfigs);

      CrossServiceAppHarness.GameSessionHolder gameSession =
          CrossServiceAppHarness.startGameSession(
              gameLogic.grpcPort(),
              accountStub.port(),
              props -> {
                props.put("game.logic.default-room-id", defaultRoomId);
                props.put("firemud.redis.host", redisHost);
                props.put("firemud.redis.port", redisPort);
                props.put("firemud.postgres.host", postgresHost);
                props.put("firemud.postgres.port", postgresPort);
                props.put("firemud.postgres.database", postgresDatabase);
                props.put("firemud.postgres.username", postgresUsername);
                props.put("firemud.postgres.password", postgresPassword);
                props.put("firemud.database.enabled", "true");
                props.put("spring.jpa.hibernate.ddl-auto", "none");
                props.put("firemud.services.entityManagementService", entityStub.endpoint());
                if (socialStub != null) {
                  props.put("firemud.services.socialGroupsService", socialStub.endpoint());
                }
                props.putAll(gameSessionProps);
              },
              gameSessionConfigs);

      return new GameplayCrossServiceStack(
          accountStub,
          worldStub,
          entityStub,
          socialStub,
          initialRoomEntities,
          gameLogic,
          gameSession);
    }

    private void requireConfigured() {
      Objects.requireNonNull(postgresHost, "postgresHost");
      Objects.requireNonNull(postgresDatabase, "postgresDatabase");
      Objects.requireNonNull(postgresUsername, "postgresUsername");
      Objects.requireNonNull(postgresPassword, "postgresPassword");
      Objects.requireNonNull(redisHost, "redisHost");
    }
  }
}
