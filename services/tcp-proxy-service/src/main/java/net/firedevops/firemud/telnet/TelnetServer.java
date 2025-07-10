package net.firedevops.firemud.telnet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
  private final EventLoopGroup workerGroup = new NioEventLoopGroup();
  private Channel serverChannel;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public TelnetServer(
      @Value("${TCP_PROXY_PORT:2323}") int port,
      @Value("${GATEWAY_WS_URL:ws://spring-cloud-gateway:8080/ws}") String gatewayWsUrl) {
    this.port = port;
    this.gatewayWsUrl = gatewayWsUrl;
  }

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
                ch.pipeline()
                    .addLast(new LineBasedFrameDecoder(1024))
                    .addLast(new StringDecoder())
                    .addLast(new TelnetServerHandler(gatewayWsUrl));
              }
            });
    serverChannel = b.bind(port).sync().channel();
    running.set(true);
    logger.info("Telnet server started on port {}", port);
  }

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
}
