package net.firedevops.firemud.worldmanagement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TLS configuration for outbound gRPC connections. */
@Data
@ConfigurationProperties(prefix = "firemud.grpc")
public class GrpcClientProperties {
  private String certChain;
  private String privateKey;
  private String caCert;
}
