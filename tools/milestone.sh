#!/usr/bin/env bash
# Cria um milestone (tag anotada) + registra no docs/MILESTONES.md + push.
# Uso: tools/milestone.sh <nome-curto> "<descrição>"
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="$1"; DESC="$2"
TAG="milestone-$(date +%Y-%m-%d)-${NAME}"
HASH=$(git rev-parse --short HEAD)
DATE=$(date +%Y-%m-%d)

# Tag anotada (reusa se já existir — tags nunca são movidas)
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "tag $TAG já existe — reutilizando"
else
  git tag -a "$TAG" -m "MILESTONE: $DESC
Commit: $HASH
Voltar: git switch -c retorno-milestone $TAG"
fi

# Registra no mapa (Python: robusto a /, |, acentos na descrição)
python3 - "$TAG" "$HASH" "$DATE" "$DESC" <<'PYEOF'
import sys
tag, h, date, desc = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
# sanitiza para célula de tabela markdown (| quebra células)
cell = desc.replace("|", "/").replace("\n", " ")
row = f"| `{tag}` | `{h}` | {date} | {cell} |"
p = "docs/MILESTONES.md"
src = open(p).read()
if tag in src:
    print("mapa já contém", tag)
else:
    src = src.replace("| *(próximo)* | | |", row + "\n| *(próximo)* | | |")
    open(p, "w").write(src)
    print("mapa atualizado:", tag)
PYEOF

git add docs/MILESTONES.md
git commit -m "docs: milestone $TAG" || echo "(nada a commitar)"
git push origin "$TAG" master 2>/dev/null || echo "(push falhou — rode: git push origin $TAG master)"
echo "Milestone pronto: $TAG -> $HASH"
