#!/usr/bin/env bash
# Preflight do sync com o upstream: busca, lista conflitos e diffs. Não mergeia.
set -euo pipefail
UPSTREAM_URL="https://github.com/utkarshdalal/GameNative"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
git fetch --no-tags upstream master
base=$(git merge-base HEAD upstream/master)
echo "merge-base: $base"
echo "commits novos: $(git rev-list --count "$base..upstream/master")"
echo "--- conflitos previstos ---"
git merge-tree --write-tree --name-only HEAD upstream/master || true
echo "--- arquivos tocados pelos dois lados ---"
comm -12 <(git diff --name-only "$base" HEAD | sort) \
         <(git diff --name-only "$base" upstream/master | sort)
