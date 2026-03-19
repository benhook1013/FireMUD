package net.firedevops.firemud.common.grpc;

import lombok.Data;

/** Shared TLS configuration for outbound gRPC connections. */
@Data
public class CommonGrpcClientProperties {
  private String certChain;
  private String privateKey;
  private String caCert;
  private boolean plaintext;
}
