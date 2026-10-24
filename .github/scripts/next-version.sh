#!/usr/bin/env bash
# Prints the next release version (no "v" prefix) from the git history. Run from a full clone with tags.
#
# Versions are git tags vX.Y.Z. The bump comes from the conventional-commit messages since the latest tag:
#   - a "!" before the colon in a subject (feat!: / fix(scope)!:) or BREAKING CHANGE in a body -> major
#   - else any feat subject -> minor
#   - else -> patch
# With no v* tag yet the version is 0.1.0 (the first release, no bump). If HEAD already carries a
# tag (a re-run), that version is printed unchanged.
set -euo pipefail

if head_tag=$(git describe --tags --exact-match --match 'v[0-9]*.[0-9]*.[0-9]*' HEAD 2>/dev/null); then
  echo "${head_tag#v}"
  exit 0
fi

latest=$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -1)
if [ -z "$latest" ]; then
  echo "0.1.0"
  exit 0
fi

log=$(git log "$latest..HEAD" --format='%s%n%b%n--END--')
IFS=. read -r major minor patch <<< "${latest#v}"

if grep -qE '^[a-z]+(\([^)]*\))?!:|^BREAKING[ -]CHANGE' <<< "$log"; then
  echo "$((major + 1)).0.0"
elif grep -qE '^feat(\([^)]*\))?:' <<< "$log"; then
  echo "$major.$((minor + 1)).0"
else
  echo "$major.$minor.$((patch + 1))"
fi
