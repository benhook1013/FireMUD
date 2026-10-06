# Build from the fixed MinIO source commit supplied as the build context.
FROM golang:1.27.1-bookworm@sha256:8d48e12ec56735e9358640898b9d9b9fcca110612ed8a5567438c0a1baa24e66 AS build

ENV CGO_ENABLED=0 \
    GOSUMDB=sum.golang.org \
    GOTOOLCHAIN=local \
    GOOS=linux \
    GOARCH=amd64

WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    mkdir -p /out && go build -mod=readonly -tags=kqueue -trimpath -o /out/minio .

FROM alpine:3.24.2@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6

LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD" \
      org.opencontainers.image.title="FireMUD smoke MinIO server"

COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /licenses/LICENSE
COPY --from=build /src/CREDITS /licenses/CREDITS

EXPOSE 9000
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
