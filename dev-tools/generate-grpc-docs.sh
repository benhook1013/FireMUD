#!/usr/bin/env bash
# Generate gRPC API documentation for all proto files
set -euo pipefail

OUT_DIR="design/grpc-docs"
mkdir -p "$OUT_DIR"

PROTO_FILES=$(find protos -name '*.proto' | sort)

protoc -I protos \
  --doc_out="$OUT_DIR" \
  --doc_opt=markdown,grpc-api.md \
  $PROTO_FILES

echo "Generated gRPC docs in $OUT_DIR"
