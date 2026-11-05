// Copyright (c) 2026 VEXXHOST, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.vexxhost.keycloak.sts.sessiontag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class SessionTagClaimBuilder {
    static final String AWS_SESSION_TAGS_CLAIM = "https://aws.amazon.com/tags";
    static final String PRINCIPAL_TAGS_KEY = "principal_tags";
    static final String TRANSITIVE_TAG_KEYS_KEY = "transitive_tag_keys";

    enum ClaimFormat {
        RADOSGW_ARRAY,
        AWS_NESTED
    }

    private final String principalTagAttributePrefix;
    private final String transitiveTagKeysAttribute;
    private final boolean principalTagsEnabled;
    private final boolean transitiveTagKeysEnabled;
    private final Map<String, LinkedHashSet<String>> principalTags = new LinkedHashMap<>();
    private final LinkedHashSet<String> transitiveTagKeys = new LinkedHashSet<>();

    SessionTagClaimBuilder(String principalTagAttributePrefix, String transitiveTagKeysAttribute,
            boolean principalTagsEnabled, boolean transitiveTagKeysEnabled) {
        this.principalTagAttributePrefix = normalizeValue(
                principalTagAttributePrefix, StsSessionTagMapper.DEFAULT_PRINCIPAL_TAG_ATTRIBUTE_PREFIX);
        this.transitiveTagKeysAttribute = normalizeValue(
                transitiveTagKeysAttribute, StsSessionTagMapper.DEFAULT_TRANSITIVE_TAG_KEYS_ATTRIBUTE);
        this.principalTagsEnabled = principalTagsEnabled;
        this.transitiveTagKeysEnabled = transitiveTagKeysEnabled;
    }

    void addAttributes(Map<String, List<String>> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }

        attributes.forEach((attributeName, values) -> {
            if (values == null) {
                return;
            }

            if (principalTagsEnabled) {
                String tagKey = tagKeyFromAttributeName(attributeName);
                if (tagKey != null) {
                    for (String value : values) {
                        addPrincipalTagValue(tagKey, value);
                    }
                }
            }

            if (transitiveTagKeysEnabled && transitiveTagKeysAttribute.equals(attributeName)) {
                for (String value : values) {
                    addTransitiveTagKey(value);
                }
            }
        });
    }

    boolean isEmpty() {
        return principalTags.isEmpty() && transitiveTagKeys.isEmpty();
    }

    Object buildAwsSessionTagsClaim(ClaimFormat claimFormat) {
        Map<String, Object> nestedClaim = buildNestedClaim();
        if (claimFormat == ClaimFormat.AWS_NESTED) {
            return nestedClaim;
        }

        return List.of(nestedClaim);
    }

    private Map<String, Object> buildNestedClaim() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        principalTags.forEach((key, values) -> tags.put(key, new ArrayList<>(values)));

        Map<String, Object> wrapper = new LinkedHashMap<>();
        if (!tags.isEmpty()) {
            wrapper.put(PRINCIPAL_TAGS_KEY, tags);
        }
        if (!transitiveTagKeys.isEmpty()) {
            wrapper.put(TRANSITIVE_TAG_KEYS_KEY, new ArrayList<>(transitiveTagKeys));
        }

        return wrapper;
    }

    private String tagKeyFromAttributeName(String attributeName) {
        if (attributeName == null || !attributeName.startsWith(principalTagAttributePrefix)) {
            return null;
        }

        String tagKey = attributeName.substring(principalTagAttributePrefix.length()).trim();
        return tagKey.isEmpty() ? null : tagKey;
    }

    private void addPrincipalTagValue(String tagKey, String rawValue) {
        if (rawValue == null) {
            return;
        }

        String value = rawValue.trim();
        if (value.isEmpty()) {
            return;
        }

        principalTags.computeIfAbsent(tagKey, ignored -> new LinkedHashSet<>()).add(value);
    }

    private void addTransitiveTagKey(String rawValue) {
        if (rawValue == null) {
            return;
        }

        String value = rawValue.trim();
        if (!value.isEmpty()) {
            transitiveTagKeys.add(value);
        }
    }

    private static String normalizeValue(String configuredValue, String defaultValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }

        return configuredValue.trim();
    }
}
