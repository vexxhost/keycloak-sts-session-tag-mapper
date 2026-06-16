package com.vexxhost.keycloak.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;

class StsSessionTagMapperConfigTest {
    @Test
    void exposesExpectedProviderIdentityAndTokenDefaults() {
        StsSessionTagMapper mapper = new StsSessionTagMapper();
        Map<String, ProviderConfigProperty> config = mapper.getConfigProperties().stream()
                .collect(Collectors.toMap(ProviderConfigProperty::getName, property -> property));

        assertEquals("sts-session-tag-mapper", mapper.getId());
        assertEquals("STS Session Tags", mapper.getDisplayType());
        assertEquals(Boolean.TRUE,
                config.get(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN).getDefaultValue());
        assertEquals(Boolean.TRUE,
                config.get(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN).getDefaultValue());
        assertEquals("radosgw-array", config.get("claim.format").getDefaultValue());
        assertEquals(Boolean.FALSE, config.get("transitive.tag.keys.enabled").getDefaultValue());
    }
}
