package net.firedevops.firemud.socialgroups;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import net.firedevops.firemud.socialgroups.client.AccountClient;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import net.firedevops.firemud.socialgroups.client.ModerationPolicyClient;
import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.repository.ChatMessageRepository;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = {
      SocialGroupsServiceApplication.class,
      SocialGroupsApplicationIntegrationTest.MockClientsConfiguration.class
    },
    properties = {
      "spring.profiles.active=test",
      "spring.grpc.server.port=0",
      "spring.grpc.server.ssl.enabled=false",
      "FIREMUD_GRPC_CERT_CHAIN_PATH=classpath:certs/dev-cert.pem",
      "FIREMUD_GRPC_PRIVATE_KEY_PATH=classpath:certs/dev-key.pem",
      "FIREMUD_GRPC_CA_CERT_PATH=classpath:certs/dev-ca.pem",
      "firemud.grpc.plaintext=true",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
@Import(NoGrpcServerTestConfiguration.class)
class SocialGroupsApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final JwtUtil JWT_UTIL =
      new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 3600000L);
  private static final LoggingAdminClient TEST_LOGGING_ADMIN_CLIENT =
      Mockito.mock(LoggingAdminClient.class);
  private static final ModerationPolicyClient TEST_MODERATION_POLICY_CLIENT =
      Mockito.mock(ModerationPolicyClient.class);
  private static final GameSessionClient TEST_GAME_SESSION_CLIENT =
      Mockito.mock(GameSessionClient.class);
  private static final AccountClient TEST_ACCOUNT_CLIENT = Mockito.mock(AccountClient.class);
  private static final SocialAccessGuard TEST_SOCIAL_ACCESS_GUARD =
      Mockito.mock(SocialAccessGuard.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "social_groups_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;

  @Autowired private ChatService chatService;

  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private AccountFriendLinkRepository accountFriendLinkRepository;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  @BeforeEach
  void setUpClients() {
    Mockito.reset(
        TEST_LOGGING_ADMIN_CLIENT,
        TEST_MODERATION_POLICY_CLIENT,
        TEST_GAME_SESSION_CLIENT,
        TEST_ACCOUNT_CLIENT,
        TEST_SOCIAL_ACCESS_GUARD);
    Mockito.when(
            TEST_MODERATION_POLICY_CLIENT.evaluateChatSend(Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(EvaluateModerationPolicyResponse.newBuilder().setAllowed(true).build());
    Mockito.when(
            TEST_ACCOUNT_CLIENT.getPresenceVisibilityPolicies(
                Mockito.anyLong(), Mockito.anyCollection()))
        .thenAnswer(
            invocation -> {
              java.util.Collection<Long> accountIds = invocation.getArgument(1);
              return accountIds.stream()
                  .collect(
                      java.util.stream.Collectors.toMap(
                          accountId -> accountId,
                          ignored ->
                              net.firedevops.firemud.socialgroups.dto
                                  .FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY));
            });
    Mockito.when(TEST_ACCOUNT_CLIENT.getPresenceVisibilityPolicy(1L, 2L))
        .thenReturn(
            java.util.Optional.of(
                net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue
                    .FRIENDS_ONLY));
    accountFriendLinkRepository.deleteAll();
  }

  @Test
  void pingEndpointReturnsPong() throws Exception {
    String token =
        JWT_UTIL.generateToken(
            "social-groups-test", Map.of("globalRoles", List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ping"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("pong");
  }

  @Test
  void friendRosterRejectsMalformedTenantIdAsInvalidArgument() throws Exception {
    String token = privilegedAccountToken(2L);

    HttpResponse<String> response =
        send(authedGet(token, "http://localhost:" + port + "/friends?tenantId=bad&accountId=2"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be numeric\"");
  }

  @Test
  void friendRosterRejectsMalformedFilterAsInvalidArgument() throws Exception {
    String token = privilegedAccountToken(2L);

    HttpResponse<String> response =
        send(
            authedGet(
                token,
                "http://localhost:"
                    + port
                    + "/friends?tenantId=1&accountId=2&filter=NOT_A_FILTER"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"filter is invalid\"");
  }

  @Test
  void duplicateEffectIdReturnsExistingChatMessageWithoutRepublishing() {
    SendMessageRequestDto request =
        new SendMessageRequestDto(
            1L, 2L, ChatType.SAY, null, 2L, null, null, "hello there", "fx-comm-42");

    ChatMessageDto first = chatService.sendMessage(request);
    ChatMessageDto replay = chatService.sendMessage(request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(chatMessageRepository.findByTenantIdAndEffectId(1L, "fx-comm-42"))
        .hasValueSatisfying(message -> assertThat(message.getId()).isEqualTo(first.id()));

    List<Object> redisRange = redisTemplate.opsForList().range("chat:say:1:2", 0, -1);
    List<Object> cachedMessages = new ArrayList<>(redisRange == null ? List.of() : redisRange);
    assertThat(cachedMessages).containsExactly("hello there");
  }

  @Test
  void friendRosterAndPresenceEndpointsReturnCanonicalEmbeddedPresence() throws Exception {
    String token = privilegedAccountToken(2L);
    HttpResponse<String> addResponse =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId":1,"accountId":2,"friendAccountId":3}
                        """))
                .build());
    assertThat(addResponse.statusCode()).isEqualTo(200);

    Mockito.when(TEST_GAME_SESSION_CLIENT.queryAccountPresence(1L, 2L, List.of(3L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setGameInstanceId("9")
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo")
                        .setWorldDisplayName("Demo World")
                        .setRealmSlug("production")
                        .setRealmDisplayName("Live Realm")
                        .setPointerVersion(17)
                        .setCharacterId("99")
                        .setCharacterName("Sora")
                        .setActivityState(
                            AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                        .setRecentDisposition(
                            net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition
                                .ACCOUNT_RECENT_PRESENCE_DISPOSITION_LOGOUT)
                        .build())
                .build());

    HttpResponse<String> rosterResponse =
        send(authedGet(token, "http://localhost:" + port + "/friends?tenantId=1&accountId=2"));
    assertThat(rosterResponse.statusCode()).isEqualTo(200);
    assertThat(rosterResponse.body()).contains("\"friendAccountId\":3");
    assertThat(rosterResponse.body()).contains("\"status\":\"active\"");
    assertThat(rosterResponse.body()).contains("\"presence\"");
    assertThat(rosterResponse.body()).contains("\"characterName\":\"Sora\"");
    assertThat(rosterResponse.body()).contains("\"playableStateScope\":\"SHARED\"");

    HttpResponse<String> presenceResponse =
        send(
            authedGet(
                token, "http://localhost:" + port + "/friends/presence?tenantId=1&accountId=2"));
    assertThat(presenceResponse.statusCode()).isEqualTo(200);
    assertThat(presenceResponse.body()).contains("\"friendAccountId\":3");
    assertThat(presenceResponse.body()).contains("\"worldSlug\":\"demo\"");
    assertThat(presenceResponse.body()).contains("\"characterName\":\"Sora\"");

    HttpResponse<String> detailResponse =
        send(authedGet(token, "http://localhost:" + port + "/friends/3?tenantId=1&accountId=2"));
    assertThat(detailResponse.statusCode()).isEqualTo(200);
    assertThat(detailResponse.body()).contains("\"friendLinkId\"");
    assertThat(detailResponse.body()).contains("\"friendAccountId\":3");
    assertThat(detailResponse.body()).contains("\"characterName\":\"Sora\"");

    HttpResponse<String> ordinalDetailResponse =
        send(
            authedGet(
                token, "http://localhost:" + port + "/friends/entry/1?tenantId=1&accountId=2"));
    assertThat(ordinalDetailResponse.statusCode()).isEqualTo(200);
    assertThat(ordinalDetailResponse.body()).contains("\"ordinal\":1");
    assertThat(ordinalDetailResponse.body()).contains("\"friendAccountId\":3");
  }

  @Test
  void friendRosterEndpointFallsBackToOfflineWhenPresenceIsUnavailable() throws Exception {
    String token = privilegedAccountToken(2L);
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build());

    Mockito.when(TEST_GAME_SESSION_CLIENT.queryAccountPresence(1L, 2L, List.of(3L)))
        .thenReturn(QueryAccountPresenceResponse.newBuilder().build());

    HttpResponse<String> rosterResponse =
        send(authedGet(token, "http://localhost:" + port + "/friends?tenantId=1&accountId=2"));

    assertThat(rosterResponse.statusCode()).isEqualTo(200);
    assertThat(rosterResponse.body()).contains("\"friendAccountId\":3");
    assertThat(rosterResponse.body()).contains("\"online\":false");
  }

  @Test
  void friendRosterEndpointSupportsCanonicalOnlineFilter() throws Exception {
    String token = privilegedAccountToken(2L);
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build());
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":4}
                    """))
            .build());

    Mockito.when(TEST_GAME_SESSION_CLIENT.queryAccountPresence(1L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder().setAccountId("3").setOnline(true).build())
                .addPresences(
                    AccountPresenceEntry.newBuilder().setAccountId("4").setOnline(false).build())
                .build());

    HttpResponse<String> response =
        send(
            authedGet(
                token,
                "http://localhost:" + port + "/friends?tenantId=1&accountId=2&filter=ONLINE"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"filter\":\"ONLINE\"");
    assertThat(response.body()).contains("\"totalCount\":2");
    assertThat(response.body()).contains("\"matchCount\":1");
    assertThat(response.body()).contains("\"friendAccountId\":3");
    assertThat(response.body()).doesNotContain("\"friendAccountId\":4");
  }

  @Test
  void friendRosterEndpointSupportsCanonicalSharedScopeFilter() throws Exception {
    String token = privilegedAccountToken(2L);
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build());
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":4}
                    """))
            .build());

    Mockito.when(TEST_GAME_SESSION_CLIENT.queryAccountPresence(1L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(true)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .build())
                .build());

    HttpResponse<String> response =
        send(
            authedGet(
                token,
                "http://localhost:" + port + "/friends?tenantId=1&accountId=2&filter=SHARED"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"filter\":\"SHARED\"");
    assertThat(response.body()).contains("\"totalCount\":2");
    assertThat(response.body()).contains("\"matchCount\":1");
    assertThat(response.body()).contains("\"friendAccountId\":3");
    assertThat(response.body()).doesNotContain("\"friendAccountId\":4");
  }

  @Test
  void friendRosterSummaryEndpointReturnsCanonicalCounts() throws Exception {
    String token = privilegedAccountToken(2L);
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build());
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":4}
                    """))
            .build());

    Mockito.when(TEST_GAME_SESSION_CLIENT.queryAccountPresence(1L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder().setAccountId("3").setOnline(true).build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(false)
                        .setLastSeenAtMs(1_744_353_730_000L)
                        .build())
                .build());

    HttpResponse<String> response =
        send(
            authedGet(
                token, "http://localhost:" + port + "/friends/summary?tenantId=1&accountId=2"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"totalCount\":2");
    assertThat(response.body()).contains("\"onlineCount\":1");
    assertThat(response.body()).contains("\"offlineCount\":1");
    assertThat(response.body()).contains("\"recentCount\":1");
    assertThat(response.body()).contains("\"publicCount\":0");
    assertThat(response.body()).contains("\"friendsOnlyCount\":2");
    assertThat(response.body()).contains("\"privateCount\":0");
    assertThat(response.body()).contains("\"hiddenStaffCount\":0");
    assertThat(response.body()).contains("\"unspecifiedVisibilityCount\":0");
  }

  @Test
  void addFriendIsIdempotentForExistingActiveAccountLink() throws Exception {
    String token = privilegedAccountToken(2L);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build();

    HttpResponse<String> firstResponse = send(request);
    HttpResponse<String> secondResponse = send(request);

    assertThat(firstResponse.statusCode()).isEqualTo(200);
    assertThat(secondResponse.statusCode()).isEqualTo(200);
    assertThat(accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(1L, 2L, "active"))
        .hasSize(1);
  }

  @Test
  void addFriendRejectsSelfLink() throws Exception {
    String token = privilegedAccountToken(2L);
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId":1,"accountId":2,"friendAccountId":2}
                        """))
                .build());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("Cannot add or remove your own account as a friend");
    assertThat(accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(1L, 2L, "active"))
        .isEmpty();
  }

  @Test
  void removeFriendDeletesExistingActiveAccountLinkAndIsIdempotent() throws Exception {
    String token = privilegedAccountToken(2L);
    HttpRequest addRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build();

    send(addRequest);

    HttpResponse<String> firstDelete =
        send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/friends/3?tenantId=1&accountId=2"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .DELETE()
                .build());
    HttpResponse<String> secondDelete =
        send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/friends/3?tenantId=1&accountId=2"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .DELETE()
                .build());

    assertThat(firstDelete.statusCode()).isEqualTo(200);
    assertThat(secondDelete.statusCode()).isEqualTo(200);
    assertThat(accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(1L, 2L, "active"))
        .isEmpty();
  }

  @Test
  void removeFriendByOrdinalDeletesCanonicalRosterEntry() throws Exception {
    String token = privilegedAccountToken(2L);
    send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":1,"accountId":2,"friendAccountId":3}
                    """))
            .build());

    HttpResponse<String> deleteResponse =
        send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/friends/entry/1?tenantId=1&accountId=2"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .DELETE()
                .build());

    assertThat(deleteResponse.statusCode()).isEqualTo(200);
    assertThat(deleteResponse.body()).contains("\"friendAccountId\":3");
    assertThat(accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(1L, 2L, "active"))
        .isEmpty();
  }

  @Test
  void friendVisibilityEndpointsExposeCanonicalPolicyReadAndWrite() throws Exception {
    String token = privilegedAccountToken(2L);

    HttpResponse<String> getResponse =
        send(
            authedGet(
                token, "http://localhost:" + port + "/friends/visibility?tenantId=1&accountId=2"));

    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(getResponse.body()).contains("\"currentPolicy\":\"FRIENDS_ONLY\"");

    Mockito.when(
            TEST_ACCOUNT_CLIENT.updatePresenceVisibilityPolicy(
                1L,
                2L,
                net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue
                    .PRIVATE))
        .thenReturn(true);

    HttpResponse<String> putResponse =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/friends/visibility"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"tenantId":1,"accountId":2,"visibilityPolicy":"PRIVATE"}
                        """))
                .build());

    assertThat(putResponse.statusCode()).isEqualTo(200);
    assertThat(putResponse.body()).contains("\"currentPolicy\":\"PRIVATE\"");
  }

  private static HttpRequest authedGet(String token, String uri) {
    return HttpRequest.newBuilder(URI.create(uri))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .GET()
        .build();
  }

  private static String privilegedAccountToken(long accountId) {
    return JWT_UTIL.generateToken(
        Long.toString(accountId),
        Map.of(
            "accountId", Long.toString(accountId),
            "globalRoles", List.of("platformAdmin"),
            "scopedRoles", Map.of("1", List.of("tenantAdmin"))));
  }

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration
  static class MockClientsConfiguration {
    @Bean
    @Primary
    LoggingAdminClient testLoggingAdminClient() {
      return TEST_LOGGING_ADMIN_CLIENT;
    }

    @Bean
    @Primary
    ModerationPolicyClient testModerationPolicyClient() {
      return TEST_MODERATION_POLICY_CLIENT;
    }

    @Bean
    @Primary
    GameSessionClient testGameSessionClient() {
      return TEST_GAME_SESSION_CLIENT;
    }

    @Bean
    @Primary
    AccountClient testAccountClient() {
      return TEST_ACCOUNT_CLIENT;
    }

    @Bean
    @Primary
    SocialAccessGuard testSocialAccessGuard() {
      return TEST_SOCIAL_ACCESS_GUARD;
    }
  }
}
