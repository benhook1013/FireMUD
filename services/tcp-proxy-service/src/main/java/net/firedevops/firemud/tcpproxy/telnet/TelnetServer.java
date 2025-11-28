package net.firedevops.firemud.tcpproxy.telnet;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;

/** Simple Netty-based Telnet server that forwards input to the gateway via WebSocket. */
@Component
public final class TelnetServer {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServer.class);

  private final int port;
  private final String gatewayWsUrl;
  private final boolean tlsEnabled;
  private final boolean devIsolated;
  private final String certPath;
  private final String keyPath;
  private final boolean advertiseMcp;
  private final java.util.concurrent.atomic.AtomicInteger activeConnections =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.concurrent.atomic.AtomicInteger bufferDepth =
      new java.util.concurrent.atomic.AtomicInteger();
  private final Counter connectionCounter;
  private final Counter discardedCommandCounter;
  private final Counter tlsMisconfigCounter;
  private final MeterRegistry meterRegistry;
  private final TcpProxyEventService eventService;
  private volatile int boundPort;

  private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
  private final EventLoopGroup workerGroup = new NioEventLoopGroup();
  private Channel serverChannel;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private SslContext sslContext;

  public TelnetServer(
      @Value("${TCP_PROXY_PORT:2323}") int port,
      @Value("${GATEWAY_WS_URL:ws://spring-cloud-gateway:8080/ws/game}") String gatewayWsUrl,
      @Value("${TCP_PROXY_TLS_ENABLED:false}") boolean tlsEnabled,
      @Value("${TCP_PROXY_DEV_ISOLATED:false}") boolean devIsolated,
      @Value("${TCP_PROXY_TLS_CERT:}") String certPath,
      @Value("${TCP_PROXY_TLS_KEY:}") String keyPath,
      @Value("${TCP_PROXY_MCP_ENABLED:false}") boolean advertiseMcp,
      MeterRegistry meterRegistry,
      TcpProxyEventService eventService) {
    this.port = port;
    this.boundPort = port;
    this.gatewayWsUrl = gatewayWsUrl;
    this.tlsEnabled = tlsEnabled;
    this.devIsolated = devIsolated;
    this.certPath = certPath;
    this.keyPath = keyPath;
    this.advertiseMcp = advertiseMcp;
    this.meterRegistry = meterRegistry;
    this.connectionCounter = meterRegistry.counter("tcpproxy.connections.total");
    this.discardedCommandCounter = meterRegistry.counter("tcpproxy.telnet.discarded");
    this.tlsMisconfigCounter = meterRegistry.counter("tcpproxy.tls.misconfig");
    this.eventService = eventService;
    Gauge.builder(
            "tcpproxy.connections.active",
            activeConnections,
            java.util.concurrent.atomic.AtomicInteger::get)
        .register(meterRegistry);
    Gauge.builder("tcpproxy.buffer.depth", bufferDepth, java.util.concurrent.atomic.AtomicInteger::get)
        .register(meterRegistry);
    meterRegistry.counter("tcpproxy.websocket.reconnects").increment(0.0);
    if (tlsEnabled) {
      validateTlsConfiguration();
      try {
        sslContext = SslContextBuilder.forServer(new File(certPath), new File(keyPath)).build();
      } catch (SSLException e) {
        tlsMisconfigCounter.increment();
        String message = "TCP proxy TLS configuration failed to load";
        logger.error(message, e);
        throw new IllegalStateException(message, e);
      }
    }
  }

  private void validateTlsConfiguration() {
    if (!StringUtils.hasText(certPath) || !StringUtils.hasText(keyPath)) {
      tlsMisconfigCounter.increment();
      String message = "TCP proxy TLS is enabled but TCP_PROXY_TLS_CERT and TCP_PROXY_TLS_KEY must be set";
      logger.error(message);
      throw new IllegalStateException(message);
    }

    File certFile = new File(certPath);
    File keyFile = new File(keyPath);
    if (!certFile.isFile() || !keyFile.isFile() || !certFile.canRead() || !keyFile.canRead()) {
      tlsMisconfigCounter.increment();
      String message =
          "TCP proxy TLS configuration invalid: certificate or key file does not exist or is unreadable";
      logger.error(
          "{} (certPath={}, keyPath={})", message, certFile.getAbsolutePath(), keyFile.getAbsolutePath());
      throw new IllegalStateException(message);
    }
  }

  @Timed(value = "tcpproxy.start")
  public void start() throws InterruptedException {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  var pipeline = ch.pipeline();
                  if (tlsEnabled && sslContext != null) {
                    pipeline.addLast(sslContext.newHandler(ch.alloc()));
                  }
                  pipeline
                      .addLast(new LineBasedFrameDecoder(1024, false, true))
                      .addLast(new StringDecoder(StandardCharsets.ISO_8859_1))
                      .addLast(new StringEncoder(StandardCharsets.ISO_8859_1))
                      .addLast(
                          new TelnetServerHandler(
                              gatewayWsUrl,
                              devIsolated,
                              activeConnections::incrementAndGet,
                              activeConnections::decrementAndGet,
                              connectionCounter,
                              discardedCommandCounter,
                              advertiseMcp,
                              meterRegistry,
                              eventService,
                              bufferDepth));
                }
              });
      serverChannel = b.bind(port).sync().channel();
      boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
      logger.info("Telnet server started on port {}", boundPort);
    } catch (InterruptedException e) {
      running.set(false);
      Thread.currentThread().interrupt();
      throw e;
    } catch (Exception e) {
      running.set(false);
      serverChannel = null;
      String message = "Telnet server failed to start";
      logger.error(message, e);
      throw new IllegalStateException(message, e);
    }
  }

  @Timed(value = "tcpproxy.stop")
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    try {
      if (serverChannel != null) {
        serverChannel.close().sync();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    finally {
      serverChannel = null;
    }
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
    logger.info("Telnet server stopped");
  }

  /** Expose the configured port for testing purposes. */
  public int getPort() {
    return boundPort;
  }

  /** Current active connection count for metrics testing. */
  int getActiveConnectionCount() {
    return activeConnections.get();
  }

  /** Total connection count for metrics testing. */
  double getTotalConnections() {
    return connectionCounter.count();
  }
}
