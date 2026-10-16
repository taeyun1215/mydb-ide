#!/bin/sh
# Prepare an intellij-community checkout (tag idea/262.10968.63, plus its android/ clone) to build MyDB IDE.
# 1. link src/product as <community>/mydb
# 2. apply patches/ (module registration, generated Bazel library entries, platform fix)
# 3. regenerate Bazel files from the JPS model
# Build afterwards: cd <community> && ./build/run_build_target.sh "$(pwd)" //mydb/build:i_build_target
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
community="${1:-$here/../../intellij-community}"
cd "$community"
ln -sfn "$here" mydb
for p in "$here"/patches/*.patch; do
  if git apply --check "$p" 2>/dev/null; then git apply "$p"; echo "applied $(basename "$p")"
  else echo "skip $(basename "$p") (already applied or conflicts)"; fi
done
./build/jpsModelToBazelCommunityOnly.cmd
