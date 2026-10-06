package net.firedevops.firemud.accountservice.service.impl;

import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import net.firedevops.firemud.accountservice.config.PlatformAuthRateLimitProperties;
import net.firedevops.firemud.common.config.RedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Private Cache Redis client; it deliberately is not a Spring RedisConnectionFactory bean. */
public final class PlatformAuthCacheRedisClient implements AutoCloseable {
  private final ClientResources clientResources;
  private final LettuceConnectionFactory connectionFactory;
  private final StringRedisTemplate template;

  public PlatformAuthCacheRedisClient(
      PlatformAuthRateLimitProperties.CacheRedis cacheProperties,
      RedisProperties coordinationProperties) {
    Endpoint cache = resolve(cacheProperties);
    if (sameConfiguredEndpoint(cache.host(), cache.port(), coordinationProperties)) {
      throw new IllegalStateException(
          "Account platform-auth Cache Redis must use a distinct endpoint from Coordination Redis");
    }

    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(cache.host(), cache.port());
    standalone.setDatabase(cache.database());
    if (!cache.username().isBlank()) {
      standalone.setUsername(cache.username());
    }
    if (!cache.password().isBlank()) {
      standalone.setPassword(RedisPassword.of(cache.password()));
    }

    clientResources = DefaultClientResources.create();
    LettuceClientConfiguration.LettuceClientConfigurationBuilder clientBuilder =
        LettuceClientConfiguration.builder()
            .clientResources(clientResources)
            .commandTimeout(Duration.ofMillis(cache.commandTimeoutMillis()));
    if (cache.useSsl() || cache.startTls()) {
      LettuceClientConfiguration.LettuceSslClientConfigurationBuilder sslBuilder =
          clientBuilder.useSsl();
      if (cache.startTls()) {
        sslBuilder.startTls();
      }
      if (cache.verifyPeer()) {
        sslBuilder.verifyPeer(SslVerifyMode.FULL);
      } else {
        sslBuilder.disablePeerVerification();
      }
      clientBuilder = sslBuilder.and();
    }
    connectionFactory = new LettuceConnectionFactory(standalone, clientBuilder.build());
    connectionFactory.afterPropertiesSet();
    template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
  }

  StringRedisTemplate template() {
    return template;
  }

  @Override
  public void close() {
    connectionFactory.destroy();
    clientResources.shutdown();
  }

  private static boolean sameConfiguredEndpoint(
      String cacheHost, int cachePort, RedisProperties coordinationProperties) {
    return normalizeHost(cacheHost).equals(normalizeHost(coordinationProperties.getHost()))
        && cachePort == coordinationProperties.getPort();
  }

  private static String normalizeHost(String host) {
    return host == null ? "" : host.trim().replaceAll("\\.$", "").toLowerCase(Locale.ROOT);
  }

  private static Endpoint resolve(PlatformAuthRateLimitProperties.CacheRedis properties) {
    int commandTimeoutMillis = properties.getCommandTimeoutMillis();
    if (commandTimeoutMillis < 1 || commandTimeoutMillis > 5_000) {
      throw new IllegalStateException(
          "Account platform-auth Cache Redis command timeout is invalid");
    }
    if (properties.getUrl() == null || properties.getUrl().isBlank()) {
      validateHostAndPort(properties.getHost(), properties.getPort());
      return new Endpoint(
          properties.getHost().trim(),
          properties.getPort(),
          Math.max(0, properties.getDatabase()),
          nullToEmpty(properties.getUsername()),
          nullToEmpty(properties.getPassword()),
          properties.isUseSsl(),
          properties.isStartTls(),
          properties.isVerifyPeer(),
          commandTimeoutMillis);
    }
    try {
      URI uri = URI.create(properties.getUrl());
      String scheme = uri.getScheme();
      if (uri.getHost() == null
          || !("redis".equalsIgnoreCase(scheme) || "rediss".equalsIgnoreCase(scheme))
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("unsupported Redis URL");
      }
      int port = uri.getPort() < 0 ? 6379 : uri.getPort();
      int database = parseDatabase(uri.getPath());
      String username = nullToEmpty(properties.getUsername());
      String password = nullToEmpty(properties.getPassword());
      String userInfo = uri.getUserInfo();
      if (userInfo != null) {
        int separator = userInfo.indexOf(':');
        if (separator < 0) {
          username = userInfo;
        } else {
          username = userInfo.substring(0, separator);
          password = userInfo.substring(separator + 1);
        }
      }
      validateHostAndPort(uri.getHost(), port);
      boolean useSsl = "rediss".equalsIgnoreCase(scheme) || properties.isUseSsl();
      return new Endpoint(
          uri.getHost(),
          port,
          database,
          username,
          password,
          useSsl,
          properties.isStartTls(),
          properties.isVerifyPeer(),
          commandTimeoutMillis);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Account platform-auth Cache Redis URL is invalid");
    }
  }

  private static int parseDatabase(String path) {
    if (path == null || path.isEmpty() || "/".equals(path)) {
      return 0;
    }
    if (!path.matches("/[0-9]{1,9}")) {
      throw new IllegalArgumentException("unsupported Redis URL path");
    }
    return Integer.parseInt(path.substring(1));
  }

  private static void validateHostAndPort(String host, int port) {
    if (host == null || host.isBlank() || port < 1 || port > 65535) {
      throw new IllegalStateException("Account platform-auth Cache Redis endpoint is invalid");
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record Endpoint(
      String host,
      int port,
      int database,
      String username,
      String password,
      boolean useSsl,
      boolean startTls,
      boolean verifyPeer,
      int commandTimeoutMillis) {
    private Endpoint {
      if (database < 0) {
        database = 0;
      }
    }

    @Override
    public String toString() {
      return "Endpoint[host=redacted, port=" + port + ", database=" + database + "]";
    }
  }
}
