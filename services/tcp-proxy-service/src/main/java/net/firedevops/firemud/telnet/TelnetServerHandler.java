package net.firedevops.firemud.telnet;

import io.micrometer.core.annotation.Timed;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);

  private final String gatewayWsUrl;
  private final boolean logOnly;
  private final Runnable onConnect;
  private final Runnable onDisconnect;
  private final io.micrometer.core.instrument.Counter connectionCounter;
  private WebSocket webSocket;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();

  public TelnetServerHandler(
      String gatewayWsUrl,
      boolean logOnly,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.logOnly = logOnly;
    this.onConnect = onConnect;
    this.onDisconnect = onDisconnect;
    this.connectionCounter = connectionCounter;
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
    String ip = null;
    if (remote instanceof java.net.InetSocketAddress address) {
      var inetAddress = address.getAddress();
      if (inetAddress != null) {
        ip = inetAddress.getHostAddress();
      }
    }
    connectionCounter.increment();
    onConnect.run();
    logger.info("Telnet client connected from {} targeting {}", ip != null ? ip : remote, gatewayWsUrl);
    if (logOnly) {
      logger.info("Log-only mode enabled; skipping WebSocket bridge for {}", gatewayWsUrl);
      return;
    }
    HttpClient client = HttpClient.newHttpClient();
    var builder = client.newWebSocketBuilder();
    if (ip != null) {
      builder.header("X-Client-IP", ip);
    }
    builder.buildAsync(
        URI.create(gatewayWsUrl),
        new Listener() {
          @Override
          public void onOpen(WebSocket webSocket) {
            setWebSocket(webSocket);
            webSocket.request(1);
            logger.info("WebSocket connected to {}", gatewayWsUrl);
          }

          @Override
          public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            logger.info("Gateway response: {}", data);
            ctx.writeAndFlush(data.toString() + "\n");
            webSocket.request(1);
            return null;
          }
        })
        .whenComplete(
            (socket, error) -> {
              if (error != null) {
                logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
              }
            });
  }

  @Override
  @Timed(value = "tcpproxy.command")
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    String sanitized = sanitize(msg);
    if (sanitized == null) {
      return;
    }

    logger.info("Received Telnet input: {}", sanitized);

    if (logOnly) {
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
    onDisconnect.run();
    buffer.clear();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Telnet handler error", cause);
    ctx.close();
  }

  private static final byte IAC = (byte) 255;

  private static final Set<Byte> ALLOWED_COMMANDS =
      Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);

  private String sanitize(String msg) {
    byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == IAC) {
        if (i + 1 < bytes.length) {
          byte cmd = bytes[++i];
          if (!ALLOWED_COMMANDS.contains(cmd)) {
            continue;
          }
          // drop allowed Telnet commands rather than forwarding
          continue;
        }
        continue;
      }
      if (b >= 32 && b <= 126) {
        sb.append((char) b);
      }
    }
    String cleaned = sb.toString().trim();
    return cleaned.isEmpty() ? null : cleaned;
  }
}
