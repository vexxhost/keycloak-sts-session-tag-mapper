# Copyright (c) 2026 VEXXHOST, Inc.
# SPDX-License-Identifier: Apache-2.0

# The image only carries the provider jar. Use it as a Kubernetes init container
# that copies the jar into Keycloak's providers directory, or with
# `COPY --from=` when building a custom Keycloak image.

FROM --platform=$BUILDPLATFORM maven:3.10.2-eclipse-temurin-21@sha256:451c216d03f7cb0a0a26f61bb037fe24dcebaf6aa4e3168789911619ff9aaea0 AS build
WORKDIR /src
COPY pom.xml LICENSE ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests && \
    mkdir /dist && \
    cp target/keycloak-sts-session-tag-mapper-*.jar /dist/keycloak-sts-session-tag-mapper.jar

# Exports the jar on its own: docker buildx build --target jar --output type=local,dest=dist .
FROM scratch AS jar
COPY --from=build /dist/keycloak-sts-session-tag-mapper.jar /

FROM busybox:1.38.0@sha256:a46257e06c82ad85242d0d38077d0a52d9896389ca60a1a59d3463db586e5fa4
COPY LICENSE /licenses/LICENSE
COPY --from=build /dist/keycloak-sts-session-tag-mapper.jar /opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar
RUN mkdir /providers && chown 65534:65534 /providers
USER 65534:65534
CMD ["cp", "-v", "/opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar", "/providers/"]
