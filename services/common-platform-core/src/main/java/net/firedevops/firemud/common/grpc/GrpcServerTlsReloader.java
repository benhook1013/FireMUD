package net.firedevops.firemud.common.grpc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.stereotype.Component;

/**
 * Reloads TLS certificates for the gRPC server when the underlying files change. The service runner
 * is stopped and restarted gracefully so active calls finish before shutdown.
 */
@Component
@ConditionalOnBean(GrpcServerLifecycle.class)
public class GrpcServerTlsReloader {
  private static final Logger logger = LoggingUtil.getLogger(GrpcServerTlsReloader.class);
  private static final String CERT_CHAIN_ENV = "FIREMUD_GRPC_CERT_CHAIN_PATH";
  private static final String PRIVATE_KEY_ENV = "FIREMUD_GRPC_PRIVATE_KEY_PATH";
  private static final String CA_CERT_ENV = "FIREMUD_GRPC_CA_CERT_PATH";

  private final GrpcServerLifecycle serverLifecycle;
  private TlsCertificateWatcher watcher;

  public GrpcServerTlsReloader(GrpcServerLifecycle serverLifecycle) {
    this.serverLifecycle = serverLifecycle;
  }

  @PostConstruct
  void init() throws IOException {
    List<Path> files = new ArrayList<>();
    Optional.ofNullable(System.getenv(CERT_CHAIN_ENV)).ifPresent(p -> files.add(Path.of(p)));
    Optional.ofNullable(System.getenv(PRIVATE_KEY_ENV)).ifPresent(p -> files.add(Path.of(p)));
    Optional.ofNullable(System.getenv(CA_CERT_ENV)).ifPresent(p -> files.add(Path.of(p)));
    if (!files.isEmpty()) {
      watcher = TlsCertificateWatcher.createAndStart(files, this::reload);
    }
  }

  private synchronized void reload() {
    try {
      logger.info("TLS certificates changed; restarting gRPC server");
      logger.info("Stopping gRPC server to reload TLS certificates");
      serverLifecycle.stop();
      logger.info("gRPC server stopped; restarting");
      serverLifecycle.start();
    } catch (Exception e) {
      logger.error("Failed to restart gRPC server", e);
    }
  }

  @PreDestroy
  void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
  }
}
