#!/usr/bin/env bash
set -eu

# Prepares Mill selective execution, so that subsequent "./mill selective.run <tasks>" or
# "./mill selective.resolve <tasks>" calls only run or print the tasks affected by the changes
# between a base commit and the current one. See
# https://mill-build.org/mill/large/selective-execution.html
#
# Usage: .github/scripts/selective-prepare.sh [base-commit]
#
# On GitHub Actions pull requests, the base commit defaults to the first parent of the merge
# commit actions/checkout checks out, so that we compare exactly against what the PR is merged
# into. Elsewhere (pushes to the main branch, tags, …), when no base commit can be found, or if
# anything goes wrong here, the selective execution metadata is left empty, which makes
# selective.run run all the tasks passed to it, as if selective execution wasn't used.

out="${MILL_OUTPUT_DIR:-out}"
metadata="$out/mill-selective-execution.json"

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  mill=./mill.bat
else
  mill=./mill
fi

run_everything() {
  echo "$1, all tasks will be run" >&2
  mkdir -p "$out"
  : > "$metadata"
  exit 0
}

base="${1:-}"
if [ -z "$base" ]; then
  if [ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]; then
    run_everything "Not running for a pull request"
  fi
  # actions/checkout checks out a merge commit of the PR head into its base branch
  if ! git rev-parse -q --verify "HEAD^2" > /dev/null; then
    run_everything "HEAD isn't a merge commit"
  fi
  base="HEAD^1"
fi

if ! git diff --quiet HEAD; then
  run_everything "Working directory has uncommitted changes"
fi

head="$(git rev-parse HEAD)"
base="$(git rev-parse "$base^{commit}")" || run_everything "Base commit $base not found"

echo "Preparing selective execution against base commit $base" >&2
git checkout -q --detach "$base"
if ! "$mill" -i selective.prepare; then
  git checkout -q --detach "$head"
  run_everything "Preparing selective execution failed"
fi
git checkout -q --detach "$head"

# The meta-build's incremental compilation sometimes fails with spurious "conflicting overrides"
# errors after the build sources changed, so start its compilation from scratch in that case.
if ! git diff --quiet "$base" "$head" -- '*.mill' mill-build; then
  rm -rf "$out/mill-build"
fi

echo "Changes since the base commit:" >&2
git diff --stat "$base" "$head" >&2
