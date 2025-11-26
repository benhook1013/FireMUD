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
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;

class TelnetServerHandlerTest {

  private TelnetServerHandler newHandler(SimpleMeterRegistry registry, boolean advertiseMcp) {
    return new TelnetServerHandler(
        "ws://localhost/ws",
        false,
        () -> {},
        () -> {},
        registry.counter("test"),
        registry.counter("discarded"),
        advertiseMcp,
        registry,
        Mockito.mock(TcpProxyEventService.class),
        new AtomicInteger());
  }

  private TelnetServerHandler newHandler(
      SimpleMeterRegistry registry,
      boolean advertiseMcp,
      TelnetServerHandler.WebSocketConnector connector) {
    return new TelnetServerHandler(
        "ws://localhost/ws",
        false,
        () -> {},
        () -> {},
        registry.counter("test"),
        registry.counter("discarded"),
        advertiseMcp,
        registry,
        connector,
        Mockito.mock(TcpProxyEventService.class),
        new AtomicInteger());
  }

  private WebSocket stubWebSocket() {
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    return ws;
  }

  @Test
  void logOnlyModeSkipsGatewayConnection() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler.WebSocketConnector connector =
        Mockito.mock(TelnetServerHandler.WebSocketConnector.class);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            true,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            Mockito.mock(TcpProxyEventService.class));

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");
    handler.channelRead0(ctx, "look");

    verify(connector, never()).connect(anyString(), anyString(), anyString(), anyString(), any());
    assertEquals(0, handler.getBufferedSize());

    executor.shutdownGracefully();
  }

  @Test
  void bufferedInputFlushedOnWebSocketConnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url, ip, session, tenant, listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");
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
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url, ip, session, tenant, listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");
    handler.channelRead0(ctx, "say hello");
    assertEquals(1, handler.getBufferedSize());

    handler.channelInactive(ctx);
    assertEquals(0, handler.getBufferedSize());
  }

  @Test
  void connectionClosedWhenBufferDepthExceeded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url, ip, session, tenant, listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    for (int i = 0; i < 600; i++) {
      handler.channelRead0(ctx, "cmd" + i);
    }

    verify(ctx).close();
    assertEquals(512, handler.getBufferedSize());
    assertEquals(1.0, registry.counter("discarded").count());
    executor.shutdownGracefully();
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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    byte[] bytes = {(byte) 255, (byte) 1, 'l', 'o', 'o', 'k'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText("look", true);
  }

  @Test
  void applicationInputIsIgnoredUntilSessionEnvelopeReceived() {
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

    handler.channelRead0(ctx, "look");
    verify(ws, never()).sendText(anyString(), eq(true));

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");
    handler.channelRead0(ctx, "move north");

    verify(ws).sendText("move north", true);
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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    byte[] bytes = {'t', 'e', 's', 't', 7};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText("test", true);
  }

  @Test
  void sessionEnvelopePropagatesIntoWebSocketHeaders() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    List<String> captured = new ArrayList<>();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url, ip, session, tenant, listener) -> {
              captured.add(session);
              captured.add(tenant);
              WebSocket ws = mock(WebSocket.class);
              return CompletableFuture.completedFuture(ws);
            });
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-9");

    assertEquals(List.of("sess-1", "tenant-9"), captured);
    executor.shutdownGracefully();
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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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

    handler.setWebSocket(stubWebSocket());
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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
    assertEquals(0, handler.getBufferedSize());
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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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

    handler.setWebSocket(stubWebSocket());
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

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

  @Test
  void sessionMetadataCapturedAndSentToConnector() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    assertEquals("sess-1", connector.getSessionId());
    assertEquals("tenant-1", connector.getTenantId());
    executor.shutdownGracefully();
  }

  @Test
  void sessionEnvelopeSupportsColonSeparatedPayload() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1:tenant-7");

    assertEquals("sess-1", connector.getSessionId());
    assertEquals("tenant-7", connector.getTenantId());
    executor.shutdownGracefully();
  }

  @Test
  void malformedSessionEnvelopeIsIgnored() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION missingTenant");

    assertEquals(null, connector.getSessionId());
    assertEquals(null, connector.getTenantId());
    executor.shutdownGracefully();
  }

  @Test
  void disconnectNotificationSentWhenSessionKnown() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            (url, ip, session, tenant, listener) -> {
              listener.onOpen(new RecordingWebSocket());
              return CompletableFuture.completedFuture(new RecordingWebSocket());
            },
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");
    handler.channelInactive(ctx);

    Mockito.verify(eventService, Mockito.timeout(500)).notifyDisconnect("sess-1", "tenant-1");
    executor.shutdownGracefully();
  }

  @Test
  void bufferedCommandsPushedAfterGatewayReconnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TestConnector connector = new TestConnector();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    when(eventService.pushBufferedInput(Mockito.anyString(), Mockito.anyList(), Mockito.anyString()))
        .thenReturn(
            PushBufferedInputResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("OK").build())
                .build());
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    StubWebSocket initialSocket = connector.getCurrent();
    handler.channelRead0(ctx, "look");
    handler.channelRead0(ctx, "say hi");

    connector.getListener().onClose(initialSocket, 1001, "closing");
    handler.channelRead0(ctx, "move north");
    handler.channelRead0(ctx, "get sword");

    assertEquals(2, handler.getBufferedSize());

    connector.reconnect();
    executor.shutdownGracefully();

    Mockito.verify(eventService, Mockito.timeout(1000))
        .pushBufferedInput(
            Mockito.eq("sess-1"), Mockito.eq(List.of("move north", "get sword")), Mockito.eq("tenant-1"));
    assertTrue(connector.getCurrent().getSentTexts().isEmpty());
  }

  @Test
  void stuckSendIsCancelledAndPushedAfterDisconnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ControllableConnector connector = new ControllableConnector(new HangingWebSocket());
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    when(eventService.pushBufferedInput(Mockito.anyString(), Mockito.anyList(), Mockito.anyString()))
        .thenReturn(
            PushBufferedInputResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("OK").build())
                .build());
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    HangingWebSocket initialSocket = (HangingWebSocket) connector.getCurrent();
    handler.channelRead0(ctx, "look");

    connector.getListener().onClose(initialSocket, 1001, "closing");

    RecordingWebSocket reconnectedSocket = new RecordingWebSocket();
    connector.reconnect(reconnectedSocket);

    executor.shutdownGracefully();

    assertTrue(initialSocket.getSendFuture().isCancelled());
    Mockito.verify(eventService, Mockito.timeout(1000))
        .pushBufferedInput(Mockito.eq("sess-1"), Mockito.eq(List.of("look")), Mockito.eq("tenant-1"));
    assertTrue(reconnectedSocket.getSentTexts().isEmpty());
  }

  @Test
  void bufferedCommandsArePushedToSessionServiceOnReconnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TestConnector connector = new TestConnector();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    when(eventService.pushBufferedInput(Mockito.anyString(), Mockito.anyList(), Mockito.anyString()))
        .thenReturn(
            PushBufferedInputResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("OK").build())
                .build());
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    handler.channelRead0(ctx, "say hi");
    connector.getListener().onClose(connector.getCurrent(), 1001, "closing");
    handler.channelRead0(ctx, "move north");

    connector.reconnect();
    executor.shutdownGracefully();

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    Mockito.verify(eventService, Mockito.timeout(1000))
        .pushBufferedInput(Mockito.eq("sess-1"), captor.capture(), Mockito.eq("tenant-1"));
    assertEquals(List.of("move north"), captor.getValue());
    assertTrue(connector.getCurrent().getSentTexts().isEmpty());
  }

  @Test
  void bufferedCommandsRetryWhenPushReturnsErrorDetail() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TestConnector connector = new TestConnector();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    when(eventService.pushBufferedInput(Mockito.eq("sess-1"), Mockito.anyList(), Mockito.eq("tenant-1")))
        .thenReturn(
            PushBufferedInputResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("UPSTREAM_FAILURE")
                        .setMessage("session service down")
                        .build())
                .build());
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            connector,
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-1 tenant-1");

    connector.getListener().onClose(connector.getCurrent(), 1001, "closing");
    handler.channelRead0(ctx, "cast fireball");

    connector.reconnect();
    executor.shutdownGracefully();

    Mockito.verify(eventService, Mockito.timeout(1000))
        .pushBufferedInput(Mockito.eq("sess-1"), Mockito.eq(List.of("cast fireball")), Mockito.eq("tenant-1"));

    Thread.sleep(200);

    assertEquals(List.of("cast fireball"), connector.getCurrent().getSentTexts());
    assertEquals(0, handler.getBufferedSize());
  }

  @Test
  void structuredEventsIncludeConnectionDuration() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            false,
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            eventService,
            new AtomicInteger());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("10.0.0.5", 9000));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "SESSION sess-99 tenant-42");
    handler.channelInactive(ctx);

    ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(eventService).recordConnectEvent("sess-99", "tenant-42", "10.0.0.5");
    verify(eventService)
        .recordDisconnectEvent(
            eq("sess-99"), eq("tenant-42"), eq("10.0.0.5"), durationCaptor.capture());
    assertTrue(durationCaptor.getValue().toMillis() >= 0);
    executor.shutdownGracefully();
  }

  private static final class RecordingConnector implements TelnetServerHandler.WebSocketConnector {
    private StubWebSocket current;
    private WebSocket.Listener listener;
    private String sessionId;
    private String tenantId;

    @Override
    public CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String sessionId,
        String tenantId,
        WebSocket.Listener listener) {
      this.listener = listener;
      this.sessionId = sessionId;
      this.tenantId = tenantId;
      current = new StubWebSocket();
      listener.onOpen(current);
      return CompletableFuture.completedFuture(current);
    }

    String getSessionId() {
      return sessionId;
    }

    String getTenantId() {
      return tenantId;
    }

    StubWebSocket getCurrent() {
      return current;
    }

    WebSocket.Listener getListener() {
      return listener;
    }
  }

  private static final class TestConnector implements TelnetServerHandler.WebSocketConnector {
    private StubWebSocket current;
    private WebSocket.Listener listener;

    @Override
    public CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String sessionId,
        String tenantId,
        WebSocket.Listener listener) {
      this.listener = listener;
      current = new StubWebSocket();
      listener.onOpen(current);
      return CompletableFuture.completedFuture(current);
    }

    StubWebSocket getCurrent() {
      return current;
    }

    WebSocket.Listener getListener() {
      return listener;
    }

    StubWebSocket reconnect() {
      current = new StubWebSocket();
      listener.onOpen(current);
      return current;
    }
  }

  private static final class ControllableConnector implements TelnetServerHandler.WebSocketConnector {
    private WebSocket current;
    private WebSocket.Listener listener;

    ControllableConnector(WebSocket first) {
      this.current = first;
    }

    @Override
    public CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String sessionId,
        String tenantId,
        WebSocket.Listener listener) {
      this.listener = listener;
      listener.onOpen(current);
      return CompletableFuture.completedFuture(current);
    }

    WebSocket.Listener getListener() {
      return listener;
    }

    WebSocket getCurrent() {
      return current;
    }

    WebSocket reconnect(WebSocket next) {
      current = next;
      listener.onOpen(current);
      return current;
    }
  }

  private static final class StubWebSocket implements WebSocket {
    private final List<String> sentTexts = new ArrayList<>();
    private boolean closed;

    List<String> getSentTexts() {
      return sentTexts;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      sentTexts.add(data.toString());
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      closed = true;
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return closed;
    }

    @Override
    public boolean isInputClosed() {
      return closed;
    }

    @Override
    public void abort() {
      closed = true;
    }
  }

  private static final class HangingWebSocket implements WebSocket {
    private final CompletableFuture<WebSocket> sendFuture = new CompletableFuture<>();

    CompletableFuture<WebSocket> getSendFuture() {
      return sendFuture;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      return sendFuture;
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      sendFuture.cancel(true);
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return false;
    }

    @Override
    public boolean isInputClosed() {
      return false;
    }

    @Override
    public void abort() {
      sendFuture.cancel(true);
    }
  }

  private static final class RecordingWebSocket implements WebSocket {
    private final List<String> sentTexts = new ArrayList<>();

    List<String> getSentTexts() {
      return sentTexts;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      sentTexts.add(data.toString());
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return false;
    }

    @Override
    public boolean isInputClosed() {
      return false;
    }

    @Override
    public void abort() {}
  }
}
