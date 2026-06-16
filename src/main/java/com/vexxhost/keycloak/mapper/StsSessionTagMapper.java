package com.vexxhost.keycloak.mapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

public class StsSessionTagMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "sts-session-tag-mapper";
    public static final String DEFAULT_PRINCIPAL_TAG_ATTRIBUTE_PREFIX = "sts-session-tags.";
    public static final String DEFAULT_TRANSITIVE_TAG_KEYS_ATTRIBUTE = "sts-session-transitive-tag-keys";

    private static final String PRINCIPAL_TAGS_ENABLED_CONFIG = "principal.tags.enabled";
    private static final String PRINCIPAL_TAG_ATTRIBUTE_PREFIX_CONFIG = "principal.tag.attribute.prefix";
    private static final String TRANSITIVE_TAG_KEYS_ENABLED_CONFIG = "transitive.tag.keys.enabled";
    private static final String TRANSITIVE_TAG_KEYS_ATTRIBUTE_CONFIG = "transitive.tag.keys.attribute";
    private static final String CLAIM_FORMAT_CONFIG = "claim.format";
    private static final String INCLUDE_USER_ATTRIBUTES_CONFIG = "include.user.attributes";
    private static final String INCLUDE_PARENT_GROUPS_CONFIG = "include.parent.groups";
    private static final String CLAIM_FORMAT_RADOSGW_ARRAY = "radosgw-array";
    private static final String CLAIM_FORMAT_AWS_NESTED = "aws-nested";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                PRINCIPAL_TAGS_ENABLED_CONFIG,
                "Enable principal_tags",
                "Emit the principal_tags section inside the AWS session tags claim.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.TRUE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                PRINCIPAL_TAG_ATTRIBUTE_PREFIX_CONFIG,
                "Principal tag attribute prefix",
                "User and group attributes with this prefix are converted to RGW STS principal tags. "
                        + "For example, sts-session-tags.project-access becomes project-access.",
                ProviderConfigProperty.STRING_TYPE,
                DEFAULT_PRINCIPAL_TAG_ATTRIBUTE_PREFIX));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                TRANSITIVE_TAG_KEYS_ENABLED_CONFIG,
                "Enable transitive_tag_keys",
                "Emit the transitive_tag_keys section inside the AWS session tags claim. Disabled by default.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.FALSE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                TRANSITIVE_TAG_KEYS_ATTRIBUTE_CONFIG,
                "Transitive tag keys attribute",
                "Multivalued user or group attribute containing tag keys to mark as transitive.",
                ProviderConfigProperty.STRING_TYPE,
                DEFAULT_TRANSITIVE_TAG_KEYS_ATTRIBUTE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                CLAIM_FORMAT_CONFIG,
                "Claim format",
                "RADOS Gateway documents the tags namespace as a one-element array. AWS documents the same namespace as a nested object.",
                ProviderConfigProperty.LIST_TYPE,
                CLAIM_FORMAT_RADOSGW_ARRAY,
                CLAIM_FORMAT_RADOSGW_ARRAY,
                CLAIM_FORMAT_AWS_NESTED));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                INCLUDE_USER_ATTRIBUTES_CONFIG,
                "Include user attributes",
                "Also read matching attributes directly from the user. Group attributes are always read.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.FALSE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                INCLUDE_PARENT_GROUPS_CONFIG,
                "Include parent group attributes",
                "When a user is a member of a nested group, also read matching attributes from its parent groups.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.TRUE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN,
                "Add to access token",
                "Add the AWS session tags claim to the access token.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.TRUE));

        CONFIG_PROPERTIES.add(new ProviderConfigProperty(
                OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN,
                "Add to ID token",
                "Add the AWS session tags claim to the ID token. Enable this when the token passed to STS is an ID token.",
                ProviderConfigProperty.BOOLEAN_TYPE,
                Boolean.TRUE));
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "STS Session Tags";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Builds the https://aws.amazon.com/tags claim for AWS STS and RADOS Gateway session tags from Keycloak group attributes.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        UserModel user = userSession.getUser();
        if (user == null) {
            return;
        }

        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                getStringConfig(mappingModel, PRINCIPAL_TAG_ATTRIBUTE_PREFIX_CONFIG,
                        DEFAULT_PRINCIPAL_TAG_ATTRIBUTE_PREFIX),
                getStringConfig(mappingModel, TRANSITIVE_TAG_KEYS_ATTRIBUTE_CONFIG,
                        DEFAULT_TRANSITIVE_TAG_KEYS_ATTRIBUTE),
                getBooleanConfig(mappingModel, PRINCIPAL_TAGS_ENABLED_CONFIG, true),
                getBooleanConfig(mappingModel, TRANSITIVE_TAG_KEYS_ENABLED_CONFIG, false));
        boolean includeParentGroups = getBooleanConfig(mappingModel, INCLUDE_PARENT_GROUPS_CONFIG, true);

        Set<String> seenGroupIds = new HashSet<>();
        user.getGroupsStream().forEach(group -> addGroupAttributes(builder, group, includeParentGroups, seenGroupIds));

        if (getBooleanConfig(mappingModel, INCLUDE_USER_ATTRIBUTES_CONFIG, false)) {
            builder.addAttributes(user.getAttributes());
        }

        if (!builder.isEmpty()) {
            token.setOtherClaims(SessionTagClaimBuilder.AWS_SESSION_TAGS_CLAIM,
                    builder.buildAwsSessionTagsClaim(getClaimFormat(mappingModel)));
        }
    }

    private static void addGroupAttributes(SessionTagClaimBuilder builder, GroupModel group, boolean includeParentGroups,
            Set<String> seenGroupIds) {
        GroupModel current = group;
        while (current != null) {
            if (seenGroupIds.add(current.getId())) {
                builder.addAttributes(current.getAttributes());
            }

            current = includeParentGroups ? current.getParent() : null;
        }
    }

    private static String getStringConfig(ProtocolMapperModel mappingModel, String key, String defaultValue) {
        if (mappingModel == null || mappingModel.getConfig() == null) {
            return defaultValue;
        }

        String configuredValue = mappingModel.getConfig().get(key);
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }

        return configuredValue.trim();
    }

    private static SessionTagClaimBuilder.ClaimFormat getClaimFormat(ProtocolMapperModel mappingModel) {
        String configuredValue = getStringConfig(mappingModel, CLAIM_FORMAT_CONFIG, CLAIM_FORMAT_RADOSGW_ARRAY);
        if (CLAIM_FORMAT_AWS_NESTED.equals(configuredValue)) {
            return SessionTagClaimBuilder.ClaimFormat.AWS_NESTED;
        }

        return SessionTagClaimBuilder.ClaimFormat.RADOSGW_ARRAY;
    }

    private static boolean getBooleanConfig(ProtocolMapperModel mappingModel, String key, boolean defaultValue) {
        if (mappingModel == null || mappingModel.getConfig() == null) {
            return defaultValue;
        }

        String configuredValue = mappingModel.getConfig().get(key);
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(configuredValue);
    }
}
