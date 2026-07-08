package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.test.TestAsyncAssertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TelnetServerHandlerTest {

  private final LookCacheService lookCacheService = mock(LookCacheService.class);

  private TelnetServerHandler newHandler(SimpleMeterRegistry registry, boolean advertiseMcp) {
    return newHandler(registry, advertiseMcp, () -> true, TelnetServerHandler::createWebSocket);
  }

  private TelnetServerHandler newHandler(
      SimpleMeterRegistry registry,
      boolean advertiseMcp,
      TelnetServerHandler.WebSocketConnector connector) {
    return newHandler(registry, advertiseMcp, () -> true, connector);
  }

  private TelnetServerHandler newHandler(
      SimpleMeterRegistry registry,
      boolean advertiseMcp,
      java.util.function.BooleanSupplier gameplayTrafficReady,
      TelnetServerHandler.WebSocketConnector connector) {
    return new TelnetServerHandler(
        "ws://localhost/ws",
        () -> {},
        () -> {},
        registry.counter("test"),
        registry.counter("discarded"),
        advertiseMcp,
        registry,
        gameplayTrafficReady,
        connector,
        Mockito.mock(TcpProxyEventService.class),
        new AtomicInteger(),
        "1",
        "1",
        "demo",
        "production",
        "1",
        lookCacheService);
  }

  private WebSocket stubWebSocket() {
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);
    return ws;
  }

  @Test
  void bufferedInputFlushedOnWebSocketConnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> new CompletableFuture<>());
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
  void connectionRejectedWhileGameplayPathIsUnready() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            () -> false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) ->
                CompletableFuture.failedFuture(new IllegalStateException("should not connect")));
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);

    handler.channelActive(ctx);

    verify(ctx)
        .writeAndFlush("DISCONNECT startup_unavailable Gameplay path starting; please reconnect\n");
    verify(future).addListener(any(ChannelFutureListener.class));
    executor.shutdownGracefully();
  }

  @Test
  void connectionWritesInitialGuidanceWhenGameplayPathIsReady() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(ctx.writeAndFlush(any())).thenReturn(null);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    verify(ctx)
        .writeAndFlush(
            "OK CONNECTED\n"
                + "Type WORLDS to list available worlds.\n"
                + "Type LOGIN <email> <password> to authenticate.\n"
                + "Type PLAY <world> after LOGIN to enter a world.\n"
                + "Type HELP for commands.\n");
    executor.shutdownGracefully();
  }

  @Test
  void helpCommandReprintsInitialGuidance() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler = newHandler(registry, false);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(ctx.writeAndFlush(any())).thenReturn(null);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "HELP");

    verify(ctx, times(2))
        .writeAndFlush(
            "OK CONNECTED\n"
                + "Type WORLDS to list available worlds.\n"
                + "Type LOGIN <email> <password> to authenticate.\n"
                + "Type PLAY <world> after LOGIN to enter a world.\n"
                + "Type HELP for commands.\n");
    executor.shutdownGracefully();
  }

  @Test
  void bufferClearedOnDisconnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> new CompletableFuture<>());
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
  void connectionClosedWhenBufferDepthExceeded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    for (int i = 0; i < 600; i++) {
      handler.channelRead0(ctx, "cmd" + i);
    }

    verify(ctx).close();
    assertEquals(512, handler.getBufferedSize());
    assertEquals(1.0, registry.counter("discarded").count());
    executor.shutdownGracefully();
  }

  @Test
  void telnetConnectionClosesWhenBufferDepthLimitReached() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    int maxDepth = maxBufferDepth();
    for (int i = 0; i < maxDepth; i++) {
      handler.channelRead0(ctx, "cmd" + i);
    }

    assertEquals(maxDepth, handler.getBufferedSize());
    handler.channelRead0(ctx, "overflow");

    verify(ctx).close();
    executor.shutdownGracefully();
  }

  @Test
  void discardedCounterIncrementsWhenBufferDepthLimitReached() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> new CompletableFuture<>());
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    int maxDepth = maxBufferDepth();
    for (int i = 0; i < maxDepth; i++) {
      handler.channelRead0(ctx, "cmd" + i);
    }

    assertEquals(0.0, registry.counter("discarded").count());
    handler.channelRead0(ctx, "overflow");

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

    byte[] bytes = {(byte) 255, (byte) 1, 'l', 'o', 'o', 'k'};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText(anyString(), eq(true));
  }

  @Test
  void applicationInputUsesHiddenBootstrapDefaults() {
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

    byte[] bytes = {'t', 'e', 's', 't', 7};
    String msg = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    handler.channelRead0(ctx, msg);

    verify(ws).sendText("test", true);
  }

  @Test
  void hiddenBootstrapMetadataPropagatesIntoWebSocketHeaders() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    List<String> captured = new ArrayList<>();
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
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

    assertEquals(List.of("1", "1"), captured);
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
    TelnetServerHandler handler =
        newHandler(
            registry,
            false,
            (gatewayWsUrl,
                clientIp,
                proxyConnectionId,
                gameInstanceId,
                tenantId,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> CompletableFuture.completedFuture(mock(WebSocket.class)));
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Mockito.when(ctx.writeAndFlush(any())).thenReturn(null);
    WebSocket ws = mock(WebSocket.class);
    CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(ws);
    org.mockito.Mockito.when(ws.sendText(anyString(), eq(true))).thenReturn(future);

    handler.setWebSocket(ws);

    byte[] bytes = {
      (byte) 255, (byte) 250, (byte) 99, 'x', (byte) 255, (byte) 240, 'l', 'o', 'o', 'k'
    };
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

  @Test
  void defaultBootstrapMetadataCapturedAndSentToConnector() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    assertEquals("1", connector.getSessionId());
    assertEquals("1", connector.getTenantId());
    assertEquals("demo", connector.getWorldSlug());
    assertEquals("production", connector.getRealmSlug());
    assertEquals("1", connector.getPointerVersion());
    executor.shutdownGracefully();
  }

  @Test
  void preLoginCommandsFlowWithoutDefaultBootstrapMetadata() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            null,
            null,
            null,
            null,
            null,
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "WORLDS");

    assertEquals("WORLDS", connector.current.sentTexts.get(0));
    assertNull(connector.getSessionId());
    assertNull(connector.getTenantId());
    executor.shutdownGracefully();
  }

  @Test
  void partialDefaultRoutingBundleIsDroppedBeforeGatewayBootstrap() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    assertEquals("1", connector.getSessionId());
    assertEquals("1", connector.getTenantId());
    assertNull(connector.getWorldSlug());
    assertNull(connector.getRealmSlug());
    assertNull(connector.getPointerVersion());
    executor.shutdownGracefully();
  }

  @Test
  void nonPositiveDefaultRoutingBundleIsDroppedBeforeGatewayBootstrap() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingConnector connector = new RecordingConnector();
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            connector,
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "0",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);

    assertEquals("1", connector.getSessionId());
    assertEquals("1", connector.getTenantId());
    assertNull(connector.getWorldSlug());
    assertNull(connector.getRealmSlug());
    assertNull(connector.getPointerVersion());
    executor.shutdownGracefully();
  }

  @Test
  void disconnectNotificationSentWhenSessionKnown() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listener.onOpen(new RecordingWebSocket());
              return CompletableFuture.completedFuture(new RecordingWebSocket());
            },
            eventService,
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "look");
    handler.channelInactive(ctx);

    Mockito.verify(eventService, Mockito.timeout(500))
        .notifyDisconnect(eq("1"), eq("1"), anyString(), anyLong());
    executor.shutdownGracefully();
  }

  @Test
  void shutdownPathSuppressesExpectedDisconnectNotifyTransportNoise() throws InterruptedException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    Mockito.when(eventService.notifyDisconnect(eq("1"), eq("1"), anyString(), anyLong()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listener.onOpen(new RecordingWebSocket());
              return CompletableFuture.completedFuture(new RecordingWebSocket());
            },
            eventService,
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    EventExecutor executor = mock(EventExecutor.class);
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(executor.isShuttingDown()).thenReturn(true);

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "look");
    handler.channelInactive(ctx);

    TestAsyncAssertions.assertEventually(
        "expected shutdown disconnect notify metric",
        Duration.ofSeconds(1),
        () ->
            registry
                    .counter(
                        "tcpproxy.disconnect.notify.expected_shutdown_transport_failure",
                        "status",
                        Status.Code.UNAVAILABLE.name())
                    .count()
                >= 1.0);
    assertEquals(
        0.0,
        registry
            .counter("tcpproxy.disconnect.notify.transport_failure", "status", "UNAVAILABLE")
            .count());
  }

  @Test
  void gatewayDisconnectFailClosesTelnet() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicInteger connectAttempts = new AtomicInteger();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
    AtomicReference<RecordingWebSocket> initialSocket = new AtomicReference<>();
    List<ScheduledTask> scheduledTasks = new ArrayList<>();
    EventExecutor executor = mock(EventExecutor.class);
    when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(
            invocation -> {
              Runnable command = invocation.getArgument(0);
              long delay = invocation.getArgument(1);
              TimeUnit unit = invocation.getArgument(2);
              scheduledTasks.add(new ScheduledTask(command, unit.toMillis(delay)));
              return mockScheduledFutureTyped();
            });
    when(executor.scheduleAtFixedRate(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mockScheduledFutureTyped());
    when(executor.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mockScheduledFutureTyped());

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              if (connectAttempts.getAndIncrement() == 0) {
                RecordingWebSocket ws = new RecordingWebSocket();
                initialSocket.set(ws);
                listener.onOpen(ws);
                return CompletableFuture.completedFuture(ws);
              }
              return CompletableFuture.failedFuture(new RuntimeException("boom"));
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);
    Channel channel = mock(Channel.class);
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);

    handler.channelActive(ctx);
    handler.channelRead0(ctx, "look");

    WebSocket.Listener listener = listenerRef.get();
    listener.onClose(initialSocket.get(), 1001, "closing");

    verify(ctx).writeAndFlush(startsWith("DISCONNECT backend_unavailable "));
    verify(future).addListener(any(ChannelFutureListener.class));
  }

  @Test
  void gatewayCleanLogoutWithGatewayRestartPreservesLogoutSubreason() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              RecordingWebSocket ws = new RecordingWebSocket();
              listener.onOpen(ws);
              return CompletableFuture.completedFuture(ws);
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);

    handler.channelActive(ctx);
    listenerRef.get().onClose(mock(WebSocket.class), 1000, "logout;subreason=gateway_restart");

    verify(ctx)
        .writeAndFlush(
            "DISCONNECT logout;subreason=gateway_restart Gameplay session ended; please reconnect\n");
    verify(future).addListener(any(ChannelFutureListener.class));
    assertEquals(
        1.0,
        registry.counter("tcpproxy.bridge.shutdown", "classification", "planned_drain").count());
    executor.shutdownGracefully();
  }

  @Test
  void gatewayCleanLogoutWithTakeoverPreservesLogoutSubreason() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              RecordingWebSocket ws = new RecordingWebSocket();
              listener.onOpen(ws);
              return CompletableFuture.completedFuture(ws);
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);

    handler.channelActive(ctx);
    listenerRef.get().onClose(mock(WebSocket.class), 1000, "logout;subreason=takeover");

    verify(ctx)
        .writeAndFlush(
            "DISCONNECT logout;subreason=takeover Gameplay session ended; please reconnect\n");
    verify(future).addListener(any(ChannelFutureListener.class));
    assertEquals(
        1.0,
        registry.counter("tcpproxy.bridge.shutdown", "classification", "upstream_logout").count());
    executor.shutdownGracefully();
  }

  @Test
  void gatewayErrorClosesTelnetAsBackendUnavailable() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              RecordingWebSocket ws = new RecordingWebSocket();
              listener.onOpen(ws);
              return CompletableFuture.completedFuture(ws);
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);

    handler.channelActive(ctx);
    listenerRef.get().onError(mock(WebSocket.class), new RuntimeException("boom"));

    verify(ctx).writeAndFlush(startsWith("DISCONNECT backend_unavailable "));
    verify(future).addListener(any(ChannelFutureListener.class));
    assertEquals(
        1.0,
        registry
            .counter("tcpproxy.bridge.shutdown", "classification", "unattributed_failure")
            .count());
    executor.shutdownGracefully();
  }

  @Test
  void gatewayExplicitInternalErrorPreservesInternalErrorDisconnect() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              RecordingWebSocket ws = new RecordingWebSocket();
              listener.onOpen(ws);
              return CompletableFuture.completedFuture(ws);
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);

    handler.channelActive(ctx);
    listenerRef.get().onClose(stubWebSocket(), 1011, "internal_error");

    verify(ctx)
        .writeAndFlush("DISCONNECT internal_error Gameplay connection failed; please reconnect\n");
    verify(future).addListener(any(ChannelFutureListener.class));
    assertEquals(
        1.0,
        registry
            .counter("tcpproxy.bridge.shutdown", "classification", "unattributed_failure")
            .count());
    executor.shutdownGracefully();
  }

  @Test
  void gatewayMissingCloseMetadataFallsBackToBackendUnavailable() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();

    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            (url,
                ip,
                proxyConnectionId,
                session,
                tenant,
                worldSlug,
                realmSlug,
                pointerVersion,
                listener) -> {
              listenerRef.set(listener);
              RecordingWebSocket ws = new RecordingWebSocket();
              listener.onOpen(ws);
              return CompletableFuture.completedFuture(ws);
            },
            Mockito.mock(TcpProxyEventService.class),
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    ChannelFuture future = mock(ChannelFuture.class);
    when(future.addListener(any(ChannelFutureListener.class))).thenReturn(future);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    when(ctx.writeAndFlush(any())).thenReturn(future);

    handler.channelActive(ctx);
    listenerRef.get().onClose(stubWebSocket(), 1006, "");

    verify(ctx)
        .writeAndFlush("DISCONNECT backend_unavailable Gateway link dropped; please reconnect\n");
    verify(future).addListener(any(ChannelFutureListener.class));
    assertEquals(
        1.0,
        registry
            .counter("tcpproxy.bridge.shutdown", "classification", "unattributed_failure")
            .count());
    executor.shutdownGracefully();
  }

  @Test
  void structuredEventsIncludeConnectionDuration() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TelnetServerHandler handler =
        new TelnetServerHandler(
            "ws://localhost/ws",
            () -> {},
            () -> {},
            registry.counter("test"),
            registry.counter("discarded"),
            false,
            registry,
            () -> true,
            TelnetServerHandler::createWebSocket,
            eventService,
            new AtomicInteger(),
            "1",
            "1",
            "demo",
            "production",
            "1",
            lookCacheService);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    DefaultEventExecutor executor = new DefaultEventExecutor();
    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress("10.0.0.5", 9000));

    handler.channelActive(ctx);
    handler.channelInactive(ctx);

    ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(eventService).recordConnectEvent("1", "1", "10.0.0.5");
    verify(eventService)
        .recordDisconnectEvent(eq("1"), eq("1"), eq("10.0.0.5"), durationCaptor.capture());
    assertTrue(durationCaptor.getValue().toMillis() >= 0);
    executor.shutdownGracefully();
  }

  private static int maxBufferDepth() {
    try {
      Field field = TelnetServerHandler.class.getDeclaredField("MAX_BUFFER_DEPTH");
      field.setAccessible(true);
      return field.getInt(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <V> ScheduledFuture<V> mockScheduledFutureTyped() {
    return mock(ScheduledFuture.class);
  }

  private static final class RecordingConnector implements TelnetServerHandler.WebSocketConnector {
    private StubWebSocket current;
    private String sessionId;
    private String tenantId;
    private String worldSlug;
    private String realmSlug;
    private String pointerVersion;

    @Override
    public CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String proxyConnectionId,
        String sessionId,
        String tenantId,
        String worldSlug,
        String realmSlug,
        String pointerVersion,
        WebSocket.Listener listener) {
      this.sessionId = sessionId;
      this.tenantId = tenantId;
      this.worldSlug = worldSlug;
      this.realmSlug = realmSlug;
      this.pointerVersion = pointerVersion;
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

    String getWorldSlug() {
      return worldSlug;
    }

    String getRealmSlug() {
      return realmSlug;
    }

    String getPointerVersion() {
      return pointerVersion;
    }
  }

  private static final class StubWebSocket implements WebSocket {
    private final List<String> sentTexts = new ArrayList<>();
    private boolean closed;

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

  private static final class RecordingWebSocket implements WebSocket {
    private final List<String> sentTexts = new ArrayList<>();

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

  private static final record ScheduledTask(Runnable command, long delayMillis) {}
}
