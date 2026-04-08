package net.firedevops.firemud.test;

/** Shared Boot 4 test property constants for reactive gateway-style test contexts. */
public final class GatewayTestProperties {
  public static final String SPRING_GRPC_SERVER_RANDOM_PORT = "spring.grpc.server.port=0";
  public static final String SPRING_GRPC_SERVER_SSL_DISABLED =
      "spring.grpc.server.ssl.enabled=false";
  public static final String FIREMUD_GRPC_CERT_CHAIN_PATH =
      "FIREMUD_GRPC_CERT_CHAIN_PATH=classpath:certs/dev-cert.pem";
  public static final String FIREMUD_GRPC_PRIVATE_KEY_PATH =
      "FIREMUD_GRPC_PRIVATE_KEY_PATH=classpath:certs/dev-key.pem";
  public static final String FIREMUD_GRPC_CA_CERT_PATH =
      "FIREMUD_GRPC_CA_CERT_PATH=classpath:certs/dev-ca.pem";
  public static final String REACTIVE_WEB_APPLICATION = "spring.main.web-application-type=reactive";
  public static final String DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER =
      "spring.autoconfigure.exclude="
          + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,"
          + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration";
  public static final String DISABLE_GATEWAY_WARNING_GRPC_SERVER_AND_SERVLET =
      DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
          + ",org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration";

  private GatewayTestProperties() {}
}
