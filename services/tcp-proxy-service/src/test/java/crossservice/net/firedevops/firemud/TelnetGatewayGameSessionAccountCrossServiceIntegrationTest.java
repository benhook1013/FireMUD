package crossservice.net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.GameSessionServiceApplication;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.tcpproxy.TcpProxyServiceApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import crossservice.net.firedevops.firemud.stub.GatewayStubApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
class TelnetGatewayGameSessionAccountCrossServiceIntegrationTest {

  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = 7L;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static AccountServiceStub ACCOUNT_STUB;
  private static GameSessionHolder GAME_SESSION;
  private static GatewayHolder GATEWAY;

  @Autowired private TelnetServer telnetServer;
  @SuppressWarnings("removal")
  @MockBean private GRpcServerRunner grpcServerRunner;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    ensureTestServicesStarted();
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
  }

  @AfterAll
  static void stopTestServices() {
    if (GATEWAY != null) {
      GATEWAY.close();
      GATEWAY = null;
    }
    if (GAME_SESSION != null) {
      GAME_SESSION.close();
      GAME_SESSION = null;
    }
    if (ACCOUNT_STUB != null) {
      ACCOUNT_STUB.close();
      ACCOUNT_STUB = null;
    }
  }

  @Test
  void telnetLoginMatchesGatewayWebSocketResponses() throws Exception {
    ensureTestServicesStarted();
    List<String> websocketResponses =
        runGatewayWebSocketCommands("LOGIN demo@example.com swordfish", "LOOK");

    String telnetLoginResponse;
    String telnetLookResponse;
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1), true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("SESSION " + GAME_SESSION.sessionId() + " " + TENANT_ID);
      writer.println("LOGIN demo@example.com swordfish");
      telnetLoginResponse = reader.readLine();
      assertThat(telnetLoginResponse).isNotNull();
      writer.println("LOOK");
      telnetLookResponse = readMultiLineResponse(reader);
    }

    assertThat(websocketResponses).hasSizeGreaterThanOrEqualTo(2);
    assertThat(telnetLoginResponse).isEqualTo(websocketResponses.get(0));
    assertThat(telnetLookResponse).isEqualTo(websocketResponses.get(1));

    assertThat(ACCOUNT_STUB.capturedRequests())
        .anyMatch(
            request ->
                request.getUsername().equals("demo@example.com")
                    && request.getPassword().equals("swordfish")
                    && request.getTenantId().equals(String.valueOf(TENANT_ID)));
  }

  private static synchronized void ensureTestServicesStarted() {
    if (ACCOUNT_STUB == null) {
      try {
        ACCOUNT_STUB = startAccountStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start account stub", e);
      }
    }
    if (GAME_SESSION == null) {
      GAME_SESSION = startGameSession(ACCOUNT_STUB.port());
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(GAME_SESSION.port());
    }
  }

  private static AccountServiceStub startAccountStub() throws IOException {
    int port = TestSocketUtils.findAvailableTcpPort();
    return new AccountServiceStub(port);
  }

  private static GameSessionHolder startGameSession(int accountPort) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", "0");
    props.put("grpc.server.port", "0");
    props.put("grpc.server.enabled", "false");
    props.put("game-session.log-only", "false");
    props.put("game-session.require-authenticated-commands", "true");
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.postgres.host", POSTGRES.getHost());
    props.put("firemud.postgres.port", String.valueOf(POSTGRES.getMappedPort(5432)));
    props.put("firemud.postgres.database", POSTGRES.getDatabaseName());
    props.put("firemud.postgres.username", POSTGRES.getUsername());
    props.put("firemud.postgres.password", POSTGRES.getPassword());
    props.put("firemud.redis.host", REDIS.getHost());
    props.put("firemud.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
    props.put("firemud.database.enabled", "true");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameSessionServiceApplication.class)
            .properties(props)
            .run();

    JdbcTemplate jdbc =
        new JdbcTemplate(context.getBean(DataSource.class));
    Long insertedId =
        jdbc.queryForObject(
            "INSERT INTO game_instances (tenant_id, runtime_version, script_patch_version, owner_account_id, status) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            TENANT_ID,
            "0.1.0",
            "initial",
            ACCOUNT_ID,
            "ACTIVE");
    int port = ((org.springframework.boot.web.context.WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
    return new GameSessionHolder(context, port, insertedId);
  }

  private static GatewayHolder startGateway(int gameSessionPort) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GatewayStubApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "gateway.stub.target-uri=ws://localhost:" + gameSessionPort + "/ws/game")
            .run();
    int port = ((org.springframework.boot.web.context.WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
    return new GatewayHolder(context, port);
  }

  private static String readMultiLineResponse(BufferedReader reader) throws IOException {
    StringBuilder builder = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      builder.append(line).append("\n");
      if (line.isEmpty()) {
        break;
      }
    }
    return builder.toString();
  }

  private List<String> runGatewayWebSocketCommands(String loginCommand, String lookCommand)
      throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    List<String> responses = new CopyOnWriteArrayList<>();
    CompletableFuture<Void> responsesReady = new CompletableFuture<>();
    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .buildAsync(
                URI.create(GATEWAY.websocketUrl()),
                new Listener() {
                  private int received;

                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    received++;
                    webSocket.request(1);
                    if (received >= 2 && !responsesReady.isDone()) {
                      responsesReady.complete(null);
                    }
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();
    webSocket.sendText(loginCommand, true).join();
    webSocket.sendText(lookCommand, true).join();
    responsesReady.get(COMMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private static final class AccountServiceStub extends AccountServiceGrpc.AccountServiceImplBase {
    private final Server server;
    private final List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    private final int port;

    AccountServiceStub(int port) throws IOException {
      this.port = port;
      this.server = ServerBuilder.forPort(port).addService(this).build().start();
    }

    List<AuthenticateRequest> capturedRequests() {
      return List.copyOf(requests);
    }

    int port() {
      return port;
    }

    @Override
    public void authenticate(
        AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
      requests.add(request);
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAccountId(String.valueOf(ACCOUNT_ID))
              .setAuthToken("stub-token")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    void close() {
      server.shutdownNow();
    }
  }

  private static final class GameSessionHolder {
    private final ConfigurableApplicationContext context;
    private final int port;
    private final long sessionId;

    GameSessionHolder(ConfigurableApplicationContext context, int port, Long sessionId) {
      this.context = context;
      this.port = port;
      this.sessionId = sessionId;
    }

    int port() {
      return port;
    }

    long sessionId() {
      return sessionId;
    }

    void close() {
      context.close();
    }
  }

  private static final class GatewayHolder {
    private final ConfigurableApplicationContext context;
    private final int port;

    GatewayHolder(ConfigurableApplicationContext context, int port) {
      this.context = context;
      this.port = port;
    }

    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    void close() {
      context.close();
    }
  }
}
