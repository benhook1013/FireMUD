package net.firedevops.firemud.tcpproxy.telnet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.tcpproxy.health.GatewayGameplayReadinessProbe;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                        "GATEWAY_WS_URL=ws://localhost/ws",
                        "TCP_PROXY_TLS_CERT=/nonexistent/cert.pem",
                        "TCP_PROXY_TLS_KEY=/nonexistent/key.pem",
                        "spring.flyway.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
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
    LookCacheService lookCacheService() {
      return Mockito.mock(LookCacheService.class);
    }

    @Bean
    GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe() {
      GatewayGameplayReadinessProbe probe = Mockito.mock(GatewayGameplayReadinessProbe.class);
      Mockito.when(probe.isReady()).thenReturn(true);
      return probe;
    }

    @Bean
    TelnetServer telnetServer(
        @Value("${TCP_PROXY_PORT:2323}") int port,
        @Value("${GATEWAY_WS_URL:ws://localhost/ws}") String gatewayWsUrl,
        @Value("${TCP_PROXY_TLS_ENABLED:false}") boolean tlsEnabled,
        @Value("${TCP_PROXY_TLS_CERT:}") String certPath,
        @Value("${TCP_PROXY_TLS_KEY:}") String keyPath,
        @Value("${TCP_PROXY_MCP_ENABLED:false}") boolean advertiseMcp,
        MeterRegistry meterRegistry,
        TcpProxyEventService tcpProxyEventService,
        GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe,
        LookCacheService lookCacheService) {
      return new TelnetServer(
          port,
          gatewayWsUrl,
          tlsEnabled,
          certPath,
          keyPath,
          advertiseMcp,
          0,
          0,
          4096,
          meterRegistry,
          tcpProxyEventService,
          gatewayGameplayReadinessProbe,
          lookCacheService);
    }
  }
}
