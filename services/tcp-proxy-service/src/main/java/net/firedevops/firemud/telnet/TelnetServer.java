package net.firedevops.firemud.telnet;

import io.micrometer.core.annotation.Timed;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Simple Netty-based Telnet server that forwards input to the gateway via WebSocket. */
@Component
public class TelnetServer {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServer.class);

  private final int port;
  private final String gatewayWsUrl;
  private final boolean tlsEnabled;
  private final String certPath;
  private final String keyPath;
  private final int maxConnectionsPerIp;
  private final int maxMessagesPerSecond;

  private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
  private final EventLoopGroup workerGroup = new NioEventLoopGroup();
  private Channel serverChannel;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private SslContext sslContext;
  private final ConnectionThrottler connectionThrottler;

  public TelnetServer(
      @Value("${TCP_PROXY_PORT:2323}") int port,
      @Value("${GATEWAY_WS_URL:ws://spring-cloud-gateway:8080/ws}") String gatewayWsUrl,
      @Value("${TCP_PROXY_TLS_ENABLED:false}") boolean tlsEnabled,
      @Value("${TCP_PROXY_TLS_CERT:}") String certPath,
      @Value("${TCP_PROXY_TLS_KEY:}") String keyPath,
      @Value("${TCP_PROXY_MAX_CONNECTIONS_PER_IP:5}") int maxConnectionsPerIp,
      @Value("${TCP_PROXY_MAX_MSGS_PER_SEC:5}") int maxMessagesPerSec)
      throws SSLException {
    this.port = port;
    this.gatewayWsUrl = gatewayWsUrl;
    this.tlsEnabled = tlsEnabled;
    this.certPath = certPath;
    this.keyPath = keyPath;
    this.maxConnectionsPerIp = maxConnectionsPerIp;
    this.maxMessagesPerSecond = maxMessagesPerSec;
    this.connectionThrottler = new ConnectionThrottler(maxConnectionsPerIp);
    if (tlsEnabled) {
      sslContext = SslContextBuilder.forServer(new File(certPath), new File(keyPath)).build();
    }
  }

  @Timed(value = "tcpproxy.start")
  public void start() throws InterruptedException {
    if (running.get()) {
      return;
    }
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
                    .addLast(new LineBasedFrameDecoder(1024))
                    .addLast(new StringDecoder())
                    .addLast(
                        new TelnetServerHandler(
                            gatewayWsUrl, connectionThrottler, maxMessagesPerSecond));
              }
            });
    serverChannel = b.bind(port).sync().channel();
    running.set(true);
    logger.info("Telnet server started on port {}", port);
  }

  @Timed(value = "tcpproxy.stop")
  public void stop() {
    if (!running.get()) {
      return;
    }
    try {
      serverChannel.close().sync();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
    running.set(false);
    logger.info("Telnet server stopped");
  }

  /** Expose the configured port for testing purposes. */
  public int getPort() {
    return port;
  }
}
