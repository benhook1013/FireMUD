# Temporary Audit Fix Worklist

This file tracks the current integrated fix batch from the latest static audit. It is temporary and should be removed once the batch is implemented and validated.

## Confirmed Findings In Scope

- [ ] Replace the destructive misuse of `ApplyModerationAction` from account-service logging paths with a dedicated non-destructive audit/reporting RPC in Logging & Admin.
- [ ] Add shared internal gRPC auth propagation for blocking clients so secured downstream RPCs receive `Authorization` metadata.
- [ ] Move remote side effects out of open local DB transactions in:
  - [ ] `PurchaseWorkflowServiceImpl`
  - [ ] `ModerationServiceImpl`
- [ ] Add baseline auth enforcement to world-management and entity-management service boundaries:
  - [ ] gRPC
  - [ ] REST
- [ ] Normalize the remaining gRPC app-error outliers:
  - [ ] `AccountGrpcService`
  - [ ] `GatewayManagementGrpcService`

## Validation

- [ ] `./gradlew spotlessApply`
- [ ] relevant touched-service checks
- [ ] `./gradlew check`
- [ ] remove this temporary file once the batch is complete
