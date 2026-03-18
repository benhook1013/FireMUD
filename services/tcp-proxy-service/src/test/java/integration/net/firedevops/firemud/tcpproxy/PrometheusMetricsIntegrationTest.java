package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Properties;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventClient;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class,
    properties = {
      "TCP_PROXY_PORT=0",
      "TCP_PROXY_TLS_ENABLED=false",
      "GATEWAY_WS_URL=ws://localhost/ws",
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
      "management.endpoints.web.exposure.include=health,prometheus",
      "management.endpoint.prometheus.enabled=true"
    })
@Import({
  NoGrpcServerTestConfiguration.class,
  PrometheusMetricsIntegrationTest.MetricsTestConfig.class
})
class PrometheusMetricsIntegrationTest {

  @LocalServerPort private int port;

  @MockitoBean private TcpProxyEventClient tcpProxyEventClient;

  @Test
  void prometheusEndpointExposesTcpProxyMetrics() throws Exception {
    String body = HttpTestSupport.getBody("http://localhost:" + port + "/actuator/prometheus");

    assertThat(body)
        .contains("tcpproxy_buffer_depth")
        .contains("tcpproxy_websocket_reconnects_total")
        .contains("tcpproxy_connection_duration_seconds")
        .contains("tcpproxy_tls_misconfig_total");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MetricsTestConfig {
    @Bean
    PrometheusMeterRegistry prometheusMeterRegistry() {
      return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Bean
    PrometheusScrapeEndpoint prometheusScrapeEndpoint(PrometheusMeterRegistry registry) {
      return new PrometheusScrapeEndpoint(registry.getPrometheusRegistry(), new Properties());
    }
  }
}
