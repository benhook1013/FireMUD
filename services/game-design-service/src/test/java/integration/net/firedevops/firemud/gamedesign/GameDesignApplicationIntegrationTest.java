package net.firedevops.firemud.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameDesignApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final JwtUtil JWT_UTIL =
      new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 3600000L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @LocalServerPort private int port;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void listTemplatesRejectsMalformedTenantIdWithInvalidArgumentEnvelope() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/templates?tenantId=bad-tenant"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken())
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be numeric\"");
  }

  @Test
  void createTemplateRejectsZeroTenantIdWithInvalidArgumentEnvelope() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/templates"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken())
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":"0","name":"demo-template","config":"{}"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be positive\"");
  }

  @Test
  void uploadAssetRejectsMalformedTenantIdWithInvalidArgumentEnvelope() throws Exception {
    String boundary = "FiremudBoundary";
    String multipartBody =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"tenantId\"\r\n\r\nbad-tenant\r\n--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"demo.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--"
            + boundary
            + "--\r\n";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/assets"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken())
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    multipartBody.getBytes(StandardCharsets.UTF_8)))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be numeric\"");
  }

  private String operatorToken() {
    return JWT_UTIL.generateToken("operator", Map.of("globalRoles", List.of("platformAdmin")));
  }
}
