package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.InMemorySessionContextTestConfiguration;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SuppressWarnings({"removal"})
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "game-session.require-authenticated-commands=true",
      "firemud.database.enabled=false",
      "spring.task.scheduling.enabled=false",
      "spring.data.redis.repositories.enabled=false",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0"
    })
@Import({NoGrpcServerTestConfiguration.class, InMemorySessionContextTestConfiguration.class})
class GameSessionLoginIntegrationTest {
  @LocalServerPort private int port;

  @MockitoBean private AccountClient accountClient;
  @MockitoBean private GameInstanceRepository gameInstanceRepository;
  @MockitoBean private CommandService commandService;

  @MockitoBean
  private GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  @MockitoBean
  private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

  @MockitoBean
  private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

  @MockitoBean
  private org.springframework.data.redis.core.ValueOperations<String, Object> redisValueOperations;

  @Autowired private SessionContextService sessionContextService;

  private final ConcurrentMap<String, Object> redisValueStore = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    redisValueStore.clear();
    when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
    when(redisValueOperations.get(anyString()))
        .thenAnswer(invocation -> redisValueStore.get(invocation.getArgument(0)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              redisValueStore.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(redisValueOperations)
        .set(
            anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              redisValueStore.remove(invocation.getArgument(0));
              return null;
            })
        .when(redisTemplate)
        .delete(anyString());
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken("stub-token").setAccountId("7").build());
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(42L);
    instance.setOwnerAccountId(7L);
    when(gameInstanceRepository.findById(anyLong())).thenReturn(Optional.of(instance));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(42L, 1L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    42L,
                    1L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
  }

  @Test
  void loginCallsAccountServiceAndReturnsOk() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connect(
            URI.create("ws://localhost:" + port + "/ws/game"),
            java.time.Duration.ofSeconds(5),
            java.util.Map.of("X-Game-Instance-Id", "1", "X-Tenant-Id", "42"))) {
      client.login("demo@example.com", "swordfish");
      payloads = client.responses();
    }

    assertThat(payloads).anyMatch(s -> s.startsWith("OK LOGIN"));
    assertThat(sessionContextService.findByTenantAndSessionId(42L, 1L)).isPresent();

    ArgumentCaptor<String> tenantCaptor = ArgumentCaptor.forClass(String.class);
    verify(accountClient)
        .authenticate(tenantCaptor.capture(), eq("demo@example.com"), eq("swordfish"), eq(""));
    assertThat(tenantCaptor.getValue()).isEqualTo("42");
  }
}
