#!/bin/bash
set -euo pipefail

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://localhost:8080"
REALM="rgw"
CLIENT_ID="rgw-test"
USERNAME="alice"
PASSWORD="alice"

/opt/keycloak/bin/kc.sh start-dev &
KEYCLOAK_PID="$!"

echo "Waiting for Keycloak to start..."
until "$KCADM" config credentials \
  --server "$SERVER_URL" \
  --realm master \
  --user "${KEYCLOAK_ADMIN}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}" >/dev/null 2>&1; do
  sleep 2
done

echo "Configuring Keycloak test realm..."
"$KCADM" update realms/master -s sslRequired=NONE

if ! "$KCADM" get "realms/${REALM}" >/dev/null 2>&1; then
  "$KCADM" create realms -s "realm=${REALM}" -s enabled=true -s sslRequired=NONE
fi
"$KCADM" update "realms/${REALM}" -s sslRequired=NONE -s accessTokenLifespan=300

CLIENT_UUID=$("$KCADM" get clients -r "$REALM" -q "clientId=${CLIENT_ID}" --fields id 2>/dev/null \
  | sed -n 's/.*"id" : "\(.*\)".*/\1/p' | head -1)

if [ -z "$CLIENT_UUID" ]; then
  "$KCADM" create clients -r "$REALM" \
    -s "clientId=${CLIENT_ID}" \
    -s enabled=true \
    -s protocol=openid-connect \
    -s publicClient=true \
    -s standardFlowEnabled=true \
    -s directAccessGrantsEnabled=true \
    -s 'redirectUris=["http://localhost:8080/*"]'

  CLIENT_UUID=$("$KCADM" get clients -r "$REALM" -q "clientId=${CLIENT_ID}" --fields id \
    | sed -n 's/.*"id" : "\(.*\)".*/\1/p' | head -1)
fi

if ! "$KCADM" get "clients/${CLIENT_UUID}/protocol-mappers/models" -r "$REALM" \
  | grep -q '"protocolMapper" : "sts-session-tag-mapper"'; then
  "$KCADM" create "clients/${CLIENT_UUID}/protocol-mappers/models" -r "$REALM" -f - <<'EOF'
{
  "name": "STS Session Tags",
  "protocol": "openid-connect",
  "protocolMapper": "sts-session-tag-mapper",
  "config": {
    "access.token.claim": "true",
    "id.token.claim": "true",
    "principal.tags.enabled": "true",
    "principal.tag.attribute.prefix": "sts-session-tags.",
    "transitive.tag.keys.enabled": "true",
    "transitive.tag.keys.attribute": "sts-session-transitive-tag-keys",
    "claim.format": "radosgw-array",
    "include.parent.groups": "true",
    "include.user.attributes": "false"
  }
}
EOF
fi

get_group_id() {
  local name="$1"

  "$KCADM" get groups -r "$REALM" -q "search=${name}" --fields id,name 2>/dev/null \
    | sed -n 's/.*"id" : "\(.*\)".*/\1/p' \
    | head -1
}

create_group() {
  local name="$1"
  local tag_value="$2"
  local extra_tag_value="$3"

  local group_id
  group_id=$(get_group_id "$name")

  if [ -z "$group_id" ]; then
    group_id=$("$KCADM" create groups -r "$REALM" -s "name=${name}" -i)
  fi

  "$KCADM" update "groups/${group_id}" -r "$REALM" -f - <<EOF
{
  "id": "${group_id}",
  "name": "${name}",
  "attributes": {
    "sts-session-tags.project-access": ["${tag_value}"],
    "sts-session-tags.something-else": ["${extra_tag_value}"],
    "sts-session-transitive-tag-keys": ["project-access", "something-else"]
  }
}
EOF
}

create_group "project-7a96a68d-rw" "7a96a68dc8264f3d84fafd95a72265c5:readwrite" "free-json-compatible-thing"
create_group "project-37c05c43-rw" "37c05c43a57d419097dce9eee2769027:readwrite" "xxxxx"

USER_ID=$("$KCADM" get users -r "$REALM" -q "username=${USERNAME}" --fields id 2>/dev/null \
  | sed -n 's/.*"id" : "\(.*\)".*/\1/p' | head -1)

if [ -z "$USER_ID" ]; then
  "$KCADM" create users -r "$REALM" \
    -s "username=${USERNAME}" \
    -s enabled=true \
    -s "email=${USERNAME}@example.com" \
    -s "firstName=Alice" \
    -s "lastName=Example" \
    -s emailVerified=true

  USER_ID=$("$KCADM" get users -r "$REALM" -q "username=${USERNAME}" --fields id \
    | sed -n 's/.*"id" : "\(.*\)".*/\1/p' | head -1)

  "$KCADM" set-password -r "$REALM" --userid "$USER_ID" --new-password "$PASSWORD" --temporary=false
fi

"$KCADM" update "users/${USER_ID}" -r "$REALM" \
  -s enabled=true \
  -s "email=${USERNAME}@example.com" \
  -s "firstName=Alice" \
  -s "lastName=Example" \
  -s emailVerified=true \
  -s 'requiredActions=[]'
"$KCADM" set-password -r "$REALM" --userid "$USER_ID" --new-password "$PASSWORD" --temporary=false

for group in project-7a96a68d-rw project-37c05c43-rw; do
  GROUP_ID=$(get_group_id "$group")
  "$KCADM" update "users/${USER_ID}/groups/${GROUP_ID}" -r "$REALM" -n >/dev/null 2>&1 || true
done

echo "Environment setup complete."
echo "Realm: ${REALM}"
echo "Client: ${CLIENT_ID}"
echo "User: ${USERNAME} / ${PASSWORD}"

wait "$KEYCLOAK_PID"
