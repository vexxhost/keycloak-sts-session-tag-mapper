# STS Session Tag Mapper

Keycloak OIDC protocol mapper for AWS STS and Ceph RADOS Gateway STS session tags.

The mapper reads Keycloak group attributes named with the `sts-session-tags.` prefix
and emits the STS session tag claim namespace.

By default, the mapper keeps the RADOS Gateway-documented array wrapper:

```json
"https://aws.amazon.com/tags": [{
  "principal_tags": {
    "project-access": [
      "7a96a68dc8264f3d84fafd95a72265c5:readwrite",
      "37c05c43a57d419097dce9eee2769027:readwrite"
    ],
    "something-else": [
      "free-json-compatible-thing",
      "xxxxx"
    ]
  }
}]
```

For official AWS IAM STS, set `Claim format` to `aws-nested` so the claim is emitted
as the nested object form documented by AWS:

```json
"https://aws.amazon.com/tags": {
  "principal_tags": {
    "Project": ["Automation"]
  },
  "transitive_tag_keys": ["Project"]
}
```

## Keycloak Attributes

Use group attributes by default for principal tags:

```text
sts-session-tags.project-access =
  7a96a68dc8264f3d84fafd95a72265c5:readwrite
  37c05c43a57d419097dce9eee2769027:readwrite

sts-session-tags.something-else =
  free-json-compatible-thing
  xxxxx
```

If `Enable transitive_tag_keys` is turned on, add a multivalued attribute with tag
keys to mark as transitive:

```text
sts-session-transitive-tag-keys =
  project-access
  something-else
```

The mapper merges values for the same tag key from all user groups. Duplicate values
are removed while preserving first-seen order.

User attributes are supported by the mapper but disabled by default. Enable
`Include user attributes` only when direct per-user tags are intentionally part of the
access model.

Mapper defaults:

```text
Enable principal_tags: true
Enable transitive_tag_keys: false
Claim format: radosgw-array
Add to access token: true
Add to ID token: true
```

The mapper intentionally does not expose `introspection.token.claim`; it only
implements access-token and ID-token mapping.

## Docker-Only Build

No local Java or Maven is required:

```bash
docker run --rm \
  -v "$PWD":/workspace \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-17 \
  mvn test package
```

The provider JAR is written to:

```text
target/sts-session-tag-mapper-0.1.0-SNAPSHOT.jar
```

## Keycloak Test Container

After building the JAR:

```bash
docker compose up keycloak
```

This follows the same pattern as `vexxhost/keystone-keycloak-backend`: the
container runs `kc.sh start-dev`, which uses Keycloak's embedded H2 dev database.
There is no dedicated Keycloak database service in this compose file.

Then open:

```text
http://localhost:8080
```

Admin credentials:

```text
admin / admin
```

Add the mapper from:

```text
Clients or client scopes -> Mappers -> Add mapper -> By configuration -> STS Session Tags
```

The compose harness also bootstraps a test realm automatically:

```text
Realm: rgw
Client: rgw-test
User: alice / alice
```

That realm has two project groups with `sts-session-tags.*` attributes and the
custom mapper attached to the test client. The test mapper config enables
`transitive_tag_keys` so a password-grant token for `alice` should include:

```json
"https://aws.amazon.com/tags": [{
  "principal_tags": {
    "project-access": [
      "37c05c43a57d419097dce9eee2769027:readwrite",
      "7a96a68dc8264f3d84fafd95a72265c5:readwrite"
    ],
    "something-else": [
      "xxxxx",
      "free-json-compatible-thing"
    ]
  },
  "transitive_tag_keys": [
    "project-access",
    "something-else"
  ]
}]
```

The order of values follows Keycloak group iteration and should not be used for
authorization decisions.
