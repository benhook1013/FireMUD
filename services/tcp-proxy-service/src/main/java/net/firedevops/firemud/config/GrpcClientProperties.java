package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TLS configuration for outbound gRPC connections from the TCP proxy. */
@Data
@ConfigurationProperties(prefix = "firemud.grpc")
public class GrpcClientProperties {
  private String certChain;
  private String privateKey;
  private String caCert;
}
