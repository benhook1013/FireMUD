package net.firedevops.firemud.tcpproxy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TcpProxyEventClientTest {

  @Test
  void notifyDisconnectFailsClearlyWhenClosedOrUninitialized() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            mock(GrpcChannelFactory.class),
            mock(GrpcTlsMaterialResolver.class),
            BlockingGrpcStubCustomizer.noop());

    RuntimeException uninitialized =
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class, () -> client.notifyDisconnect("42", "7", "proxy-1", 9L));
    assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(uninitialized).getCode());
    assertEquals(
        "TcpProxyEventClient is closed or not initialized",
        Status.fromThrowable(uninitialized).getDescription());

    client.close();
    RuntimeException closed =
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class, () -> client.notifyDisconnect("42", "7", "proxy-1", 9L));
    assertInstanceOf(io.grpc.StatusRuntimeException.class, closed);
    assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(closed).getCode());
    assertEquals(
        "TcpProxyEventClient is closed or not initialized",
        Status.fromThrowable(closed).getDescription());
  }

  @Test
  void notifyDisconnectUsesBoundedDeadlineAndCanonicalRequestFields() {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    BlockingGrpcStubCustomizer stubCustomizer = BlockingGrpcStubCustomizer.noop();
    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            mock(GrpcChannelFactory.class),
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub stub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub deadlineStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    when(stub.withDeadlineAfter(2000L, TimeUnit.MILLISECONDS)).thenReturn(deadlineStub);
    when(deadlineStub.notifyDisconnect(any()))
        .thenReturn(NotifyDisconnectResponse.newBuilder().build());
    setField(client, "stub", stub);

    client.notifyDisconnect("42", "7", "proxy-1", 9L);

    ArgumentCaptor<net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest> requestCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.class);
    verify(stub).withDeadlineAfter(2000L, TimeUnit.MILLISECONDS);
    verify(deadlineStub).notifyDisconnect(requestCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("", requestCaptor.getValue().getSessionId());
    org.junit.jupiter.api.Assertions.assertEquals(
        "42", requestCaptor.getValue().getGameInstanceId());
    org.junit.jupiter.api.Assertions.assertEquals("7", requestCaptor.getValue().getTenantId());
    org.junit.jupiter.api.Assertions.assertEquals(
        "proxy-1", requestCaptor.getValue().getProxyConnectionId());
    org.junit.jupiter.api.Assertions.assertEquals(
        9L, requestCaptor.getValue().getDisconnectSequence());
  }

  @Test
  void reloadChannelCustomizesOutboundStub() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    io.grpc.ManagedChannel channel = mock(io.grpc.ManagedChannel.class);
    when(channelFactory.buildChannel(any(), any(Integer.class), any(), any(Boolean.class), any()))
        .thenReturn(channel);
    BlockingGrpcStubCustomizer stubCustomizer = mock(BlockingGrpcStubCustomizer.class);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub customizedStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);
    when(stubCustomizer.customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class)))
        .thenReturn(customizedStub);

    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            channelFactory,
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);

    invokeReloadChannel(client);

    verify(stubCustomizer).customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class));
    org.junit.jupiter.api.Assertions.assertSame(customizedStub, getField(client, "stub"));
  }

  @Test
  void reloadChannelKeepsExistingStateWhenStubCustomizationFails() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    io.grpc.ManagedChannel previousChannel = mock(io.grpc.ManagedChannel.class);
    io.grpc.ManagedChannel newChannel = mock(io.grpc.ManagedChannel.class);
    when(channelFactory.buildChannel(any(), any(Integer.class), any(), any(Boolean.class), any()))
        .thenReturn(newChannel);
    BlockingGrpcStubCustomizer stubCustomizer = mock(BlockingGrpcStubCustomizer.class);
    when(stubCustomizer.customize(any(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class)))
        .thenThrow(new IllegalStateException("customizer failed"));
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub previousStub =
        mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class);

    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            channelFactory,
            mock(GrpcTlsMaterialResolver.class),
            stubCustomizer);
    setField(client, "channel", previousChannel);
    setField(client, "stub", previousStub);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> invokeReloadChannel(client));

    org.junit.jupiter.api.Assertions.assertSame(previousChannel, getField(client, "channel"));
    org.junit.jupiter.api.Assertions.assertSame(previousStub, getField(client, "stub"));
    verify(newChannel).shutdown();
    verify(previousChannel, org.mockito.Mockito.never()).shutdown();
  }

  @Test
  void closeDuringReloadDiscardsUnpublishedReplacementChannel() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    io.grpc.ManagedChannel previousChannel = mock(io.grpc.ManagedChannel.class);
    io.grpc.ManagedChannel replacementChannel = mock(io.grpc.ManagedChannel.class);
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    when(channelFactory.buildChannel(any(), any(Integer.class), any(), any(Boolean.class), any()))
        .thenAnswer(
            ignored -> {
              buildStarted.countDown();
              if (!releaseBuild.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release replacement build");
              }
              return replacementChannel;
            });
    GrpcTlsMaterialResolver resolver = mock(GrpcTlsMaterialResolver.class);
    when(resolver.resolve(tlsProps)).thenReturn(null);
    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints, tlsProps, channelFactory, resolver, BlockingGrpcStubCustomizer.noop());
    setField(client, "channel", previousChannel);
    setField(client, "stub", mock(TcpProxyServiceGrpc.TcpProxyServiceBlockingStub.class));
    AtomicReference<Throwable> reloadFailure = new AtomicReference<>();

    Thread reloadThread =
        new Thread(
            () -> {
              try {
                invokeReloadChannel(client);
              } catch (Throwable e) {
                reloadFailure.set(e);
              }
            });
    reloadThread.start();
    try {
      org.junit.jupiter.api.Assertions.assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
      client.close();
    } finally {
      releaseBuild.countDown();
      reloadThread.join(5_000);
    }

    org.junit.jupiter.api.Assertions.assertFalse(reloadThread.isAlive());
    org.junit.jupiter.api.Assertions.assertNull(reloadFailure.get());
    org.junit.jupiter.api.Assertions.assertNull(getField(client, "channel"));
    org.junit.jupiter.api.Assertions.assertNull(getField(client, "stub"));
    verify(previousChannel).shutdown();
    verify(replacementChannel).shutdown();
  }

  @Test
  void watcherReloadPropagatesFailureToCertificateWatcher() throws Exception {
    ServiceEndpointsProperties endpoints = mock(ServiceEndpointsProperties.class);
    when(endpoints.copy()).thenReturn(endpoints);
    CommonGrpcClientProperties tlsProps = mock(CommonGrpcClientProperties.class);
    when(tlsProps.copy()).thenReturn(tlsProps);
    GrpcTlsMaterialResolver resolver = mock(GrpcTlsMaterialResolver.class);
    when(resolver.resolve(tlsProps)).thenThrow(new java.io.IOException("material unavailable"));

    TcpProxyEventClient client =
        new TcpProxyEventClient(
            endpoints,
            tlsProps,
            mock(GrpcChannelFactory.class),
            resolver,
            BlockingGrpcStubCustomizer.noop());

    Throwable failure = invokeSafeReload(client);

    org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure);
    org.junit.jupiter.api.Assertions.assertEquals(
        "Failed to reload gRPC channel", failure.getMessage());
    org.junit.jupiter.api.Assertions.assertInstanceOf(
        java.io.IOException.class, failure.getCause());
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }

  private static Object getField(Object target, String fieldName) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to read field " + fieldName, e);
    }
  }

  private static void invokeReloadChannel(TcpProxyEventClient client) {
    try {
      var method = TcpProxyEventClient.class.getDeclaredMethod("reloadChannel");
      method.setAccessible(true);
      method.invoke(client);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to invoke reloadChannel", e);
    }
  }

  private static Throwable invokeSafeReload(TcpProxyEventClient client) {
    try {
      var method = TcpProxyEventClient.class.getDeclaredMethod("safeReload");
      method.setAccessible(true);
      method.invoke(client);
      throw new AssertionError("safeReload unexpectedly succeeded");
    } catch (InvocationTargetException e) {
      return e.getCause();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to invoke safeReload", e);
    }
  }
}
