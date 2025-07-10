package net.firedevops.firemud.telnet;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);

  private final String gatewayWsUrl;
  private WebSocket webSocket;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();

  public TelnetServerHandler(String gatewayWsUrl) {
    this.gatewayWsUrl = gatewayWsUrl;
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
    if (webSocket != null) {
      webSocket.sendText(msg, true);
    } else {
      buffer.add(msg);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (webSocket != null) {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      webSocket = null;
    }
    buffer.clear();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Telnet handler error", cause);
    ctx.close();
  }
}
