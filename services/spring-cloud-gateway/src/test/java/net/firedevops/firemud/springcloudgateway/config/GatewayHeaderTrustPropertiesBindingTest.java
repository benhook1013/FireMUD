package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = SpringCloudGatewayApplication.class,
    properties = {
      "spring.flyway.enabled=false",
      "firemud.database.enabled=false",
      GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      GatewayTestProperties.REACTIVE_WEB_APPLICATION,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER
    })
@Import({NoGrpcServerTestConfiguration.class, TestGatewayRateLimiterConfig.class})
@ActiveProfiles("test")
class GatewayHeaderTrustPropertiesBindingTest {

  @Autowired private GatewayHeaderTrustProperties properties;

  @Test
  void testProfileBindsLocalTcpProxyTrustDefaults() {
    assertThat(properties.getTcpProxy().isAllowInsecureHeadersFromTrustedCidrs()).isTrue();
    assertThat(properties.getTcpProxy().getInsecureTrustedCidrs())
        .contains("127.0.0.1/32", "::1/128");
    assertThat(properties.getForwardedClientIp().getTrustedProxyCidrs())
        .contains("127.0.0.1/32", "::1/128");
  }
}
