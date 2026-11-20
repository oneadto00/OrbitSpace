# syntax=docker/dockerfile:1
FROM golang:1.24.8-alpine AS compiler
WORKDIR /src
ENV CGO_ENABLED=0

FROM compiler AS server-build
ADD --checksum=sha256:be6d0bd3696c3a13a35f02d3a0280b64319c67918b4501c5c3d87f96d000085c \
    https://codeload.github.com/minio/minio/tar.gz/refs/tags/RELEASE.2025-10-15T17-29-55Z /src.tar.gz
RUN tar -xzf /src.tar.gz --strip-components=1 && \
    go build -mod=readonly -tags kqueue -trimpath -o /bin/minio \
      -ldflags="-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z" .

FROM compiler AS client-build
ADD --checksum=sha256:29db22500374169a43951c7cef09daf19e7291ea5ba00ac10f321371b0a35b32 \
    https://codeload.github.com/minio/mc/tar.gz/refs/tags/RELEASE.2025-08-13T08-35-41Z /src.tar.gz
RUN tar -xzf /src.tar.gz --strip-components=1 && \
    go build -mod=readonly -tags kqueue -trimpath -o /bin/mc \
      -ldflags="-s -w -X github.com/minio/mc/cmd.Version=2025-08-13T08:35:41Z -X github.com/minio/mc/cmd.ReleaseTag=RELEASE.2025-08-13T08-35-41Z" .

FROM alpine:3.23 AS server
COPY --from=server-build /src/LICENSE /src/CREDITS /licenses/
COPY --from=server-build /bin/minio /usr/local/bin/
ENTRYPOINT ["minio"]

FROM alpine:3.23 AS client
COPY --from=client-build /src/LICENSE /src/CREDITS /licenses/
COPY --from=client-build /bin/mc /usr/local/bin/
ENTRYPOINT ["mc"]
