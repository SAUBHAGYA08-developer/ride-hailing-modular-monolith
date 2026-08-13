# Static binary, so the runtime image needs no Go toolchain and no libc.
FROM golang:1.24-alpine AS build
WORKDIR /src

# Dependencies resolve in their own layer, so a source-only change does not re-download them.
COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api

FROM alpine:3.20
WORKDIR /app
# TLS roots are needed to reach a managed MySQL or Valkey over TLS.
RUN apk add --no-cache ca-certificates && adduser -D -u 1001 app
COPY --from=build /out/api /app/api
# Served at /app/*; the binary reads them from the working directory.
COPY web ./web
USER app

# Matches Render's default; a platform-injected PORT still wins over this.
ENV SERVER_PORT=10000
EXPOSE 10000

ENTRYPOINT ["/app/api"]
