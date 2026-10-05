package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = RemovedExternalAccountRouteHttpTest.TestApplication.class)
@ContextConfiguration(classes = RemovedExternalAccountRouteHttpTest.TestApplication.class)
@WithFiremudHttpAuthTestProperties
@TestPropertySource(
    properties = {
      "firemud.database.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration"
    })
class RemovedExternalAccountRouteHttpTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private AccountService accountService;
  @Autowired private JwtUtil jwtUtil;

  @Test
  void removedExternalRouteRequiresAuthenticationThenUsesDefaultNotFoundErrorPath()
      throws Exception {
    URI route = URI.create("http://localhost:" + port + "/accounts/2/external");
    HttpRequest unauthenticatedRequest = externalLinkRequest(route, null);

    HttpResponse<String> unauthenticatedResponse =
        HTTP_CLIENT.send(unauthenticatedRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(unauthenticatedResponse.statusCode()).isEqualTo(401);

    String accountId = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
    String token = jwtUtil.generateToken(accountId, Map.of("accountId", accountId));
    HttpRequest authenticatedRequest = externalLinkRequest(route, token);

    HttpResponse<String> authenticatedResponse =
        HTTP_CLIENT.send(authenticatedRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(authenticatedResponse.statusCode()).isEqualTo(404);
    assertThat(authenticatedResponse.body())
        .contains("\"status\":404")
        .contains("\"path\":\"/accounts/2/external\"");
    verifyNoInteractions(accountService);
  }

  private HttpRequest externalLinkRequest(URI route, String token) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(route)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"tenantId\":1,\"accountId\":2,\"provider\":\"steam\",\"externalId\":\"demo\"}"));
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return request.build();
  }

  @Configuration(proxyBeanMethods = false)
  @TestComponent
  @EnableAutoConfiguration
  @Import(AccountController.class)
  static class TestApplication {
    @Bean
    AccountService accountService() {
      return mock(AccountService.class);
    }
  }
}
