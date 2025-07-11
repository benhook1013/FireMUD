package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient implements AutoCloseable {
  private final String host;
  private final int port;

  private ManagedChannel channel;
  private ReportServiceGrpc.ReportServiceBlockingStub stub;

  public LoggingAdminClient(
      @Value("${loggingAdmin.host:logging-admin-service}") String host,
      @Value("${loggingAdmin.port:6565}") int port) {
    this.host = host;
    this.port = port;
  }

  @PostConstruct
  void init() {
    channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    stub = ReportServiceGrpc.newBlockingStub(channel);
  }

  /**
   * Create a moderation report for a chat message.
   *
   * @param tenantId tenant identifier
   * @param accountId account that sent the message
   * @param description details of the violation
   */
  public void reportChatViolation(long tenantId, long accountId, String description) {
    CreateReportRequest request =
        CreateReportRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setReporterAccountId(Long.toString(accountId))
            .setTargetAccountId(Long.toString(accountId))
            .setType("CHAT_PROFANITY")
            .setDescription(description)
            .build();
    stub.createReport(request);
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
