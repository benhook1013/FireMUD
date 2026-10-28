# Build from the fixed MinIO source commit supplied as the build context.
FROM golang:1.27.3-bookworm@sha256:05237276236793ebedaf0b87dab86add9ec9adc66ebef2dbc3107c18254cde1c AS build

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

FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a

LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD" \
      org.opencontainers.image.title="FireMUD smoke MinIO server"

COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /licenses/LICENSE
COPY --from=build /src/CREDITS /licenses/CREDITS

EXPOSE 9000
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
