# Proveniência do wrapper Vulkan `wrapper-gamenative`

**Data:** 2026-10-10
**Origem:** frente **F3** do `docs/plano-mali-mediatek.md` (Onda 0).
**Implementação/registro:** `docs/plano-mali-mediatek-onda0-impl.md` §4.1.

Este documento registra de qual snapshot do `GameNative/mesa` veio o wrapper Vulkan que
distribuímos. O manifesto `graphics_driver_download.json` só tem `id`/`name`/`url` e o app não
valida integridade do download; por isso a proveniência fica aqui, verificada, e não inventada
como campo do manifesto.

## Artefato

| Item | Valor |
|---|---|
| Asset local (flavor legacy) | `app/src/legacy/assets/graphics_driver/wrapper-gamenative.tzst` |
| Nome publicado | `wrapper-gamenative-20260724.tzst` |
| URL | `https://downloads.gamenative.app/graphics_driver/wrapper-gamenative-20260724.tzst` |
| Tamanho | 747.871 bytes |
| sha256 | `1a546621cf285b50a6de47aeb0ada4a298d41ac24fc5ca2cdc46673859056d37` |

O asset local é **byte-idêntico** ao publicado (mesmo sha256).

## Origem do código

| Item | Valor |
|---|---|
| Repositório | `GameNative/mesa` |
| Branch | `wrapper-25` |
| Commit | `b8663654f7ea` (merge do PR #8, 2026-07-25T05:04:22Z) |
| Tree | `874f4ce8e3c522267a81646daa61fe912f85b0b6` |
| Faixa equivalente | `0bd55cbed77d` .. `b8663654f7ea` (mesmo tree) |
| Confiança | alta para o tree/faixa |

O blob é **anterior à CI do PR #16** (`8a01bd8`, 2026-10-10): o tar tem dono
`utkarshdalal/staff` e xattr `com.apple.provenance`, ou seja, foi empacotado manualmente em
macOS. A CI do PR #16 reproduz a mesma receita, layout e nome
(`BUILD_DATE="$(date -u +%Y%m%d)"`).

**Não** corresponde ao HEAD do clone `reference/GameNative-mesa` (`8a01bd8`), cujo wrapper é
cerca de três meses mais novo.

## Membros e hashes

O `.tzst` contém 7 arquivos:

| Arquivo | Bytes | sha256 |
|---|---:|---|
| `usr/lib/libvulkan_wrapper.so` | 2.710.376 | `2aecf1c11cde57008a3cc5e1a551e095d7843d3aa84b3756953afc134035e7c5` |
| `usr/lib/libadrenotools.so` | 47.760 | `7412c757d22aaa6c9ee94e567a0637a203c852de3d095e75397e85264108858c` |
| `usr/lib/libhook_impl.so` | 40.968 | `63d5960e97119a93a00c0d5b7fa1c4e4a84ec47f7dd7c15024142f1943487632` |
| `usr/lib/libmain_hook.so` | 7.560 | `de9418db319242ad08e2c0d52ecfefb1eeede5341989ef1aa20cba8dca8fb926` |
| `usr/lib/libfile_redirect_hook.so` | 6.448 | `081809c19edc4d6ef44667f16bacdc3ff534be5669a87db015d24ca50ee60953` |
| `usr/lib/libgsl_alloc_hook.so` | 7.488 | `80969e481472084ce5f565b716a4707c91ff1f9fe99b9ec70eebebff0ab54cc7` |
| `usr/share/vulkan/icd.d/wrapper_icd.aarch64.json` | 140 | `adb27bf7cad76917424a9eaee918c5f0ec755adc98c0921f78434d67f89de304` |

O ICD declara `api_version` `1.3.289`.

## Como a origem foi determinada

O `.so` é stripped e não embute hash de commit. A identificação veio de marcadores funcionais:

- O callsite `Dedicated image/buffer handle type mismatch` loga em nível `info` até
  `c4c42fa81b61` (2026-07-24) e em nível `error` a partir de `0bd55cbed77d` (2026-07-25). O
  binário usa o literal `error`.
- Todos os literais introduzidos no wrapper a partir de agosto/2026 (`f41c623d`, `67a15a6b`,
  `4670c1a2`, `6d021f50`, `621389f7`, `dc8ecb04`…) estão **ausentes**.
- O `mtime` do membro `libvulkan_wrapper.so` é `2026-07-25T05:09:37Z`, 5 minutos após o merge.

Não houve rebuild byte-a-byte (exigiria NDK r29 + sysroot Termux); a datação é por string e
disassembly do nível de log.

## Recomendações

1. Usar **releases por tag `wrapper-*`** da CI do PR #16 como fonte de verdade — as notas da
   release gravam `Built from ${GITHUB_SHA}` e o artefato sai com `.sha256` ao lado.
2. Registrar cada par `(nome, sha256, commit)` neste documento quando o asset for trocado.
3. Se algum dia o app for validar integridade, é uma decisão de schema do
   `graphics_driver_download.json` (campo `sha256` + verificação no `GraphicsDriverDownloader`);
   o valor correto para o asset atual é o da primeira tabela.
4. Observação de consistência: o `updatedAt` do manifesto é `2026-07-17`, anterior ao artefato
   de 2026-07-24/25 — o campo não foi atualizado para esse componente.
