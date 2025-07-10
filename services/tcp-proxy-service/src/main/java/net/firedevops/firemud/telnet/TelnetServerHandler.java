package net.firedevops.firemud.telnet;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);

  private final String gatewayWsUrl;
  private final ConnectionThrottler connectionThrottler;
  private final int maxMessagesPerSecond;
  private WebSocket webSocket;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
  private final Deque<Long> messageTimes = new ArrayDeque<>();

  public TelnetServerHandler(
      String gatewayWsUrl, ConnectionThrottler connectionThrottler, int maxMessagesPerSecond) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.connectionThrottler = connectionThrottler;
    this.maxMessagesPerSecond = maxMessagesPerSecond;
  }

  void setWebSocket(WebSocket webSocket) {
    this.webSocket = webSocket;
    flushBuffer();
  }

  int getBufferedSize() {
    return buffer.size();
  }

  private void flushBuffer() {
    if (webSocket == null) {
      return;
    }
    String msg;
    while ((msg = buffer.poll()) != null) {
      webSocket.sendText(msg, true);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    var remote = ctx.channel() != null ? ctx.channel().remoteAddress() : null;
    if (!connectionThrottler.tryAcquire(remote)) {
      logger.warn("Connection limit exceeded for {}", remote);
      ctx.close();
      return;
    }
    HttpClient client = HttpClient.newHttpClient();
    client
        .newWebSocketBuilder()
        .buildAsync(
            URI.create(gatewayWsUrl),
            new Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                setWebSocket(webSocket);
                webSocket.request(1);
                logger.info("WebSocket connected to {}", gatewayWsUrl);
              }

              @Override
              public CompletionStage<?> onText(
                  WebSocket webSocket, CharSequence data, boolean last) {
                ctx.writeAndFlush(data.toString() + "\n");
                webSocket.request(1);
                return null;
              }
            });
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    long now = System.currentTimeMillis();
    messageTimes.addLast(now);
    while (!messageTimes.isEmpty() && now - messageTimes.peekFirst() > 1000) {
      messageTimes.removeFirst();
    }
    var remote = ctx.channel() != null ? ctx.channel().remoteAddress() : null;
    if (messageTimes.size() > maxMessagesPerSecond) {
      logger.warn("Rate limit exceeded from {}", remote);
      return;
    }

    String sanitized = sanitize(msg);
    if (sanitized == null) {
      return;
    }

    if (webSocket != null) {
      webSocket.sendText(sanitized, true);
    } else {
      buffer.add(sanitized);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (webSocket != null) {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      webSocket = null;
    }
    var remote = ctx.channel() != null ? ctx.channel().remoteAddress() : null;
    connectionThrottler.release(remote);
    messageTimes.clear();
    buffer.clear();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Telnet handler error", cause);
    ctx.close();
  }

  private String sanitize(String msg) {
    String cleaned = msg.replaceAll("[^\\p{Print}]", "").trim();
    if (cleaned.isEmpty()) {
      return null;
    }
    return cleaned;
  }
}
