# Automation Scripting Service

Design documentation lives at:
[📄 Automation Scripting Service Design](../../design/architecture/microservices/automation-scripting-service/README.md)

### Script Upload Workflow

Scripts are uploaded via the `UpdateScript` gRPC method. The operation runs as a
Saga using `SagaBuilder` and `SagaRunner` from the shared library so that
failures can roll back the persisted record. A `correlationId` is attached to
logs for each saga execution and the number of active sagas is exported via the
`sagas.active` metric.

This README is a stub. **Do not place design details here.**
