package net.firedevops.firemud.telnet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.service.TcpProxyEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import net.firedevops.firemud.telnet.TelnetServer;

class TlsMisconfigurationIntegrationTest {

  @Test
  void tlsMisconfigurationFailsFastWithClearMessage() {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(TestConfig.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                        "TCP_PROXY_PORT=0",
                        "TCP_PROXY_TLS_ENABLED=true",
                        "TCP_PROXY_TLS_CERT=/nonexistent/cert.pem",
                        "TCP_PROXY_TLS_KEY=/nonexistent/key.pem",
                        "spring.flyway.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration")
                    .run())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "TCP proxy TLS configuration invalid: certificate or key file does not exist or is unreadable");
  }

  @Configuration
  static class TestConfig {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    TcpProxyEventService tcpProxyEventService() {
      return Mockito.mock(TcpProxyEventService.class);
    }

    @Bean
    TelnetServer telnetServer(
        @Value("${TCP_PROXY_PORT:2323}") int port,
        @Value("${GATEWAY_WS_URL:ws://localhost/ws}") String gatewayWsUrl,
        @Value("${TCP_PROXY_TLS_ENABLED:false}") boolean tlsEnabled,
        @Value("${TCP_PROXY_LOG_ONLY:false}") boolean logOnly,
        @Value("${TCP_PROXY_TLS_CERT:}") String certPath,
        @Value("${TCP_PROXY_TLS_KEY:}") String keyPath,
        @Value("${TCP_PROXY_MCP_ENABLED:false}") boolean advertiseMcp,
        MeterRegistry meterRegistry,
        TcpProxyEventService tcpProxyEventService) {
      return new TelnetServer(
          port,
          gatewayWsUrl,
          tlsEnabled,
          logOnly,
          certPath,
          keyPath,
          advertiseMcp,
          meterRegistry,
          tcpProxyEventService);
    }
  }
}
