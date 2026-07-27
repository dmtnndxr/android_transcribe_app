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

if [ "${1:-}" = "shell" ]; then
    exec docker run "${RUN_ARGS[@]}" -it --entrypoint /bin/bash "$IMAGE"
fi

exec docker run "${RUN_ARGS[@]}" "$IMAGE" "${1:-all}"
