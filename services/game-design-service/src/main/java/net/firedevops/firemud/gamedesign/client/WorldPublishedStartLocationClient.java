package net.firedevops.firemud.gamedesign.client;

import java.io.IOException;
import java.util.Objects;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opt-in Game Design owner for the canonical reloading mTLS World selector client. Initial capture
 * runs outside owner transactions; publication retries use the persisted operation evidence. This
 * client does not authenticate a creator or establish Account COMMIT_ORDER.
 */
public final class WorldPublishedStartLocationClient implements AutoCloseable {
  private final net.firedevops.firemud.common.world.WorldPublishedStartLocationClient transport;

  public WorldPublishedStartLocationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    this(
        new net.firedevops.firemud.common.world.WorldPublishedStartLocationClient(
            endpoints, tlsProperties, channelFactory, workloadNamespace));
  }

  WorldPublishedStartLocationClient(
      net.firedevops.firemud.common.world.WorldPublishedStartLocationClient transport) {
    this.transport = Objects.requireNonNull(transport);
  }

  /** Explicit lifecycle initialization; no automatic feature activation or credential fallback. */
  public void init() throws IOException {
    transport.init();
  }

  public WorldPublishedStartLocationEvidence read(
      WorldPublishedStartLocationEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World publication capture requires no owner transaction");
    }
    return transport.read(Objects.requireNonNull(request));
  }

  @Override
  public void close() throws IOException {
    transport.close();
  }
}
