# Shared Proto Definitions (v1)

This folder contains protobuf messages that are reused across multiple FireMUD services.

- `errors.proto` defines the `ErrorDetail` message returned on failures.

Import these definitions from service-specific proto files using `import "shared/v1/errors.proto"`.
