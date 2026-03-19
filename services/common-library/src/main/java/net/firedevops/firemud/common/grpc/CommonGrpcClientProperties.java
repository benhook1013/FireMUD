package net.firedevops.firemud.common.grpc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared TLS configuration for outbound gRPC connections. */
@Data
@ConfigurationProperties(prefix = "firemud.grpc")
public class CommonGrpcClientProperties {
  private String certChain;
  private String privateKey;
  private String caCert;
  private boolean plaintext;

  public CommonGrpcClientProperties copy() {
    CommonGrpcClientProperties copy = new CommonGrpcClientProperties();
    copy.setCertChain(certChain);
    copy.setPrivateKey(privateKey);
    copy.setCaCert(caCert);
    copy.setPlaintext(plaintext);
    return copy;
  }
}
