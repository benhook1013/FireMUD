package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedGrpcRequestMarshallerTest {
  private static final Context.Key<String> CONTEXT = Context.key("bounded-admission-test");

  @Test
  void exactUnknownLengthBoundaryParsesThenPreservesContextResponseAndLifecycle() {
    var harness = new Harness(false);
    harness.deliver(
        new ByteArrayInputStream(new byte[8]) {
          @Override
          public int available() {
            throw new AssertionError("available is not admission");
          }
        });
    assertThat(harness.parses).hasValue(1);
    harness.listener.onHalfClose();
    harness.listener.onComplete();
    assertThat(harness.events)
        .containsExactly(
            "start:identity", "message:identity", "half-close:identity", "complete:identity");
    assertThat(harness.call.closed).extracting(Status::getCode).containsExactly(Status.Code.OK);
    assertThat(harness.call.response).isEqualTo(new byte[] {1, 2, 3});
    assertThat(harness.originalDescriptorObserved).isTrue();
    assertThat(harness.originalHeadersObserved).isTrue();
  }

  @Test
  void chunkedReplayPreservesExactBytesAcrossFullChunksAndPartialTail() {
    byte[] expected = new byte[2 * 64 * 1024 + 17];
    for (int index = 0; index < expected.length; index++) {
      expected[index] = (byte) (index % 251);
    }
    var harness = new Harness(false, expected.length);
    harness.deliver(
        new ByteArrayInputStream(expected) {
          @Override
          public int available() {
            throw new AssertionError("available is not admission");
          }
        });
    assertThat(harness.parses).hasValue(1);
    assertThat(harness.received).isEqualTo(expected);
    harness.listener.onHalfClose();
    assertThat(harness.call.closed).extracting(Status::getCode).containsExactly(Status.Code.OK);
  }

  @Test
  void overflowAcrossChunkBoundaryStillStopsAtSentinelBeforeOriginalParser() {
    int maximum = 64 * 1024 + 17;
    var harness = new Harness(false, maximum);
    AtomicInteger reads = new AtomicInteger();
    harness.deliver(
        new InputStream() {
          @Override
          public int read() {
            reads.incrementAndGet();
            return 255;
          }
        });
    harness.listener.onHalfClose();
    assertThat(reads).hasValue(maximum + 1);
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.call.closed)
        .extracting(Status::getCode)
        .containsExactly(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(harness.events).containsExactly("start:identity");
  }

  @Test
  void oversizedMalformedUnknownLengthClosesExplicitlyWithoutParserOrOwnerDispatch() {
    var harness = new Harness(false);
    AtomicInteger reads = new AtomicInteger();
    harness.deliver(
        new InputStream() {
          @Override
          public int read() {
            reads.incrementAndGet();
            return 255;
          }

          @Override
          public int available() {
            return 0;
          }
        });
    assertThat(reads).hasValue(9);
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.call.closed)
        .extracting(Status::getCode)
        .containsExactly(Status.Code.RESOURCE_EXHAUSTED);
    harness.deliver(new ByteArrayInputStream(new byte[8]));
    harness.listener.onHalfClose();
    harness.listener.onReady();
    harness.listener.onCancel();
    harness.listener.onCancel();
    harness.listener.onComplete();
    assertThat(harness.events).containsExactly("start:identity", "cancel:identity");
    assertThat(harness.call.closed).hasSize(1);
    assertThat(harness.parses).hasValue(0);
  }

  @Test
  void readFailureClosesInternalAndTerminalCompleteStillCleansUpOnce() {
    var harness = new Harness(false);
    harness.deliver(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("broken transport");
          }
        });
    harness.listener.onHalfClose();
    harness.listener.onComplete();
    harness.listener.onComplete();
    harness.listener.onCancel();
    assertThat(harness.call.closed)
        .extracting(Status::getCode)
        .containsExactly(Status.Code.INTERNAL);
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.events).containsExactly("start:identity", "complete:identity");
  }

  @Test
  void decompressionOrTransportStatusDuringReadIsClosedExplicitly() {
    var harness = new Harness(false);
    harness.deliver(
        new InputStream() {
          @Override
          public int read() {
            throw Status.RESOURCE_EXHAUSTED
                .withDescription("transport decompression bound")
                .asRuntimeException();
          }
        });
    harness.listener.onHalfClose();
    assertThat(harness.call.closed)
        .extracting(Status::getCode)
        .containsExactly(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.events).containsExactly("start:identity");
  }

  @Test
  void cancellationSuppressesReadAndHalfCloseAndForwardsCleanupOnce() {
    var harness = new Harness(false);
    harness.call.cancelled = true;
    harness.deliver(
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("cancelled request must not be read");
          }
        });
    harness.listener.onHalfClose();
    harness.listener.onReady();
    harness.listener.onCancel();
    harness.listener.onCancel();
    harness.listener.onComplete();
    assertThat(harness.call.closed).isEmpty();
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.events).containsExactly("start:identity", "cancel:identity");
  }

  @Test
  void existingHandlerRejectionBeforeMessagesRetainsItsStatusAndPreventsParsing() {
    var harness = new Harness(true);
    harness.deliver(
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("denied request must not be read");
          }
        });
    harness.listener.onHalfClose();
    harness.listener.onComplete();
    assertThat(harness.call.closed)
        .extracting(Status::getCode)
        .containsExactly(Status.Code.PERMISSION_DENIED);
    assertThat(harness.parses).hasValue(0);
    assertThat(harness.events).containsExactly("start:identity", "complete:identity");
  }

  private static final class Harness {
    private final AtomicInteger parses = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private final RecordingCall call = new RecordingCall();
    private final ServerCall.Listener<InputStream> listener;
    private boolean originalDescriptorObserved;
    private boolean originalHeadersObserved;
    private byte[] received;

    private Harness(boolean rejectAtStart) {
      this(rejectAtStart, 8);
    }

    @SuppressWarnings("unchecked")
    private Harness(boolean rejectAtStart, int maximum) {
      MethodDescriptor<byte[], byte[]> original =
          MethodDescriptor.<byte[], byte[]>newBuilder()
              .setFullMethodName("test.Bounded/Read")
              .setType(MethodDescriptor.MethodType.UNARY)
              .setRequestMarshaller(parser(parses))
              .setResponseMarshaller(parser(new AtomicInteger()))
              .build();
      Metadata headers = new Metadata();
      ServerCallHandler<byte[], byte[]> handler =
          (observedCall, observedHeaders) -> {
            events.add("start:" + CONTEXT.get());
            originalDescriptorObserved = observedCall.getMethodDescriptor() == original;
            originalHeadersObserved = observedHeaders == headers;
            if (rejectAtStart) {
              observedCall.close(Status.PERMISSION_DENIED, new Metadata());
            }
            return new ServerCall.Listener<>() {
              @Override
              public void onMessage(byte[] message) {
                received = message;
                events.add("message:" + CONTEXT.get());
              }

              @Override
              public void onHalfClose() {
                events.add("half-close:" + CONTEXT.get());
                observedCall.sendHeaders(new Metadata());
                observedCall.sendMessage(new byte[] {1, 2, 3});
                observedCall.close(Status.OK, new Metadata());
              }

              @Override
              public void onReady() {
                events.add("ready:" + CONTEXT.get());
              }

              @Override
              public void onCancel() {
                events.add("cancel:" + CONTEXT.get());
              }

              @Override
              public void onComplete() {
                events.add("complete:" + CONTEXT.get());
              }
            };
          };
      var service =
          ServerServiceDefinition.builder("test.Bounded").addMethod(original, handler).build();
      service =
          ServerInterceptors.intercept(
              service,
              new ServerInterceptor() {
                @Override
                public <Q, R> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, R> observedCall,
                    Metadata observedHeaders,
                    ServerCallHandler<Q, R> next) {
                  return Contexts.interceptCall(
                      Context.current().withValue(CONTEXT, "identity"),
                      observedCall,
                      observedHeaders,
                      next);
                }
              });
      var policy =
          new GrpcRequestBytePolicy(
              List.of(new GrpcRequestBytePolicy.MethodBound(original, maximum)));
      var guarded = policy.guard(service, GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
      var method =
          (ServerMethodDefinition<InputStream, InputStream>)
              guarded.getMethod(original.getFullMethodName());
      call.method = method.getMethodDescriptor();
      listener = method.getServerCallHandler().startCall(call, headers);
    }

    private void deliver(InputStream message) {
      listener.onMessage(call.method.parseRequest(message));
    }
  }

  private static final class RecordingCall extends ServerCall<InputStream, InputStream> {
    private MethodDescriptor<InputStream, InputStream> method;
    private final List<Status> closed = new ArrayList<>();
    private boolean cancelled;
    private byte[] response;

    @Override
    public void request(int count) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(InputStream message) {
      try {
        response = message.readAllBytes();
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public void close(Status status, Metadata trailers) {
      closed.add(status);
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public MethodDescriptor<InputStream, InputStream> getMethodDescriptor() {
      return method;
    }
  }

  static MethodDescriptor.Marshaller<byte[]> parser(AtomicInteger parses) {
    return new MethodDescriptor.Marshaller<>() {
      @Override
      public InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
      }

      @Override
      public byte[] parse(InputStream stream) {
        parses.incrementAndGet();
        try {
          return stream.readAllBytes();
        } catch (IOException failure) {
          throw new IllegalStateException(failure);
        }
      }
    };
  }
}
