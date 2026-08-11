# QuickMenu abrindo sozinho no start do jogo (regressão do invite) — design (2026-08-11)

> **Problema:** ao abrir um jogo no GameNative, o QuickMenu abre de forma **não solicitada**
> (aba INVITE), logo no start da sessão — sem nenhum botão do jogador. O jogo também fica
> **sem pausar** com o menu aberto (porque a abertura foi marcada como "pedida pelo jogo").
>
> **Sintoma no log:** `SteamInviteState: game requested lobbyinvite lobby=...` seguido de
> `QuickMenu bootstrap` + `onRequestOpen()` nos primeiros segundos da sessão, sem input.

## 1. Causa raiz

O caminho de abertura é (introduzido em `e4f4a3dd` "Added ability to invite friends via
overlay"):

1. O host bionic Steam (`libsteambootstrap.so`) captura o callback de "Invite friends" do
   próprio jogo e o expõe como um **overlay request pendente** no socket abstrato
   `gamenative-steam-overlay` (comando `POLL`).
2. O QuickMenu roda um laço de 1s enquanto o menu está fechado
   (`QuickMenu.kt` — `LaunchedEffect(inviteMenu)`): `consumeGameInviteRequest()` faz
   `POLL` e, se houver request de invite, **abre o menu na aba INVITE** via
   `onRequestOpen()`.
3. `SteamInviteState.openedForGameRequest = true` (flag **booleana**) faz o
   `XServerScreen` **pular a pausa** na abertura do overlay.

Evidência do binário (`strings libsteambootstrap.so`): a fila de requests é **em memória**
(protocolo via socket abstrato; não há strings de persistência em arquivo) e o processo
host morre a cada troca de sessão (`SteamBootstrap.stopHost()` no exit do jogo e no
`prepareApp()` do próximo launch) — ou seja, **não existe request persistido entre
sessões**. O gatilho real é o próprio jogo emitindo o callback "game requested dialog"
(que o host loga como `overlay: game requested dialog="%s" lobby=%llu`) — alguns jogos
pedem o diálogo de invite durante o boot da sessão online, e o laço de 1s consumia esse
request **imediatamente** ao abrir o jogo → menu abria sozinho.

Dois defeitos permitiam abertura não solicitada:

- **Request no boot da sessão:** o jogo re-emite o callback de invite durante o boot do
  wine/inicialização da sessão online → o laço de 1s abria o menu nos primeiros segundos,
  sem input do jogador.
- **Host que nunca limpa o POLL:** o laço consome via `POLL`, mas se o host não esvazia a
  fila, o **mesmo** request volta em todo `POLL` → depois do 1º open (legítimo, botão do
  jogo) o menu reabre sozinho a cada segundo, para sempre, e `openedForGameRequest=true`
  deixava o jogo permanentemente sem pausa.

Além disso, a flag booleana era frágil: qualquer menu aberto depois (mesmo sem relação
com o jogo) herdava "abertura por request" e pulava a pausa até o menu fechar.

## 2. Correção (commit `bac07811`)

| Guarda | Valor | Efeito |
|---|---|---|
| `SESSION_GRACE_MS` | 20 s | Requests dentro dos primeiros 20 s após a criação do `SteamInviteState` (início da sessão) são **ignorados** — mata o request stale no start. |
| `REQUEST_DEDUPE_WINDOW_MS` | 60 s | A mesma chave `dialog|lobbyId` é consumida no máximo **uma vez por janela de 60 s** — mata o host que nunca limpa o POLL (menu não reabre em loop). |
| `OPEN_REQUEST_MAX_AGE_MS` | 30 s | `openedForGameRequest` virou **timestamp**; o `XServerScreen` só pula a pausa se a abertura por request tiver ocorrido há < 30 s — uma flag velha nunca deixa o jogo sem pausa. |

Bônus de navegação (mesma spec): a aba INVITE vazia (sem amigos **ou** host indisponível)
agora renderiza uma **row de retry focável** ("Pressione X para atualizar") em vez de um
`Text` não-focável — sem isso o menu aberto por request nasceria **morto** (aba sem item
focável → bootstrap cai no rail, e o rail não é o esperado na aba INVITE).

## 3. Verificação

- **Unit:** `GamepadStickLogicTest` (7) + `GamepadModifiersTest` (19) permanecem verdes.
- **On-device (harness + logcat):**
  1. Abrir o jogo (ex.: Silksong via
     `adb shell am start -a app.gamenative.LAUNCH_GAME -n app.gamenative/.MainActivity
     --ei app_id 1030300 --es game_source STEAM`) e observar **> 20 s** sem
     `onRequestOpen()` espontâneo; `logcat` sem `QuickMenu bootstrap` não-solicitado.
  2. Com um request pendente no POLL (reproduzir abrindo o invite do jogo), fechar o menu:
     ele **não** deve reabrir sozinho dentro de 60 s (dedupe) e o jogo deve **pausar** ao
     reabrir o menu normalmente (flag fresca).
  3. Pressionar o "Invite friends" do jogo **durante** a sessão (após a grace) → menu abre
     na aba INVITE com foco na row de retry/amigos, jogo **sem pausa** (request legítimo).

## 4. Fora de escopo

- O host (`libsteambootstrap.so`) é binário upstream — não alterado; a correção é toda no
  consumidor (Android).
