# Keycloak STS Session Tag Mapper

A Keycloak OpenID Connect protocol mapper that turns Keycloak group attributes
into [STS session tags](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_session-tags.html).
It works with AWS STS and with the STS API of
[Ceph RADOS Gateway](https://docs.ceph.com/en/latest/radosgw/session-tags/) (RGW).

Session tags let an IAM role trust policy decide who may assume a role based on
what the user is allowed to access, instead of a list of user IDs. With this
mapper, Keycloak stays the source of truth: you add tag attributes to groups,
users inherit them through group membership, and every token issued to the
client carries the tags in the `https://aws.amazon.com/tags` claim.

Every group attribute whose name starts with a prefix (`sts-session-tags.` by
default) becomes a tag. The tag key is the rest of the attribute name, so new
tag keys need no mapper changes. Keycloak's built-in "User Attribute" mapper
can produce the same claim, but it needs a separate mapper for every tag key.

## How it works

Given these group attributes:

```text
Group "tenant-a"                      sts-session-tags.department     = eng
  Subgroup "tenant-a/project-aaa"     sts-session-tags.project-access = aaa:readwrite
Group "project-bbb"                   sts-session-tags.project-access = bbb:readonly
```

a member of `tenant-a/project-aaa` and `project-bbb` gets:

```json
"https://aws.amazon.com/tags": {
  "principal_tags": {
    "department": ["eng"],
    "project-access": ["aaa:readwrite", "bbb:readonly"]
  }
}
```

The mapper reads the attributes of every group the user belongs to and,
unless disabled, of their parent groups. Values for the same key are merged and
de-duplicated. The order of values follows Keycloak's group iteration and
should not be relied on. Changes to group membership or attributes apply to the
next token Keycloak issues.

Tags describe what Keycloak says about the user. Keycloak does not sync them
with other systems such as OpenStack Keystone role assignments. Whoever can edit
group attributes in the realm controls the tags, so restrict realm
administration accordingly.

## Compatibility

Keycloak 26 is the supported target. CI builds the mapper and runs the
integration tests against the version in `pom.xml` (currently 26.8.1). The jar
is compiled for Java 17.

Protocol mappers are an internal Keycloak SPI. At startup Keycloak logs a
warning that the provider "is implementing the internal SPI protocol-mapper",
which is expected. Internal SPIs can change between Keycloak releases, so run
the test suite before moving to a new Keycloak version.

## Installation

Each release publishes the provider jar on the
[GitHub releases page](https://github.com/vexxhost/keycloak-sts-session-tag-mapper/releases)
and an OCI image that contains only the jar:

```text
ghcr.io/vexxhost/keycloak-sts-session-tag-mapper:<version>
```

The jar sits at
`/opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar` in the image.
By default the image copies it to `/providers/`, which makes it usable as an
init container.

Keycloak picks up new providers when it starts with `kc.sh start` or
`kc.sh start-dev`. If you run an optimized image (`kc.sh start --optimized`),
add the jar before `kc.sh build` instead.

### Kubernetes init container

```yaml
spec:
  initContainers:
    - name: sts-session-tag-mapper
      image: ghcr.io/vexxhost/keycloak-sts-session-tag-mapper:<version>
      volumeMounts:
        - name: keycloak-providers
          mountPath: /providers
  containers:
    - name: keycloak
      image: quay.io/keycloak/keycloak:26.8.1
      args: ["start"]
      volumeMounts:
        - name: keycloak-providers
          mountPath: /opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar
          subPath: keycloak-sts-session-tag-mapper.jar
  volumes:
    - name: keycloak-providers
      emptyDir: {}
```

The `subPath` mount adds the jar next to any providers already in the Keycloak
image.

### Custom Keycloak image

```dockerfile
FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=ghcr.io/vexxhost/keycloak-sts-session-tag-mapper:<version> \
  /opt/keycloak/providers/keycloak-sts-session-tag-mapper.jar \
  /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
```

## Configuration

Add the mapper to the client that requests tokens for STS, either directly or
through a dedicated client scope:

```text
Clients -> <client> -> Client scopes -> <client>-dedicated -> Add mapper -> By configuration -> STS Session Tags
```

Attach it only where STS needs it. Every tag value adds to the token size,
and some services cap request header size (for example, at 16 KB).

The defaults below are what the admin console fills in. When you create the
mapper through the admin REST API, `kcadm.sh` or automation, set
`access.token.claim` (and `id.token.claim` if needed) to `"true"` explicitly:
Keycloak treats a missing token option as `false`.

| Option | Config key | Default | Description |
| --- | --- | --- | --- |
| Enable principal_tags | `principal.tags.enabled` | `true` | Emit `principal_tags`. |
| Principal tag attribute prefix | `principal.tag.attribute.prefix` | `sts-session-tags.` | Attributes with this prefix become tags; the rest of the name is the tag key. |
| Enable transitive_tag_keys | `transitive.tag.keys.enabled` | `false` | Emit `transitive_tag_keys` for AWS role chaining. |
| Transitive tag keys attribute | `transitive.tag.keys.attribute` | `sts-session-transitive-tag-keys` | Multivalued attribute that lists the tag keys to mark as transitive. |
| Claim format | `claim.format` | `aws-nested` | `aws-nested` or `radosgw-array`, see below. |
| Include user attributes | `include.user.attributes` | `false` | Also read prefixed attributes set directly on the user. |
| Include parent group attributes | `include.parent.groups` | `true` | Also read attributes of the parent groups of each group. |
| Add to access token | `access.token.claim` | `true` | Add the claim to the access token. |
| Add to ID token | `id.token.claim` | `false` | Add the claim to the ID token. Enable it if your STS client sends the ID token. |

### Claim formats

`aws-nested` emits the object documented by AWS, shown above. RGW accepts it
as well.

`radosgw-array` wraps the same object in a one-element array, which is the
form shown in the
[RGW Keycloak guide](https://docs.ceph.com/en/latest/radosgw/keycloak/#adding-tags-to-a-user-in-keycloak):

```json
"https://aws.amazon.com/tags": [{
  "principal_tags": {
    "project-access": ["aaa:readwrite", "bbb:readonly"]
  }
}]
```

### Transitive tags

AWS uses `transitive_tag_keys` to keep session tags when a session assumes
another role. List the keys in the transitive tag keys attribute on a group,
then enable the option:

```text
sts-session-transitive-tag-keys = project-access
```

RGW does not implement transitive tags. It treats `transitive_tag_keys` as an
ordinary tag with that name, so leave the option off for RGW.

### User attributes

Group attributes are always read. Attributes set directly on users are ignored
unless `Include user attributes` is enabled. This keeps the tags under the
control of whoever manages groups. In Keycloak 26, a user attribute is only
stored if the realm's user profile declares it or unmanaged attributes are
enabled.

### Limits

AWS and RGW accept at most 50 session tags per session. Tag keys can be up to
128 characters and values up to 256 characters. Keys must not start with
`aws:`. AWS also limits the packed size of tags and session policies. The mapper
does not enforce these limits, so check tokens for users who belong to many
groups.

## Using the tags in a trust policy

A role trust policy can require that the caller carries a matching tag. For
example, an RGW role tagged `project-access = aaa:readwrite` can only be
assumed by users whose token contains that value:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": ["arn:aws:iam:::oidc-provider/keycloak.example.com/realms/example"]},
    "Action": ["sts:AssumeRoleWithWebIdentity", "sts:TagSession"],
    "Condition": {
      "ForAnyValue:StringEquals": {
        "aws:RequestTag/project-access": "${iam:ResourceTag/project-access}"
      },
      "StringEquals": {
        "keycloak.example.com/realms/example:aud": "sts-client"
      }
    }
  }]
}
```

`sts:TagSession` must be allowed for STS to accept the tags.

## Development

The build needs Java 17 or newer and Maven. The integration tests also need
Docker.

```bash
mvn verify
```

`mvn package` builds `target/keycloak-sts-session-tag-mapper-<version>.jar` and
runs the unit tests. `mvn verify` also runs the integration tests in
`src/test/java/**/*IT.java`. They use
[Testcontainers](https://github.com/dasniko/testcontainers-keycloak) to start
the Keycloak version from `pom.xml` with the packaged jar, create groups, users
and clients through the admin API, and check the tokens Keycloak issues.

To build without a local Java toolchain:

```bash
docker build -t keycloak-sts-session-tag-mapper .
docker buildx build --target jar --output type=local,dest=dist .
```

### Local Keycloak

```bash
docker compose up --build
```

This builds the image from your checkout, copies the jar into Keycloak's
providers directory the way an init container would, and starts Keycloak in
development mode on <http://localhost:8080> (admin / admin). The
`hack/keycloak-init.sh` script creates a test setup:

```text
Realm: rgw
Client: rgw-test (public, direct access grants enabled)
User: alice / alice
```

`alice` belongs to two groups with `sts-session-tags.*` attributes, and the
client's mapper has transitive tags and the ID token claim enabled:

```bash
curl -s http://localhost:8080/realms/rgw/protocol/openid-connect/token \
  -d grant_type=password -d client_id=rgw-test \
  -d username=alice -d password=alice -d scope=openid
```

## Releases

Pull request titles must follow
[Conventional Commits](https://www.conventionalcommits.org/) because they
become the squash-merge commit messages.
[release-please](https://github.com/googleapis/release-please) uses them to
keep a release pull request open with the next version and changelog. Merging
that pull request tags the release, attaches the jar and its SHA-256 checksum,
and publishes the image tagged with the version, `<major>.<minor>` and
`latest`. Every merge to `main` also publishes the image as `main` and as the
short commit SHA.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
