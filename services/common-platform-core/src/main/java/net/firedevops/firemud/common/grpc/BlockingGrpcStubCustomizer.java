package net.firedevops.firemud.common.grpc;

import io.grpc.stub.AbstractStub;

/** Optional service-local customization seam for blocking gRPC stubs. */
@FunctionalInterface
public interface BlockingGrpcStubCustomizer {
  <T extends AbstractStub<T>> T customize(T stub);

  static BlockingGrpcStubCustomizer noop() {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return stub;
      }
    };
  }
}
