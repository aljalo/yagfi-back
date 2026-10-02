#!/usr/bin/env bash
set -euo pipefail

app_log="${TMPDIR:-/tmp}/yagfi-smoke.log"
java -jar target/yagfi-back-0.0.1.jar \
  --spring.profiles.active=local \
  --spring.properties.github.token=smoke-placeholder \
  --spring.properties.feature-enabled.feed-generation=false \
  --spring.properties.feature-enabled.request-logging=false > "$app_log" 2>&1 &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true' EXIT

ready=false
for attempt in {1..60}; do
  if curl --fail --silent http://localhost:8080/api/issues/languages > /dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    cat "$app_log"
    exit 1
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  cat "$app_log"
  exit 1
fi

for route in issues/languages issues/licenses events feed/users; do
  curl --fail --silent --show-error "http://localhost:8080/api/$route" \
    | python3 -c 'import json,sys; data=json.load(sys.stdin); assert isinstance(data,list), data'
  echo "Smoke check passed: /api/$route"
done
