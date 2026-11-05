# Copyright (c) 2026 VEXXHOST, Inc.
# SPDX-License-Identifier: Apache-2.0

# The image only carries the provider jar. Use it as a Kubernetes init container
# that copies the jar into Keycloak's providers directory, or with
# `COPY --from=` when building a custom Keycloak image.

FROM --platform=$BUILDPLATFORM maven:3.10.0-eclipse-temurin-21@sha256:9673eef9ed503146591667ffb92eaaa22f1b8899ed60d9df0df4da3f3a1214a6 AS build
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

FROM busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec
COPY LICENSE /licenses/LICENSE
COPY --from=build /dist/keycloak-sts-session-tag-mapper.jar /opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar
RUN mkdir /providers && chown 65534:65534 /providers
USER 65534:65534
CMD ["cp", "-v", "/opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar", "/providers/"]
