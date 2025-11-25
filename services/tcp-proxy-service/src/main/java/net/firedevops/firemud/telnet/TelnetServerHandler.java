package net.firedevops.firemud.telnet;

import io.micrometer.core.annotation.Timed;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
  private final io.micrometer.core.instrument.Counter discardedCommandCounter;
  private final boolean advertiseMcp;
  private volatile boolean mcpNegotiated;
  private WebSocket webSocket;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();

  public TelnetServerHandler(
      String gatewayWsUrl,
      boolean logOnly,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      io.micrometer.core.instrument.Counter discardedCommandCounter,
      boolean advertiseMcp) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.logOnly = logOnly;
    this.onConnect = onConnect;
    this.onDisconnect = onDisconnect;
    this.connectionCounter = connectionCounter;
    this.discardedCommandCounter = discardedCommandCounter;
    this.advertiseMcp = advertiseMcp;
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
    String sanitized = sanitize(ctx, msg);
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

  private static final byte WILL = (byte) 251;
  private static final byte WONT = (byte) 252;
  private static final byte DO = (byte) 253;
  private static final byte DONT = (byte) 254;
  private static final byte SB = (byte) 250;
  private static final byte SE = (byte) 240;
  private static final String MCP_PREFIX = "#$#";

  private static final Set<Byte> ALLOWED_COMMANDS =
      Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);

  private static final Set<Byte> SUPPORTED_OPTIONS = Set.of((byte) 1, (byte) 3);

  boolean isMcpNegotiated() {
    return mcpNegotiated;
  }

  private String sanitize(ChannelHandlerContext ctx, String msg) {
    byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == IAC) {
        i = handleIacSequence(ctx, bytes, i + 1, sb);
        continue;
      }
      if (b == '\r') {
        if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
          i++;
        }
        sb.append('\n');
        continue;
      }
      if (b == '\n') {
        sb.append('\n');
        continue;
      }
      if (b >= 32 && b <= 126) {
        sb.append((char) b);
      }
    }
    String cleaned = sb.toString();
    String negotiationCandidate = cleaned.stripLeading();
    if (!negotiationCandidate.isEmpty() && negotiationCandidate.startsWith(MCP_PREFIX)) {
      boolean initialNegotiation = !mcpNegotiated;
      mcpNegotiated = true;
      if (advertiseMcp && initialNegotiation) {
        ctx.writeAndFlush("#$#mcp version:2.1\r\n");
      }
    }
    return cleaned.isBlank() ? null : cleaned;
  }

  private int handleIacSequence(ChannelHandlerContext ctx, byte[] bytes, int index, StringBuilder sb) {
    if (index >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte command = bytes[index];
    if (command == IAC) {
      sb.append((char) IAC);
      return index;
    }
    switch (command) {
      case DO:
      case DONT:
        return negotiate(ctx, command, bytes, index);
      case WILL:
      case WONT:
        return negotiate(ctx, command, bytes, index);
      case SB:
        return handleSubNegotiation(ctx, bytes, index);
      default:
        if (!ALLOWED_COMMANDS.contains(command)) {
          discardedCommandCounter.increment();
        }
        return index;
    }
  }

  private int negotiate(ChannelHandlerContext ctx, byte command, byte[] bytes, int index) {
    if (index + 1 >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte option = bytes[index + 1];
    boolean supported = SUPPORTED_OPTIONS.contains(option);
    byte response;
    if (command == DO) {
      response = supported ? WILL : WONT;
    } else if (command == DONT) {
      response = WONT;
    } else if (command == WILL) {
      response = supported ? DO : DONT;
    } else {
      response = DONT;
    }
    if (!supported) {
      discardedCommandCounter.increment();
    }
    writeNegotiationResponse(ctx, response, option);
    return index + 1;
  }

  private int handleSubNegotiation(ChannelHandlerContext ctx, byte[] bytes, int index) {
    if (index + 1 >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte option = bytes[index + 1];
    int cursor = index + 2;
    while (cursor < bytes.length - 1) {
      if (bytes[cursor] == IAC && bytes[cursor + 1] == SE) {
        break;
      }
      cursor++;
    }
    if (cursor >= bytes.length - 1) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    if (!SUPPORTED_OPTIONS.contains(option)) {
      discardedCommandCounter.increment();
      writeNegotiationResponse(ctx, DONT, option);
    }
    return cursor + 1;
  }

  private void writeNegotiationResponse(ChannelHandlerContext ctx, byte response, byte option) {
    ByteBuf responseBuf = Unpooled.buffer(3);
    responseBuf.writeByte(IAC);
    responseBuf.writeByte(response);
    responseBuf.writeByte(option);
    ctx.writeAndFlush(responseBuf);
  }
}
