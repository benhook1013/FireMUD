# Protobuf Tooling

Holds `buf.work.yaml` and `buf.gen.yaml` for Buf. From the repository root, lint schemas with:

```bash
buf lint protos
```

The active CI template pins each remote Java generator's upstream version **and** BSR rebuild revision. Renovate queries the public BSR `PluginCurationService/GetLatestCuratedPlugin` Connect GET endpoint, transforms its published versions and revisions with its supported custom datasource, and updates both fields atomically. A version alone would follow later BSR rebuilds. Registry publication can lag upstream Maven releases: the BSR listing, rather than GitHub tags, controls generator availability.

Renovate groups the protobuf generator with the catalog's protobuf runtimes/compiler and the gRPC generator with its gRPC runtimes/compiler. Updates are uncapped; the existing PR Validation Gate generates with the pinned remote plugins and compiles that exact output on Java 21 against the catalog runtimes using `compileBufGeneratedJava`. Service builds continue to use the authoritative Gradle `generateProto` path. Both boundaries must pass before a dependency update can merge.

Reproduce the remote generator compatibility proof from the repository root:

```bash
(cd protos && buf generate --template ../config/protobuf/buf.gen.yaml)
bash dev-tools/validation/run-locked-gradle.sh compileBufGeneratedJava
```

The custom listing uses the BSR's exposed legacy `v1alpha1` plugin-curation API, also used by its public plugin UI. It is not a native Renovate manager or a promise that this API will never change. A listing/schema failure prevents dependency lookup; investigate that failure rather than substituting unverified upstream releases. See [Buf's API access documentation](https://buf.build/docs/bsr/apis/api-access/), [Connect GET requests](https://connectrpc.com/docs/protocol/#unary-get-request), and [Renovate custom datasources](https://docs.renovatebot.com/modules/datasource/custom/).
