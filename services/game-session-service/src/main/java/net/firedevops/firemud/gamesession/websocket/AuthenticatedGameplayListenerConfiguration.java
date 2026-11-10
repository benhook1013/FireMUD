package net.firedevops.firemud.gamesession.websocket;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.tomcat.SslConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated internal mTLS connector; the ordinary HTTP connector cannot produce TCP custody. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.gameplay.authenticated-listener",
    name = "enabled",
    havingValue = "true")
public class AuthenticatedGameplayListenerConfiguration {
  @Bean
  Filter authenticatedGameplayPathFilter(
      @Value("${firemud.gameplay.authenticated-listener.port:8444}") int port) {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        if (request.getLocalPort() == port && !"/ws/game/tcp".equals(request.getRequestURI())) {
          response.sendError(HttpServletResponse.SC_NOT_FOUND);
          return;
        }
        chain.doFilter(request, response);
      }
    };
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> authenticatedGameplayConnector(
      SslBundles bundles,
      @Value("${firemud.gameplay.authenticated-listener.port:8444}") int port,
      @Value("${server.port:8080}") int publicPort) {
    if (port <= 0 || port > 65535 || port == publicPort)
      throw new IllegalArgumentException("Distinct internal gameplay TLS port required");
    return factory -> {
      Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
      connector.setPort(port);
      connector.setScheme("https");
      connector.setSecure(true);
      ((AbstractHttp11Protocol<?>) connector.getProtocolHandler()).setSSLEnabled(true);
      SslConnectorCustomizer tls =
          new SslConnectorCustomizer(
              LogFactory.getLog(AuthenticatedGameplayListenerConfiguration.class),
              connector,
              Ssl.ClientAuth.NEED);
      tls.customize(bundles.getBundle("firemud-grpc"), Map.of());
      bundles.addBundleUpdateHandler("firemud-grpc", bundle -> tls.update(null, bundle));
      factory.addAdditionalConnectors(connector);
    };
  }
}
