#!/usr/bin/env bash
#
# Host-side entry point: builds the verification image and runs the checks
# against the working tree.
#
#   tools/verify/run.sh              # both tiers
#   tools/verify/run.sh tier1        # logic tests only (fast)
#   tools/verify/run.sh tier2        # resources + type-check only
#   tools/verify/run.sh shell        # interactive poke-around
#
# Everything the checks need lives in the image and one named volume, so there
# is nothing to uninstall from the host. To reclaim the disk:
#
#   tools/verify/run.sh clean
#
set -euo pipefail

IMAGE=ovi-verify
VOLUME=ovi-gradle-cache
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ "${1:-}" = "clean" ]; then
    docker rmi -f "$IMAGE" 2>/dev/null || true
    docker volume rm "$VOLUME" 2>/dev/null || true
    echo "Removed image '$IMAGE' and volume '$VOLUME'."
    exit 0
fi

echo "==> Building $IMAGE (cached after the first run)"
docker build -q -t "$IMAGE" -f "$REPO_ROOT/tools/verify/Dockerfile" "$REPO_ROOT/tools/verify" >/dev/null

# The Gradle cache lives in a named volume, not the working tree: it survives
# between runs so only the first one downloads AGP and the AARs, and it does not
# litter the repo with root-owned build dirs.
docker volume create "$VOLUME" >/dev/null

RUN_ARGS=(
    --rm
    -v "$REPO_ROOT:/work"
    -v "$VOLUME:/gradle"
    -w /work
)

# Optional live smoke test against the real OpenRouter API.
#
# The key comes from $OPENROUTER_API_KEY, or from a key file if that is unset —
# the file exists because whether a shell sources your profile depends on how it
# was started, and "the test silently skipped" is a confusing failure mode.
KEY_FILE="${OPENROUTER_KEY_FILE:-$HOME/.config/ovi/openrouter.key}"
if [ -z "${OPENROUTER_API_KEY:-}" ] && [ -r "$KEY_FILE" ]; then
    OPENROUTER_API_KEY="$(tr -d '[:space:]' < "$KEY_FILE")"
    export OPENROUTER_API_KEY
fi

# Passed as a bare "-e NAME" so the value is inherited from this shell rather
# than appearing in the container's command line, where `ps` would show it.
# Absent means OpenRouterLiveTest reports itself as skipped.
for var in OPENROUTER_API_KEY OPENROUTER_MODEL; do
    if [ -n "${!var:-}" ]; then
        RUN_ARGS+=(-e "$var")
    fi
done

if [ "${1:-}" = "shell" ]; then
    exec docker run "${RUN_ARGS[@]}" -it --entrypoint /bin/bash "$IMAGE"
fi

exec docker run "${RUN_ARGS[@]}" "$IMAGE" "${1:-all}"
