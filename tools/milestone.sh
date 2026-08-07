#!/usr/bin/env bash
# Cria um milestone (tag anotada) + registra no docs/MILESTONES.md + push.
# Uso: tools/milestone.sh <nome-curto> "<descrição>"
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="$1"; DESC="$2"
TAG="milestone-$(date +%Y-%m-%d)-${NAME}"
HASH=$(git rev-parse --short HEAD)
DATE=$(date +%Y-%m-%d)
git tag -a "$TAG" -m "MILESTONE: $DESC
Commit: $HASH
Voltar: git switch -c retorno-milestone $TAG"
# registra no mapa
sed -i "s/| \*(próximo)\* | | |/| \`$TAG\` | \`$HASH\` | $DATE | $DESC |\n| *(próximo)* | | |/" docs/MILESTONES.md
git add docs/MILESTONES.md
git commit -m "docs: milestone $TAG"
git push origin "$TAG" master 2>/dev/null || echo "(push falhou — rode: git push origin $TAG master)"
echo "Milestone criado: $TAG -> $HASH"
