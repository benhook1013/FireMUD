package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class GatewayTcpProxyListenerPropertiesTest {
  @Test
  void defaultsListenerToLoopback() {
    assertThat(new GatewayTcpProxyListenerProperties().getBindAddress()).isEqualTo("127.0.0.1");
  }

  @Test
  void bindsIsoInstantExpiry() {
    StandardEnvironment environment = environmentWithExpiry("2026-09-17T10:00:00Z");
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();

    assertThat(
            Binder.get(environment)
                .bind("firemud.gateway.tcp-proxy-listener", Bindable.ofInstance(properties))
                .get()
                .getMigrationDns()
                .getExpiresAt())
        .isEqualTo(Instant.parse("2026-09-17T10:00:00Z"));
  }

  @Test
  void bindsEmptyMigrationDnsExpiryAsNull() {
    StandardEnvironment environment = environmentWithExpiry("");

    assertThat(
            Binder.get(environment)
                .bind(
                    "firemud.gateway.tcp-proxy-listener",
                    Bindable.ofInstance(new GatewayTcpProxyListenerProperties()))
                .get()
                .getMigrationDns()
                .getExpiresAt())
        .isNull();
  }

  @Test
  void rejectsMalformedInstantDuringBinding() {
    StandardEnvironment environment = environmentWithExpiry("not-an-instant");

    assertThatThrownBy(
            () ->
                Binder.get(environment)
                    .bind(
                        "firemud.gateway.tcp-proxy-listener",
                        Bindable.ofInstance(new GatewayTcpProxyListenerProperties())))
        .isInstanceOf(BindException.class)
        .hasMessageContaining("expires-at");
  }

  private static StandardEnvironment environmentWithExpiry(String expiry) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "firemud.gateway.tcp-proxy-listener.migration-dns.dns-san",
                    "tcp-proxy.internal",
                    "firemud.gateway.tcp-proxy-listener.migration-dns.owner",
                    "platform",
                    "firemud.gateway.tcp-proxy-listener.migration-dns.reason",
                    "binding test",
                    "firemud.gateway.tcp-proxy-listener.migration-dns.expires-at",
                    expiry)));
    return environment;
  }
}
