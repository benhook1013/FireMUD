package net.firedevops.firemud.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TelnetServerHandlerTest {

  @Test
  void bufferedInputFlushedOnWebSocketConnect() {
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 5);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelRead0(ctx, "look");
    handler.channelRead0(ctx, "move north");
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
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 5);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelRead0(ctx, "say hello");
    assertEquals(1, handler.getBufferedSize());

    handler.channelInactive(ctx);
    assertEquals(0, handler.getBufferedSize());
  }

  @Test
  void rateLimitDropsExcessMessages() {
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 1);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    handler.channelRead0(ctx, "look");
    handler.channelRead0(ctx, "look again");

    verify(ws, times(1)).sendText(anyString(), eq(true));
  }

  @Test
  void unsupportedTelnetCommandsAreDropped() {
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 5);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {(byte) 255, (byte) 1, 'l', 'o', 'o', 'k'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText("look", true);
  }

  @Test
  void controlCharactersAreRemoved() {
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 5);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {'t', 'e', 's', 't', 7};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText("test", true);
  }

  @Test
  void emptyAfterSanitizeIsIgnored() {
    TelnetServerHandler handler =
        new TelnetServerHandler("ws://localhost/ws", new ConnectionThrottler(1), 5);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {(byte) 255, (byte) 1};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws, times(0)).sendText(anyString(), eq(true));
  }
}
