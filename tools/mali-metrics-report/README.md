# mali-metrics-report

Resumo e comparação dos arquivos `metrics-*.jsonl` que o coletor sempre-ativo do GameNative grava
em `/sdcard/Android/data/app.gamenative/files/powercontrol/`. Foi feito para os A/B do
`docs/plano-mali-mediatek.md` (F1/F2/G1/H2/I5), mas serve para qualquer sessão de jogo.

## Uso

```bash
# analisar arquivos já puxados
python3 tools/mali-metrics-report/mali_metrics_report.py sessaoA.jsonl [sessaoB.jsonl]

# puxar do device (adb) e analisar os dois mais recentes
python3 tools/mali-metrics-report/mali_metrics_report.py --pull [--serial <serial>] [--last 2]
```

Com dois arquivos, imprime também o delta `B - A` (fps médio, p95 mediano, slow frames). O
critério de aceite dos itens de tuning do plano é: **manter só se fps↑ e p95/slow↓, sem
regressão**, e registrar o resultado no spec antes de virar default (regra de ouro do plano §5).

## O que calcula

- amostras totais e "jogando" (`fps > 0`; ignora o aquecimento);
- fps médio, p95 mediano e p95 máximo (só amostras com `fps > 0`);
- total de slow frames, média de GPU% e CPU%;
- quantas amostras trazem temperatura (`cpuTempC`/`gpuTempC`) e nível térmico (`thermalStatus`) —
  útil para saber se o device expõe esses nós.

Só usa a biblioteca padrão do Python 3.
