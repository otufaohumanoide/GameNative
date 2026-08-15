# Seed do catálogo de perfis (E — spec 2026-08-16-E-profile-catalog-comunitario)

v1 do repo-fonte do catálogo: perfis de exemplo exportados de uso real
(GamepadProfile JSON) em entries no schema do `sync_profile_repo.py`:

```json
{
  "id": "unico-estavel",
  "game": "<appId do container, ou null = universal>",
  "faceStyle": "PLAYSTATION | XBOX | NINTENDO | GENERIC",
  "controller": "texto livre (ex.: DualShock 4)",
  "name": "…", "author": "…", "description": "…", "downloads": 12,
  "profile": { …GamepadProfile JSON (schemaVersion 1)… }
}
```

Regras:
- O `game` deve casar com o appId do container ativo para o filtro "Este jogo"
  (`ProfileCatalog.forGame`); universal (null) aparece na lista geral/busca.
- O sync VALIDA tudo (allowlist de campos do GamepadProfile, enums, direções de
  swipe, LUTs clamp 0..1) — entry inválida é descartada com aviso, nunca aborta.
- Regenerar o asset só quando o conteúdo mudar:
  `python3 tools/profiles/sync_profile_repo.py` (saída determinística; gate:
  rodar 2× → diff vazio).

O repo comunitário externo pluga depois SEM mudança de formato: basta trocar a
fonte do script (commit pinado no topo do `sync_profile_repo.py`).
