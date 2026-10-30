// Copyright (c) 2026 VEXXHOST, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.vexxhost.keycloak.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Loads the packaged provider jar into a real Keycloak and checks the tokens it issues.
 */
class StsSessionTagMapperIT {
    private static final String REALM = "sts";
    private static final String PASSWORD = "password";
    private static final String CLAIM = "https://aws.amazon.com/tags";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static KeycloakContainer keycloak;
    private static RealmResource realm;
    private static String projectBbbGroupId;

    @BeforeAll
    static void startKeycloak() {
        String keycloakVersion = System.getProperty("keycloak.version");
        String mapperJar = System.getProperty("mapper.jar");
        assertNotNull(keycloakVersion, "keycloak.version system property is not set");
        assertNotNull(mapperJar, "mapper.jar system property is not set");
        assertTrue(new File(mapperJar).isFile(), "provider jar not found: " + mapperJar);

        keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:" + keycloakVersion)
                .withProviderLibsFrom(List.of(new File(mapperJar)));
        keycloak.start();

        Keycloak admin = keycloak.getKeycloakAdminClient();
        RealmRepresentation realmRepresentation = new RealmRepresentation();
        realmRepresentation.setRealm(REALM);
        realmRepresentation.setEnabled(true);
        admin.realms().create(realmRepresentation);
        realm = admin.realm(REALM);

        // Keycloak 26 rejects attributes that are not declared in the user profile unless this is enabled.
        UPConfig userProfile = realm.users().userProfile().getConfiguration();
        userProfile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        realm.users().userProfile().update(userProfile);

        createClient("defaults", Map.of());
        createClient("radosgw-array", Map.of("claim.format", "radosgw-array"));
        createClient("aws-transitive", Map.of(
                "claim.format", "aws-nested",
                "transitive.tag.keys.enabled", "true",
                "id.token.claim", "true"));
        createClient("no-parent-groups", Map.of("include.parent.groups", "false"));
        createClient("user-attributes", Map.of("include.user.attributes", "true"));

        String tenantGroupId = createGroup(null, "tenant-a", Map.of(
                "sts-session-tags.department", List.of("eng")));
        String projectAaaGroupId = createGroup(tenantGroupId, "project-aaa", Map.of(
                "sts-session-tags.project-access", List.of("aaa:readwrite"),
                "sts-session-transitive-tag-keys", List.of("project-access"),
                "unrelated", List.of("ignored")));
        projectBbbGroupId = createGroup(null, "project-bbb", Map.of(
                "sts-session-tags.project-access", List.of("bbb:readonly"),
                "sts-session-tags.cost-center", List.of("1234")));
        String untaggedGroupId = createGroup(null, "untagged", Map.of("description", List.of("no session tags")));

        createUser("alice", Map.of("sts-session-tags.user-tag", List.of("direct")),
                projectAaaGroupId, projectBbbGroupId);
        createUser("bob", Map.of(), projectAaaGroupId, projectBbbGroupId);
        createUser("carol", Map.of("sts-session-tags.user-tag", List.of("direct")),
                untaggedGroupId);
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    @Test
    void defaultsEmitAwsNestedClaimInAccessTokenOnly() throws Exception {
        Tokens tokens = login("defaults", "alice");

        JsonNode claim = tokens.accessToken().get(CLAIM);
        assertNotNull(claim, "access token has no session tags claim");
        assertTrue(claim.isObject(), "default claim format should be the AWS nested object");
        assertEquals(Set.of("principal_tags"), fieldNames(claim));

        JsonNode tags = claim.get("principal_tags");
        assertEquals(Set.of("department", "project-access", "cost-center"), fieldNames(tags),
                "only prefixed group attributes should become tags, and user attributes are off by default");
        assertEquals(Set.of("eng"), values(tags, "department"), "parent group attributes are inherited");
        assertEquals(Set.of("aaa:readwrite", "bbb:readonly"), values(tags, "project-access"),
                "values for the same key are merged across groups");
        assertEquals(Set.of("1234"), values(tags, "cost-center"));

        assertFalse(tokens.idToken().has(CLAIM), "ID token claim is off by default");
    }

    @Test
    void radosgwArrayFormatWrapsTheSameTags() throws Exception {
        JsonNode claim = login("radosgw-array", "alice").accessToken().get(CLAIM);

        assertNotNull(claim);
        assertTrue(claim.isArray(), "radosgw-array should emit a JSON array");
        assertEquals(1, claim.size());
        JsonNode tags = claim.get(0).get("principal_tags");
        assertEquals(Set.of("department", "project-access", "cost-center"), fieldNames(tags));
        assertEquals(Set.of("aaa:readwrite", "bbb:readonly"), values(tags, "project-access"));
    }

    @Test
    void transitiveTagKeysAndIdTokenWhenEnabled() throws Exception {
        Tokens tokens = login("aws-transitive", "alice");

        for (JsonNode token : List.of(tokens.accessToken(), tokens.idToken())) {
            JsonNode claim = token.get(CLAIM);
            assertNotNull(claim);
            assertTrue(claim.isObject());
            assertEquals(List.of("project-access"), JSON.convertValue(claim.get("transitive_tag_keys"), List.class));
            assertEquals(Set.of("aaa:readwrite", "bbb:readonly"), values(claim.get("principal_tags"), "project-access"));
        }
    }

    @Test
    void parentGroupsCanBeExcluded() throws Exception {
        JsonNode tags = login("no-parent-groups", "alice").accessToken().get(CLAIM).get("principal_tags");

        assertEquals(Set.of("project-access", "cost-center"), fieldNames(tags));
    }

    @Test
    void userAttributesAreReadOnlyWhenEnabled() throws Exception {
        JsonNode tags = login("user-attributes", "alice").accessToken().get(CLAIM).get("principal_tags");

        assertEquals(Set.of("direct"), values(tags, "user-tag"));
        assertEquals(Set.of("aaa:readwrite", "bbb:readonly"), values(tags, "project-access"));
    }

    @Test
    void noClaimWithoutTaggedGroups() throws Exception {
        Tokens tokens = login("defaults", "carol");

        assertFalse(tokens.accessToken().has(CLAIM),
                "a user whose groups carry no session tag attributes gets no claim");
    }

    @Test
    void leavingAGroupRemovesItsTagsFromTheNextToken() throws Exception {
        JsonNode before = login("defaults", "bob").accessToken().get(CLAIM).get("principal_tags");
        assertEquals(Set.of("aaa:readwrite", "bbb:readonly"), values(before, "project-access"));
        assertEquals(Set.of("1234"), values(before, "cost-center"));

        String bobId = realm.users().searchByUsername("bob", true).get(0).getId();
        realm.users().get(bobId).leaveGroup(projectBbbGroupId);

        JsonNode after = login("defaults", "bob").accessToken().get(CLAIM).get("principal_tags");
        assertEquals(Set.of("department", "project-access"), fieldNames(after));
        assertEquals(Set.of("aaa:readwrite"), values(after, "project-access"));
    }

    /**
     * Creates a client with the mapper configured the way the admin console does it: every
     * option starts at the mapper's declared default, then the given overrides apply.
     */
    private static void createClient(String clientId, Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>();
        new StsSessionTagMapper().getConfigProperties()
                .forEach(property -> config.put(property.getName(), String.valueOf(property.getDefaultValue())));
        config.putAll(overrides);

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("STS Session Tags");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper(StsSessionTagMapper.PROVIDER_ID);
        mapper.setConfig(config);

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        client.setProtocol("openid-connect");
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setProtocolMappers(List.of(mapper));

        try (var response = realm.clients().create(client)) {
            assertEquals(201, response.getStatus(), "failed to create client " + clientId);
        }
    }

    private static String createGroup(String parentId, String name, Map<String, List<String>> attributes) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        group.setAttributes(attributes);

        var response = parentId == null
                ? realm.groups().add(group)
                : realm.groups().group(parentId).subGroup(group);
        try (response) {
            assertEquals(201, response.getStatus(), "failed to create group " + name);
            String location = response.getLocation().getPath();
            return location.substring(location.lastIndexOf('/') + 1);
        }
    }

