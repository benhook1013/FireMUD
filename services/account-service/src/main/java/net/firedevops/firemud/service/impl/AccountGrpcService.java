package net.firedevops.firemud.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.service.AccountService;
import net.firedevops.firemud.service.PingService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private final PingService pingService;
  private final AccountService accountService;

  public AccountGrpcService(PingService pingService, AccountService accountService) {
    this.pingService = pingService;
    this.accountService = accountService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void createAccount(
      CreateAccountRequest request, StreamObserver<CreateAccountResponse> responseObserver) {
    try {
      net.firedevops.firemud.dto.CreateAccountRequest dto =
          new net.firedevops.firemud.dto.CreateAccountRequest(
              Long.valueOf(request.getTenantId()),
              request.getUsername(),
              request.getEmail(),
              request.getPassword());
      var account = accountService.createAccount(dto);
      CreateAccountResponse response =
          CreateAccountResponse.newBuilder().setAccountId(account.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          CreateAccountResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void authenticate(
      AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    try {
      String token =
          accountService.authenticate(
              Long.valueOf(request.getTenantId()),
              request.getUsername(),
              request.getPassword(),
              request.getOtp());
      AuthenticateResponse response = AuthenticateResponse.newBuilder().setAuthToken(token).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("UNAUTHENTICATED")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  public void getProfile(
      GetProfileRequest request, StreamObserver<GetProfileResponse> responseObserver) {
    try {
      var dto =
          accountService.getProfile(
              Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setProfileJson(
                  com.fasterxml.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .createObjectNode()
                      .put("displayName", dto.displayName())
                      .put("bio", dto.bio())
                      .toString())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("NOT_FOUND")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> responseObserver) {
    try {
      JsonNode node = JsonMapper.builder().build().readTree(request.getProfileJson());
      String displayName = node.path("displayName").asText(null);
      String bio = node.path("bio").asText(null);
      accountService.updateProfile(
          new net.firedevops.firemud.dto.UpdateProfileRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              displayName,
              bio));
      UpdateProfileResponse response = UpdateProfileResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
