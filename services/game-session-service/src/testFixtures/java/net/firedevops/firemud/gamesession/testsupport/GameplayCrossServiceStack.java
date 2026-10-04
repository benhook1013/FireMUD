package net.firedevops.firemud.gamesession.testsupport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.CrossServiceAppHarness;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.GameDesignStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared gameplay-oriented cross-service bootstrap fixture above the lower-level app harness. */
public final class GameplayCrossServiceStack implements AutoCloseable {
  static final String SYNTHETIC_LOAD_WORLD_PREFIX = "demo-player-";
  static final String SYNTHETIC_LOAD_ACTOR = "test/gameplay-load";

  private final AccountRuntimeStubServer accountStub;
  private final GameDesignStubServer gameDesignStub;
  private final WorldManagementStubServer worldStub;
  private final EntityManagementStubServer entityStub;
  private final SocialGroupsStubServer socialStub;
  private final net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse
      baselineRoomEntities;
  private final ListFriendPresenceResponse baselineFriendPresenceResponse;
  private final Map<Long, String> syntheticManagementOwnerUuidsBySelector = new HashMap<>();
  private CrossServiceAppHarness.GameLogicHolder gameLogic;
  private final CrossServiceAppHarness.GameSessionHolder gameSession;
  private final boolean useDefaultDemoCatalogFixture;

