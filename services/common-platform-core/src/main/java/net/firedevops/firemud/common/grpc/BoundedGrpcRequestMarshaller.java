package net.firedevops.firemud.common.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;

/** Raw stream marshalling paired with admission before the original protobuf parser. */
final class BoundedGrpcRequestMarshaller implements MethodDescriptor.Marshaller<InputStream> {
  private static final int CHUNK_BYTES = 64 * 1024;

  private BoundedGrpcRequestMarshaller() {}

  static ServerServiceDefinition rawMessages(ServerServiceDefinition service) {
    // Unlike useInputStreamMessages, this public adapter does not add a prefetching buffer.
    return ServerInterceptors.useMarshalledMessages(service, new BoundedGrpcRequestMarshaller());
  }

  @Override
  public InputStream stream(InputStream message) {
    return message;
  }

  @Override
  public InputStream parse(InputStream message) {
    return message;
  }

  static ServerCallHandler<InputStream, InputStream> guard(
      ServerCallHandler<InputStream, InputStream> next, int maximum) {
    if (maximum <= 0 || maximum == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("A finite positive request byte bound is required");
    }
    return (call, headers) -> {
      var state = new CallState();
      ServerCall<InputStream, InputStream> observedCall =
          new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
              state.closed = true;
              super.close(status, trailers);
            }
          };
      // The public stream adapter invokes the existing intercepted handler here. Its
      // authentication, Contexts listener wrappers and response marshaller remain intact.
      var delegate = next.startCall(observedCall, headers);
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
        @Override
        public void onMessage(InputStream message) {
          if (stopped()) {
            return;
          }
          Deque<byte[]> admitted = new ArrayDeque<>();
          int admittedBytes = 0;
          try {
            // Neither available(), compressed wire length nor caller metadata grants admission.
            // Retain bounded chunks without flattening the whole request into a second array.
            while (admittedBytes <= maximum) {
              int requested = Math.min(CHUNK_BYTES, maximum - admittedBytes + 1);
              byte[] chunk = message.readNBytes(requested);
              if (chunk.length == 0) {
                break;
              }
              admitted.addLast(chunk);
              admittedBytes += chunk.length;
              if (chunk.length < requested) {
                break;
              }
            }
          } catch (IOException failure) {
            reject(
                Status.INTERNAL
                    .withDescription("Unable to read bounded gRPC request")
                    .withCause(failure));
            return;
          } catch (RuntimeException failure) {
            Status status = Status.fromThrowable(failure);
            reject(
                status.getCode() == Status.Code.UNKNOWN
                    ? Status.INTERNAL
                        .withDescription("Unable to read bounded gRPC request")
                        .withCause(failure)
                    : status);
            return;
          }
          if (admittedBytes > maximum) {
            reject(
                Status.RESOURCE_EXHAUSTED.withDescription(
                    "gRPC request exceeds method byte bound"));
            return;
          }
          if (!stopped()) {
            // Only this replay reaches the stream adapter's original protobuf parser.
            super.onMessage(replay(admitted));
          }
        }

        @Override
        public void onHalfClose() {
          if (!stopped()) {
            super.onHalfClose();
          }
        }

        @Override
        public void onReady() {
          if (!stopped()) {
            super.onReady();
          }
        }

        @Override
        public void onCancel() {
          if (!state.terminal) {
            state.terminal = true;
            state.closed = true;
            super.onCancel();
          }
        }

        @Override
        public void onComplete() {
          if (!state.terminal) {
            state.terminal = true;
            state.closed = true;
            super.onComplete();
          }
        }

        private boolean stopped() {
          return state.closed || call.isCancelled();
        }

        private void reject(Status status) {
          if (!stopped()) {
            // Close explicitly: exceptions escaping marshaller.parse become UNKNOWN on the wire.
            observedCall.close(status, new Metadata());
          }
        }
      };
    };
  }

  private static InputStream replay(Deque<byte[]> chunks) {
    return new SequenceInputStream(
        new Enumeration<>() {
          @Override
          public boolean hasMoreElements() {
            return !chunks.isEmpty();
          }

          @Override
          public InputStream nextElement() {
            // Drop consumed chunks as the original parser advances, without a whole-message copy.
            return new ByteArrayInputStream(chunks.removeFirst());
          }
        });
  }

  private static final class CallState {
    private volatile boolean closed;
    private boolean terminal;
  }
}
