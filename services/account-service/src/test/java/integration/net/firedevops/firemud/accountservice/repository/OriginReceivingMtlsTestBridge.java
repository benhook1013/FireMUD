package net.firedevops.firemud.accountservice.repository;

import io.grpc.*;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;

/** Test-only typed adapter. Real mTLS identity/headers reach the production typed receiver.
 * The controlled single-use payload binding is NOT a production wire codec or registered API. */
final class OriginReceivingMtlsTestBridge {
  interface Command<T> { T run(Metadata headers) throws Exception; }
  private static final Context.Key<Metadata> HEADERS=Context.key("origin-test-headers");
  private static final MethodDescriptor.Marshaller<byte[]> BYTES=new MethodDescriptor.Marshaller<>() {
    public InputStream stream(byte[] value){return new ByteArrayInputStream(value);}
    public byte[] parse(InputStream stream){try{return stream.readNBytes(524289);}catch(IOException e){throw new UncheckedIOException(e);}}
  };
  static <T> T call(KeyManagerFactory serverKeys,TrustManagerFactory trust,KeyManagerFactory accountKeys,byte[] canonical,Command<T> command) throws Exception {
    var method=MethodDescriptor.<byte[],byte[]>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("private.test.OriginReceiving/"+UUID.randomUUID().toString().replace("-",""))
        .setRequestMarshaller(BYTES).setResponseMarshaller(BYTES).build();
    var result=new AtomicReference<T>();
    var service=ServerServiceDefinition.builder("private.test.OriginReceiving").addMethod(method,
        ServerCalls.asyncUnaryCall((byte[] received,io.grpc.stub.StreamObserver<byte[]> observer)->{
          try {
            if(!Arrays.equals(canonical,received))throw Status.INVALID_ARGUMENT.asRuntimeException();
            result.set(command.run(HEADERS.get()));observer.onNext(new byte[]{1});observer.onCompleted();
          }catch(Exception e){observer.onError(e instanceof StatusRuntimeException?e:Status.UNAVAILABLE.withCause(e).asRuntimeException());}
        })).build();
    ServerInterceptor headers=new ServerInterceptor(){
      public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata metadata,ServerCallHandler<Q,S> next){
        return Contexts.interceptCall(Context.current().withValue(HEADERS,metadata),call,metadata,next);
      }
    };
    var server=Grpc.newServerBuilderForPort(0,TlsServerCredentials.newBuilder().keyManager(serverKeys.getKeyManagers())
        .trustManager(trust.getTrustManagers()).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE).build())
        .maxInboundMessageSize(524288).addService(ServerInterceptors.interceptForward(service,new GrpcPeerIdentityInterceptor(),headers)).build().start();
    var channel=Grpc.newChannelBuilderForAddress("localhost",server.getPort(),TlsChannelCredentials.newBuilder()
        .keyManager(accountKeys.getKeyManagers()).trustManager(trust.getTrustManagers()).build()).build();
    try {
      byte[] response=ClientCalls.blockingUnaryCall(channel,method,CallOptions.DEFAULT.withDeadlineAfter(5,TimeUnit.SECONDS),canonical);
      if(!Arrays.equals(response,new byte[]{1}))throw new IllegalStateException("Missing callback completion");
      return result.get();
    } finally {channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
  }
}
