package net.firedevops.firemud.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TelnetServerHandlerTest {

  private TelnetServerHandler newHandler(SimpleMeterRegistry registry, boolean advertiseMcp) {
    return new TelnetServerHandler(
        "ws://localhost/ws",
        () -> {},
        () -> {},
        registry.counter("test"),
        registry.counter("discarded"),
        advertiseMcp);
  }

  @Test
  void bufferedInputFlushedOnWebSocketConnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
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
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
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
  void unsupportedTelnetCommandsAreDropped() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
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
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
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
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
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

  @Test
  void repliesToSupportedDoWithWill() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);

    byte[] bytes = {(byte) 255, (byte) 253, (byte) 1, 'g', 'o'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
    verify(ctx).writeAndFlush(captor.capture());
    ByteBuf buf = captor.getValue();
    assertEquals((byte) 255, buf.readByte());
    assertEquals((byte) 251, buf.readByte());
    assertEquals((byte) 1, buf.readByte());
    buf.release();
    assertEquals(1, handler.getBufferedSize());
  }

  @Test
  void unsupportedSubNegotiationIsDroppedAndCounted() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {(byte) 255, (byte) 250, (byte) 99, 'x', (byte) 255, (byte) 240, 'l', 'o', 'o', 'k'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    assertEquals(1.0, registry.counter("discarded").count());
    verify(ws).sendText("look", true);
  }

  @Test
  void mcpNegotiationAdvertisedWhenEnabled() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, true);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);

    handler.channelRead0(ctx, "#$#mcp-negotiate\r\n");

    assertTrue(handler.isMcpNegotiated());
    verify(ctx).writeAndFlush("#$#mcp version:2.1\r\n");
  }

  @Test
  void unsupportedDoNegotiationRespondsWithWontAndCounts() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {(byte) 255, (byte) 253, (byte) 99, 'c', 'm', 'd'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
    verify(ctx).writeAndFlush(captor.capture());
    ByteBuf buf = captor.getValue();
    assertEquals((byte) 255, buf.readByte());
    assertEquals((byte) 252, buf.readByte());
    assertEquals((byte) 99, buf.readByte());
    buf.release();
    assertEquals(1.0, registry.counter("discarded").count());
    verify(ws).sendText("cmd", true);
  }

  @Test
  void unsupportedWillNegotiationRespondsWithDontAndCounts() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    handler.setWebSocket(ws);

    byte[] bytes = {(byte) 255, (byte) 251, (byte) 99, 'c', 'm', 'd'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
    verify(ctx).writeAndFlush(captor.capture());
    ByteBuf buf = captor.getValue();
    assertEquals((byte) 255, buf.readByte());
    assertEquals((byte) 254, buf.readByte());
    assertEquals((byte) 99, buf.readByte());
    buf.release();
    assertEquals(1.0, registry.counter("discarded").count());
    verify(ws).sendText("cmd", true);
  }
}
