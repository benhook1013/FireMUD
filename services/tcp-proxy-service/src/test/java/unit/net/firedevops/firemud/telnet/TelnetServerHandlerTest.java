package net.firedevops.firemud.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.netty.channel.ChannelHandlerContext;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TelnetServerHandlerTest {

  @Test
  void bufferedInputFlushedOnWebSocketConnect() {
    TelnetServerHandler handler = new TelnetServerHandler("ws://localhost/ws");
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

    handler.channelRead0(ctx, "cmd1");
    handler.channelRead0(ctx, "cmd2");
    assertEquals(2, handler.getBufferedSize());

    WebSocket ws = mock(WebSocket.class);
    // sendText returns CompletableFuture<WebSocket>
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);

    handler.setWebSocket(ws);

    verify(ws, times(2)).sendText(anyString(), eq(true));
    assertEquals(0, handler.getBufferedSize());
  }

  @Test
  void bufferClearedOnDisconnect() {
    TelnetServerHandler handler = new TelnetServerHandler("ws://localhost/ws");
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

    handler.channelRead0(ctx, "line");
    assertEquals(1, handler.getBufferedSize());

    handler.channelInactive(ctx);
    assertEquals(0, handler.getBufferedSize());
  }
}
