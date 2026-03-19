package net.firedevops.firemud.gamedesign.client;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.gamedesign.config.GrpcClientProperties;
import org.springframework.stereotype.Component;

/** gRPC client for Automation & Scripting Service. */
@Component
public class AutomationScriptingClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private ManagedChannel channel;
  private AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public AutomationScriptingClient(
      ServiceEndpointsProperties endpoints,
      GrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this.endpoints = copyEndpoints(endpoints);
    this.tlsProps = copyTlsProps(tlsProps);
    this.channelFactory = channelFactory;
  }

  private static ServiceEndpointsProperties copyEndpoints(ServiceEndpointsProperties source) {
    ServiceEndpointsProperties copy = new ServiceEndpointsProperties();
    copy.setAccountService(source.getAccountService());
    copy.setGameSessionService(source.getGameSessionService());
    copy.setGameDesignService(source.getGameDesignService());
    copy.setGameLogicService(source.getGameLogicService());
    copy.setWorldManagementService(source.getWorldManagementService());
    copy.setEntityManagementService(source.getEntityManagementService());
    copy.setLoggingAdminService(source.getLoggingAdminService());
    copy.setAutomationScriptingService(source.getAutomationScriptingService());
    return copy;
  }

  private static GrpcClientProperties copyTlsProps(GrpcClientProperties source) {
    GrpcClientProperties copy = new GrpcClientProperties();
    copy.setCertChain(source.getCertChain());
    copy.setPrivateKey(source.getPrivateKey());
    copy.setCaCert(source.getCaCert());
    copy.setPlaintext(source.isPlaintext());
    return copy;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    if (tlsProps.isPlaintext()) {
      return;
    }
    watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(
                Path.of(tlsProps.getCertChain()),
                Path.of(tlsProps.getPrivateKey()),
                Path.of(tlsProps.getCaCert())),
            this::safeReload);
  }

  private synchronized void safeReload() {
    try {
      reloadChannel();
    } catch (SSLException e) {
      net.firedevops.firemud.common.LoggingUtil.getLogger(AutomationScriptingClient.class)
          .error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
    String target = endpoints.getAutomationScriptingService();
    if (target == null || target.isEmpty()) {
      target = "automation-scripting-service:6565";
    }
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true);
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = AutomationScriptingServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Notify the Automation service that a new script patch version is active. */
  public void notifyScriptVersionUpdate(
      String tenantId, String patchVersion, List<String> scripts) {
    NotifyScriptVersionUpdateRequest request =
        NotifyScriptVersionUpdateRequest.newBuilder()
            .setTenantId(tenantId)
            .setScriptPatchVersion(patchVersion)
            .addAllAffectedScripts(scripts)
            .build();
    stub.notifyScriptVersionUpdate(request);
  }

  @PreDestroy
  @Override
  public void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    if (channel != null) {
      channel.shutdown();
    }
  }
}
