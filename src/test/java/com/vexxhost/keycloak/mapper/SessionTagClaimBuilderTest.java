// Copyright (c) 2026 VEXXHOST, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.vexxhost.keycloak.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SessionTagClaimBuilderTest {
    @Test
    void buildsExpectedRadosgwClaimShape() {
        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                "sts-session-tags.",
                "sts-session-transitive-tag-keys",
                true,
                false);

        builder.addAttributes(Map.of(
                "sts-session-tags.project-access",
                List.of(
                        "7a96a68dc8264f3d84fafd95a72265c5:readwrite",
                        "37c05c43a57d419097dce9eee2769027:readwrite"),
                "sts-session-tags.something-else",
                List.of("free-json-compatible-thing", "xxxxx")));

        assertEquals(
                List.of(Map.of("principal_tags", Map.of(
                        "project-access",
                        List.of(
                                "7a96a68dc8264f3d84fafd95a72265c5:readwrite",
                                "37c05c43a57d419097dce9eee2769027:readwrite"),
                        "something-else",
                        List.of("free-json-compatible-thing", "xxxxx")))),
                builder.buildAwsSessionTagsClaim(SessionTagClaimBuilder.ClaimFormat.RADOSGW_ARRAY));
    }

    @Test
    void mergesRepeatedTagKeysAndRemovesDuplicateValues() {
        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                "sts-session-tags.",
                "sts-session-transitive-tag-keys",
                true,
                false);

        builder.addAttributes(Map.of(
                "sts-session-tags.project-access",
                List.of("7a96a68dc8264f3d84fafd95a72265c5:readwrite")));
        builder.addAttributes(Map.of(
                "sts-session-tags.project-access",
                List.of(
                        "7a96a68dc8264f3d84fafd95a72265c5:readwrite",
                        "37c05c43a57d419097dce9eee2769027:readonly")));

        assertEquals(
                List.of(Map.of("principal_tags", Map.of(
                        "project-access",
                        List.of(
                                "7a96a68dc8264f3d84fafd95a72265c5:readwrite",
                                "37c05c43a57d419097dce9eee2769027:readonly")))),
                builder.buildAwsSessionTagsClaim(SessionTagClaimBuilder.ClaimFormat.RADOSGW_ARRAY));
    }

    @Test
    void buildsTransitiveTagKeysWhenEnabled() {
        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                "sts-session-tags.",
                "sts-session-transitive-tag-keys",
                true,
                true);

        builder.addAttributes(Map.of(
                "sts-session-tags.project-access",
                List.of("7a96a68dc8264f3d84fafd95a72265c5:readwrite"),
                "sts-session-transitive-tag-keys",
                List.of("project-access", "project-access", "something-else")));

        assertEquals(
                List.of(Map.of(
                        "principal_tags",
                        Map.of("project-access",
                                List.of("7a96a68dc8264f3d84fafd95a72265c5:readwrite")),
                        "transitive_tag_keys",
                        List.of("project-access", "something-else"))),
                builder.buildAwsSessionTagsClaim(SessionTagClaimBuilder.ClaimFormat.RADOSGW_ARRAY));
    }

    @Test
    void buildsOfficialAwsNestedClaimShape() {
        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                "sts-session-tags.",
                "sts-session-transitive-tag-keys",
                true,
                true);

        builder.addAttributes(Map.of(
                "sts-session-tags.Project",
                List.of("Automation"),
                "sts-session-transitive-tag-keys",
                List.of("Project")));

        assertEquals(
                Map.of(
                        "principal_tags",
                        Map.of("Project", List.of("Automation")),
                        "transitive_tag_keys",
                        List.of("Project")),
                builder.buildAwsSessionTagsClaim(SessionTagClaimBuilder.ClaimFormat.AWS_NESTED));
    }

    @Test
    void ignoresBlankValuesAndUnmatchedAttributes() {
        SessionTagClaimBuilder builder = new SessionTagClaimBuilder(
                "sts-session-tags.",
                "sts-session-transitive-tag-keys",
                true,
                false);

        builder.addAttributes(Map.of(
                "project-access", List.of("wrong-prefix"),
                "sts-session-tags.project-access", List.of(" ", "\t")));

        assertTrue(builder.isEmpty());
    }
}