  private GameplayCrossServiceStack(
      AccountRuntimeStubServer accountStub,
      GameDesignStubServer gameDesignStub,
      WorldManagementStubServer worldStub,
      EntityManagementStubServer entityStub,
      SocialGroupsStubServer socialStub,
      net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse baselineRoomEntities,
      ListFriendPresenceResponse baselineFriendPresenceResponse,
      CrossServiceAppHarness.GameLogicHolder gameLogic,
      CrossServiceAppHarness.GameSessionHolder gameSession,
      boolean useDefaultDemoCatalogFixture) {
    this.accountStub = accountStub;
    this.gameDesignStub = gameDesignStub;
    this.worldStub = worldStub;
    this.entityStub = entityStub;
    this.socialStub = socialStub;
    this.baselineRoomEntities = baselineRoomEntities;
    this.baselineFriendPresenceResponse = baselineFriendPresenceResponse;
    this.gameLogic = gameLogic;
    this.gameSession = gameSession;
    this.useDefaultDemoCatalogFixture = useDefaultDemoCatalogFixture;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder defaultDemoBuilder(
      PostgreSQLContainer<?> postgres, GenericContainer<?> redis, String defaultAccountUuid) {
    Builder builder =
        builder()
            .withPostgres(
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword())
            .withRedis(redis.getHost(), redis.getMappedPort(6379))
            .withDefaultAccountUuid(defaultAccountUuid);
    builder.useDefaultDemoCatalogFixture = true;
    return builder;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Test stack intentionally exposes mutable stubs for scenario control.")
  public AccountRuntimeStubServer accountStub() {
    return accountStub;
  }

  public GameDesignStubServer gameDesignStub() {
    return gameDesignStub;
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
    Objects.requireNonNull(gameSession.bean(StringRedisTemplate.class).getConnectionFactory())
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
    entityStub.resetCharacters();
    entityStub.resetActorState();
    entityStub.resetItemState();
    if (socialStub != null) {
      socialStub.resetState();
      if (baselineFriendPresenceResponse != null) {
        socialStub.setFriendPresenceResponse(baselineFriendPresenceResponse);
      }
    }
    entityStub.resetSyntheticAccountCharacters();
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
      long managementOwnerSelector,
      long gameTemplateId,
      long... characterIds) {
    resetScenarioState();
    clearRedis();
    JdbcTemplate jdbc = jdbc();
    clearSyntheticAdmissionPointerState(jdbc);
    if (useDefaultDemoCatalogFixture) {
      clearDefaultDemoAdmissionBinding(jdbc, tenantId);
    }
    jdbc.execute("TRUNCATE TABLE runtime_region_status RESTART IDENTITY");
    jdbc.execute("TRUNCATE TABLE game_instances RESTART IDENTITY");
    if (characterIds.length > 0) {
      clearScreenBuffers(tenantId, gameplayInstanceId, characterIds);
    }
    long gameInstanceId =
        GameInstanceTestFixtures.insertRunningGameInstance(
            jdbc, tenantId, GameInstanceTestFixtures.TEST_OWNER_ACCOUNT_UUID, gameTemplateId);
    seedRuntimeOwnership(tenantId, gameInstanceId);
    if (useDefaultDemoCatalogFixture) {
      bindDefaultDemoPointer(tenantId, gameInstanceId, managementOwnerSelector, gameTemplateId);
    }
    return gameInstanceId;
  }

  private void clearDefaultDemoAdmissionBinding(JdbcTemplate jdbc, long tenantId) {
    String requestId = defaultDemoRequestId(tenantId);
    TransactionTemplate transaction =
        new TransactionTemplate(gameSession.bean(PlatformTransactionManager.class));
    DefaultDemoBindReset reset =
        transaction.execute(status -> clearDefaultDemoAdmissionBinding(jdbc, tenantId, requestId));
    worldStub.resetInitialAdmissionBindHold(
        tenantId,
        requestId,
        reset == null ? null : reset.requestDigest(),
        reset == null ? null : Long.toString(reset.gameInstanceId()),
        reset == null ? null : Long.toString(reset.versionId()),
        reset == null ? 0L : reset.activeLifecycleEpoch(),
        reset == null || reset.holdId() == null ? null : reset.holdId().toString(),
        reset == null || reset.holdFence() == null ? null : reset.holdFence().toString());
  }

  private static DefaultDemoBindReset clearDefaultDemoAdmissionBinding(
      JdbcTemplate jdbc, long tenantId, String requestId) {
    List<DefaultDemoBindReset> attempts =
        jdbc.query(
            """
            SELECT a.attempt_id, a.realm_id, a.playable_state_namespace_id, a.catalog_revision,
                   a.game_instance_id, a.version_id, a.active_lifecycle_epoch, a.request_digest,
                   a.playable_state_scope, a.expected_no_prior_pointer,
                   a.hold_id, a.hold_fence, a.status, a.pointer_id, a.audit_event_id,
                   c.realm_id AS catalog_realm_id,
                   c.playable_state_namespace_id AS catalog_namespace_id,
                   c.game_template_id AS catalog_game_template_id,
                   c.catalog_revision AS owner_catalog_revision,
                   c.world_slug AS catalog_world_slug,
                   c.world_display_name AS catalog_world_display_name,
                   c.realm_slug AS catalog_realm_slug,
                   c.realm_display_name AS catalog_realm_display_name,
                   c.visible AS catalog_visible,
                   c.public_production_realm AS catalog_public_production_realm,
                   c.requires_character_selection AS catalog_requires_character_selection,
                   c.state_scope AS catalog_state_scope,
                   c.character_creation_policy AS catalog_character_creation_policy
            FROM gameplay_initial_admission_bind_attempt a
            LEFT JOIN gameplay_initial_admission_bind_catalog c
              ON c.tenant_id = a.tenant_id AND c.realm_id = a.realm_id
            WHERE a.tenant_id = ? AND a.initial_admission_request_id = ?
            """,
            (row, rowNumber) ->
                new DefaultDemoBindReset(
                    row.getObject("attempt_id", UUID.class),
                    row.getObject("realm_id", UUID.class),
                    row.getObject("playable_state_namespace_id", UUID.class),
                    row.getLong("catalog_revision"),
                    row.getLong("game_instance_id"),
                    row.getLong("version_id"),
                    row.getLong("active_lifecycle_epoch"),
                    row.getString("request_digest"),
                    row.getString("playable_state_scope"),
                    row.getBoolean("expected_no_prior_pointer"),
                    row.getObject("hold_id", UUID.class),
                    row.getObject("hold_fence", UUID.class),
                    row.getString("status"),
                    row.getObject("pointer_id", Long.class),
                    row.getObject("audit_event_id", Long.class),
                    row.getObject("catalog_realm_id", UUID.class),
                    row.getObject("catalog_namespace_id", UUID.class),
                    row.getLong("catalog_game_template_id"),
                    row.getObject("owner_catalog_revision", Long.class),
                    row.getString("catalog_world_slug"),
                    row.getString("catalog_world_display_name"),
                    row.getString("catalog_realm_slug"),
                    row.getString("catalog_realm_display_name"),
                    row.getBoolean("catalog_visible"),
                    row.getBoolean("catalog_public_production_realm"),
                    row.getBoolean("catalog_requires_character_selection"),
                    row.getString("catalog_state_scope"),
                    row.getString("catalog_character_creation_policy")),
            tenantId,
            requestId);
    if (attempts.isEmpty()) {
      if (countRows(
                  jdbc,
                  "SELECT count(*) FROM gameplay_admission_pointer "
                      + "WHERE tenant_id = ? AND world_slug = 'demo' AND realm_slug = 'production'",
                  tenantId)
              != 0
          || countRows(
                  jdbc,
                  "SELECT count(*) FROM gameplay_admission_pointer_event "
                      + "WHERE tenant_id = ? AND world_slug = 'demo' AND realm_slug = 'production'",
                  tenantId)
              != 0) {
        throw new IllegalStateException(
            "default demo authority exists without the fixture-owned initial bind attempt");
      }
      return null;
    }
    if (attempts.size() != 1) {
      throw new IllegalStateException("test default demo reset found duplicate owner attempts");
    }
    DefaultDemoBindReset attempt = attempts.getFirst();
    requireOwnedDefaultDemoState(attempt);

    if (countRows(
            jdbc,
            "SELECT count(*) FROM gameplay_initial_admission_bind_attempt "
                + "WHERE tenant_id = ? AND realm_id = ?",
            tenantId,
            attempt.realmId())
        != 1) {
      throw new IllegalStateException(
          "test default demo catalog has other initial bind attempts and cannot be reset");
    }
    int pointerCount =
        countRows(
            jdbc,
            "SELECT count(*) FROM gameplay_admission_pointer "
                + "WHERE tenant_id = ? AND world_slug = 'demo' AND realm_slug = 'production'",
            tenantId);
    int auditCount =
        countRows(
            jdbc,
            "SELECT count(*) FROM gameplay_admission_pointer_event "
                + "WHERE tenant_id = ? AND world_slug = 'demo' AND realm_slug = 'production'",
            tenantId);
    if (pointerCount != (attempt.pointerId() == null ? 0 : 1)
        || auditCount != (attempt.auditEventId() == null ? 0 : 1)
        || !hasExactOwnedPointer(jdbc, tenantId, attempt)
        || !hasExactOwnedAudit(jdbc, tenantId, requestId, attempt)) {
      throw new IllegalStateException(
          "test default demo reset found pointer or audit evidence outside its owner attempt");
    }

    if (jdbc.update(
            "DELETE FROM gameplay_initial_admission_bind_attempt "
                + "WHERE tenant_id = ? AND attempt_id = ? AND initial_admission_request_id = ? "
                + "AND request_digest = ? AND realm_id = ? "
                + "AND playable_state_namespace_id = ? AND playable_state_scope = 'SHARED' "
                + "AND expected_no_prior_pointer AND catalog_revision = ? "
                + "AND game_instance_id = ? AND version_id = ? AND active_lifecycle_epoch = ? "
                + "AND hold_id IS NOT DISTINCT FROM ? AND hold_fence IS NOT DISTINCT FROM ? "
                + "AND status = ? AND pointer_id IS NOT DISTINCT FROM ? "
                + "AND audit_event_id IS NOT DISTINCT FROM ?",
            tenantId,
            attempt.attemptId(),
            requestId,
            attempt.requestDigest(),
            attempt.realmId(),
            attempt.playableStateNamespaceId(),
            attempt.catalogRevision(),
            attempt.gameInstanceId(),
            attempt.versionId(),
            attempt.activeLifecycleEpoch(),
            attempt.holdId(),
            attempt.holdFence(),
            attempt.status(),
            attempt.pointerId(),
            attempt.auditEventId())
        != 1) {
      throw new IllegalStateException("test default demo owner attempt changed during reset");
    }
    if (attempt.auditEventId() != null
        && jdbc.update(
                "DELETE FROM gameplay_admission_pointer_event "
                    + "WHERE id = ? AND tenant_id = ? AND world_slug = 'demo' "
                    + "AND world_display_name = 'Demo World' AND realm_slug = 'production' "
                    + "AND realm_display_name = 'Live Realm' AND game_instance_id = ? "
                    + "AND pointer_version = 1 AND catalog_revision = ? "
                    + "AND realm_id = ? AND playable_state_namespace_id = ? "
                    + "AND visible AND public_production_realm "
                    + "AND NOT requires_character_selection AND state_scope = 'SHARED' "
                    + "AND character_creation_policy = 'ALLOW_NEW' "
                    + "AND control_plane_request_id = ? "
                    + "AND actor_principal = 'game-session-initial-admission-bind' "
                    + "AND reason = 'initial admission pointer bind'",
                attempt.auditEventId(),
                tenantId,
                attempt.gameInstanceId(),
                attempt.catalogRevision(),
                attempt.realmId(),
                attempt.playableStateNamespaceId(),
                requestId)
            != 1) {
      throw new IllegalStateException("test default demo audit changed during reset");
    }
    if (attempt.pointerId() != null
        && jdbc.update(
                "DELETE FROM gameplay_admission_pointer "
                    + "WHERE id = ? AND tenant_id = ? AND world_slug = 'demo' "
                    + "AND world_display_name = 'Demo World' AND realm_slug = 'production' "
                    + "AND realm_display_name = 'Live Realm' AND game_instance_id = ? "
                    + "AND pointer_version = 1 AND catalog_revision = ? "
                    + "AND realm_id = ? AND playable_state_namespace_id = ? "
                    + "AND visible AND public_production_realm "
                    + "AND NOT requires_character_selection AND state_scope = 'SHARED' "
                    + "AND character_creation_policy = 'ALLOW_NEW' "
                    + "AND last_updated_by = 'game-session-initial-admission-bind' "
                    + "AND last_update_reason = 'initial admission pointer bind'",
                attempt.pointerId(),
                tenantId,
                attempt.gameInstanceId(),
                attempt.catalogRevision(),
                attempt.realmId(),
                attempt.playableStateNamespaceId())
            != 1) {
      throw new IllegalStateException("test default demo pointer changed during reset");
    }
    if (jdbc.update(
            "DELETE FROM gameplay_initial_admission_bind_catalog "
                + "WHERE tenant_id = ? AND realm_id = ? AND world_slug = 'demo' "
                + "AND game_template_id = ? AND world_display_name = 'Demo World' "
                + "AND realm_slug = 'production' AND realm_display_name = 'Live Realm' "
                + "AND catalog_revision = ? AND playable_state_namespace_id = ? "
                + "AND visible AND public_production_realm "
                + "AND NOT requires_character_selection AND state_scope = 'SHARED' "
                + "AND character_creation_policy = 'ALLOW_NEW'",
            tenantId,
            attempt.realmId(),
            attempt.catalogGameTemplateId(),
            attempt.catalogRevision(),
            attempt.playableStateNamespaceId())
        != 1) {
      throw new IllegalStateException("test default demo owner catalog changed during reset");
    }
    return attempt;
  }

  private static void requireOwnedDefaultDemoState(DefaultDemoBindReset attempt) {
    if (attempt.attemptId() == null
        || attempt.realmId() == null
        || attempt.playableStateNamespaceId() == null
        || attempt.catalogRevision() <= 0L
        || attempt.gameInstanceId() <= 0L
        || attempt.versionId() <= 0L
        || attempt.activeLifecycleEpoch() <= 0L
        || (attempt.holdId() == null) != (attempt.holdFence() == null)
        || attempt.catalogRealmId() == null
        || !attempt.realmId().equals(attempt.catalogRealmId())
        || !attempt.playableStateNamespaceId().equals(attempt.catalogNamespaceId())
        || attempt.catalogGameTemplateId() <= 0L
        || attempt.ownerCatalogRevision() == null
        || attempt.catalogRevision() != attempt.ownerCatalogRevision()
        || !"SHARED".equals(attempt.playableStateScope())
        || !attempt.expectedNoPriorPointer()
        || !"demo".equals(attempt.catalogWorldSlug())
        || !"Demo World".equals(attempt.catalogWorldDisplayName())
        || !"production".equals(attempt.catalogRealmSlug())
        || !"Live Realm".equals(attempt.catalogRealmDisplayName())
        || !attempt.catalogVisible()
        || !attempt.catalogPublicProductionRealm()
        || attempt.catalogRequiresCharacterSelection()
        || !"SHARED".equals(attempt.catalogStateScope())
        || !"ALLOW_NEW".equals(attempt.catalogCharacterCreationPolicy())
        || !("PENDING".equals(attempt.status())
            || "COMMITTED".equals(attempt.status())
            || "ABORTED".equals(attempt.status()))
        || (attempt.pointerId() == null) != (attempt.auditEventId() == null)
        || ("COMMITTED".equals(attempt.status()) != (attempt.pointerId() != null))) {
      throw new IllegalStateException("test default demo reset found mismatched owner state");
    }
  }

  private static boolean hasExactOwnedPointer(
      JdbcTemplate jdbc, long tenantId, DefaultDemoBindReset attempt) {
    if (attempt.pointerId() == null) {
      return true;
    }
    return countRows(
            jdbc,
            "SELECT count(*) FROM gameplay_admission_pointer "
                + "WHERE id = ? AND tenant_id = ? AND world_slug = 'demo' "
                + "AND world_display_name = 'Demo World' AND realm_slug = 'production' "
                + "AND realm_display_name = 'Live Realm' AND game_instance_id = ? "
                + "AND pointer_version = 1 AND catalog_revision = ? "
                + "AND realm_id = ? AND playable_state_namespace_id = ? "
                + "AND visible AND public_production_realm AND NOT requires_character_selection "
                + "AND state_scope = 'SHARED' AND character_creation_policy = 'ALLOW_NEW' "
                + "AND last_updated_by = 'game-session-initial-admission-bind' "
                + "AND last_update_reason = 'initial admission pointer bind'",
            attempt.pointerId(),
            tenantId,
            attempt.gameInstanceId(),
            attempt.catalogRevision(),
            attempt.realmId(),
            attempt.playableStateNamespaceId())
        == 1;
  }

  private static boolean hasExactOwnedAudit(
      JdbcTemplate jdbc, long tenantId, String requestId, DefaultDemoBindReset attempt) {
    if (attempt.auditEventId() == null) {
      return true;
    }
    return countRows(
            jdbc,
            "SELECT count(*) FROM gameplay_admission_pointer_event "
                + "WHERE id = ? AND tenant_id = ? AND world_slug = 'demo' "
                + "AND realm_slug = 'production' AND game_instance_id = ? "
                + "AND pointer_version = 1 AND catalog_revision = ? "
                + "AND realm_id = ? AND playable_state_namespace_id = ? "
                + "AND control_plane_request_id = ? "
                + "AND actor_principal = 'game-session-initial-admission-bind' "
                + "AND reason = 'initial admission pointer bind' "
                + "AND visible AND public_production_realm AND NOT requires_character_selection "
                + "AND state_scope = 'SHARED' AND character_creation_policy = 'ALLOW_NEW'",
            attempt.auditEventId(),
            tenantId,
            attempt.gameInstanceId(),
            attempt.catalogRevision(),
            attempt.realmId(),
            attempt.playableStateNamespaceId(),
            requestId)
        == 1;
  }

  private static int countRows(JdbcTemplate jdbc, String sql, Object... parameters) {
    Integer count = jdbc.queryForObject(sql, Integer.class, parameters);
    return count == null ? 0 : count;
  }

  private static String defaultDemoRequestId(long tenantId) {
    return "cross-service-default-demo-" + tenantId;
  }

  private void bindDefaultDemoPointer(
      long tenantId, long gameInstanceId, long ownerAccountId, long gameTemplateId) {
    InitialAdmissionBindOwnerService owner =
        gameSession.bean(InitialAdmissionBindOwnerService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        gameSession.bean(GameplayAdmissionPointerAuthorityService.class);
    InitialAdmissionBindCatalogDescriptor descriptor =
        new InitialAdmissionBindCatalogDescriptor(
            tenantId, gameTemplateId, "demo", "Demo World", "production", "Live Realm", false);
    InitialAdmissionBindCatalog catalog = owner.registerPublicSharedFixtureCatalog(descriptor);
    long versionId =
        Objects.requireNonNull(
            jdbc()
                .queryForObject(
                    "SELECT version_id FROM game_instances WHERE tenant_id = ? AND id = ?",
                    Long.class,
                    tenantId,
                    gameInstanceId),
            "test target versionId must exist");
    long activeLifecycleEpoch = 1L;
    String requestId = "cross-service-default-demo-" + tenantId;
    String requestDigest =
        defaultDemoRequestDigest(
            requestId,
            tenantId,
            gameTemplateId,
            ownerAccountId,
            catalog,
            gameInstanceId,
            versionId,
            activeLifecycleEpoch);
    InitialAdmissionBindRequest request =
        new InitialAdmissionBindRequest(
            tenantId,
            descriptor.worldSlug(),
            descriptor.realmSlug(),
            requestId,
            requestDigest,
            gameInstanceId,
            versionId,
            activeLifecycleEpoch);
    owner.beginIntent(request);

    AcquireInitialAdmissionBindHoldRequest holdRequest =
        AcquireInitialAdmissionBindHoldRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setGameInstanceId(Long.toString(gameInstanceId))
            .setVersionId(Long.toString(versionId))
            .setExpectedActiveLifecycleEpoch(activeLifecycleEpoch)
            .setInitialAdmissionRequestId(requestId)
            .setRequestDigest(requestDigest)
            .setRealmUuid(catalog.realmId().toString())
            .setPlayableStateNamespaceUuid(catalog.playableStateNamespaceId().toString())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setExpectedNoPriorPointer(true)
            .setExpectedCatalogRevision(catalog.catalogRevision())
            .build();
    AcquireInitialAdmissionBindHoldResponse holdResponse =
        acquireInitialAdmissionBindHold(holdRequest);
    if (!holdResponse.hasHold()) {
      throw new IllegalStateException(
          "test World stub did not return the requested initial admission hold");
    }
    InitialAdmissionBindHold hold = holdResponse.getHold();
    InitialAdmissionBindHoldBinding binding =
        requireDefaultDemoHold(
            hold, request, catalog, tenantId, gameInstanceId, versionId, activeLifecycleEpoch);
    owner.attachHold(binding);
    InitialAdmissionBindOwnerProof proof = owner.commit(binding);
    requireDefaultDemoProof(proof, binding);
    verifyDefaultDemoPointer(pointerAuthority, catalog, request, gameInstanceId);
  }

  private AcquireInitialAdmissionBindHoldResponse acquireInitialAdmissionBindHold(
      AcquireInitialAdmissionBindHoldRequest request) {
    ManagedChannel channel =
        ManagedChannelBuilder.forTarget(worldStub.endpoint()).usePlaintext().build();
    try {
      return WorldManagementServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3L, TimeUnit.SECONDS)
          .acquireInitialAdmissionBindHold(request);
    } finally {
      channel.shutdownNow();
    }
  }

  private static InitialAdmissionBindHoldBinding requireDefaultDemoHold(
      InitialAdmissionBindHold hold,
      InitialAdmissionBindRequest request,
      InitialAdmissionBindCatalog catalog,
      long tenantId,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch) {
    if (!isCanonicalUuid(hold.getHoldId())
        || !isCanonicalUuid(hold.getHoldFence())
        || !Long.toString(tenantId).equals(hold.getTenantId())
        || !catalog.realmId().toString().equals(hold.getRealmUuid())
        || !catalog
            .playableStateNamespaceId()
            .toString()
            .equals(hold.getPlayableStateNamespaceUuid())
        || hold.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !Long.toString(gameInstanceId).equals(hold.getGameInstanceId())
        || !Long.toString(versionId).equals(hold.getVersionId())
        || hold.getActiveLifecycleEpoch() != activeLifecycleEpoch
        || !request.initialAdmissionRequestId().equals(hold.getInitialAdmissionRequestId())
        || !request.requestDigest().equals(hold.getRequestDigest())
        || !hold.getExpectedNoPriorPointer()
        || hold.getExpectedCatalogRevision() != catalog.catalogRevision()) {
      throw new IllegalStateException(
          "test World stub returned a mismatched initial admission hold");
    }
    return new InitialAdmissionBindHoldBinding(
        hold.getHoldId(),
        hold.getHoldFence(),
        tenantId,
        hold.getRealmUuid(),
        hold.getPlayableStateNamespaceUuid(),
        "SHARED",
        gameInstanceId,
        versionId,
        activeLifecycleEpoch,
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        true,
        catalog.catalogRevision());
  }

  private static void requireDefaultDemoProof(
      InitialAdmissionBindOwnerProof proof, InitialAdmissionBindHoldBinding binding) {
    if (proof == null
        || proof.outcome() != InitialAdmissionBindOwnerProof.Outcome.COMMITTED
        || !binding.holdId().equals(proof.holdId())
        || !binding.holdFence().equals(proof.holdFence())
        || proof.tenantId() != binding.tenantId()
        || !binding.realmUuid().equals(proof.realmUuid())
        || !binding.playableStateNamespaceUuid().equals(proof.playableStateNamespaceUuid())
        || !binding.playableStateScope().equals(proof.playableStateScope())
        || proof.gameInstanceId() != binding.gameInstanceId()
        || proof.versionId() != binding.versionId()
        || proof.activeLifecycleEpoch() != binding.activeLifecycleEpoch()
        || !binding.initialAdmissionRequestId().equals(proof.initialAdmissionRequestId())
        || !binding.requestDigest().equals(proof.requestDigest())
        || proof.expectedNoPriorPointer() != binding.expectedNoPriorPointer()
        || proof.expectedCatalogRevision() != binding.expectedCatalogRevision()
        || !binding.requestDigest().equals(proof.pointerAuditRequestDigest())
        || proof.pointerVersion() != 1L
        || !isPositiveLong(proof.pointerAuditId())) {
      throw new IllegalStateException("test owner did not prove the exact committed initial bind");
    }
  }

  private static void verifyDefaultDemoPointer(
      GameplayAdmissionPointerAuthorityService pointerAuthority,
      InitialAdmissionBindCatalog catalog,
      InitialAdmissionBindRequest request,
      long gameInstanceId) {
    GameplayAdmissionPointerSnapshot pointer =
        pointerAuthority
            .findPointer(request.tenantId(), request.worldSlug(), request.realmSlug())
            .orElseThrow(
                () -> new IllegalStateException("test initial bind did not persist its pointer"));
    if (pointer.gameInstanceId() != gameInstanceId
        || pointer.pointerVersion() != 1L
        || pointer.catalogRevision() != catalog.catalogRevision()
        || !catalog.realmId().equals(pointer.realmId())
        || !catalog.playableStateNamespaceId().equals(pointer.playableStateNamespaceId())
        || !pointer.visible()
        || !pointer.publicProductionRealm()
        || !"SHARED".equals(pointer.stateScope())) {
      throw new IllegalStateException("test default demo pointer does not match its owner catalog");
    }
  }

  private static String defaultDemoRequestDigest(
      String operationId,
      long tenantId,
      long gameTemplateId,
      long ownerAccountId,
      InitialAdmissionBindCatalog catalog,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch) {
    StringBuilder canonical = new StringBuilder();
    appendDigestField(canonical, "domain", "firemud.run-owned-initial-admission-bind.v1");
    appendDigestField(canonical, "operationId", operationId);
    appendDigestField(canonical, "tenantId", Long.toString(tenantId));
    appendDigestField(canonical, "gameTemplateId", Long.toString(gameTemplateId));
    appendDigestField(canonical, "ownerAccountId", Long.toString(ownerAccountId));
    appendDigestField(canonical, "worldSlug", "demo");
    appendDigestField(canonical, "worldDisplayName", "Demo World");
    appendDigestField(canonical, "realmSlug", "production");
    appendDigestField(canonical, "realmDisplayName", "Live Realm");
    appendDigestField(canonical, "requiresCharacterSelection", "false");
    appendDigestField(canonical, "realmId", catalog.realmId().toString());
    appendDigestField(
        canonical, "playableStateNamespaceId", catalog.playableStateNamespaceId().toString());
    appendDigestField(canonical, "playableStateScope", "SHARED");
    appendDigestField(canonical, "expectedNoPriorPointer", "true");
    appendDigestField(canonical, "gameInstanceId", Long.toString(gameInstanceId));
    appendDigestField(canonical, "versionId", Long.toString(versionId));
    appendDigestField(canonical, "activeLifecycleEpoch", Long.toString(activeLifecycleEpoch));
    appendDigestField(canonical, "catalogRevision", Long.toString(catalog.catalogRevision()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void appendDigestField(StringBuilder canonical, String name, String value) {
    canonical
        .append(name.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(name)
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value);
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isPositiveLong(String value) {
    try {
      return Long.parseLong(value) > 0L;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private record DefaultDemoBindReset(
      UUID attemptId,
      UUID realmId,
      UUID playableStateNamespaceId,
      long catalogRevision,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch,
      String requestDigest,
      String playableStateScope,
      boolean expectedNoPriorPointer,
      UUID holdId,
      UUID holdFence,
      String status,
      Long pointerId,
      Long auditEventId,
      UUID catalogRealmId,
      UUID catalogNamespaceId,
      long catalogGameTemplateId,
      Long ownerCatalogRevision,
      String catalogWorldSlug,
      String catalogWorldDisplayName,
      String catalogRealmSlug,
      String catalogRealmDisplayName,
      boolean catalogVisible,
      boolean catalogPublicProductionRealm,
      boolean catalogRequiresCharacterSelection,
      String catalogStateScope,
      String catalogCharacterCreationPolicy) {}

  private void clearSyntheticAdmissionPointerState(JdbcTemplate jdbc) {
    // Audit rows are removed first so this remains safe if a future schema adds an FK to the
    // current pointer row. The world prefix is reserved for this fixture; keep canonical demo and
    // sandbox authority untouched.
    jdbc.update(
        "DELETE FROM gameplay_admission_pointer_event " + "WHERE world_slug LIKE ?",
        SYNTHETIC_LOAD_WORLD_PREFIX + "%");
    jdbc.update(
        "DELETE FROM gameplay_admission_pointer " + "WHERE world_slug LIKE ?",
        SYNTHETIC_LOAD_WORLD_PREFIX + "%");
  }

  private void seedRuntimeOwnership(long tenantId, long gameInstanceId) {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(tenantId);
    status.setGameInstanceId(gameInstanceId);
    // Match the runtime owner boundary used by TickQueueControlService. Keeping the fixture on
    // the canonical game-instance region prevents a late command from an earlier baseline from
    // becoming permanently stale when the shared test database is reset between scenarios.
    status.setRegionId(Long.toString(gameInstanceId));
    status.setRegionEpoch(1L);
    status.setExecutorFence("cross-service-fence-" + gameInstanceId);
    status.setOwnerService("game-session-cross-service-test");
    status.setOwnerInstanceId("game-session-cross-service-test-1");
    status.setPaused(false);
    status.setLastCommittedTickId(0L);
    status.setUpdatedAt(Instant.now());
    gameSession.bean(RuntimeRegionStatusRepository.class).save(status);
  }

  public void seedLiveSession(SessionContext context) {
    gameSession.bean(SessionContextService.class).save(context);
  }

  public void seedLiveSession(
      long sessionId,
      long tenantId,
      String accountId,
      String loginName,
      long characterId,
      String characterName,
      long gameInstanceId,
      String roomInstanceId,
      String jwt) {
    seedLiveSession(
        new SessionContext(
            sessionId,
            tenantId,
            accountId,
            loginName,
            characterId,
            characterName,
            gameInstanceId,
            roomInstanceId,
            jwt));
  }

  public void seedLiveSession(
      long sessionId,
      long tenantId,
      String accountId,
      String loginName,
      long characterId,
      String characterName,
      long gameInstanceId,
      String roomInstanceId,
      String jwt,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      String playableStateScope) {
    seedLiveSession(
        new SessionContext(
            sessionId,
            tenantId,
            accountId,
            loginName,
            characterId,
            characterName,
            gameInstanceId,
            roomInstanceId,
            jwt,
            null,
            gameInstanceId,
            worldSlug,
            realmSlug,
            pointerVersion,
            playableStateScope));
  }

  public long insertRunningGameInstance(
      long tenantId, long managementOwnerSelector, long gameTemplateId, boolean clearExisting) {
    JdbcTemplate jdbc = jdbc();
    if (clearExisting) {
      clearRedis();
      jdbc.update("DELETE FROM runtime_region_status");
      jdbc.update("DELETE FROM game_instances");
    }
    long gameInstanceId =
        GameInstanceTestFixtures.insertRunningGameInstance(
            jdbc,
            tenantId,
            syntheticManagementOwnerUuidsBySelector.computeIfAbsent(
                managementOwnerSelector, ignored -> UUID.randomUUID().toString()),
            gameTemplateId);
    seedRuntimeOwnership(tenantId, gameInstanceId);
    return gameInstanceId;
  }

  @Override
  public synchronized void close() {
    RuntimeException firstFailure = null;
    try {
      clearSyntheticAdmissionPointerState(jdbc());
    } catch (RuntimeException failure) {
      firstFailure = failure;
    }
    firstFailure = closeResource(() -> gameSession.close(), firstFailure);
    firstFailure = closeResource(() -> gameLogic.close(), firstFailure);
    firstFailure =
        closeResource(
            () -> {
              if (socialStub != null) {
                socialStub.close();
              }
            },
            firstFailure);
    firstFailure = closeResource(() -> entityStub.close(), firstFailure);
    firstFailure = closeResource(() -> worldStub.close(), firstFailure);
    firstFailure = closeResource(() -> gameDesignStub.close(), firstFailure);
    firstFailure = closeResource(() -> accountStub.close(), firstFailure);
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  private static RuntimeException closeResource(
      Runnable closeAction, RuntimeException firstFailure) {
    try {
      closeAction.run();
    } catch (RuntimeException failure) {
      if (firstFailure == null) {
        return failure;
      }
      firstFailure.addSuppressed(failure);
    }
    return firstFailure;
  }

  public static final class Builder {
    private String postgresHost;
    private int postgresPort;
    private String postgresDatabase;
    private String postgresUsername;
    private String postgresPassword;
    private String redisHost;
    private int redisPort;
    private String defaultAccountUuid = "a7e0feac-60ab-4fd1-9002-0ad38d585db0";
    private String defaultRoomId = LookTestFixtures.ROOM_ID;
    private net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse initialRoomEntities;
    private ListFriendPresenceResponse initialFriendPresenceResponse;
    private boolean includeSocial;
    private final Map<String, String> accountUuidMappings = new LinkedHashMap<>();
    private final Map<String, Object> gameLogicProps = new LinkedHashMap<>();
    private final Map<String, Object> gameSessionProps = new LinkedHashMap<>();
    private Class<?>[] gameLogicConfigs = new Class<?>[0];
    private Class<?>[] gameSessionConfigs = new Class<?>[0];
    private boolean useDefaultDemoCatalogFixture;

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

    public Builder withDefaultAccountUuid(String accountUuid) {
      this.defaultAccountUuid = accountUuid;
      return this;
    }

    public Builder mapAccountUuid(String email, String accountUuid) {
      this.accountUuidMappings.put(email, accountUuid);
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

    public Builder withInitialFriendPresenceResponse(ListFriendPresenceResponse response) {
      this.initialFriendPresenceResponse = response == null ? null : response.toBuilder().build();
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
      accountStub.setDefaultAccountUuid(defaultAccountUuid);
      accountUuidMappings.forEach(accountStub::mapAccountUuid);

      GameDesignStubServer gameDesignStub = new GameDesignStubServer(0);
      WorldManagementStubServer worldStub = new WorldManagementStubServer(0);
      EntityManagementStubServer entityStub = new EntityManagementStubServer(0);
      if (initialRoomEntities != null) {
        entityStub.setRoomEntities(initialRoomEntities);
      }
      SocialGroupsStubServer socialStub = includeSocial ? new SocialGroupsStubServer(0) : null;
      if (socialStub != null && initialFriendPresenceResponse != null) {
        socialStub.setFriendPresenceResponse(initialFriendPresenceResponse);
      }

      Class<?>[] selectedGameSessionConfigs = gameSessionConfigs;
      if (useDefaultDemoCatalogFixture) {
        selectedGameSessionConfigs =
            java.util.Arrays.copyOf(gameSessionConfigs, gameSessionConfigs.length + 1);
        selectedGameSessionConfigs[gameSessionConfigs.length] =
            CrossServiceAppHarness.DefaultDemoCatalogTestOverrides.class;
      }

      CrossServiceAppHarness.GameLogicHolder gameLogic =
          CrossServiceAppHarness.startGameLogic(
              0,
              worldStub.endpoint(),
              entityStub.endpoint(),
              socialStub == null ? null : socialStub.endpoint(),
              props -> {
                props.put("firemud.services.gameDesignService", gameDesignStub.endpoint());
                props.putAll(gameLogicProps);
              },
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
                props.put("firemud.services.gameDesignService", gameDesignStub.endpoint());
                props.put("firemud.services.entityManagementService", entityStub.endpoint());
                if (socialStub != null) {
                  props.put("firemud.services.socialGroupsService", socialStub.endpoint());
                }
                props.putAll(gameSessionProps);
              },
              selectedGameSessionConfigs);

      return new GameplayCrossServiceStack(
          accountStub,
          gameDesignStub,
          worldStub,
          entityStub,
          socialStub,
          initialRoomEntities,
          initialFriendPresenceResponse,
          gameLogic,
          gameSession,
          useDefaultDemoCatalogFixture);
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