    private static void createUser(String username, Map<String, List<String>> attributes, String... groupIds) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setFirstName(username);
        user.setLastName("Example");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAttributes(attributes);
        user.setCredentials(List.of(credential));

        String userId;
        try (var response = realm.users().create(user)) {
            assertEquals(201, response.getStatus(), "failed to create user " + username);
            String location = response.getLocation().getPath();
            userId = location.substring(location.lastIndexOf('/') + 1);
        }
        for (String groupId : groupIds) {
            realm.users().get(userId).joinGroup(groupId);
        }
    }

    private static Tokens login(String clientId, String username) throws Exception {
        Map<String, String> form = Map.of(
                "grant_type", "password",
                "client_id", clientId,
                "username", username,
                "password", PASSWORD,
                "scope", "openid");
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloak.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "token request failed: " + response.body());

        JsonNode tokenResponse = JSON.readTree(response.body());
        return new Tokens(
                decodePayload(tokenResponse.get("access_token").asText()),
                decodePayload(tokenResponse.get("id_token").asText()));
    }

    private static JsonNode decodePayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> values(JsonNode tags, String key) {
        List<String> list = new ArrayList<>();
        assertNotNull(tags.get(key), "missing tag " + key);
        tags.get(key).forEach(value -> list.add(value.asText()));
        assertEquals(list.size(), new HashSet<>(list).size(), "duplicate values for tag " + key);
        return new HashSet<>(list);
    }

    private record Tokens(JsonNode accessToken, JsonNode idToken) {
    }
}
