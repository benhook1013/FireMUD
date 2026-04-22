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
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import net.firedevops.firemud.socialgroups.client.ModerationPolicyClient;
import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.repository.ChatMessageRepository;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
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

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", postgres::getDatabaseName);
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @Autowired private ChatService chatService;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  @BeforeEach
  void setUpClients() {
    Mockito.reset(TEST_LOGGING_ADMIN_CLIENT, TEST_MODERATION_POLICY_CLIENT);
    Mockito.when(
            TEST_MODERATION_POLICY_CLIENT.evaluateChatSend(Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(EvaluateModerationPolicyResponse.newBuilder().setAllowed(true).build());
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
  void duplicateEffectIdReturnsExistingChatMessageWithoutRepublishing() {
    SendMessageRequestDto request =
        new SendMessageRequestDto(
            1L, 2L, ChatType.SAY, null, 2L, null, null, "hello there", "fx-comm-42");

    ChatMessageDto first = chatService.sendMessage(request);
    ChatMessageDto replay = chatService.sendMessage(request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(chatMessageRepository.findByTenantIdAndEffectId(1L, "fx-comm-42"))
        .hasValueSatisfying(message -> assertThat(message.getId()).isEqualTo(first.id()));

    List<Object> redisRange = redisTemplate.opsForList().range("say:1:2", 0, -1);
    List<Object> cachedMessages = new ArrayList<>(redisRange == null ? List.of() : redisRange);
    assertThat(cachedMessages).containsExactly("hello there");
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
  }
}
