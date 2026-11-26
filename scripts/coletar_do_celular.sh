#!/usr/bin/env bash
# Coleta, por adb e SÓ PARA LER, o que ajuda a investigar um problema do REau num celular: o estado do celular e do Android Auto,
# o logcat (o do sistema, o buffer de quedas e, em sistema/, os buffers `events` e `kernel`) e as pastas de registro do app. Nunca instala, apaga nem muda
# configuração: os únicos comandos são de leitura (getprop, dumpsys, cmd appops get, am get-standby-bucket, settings get, logcat -d, logcat -g) e a
# cópia das pastas do app por run-as (só na build debug).
# Em sistema/ também vêm, para investigar o que o Android faz com o REau (25/11/2026: ele matou o processo duas vezes por falta de memória, e o logcat do
# sistema já tinha rolado):
#   - logcat-events.txt e logcat-kernel.txt (`logcat -b events|kernel -d`): am_kill, am_proc_died e o lmkd (eventos), o que o kernel diz de memória e de matar
#     processo (kernel);
#   - dumpsys-meminfo.txt (`dumpsys meminfo`: quanta memória cada processo usa agora e o que sobra) e exit-info-reau.txt / exit-info-gearhead.txt (`dumpsys
#     activity exit-info`: por que o Android diz que o processo do REau e o do Android Auto acabaram, o mesmo histórico que o app conta como
#     `[exit] past exit`, mas o do Android Auto o app não vê).
# Nunca `logcat -G` (mudar o tamanho do buffer seria escrever no aparelho): só `-g`, que lê.
#
# Uso:  scripts/coletar_do_celular.sh [SERIAL [PASTA_DE_SAIDA]]
#   SERIAL   o aparelho (adb devices); sem ele, só roda se houver exatamente UM aparelho conectado.
#   PASTA    onde guardar (padrão: uma pasta celular-AAAAMMDD-HHMM numa pasta de registros ao lado da pasta do repositório, fora dele).
# Variável ADB: o executável do adb (padrão: adb). Variável REAU_SAIDA: a pasta de saída quando não se dá o serial.
# Dois avisos (só leitura; saem na tela e no fim do INFO.txt, em "--- avisos da coleta"):
#   - o tamanho dos buffers do logcat (`logcat -g`, lido NO COMEÇO, antes de qualquer outra leitura): buffer `main` ou `system` abaixo de 1 MiB (LOGCAT_MIN_KIB=1024)
#     quer dizer que o logcat do sistema guarda pouca história (o de 256 KiB cobre menos de 1 h): colete logo depois da corrida;
#   - o espaço das gravações (item 11c): o tamanho de sessions/ + video/ copiados (o que vale, é o estado de agora). A última linha
#     `recordings space: N files removed (X KB), Y KB left of Z KB` dos registros copiados (Y é o que ficou ocupado do teto Z; o app só a escreve quando apagou algo, então pode
#     ser de dias antes) só decide quando é mais recente que o arquivo mais novo da cópia, ou quando nada foi copiado; senão vai só ao INFO.txt. A hora da linha vem do nome do
#     `ride-AAAAMMDD-HHMMSS.log` (o dia) e da linha (a hora, no fuso do celular, que se supõe o deste computador); sem como saber, a cópia decide. Ocupado a partir de
#     REAU_AVISO_MB (padrão 800) MB, o teto está perto: o app apaga os arquivos mais antigos e um evento raro some; colete a cada ≤ 2 dias de uso.
#     A linha `recordings space: sessions over their share: …` (a cota das sessões, metade do teto) é de outra conta e não vale aqui: só a do total.
#
# ATENÇÃO: o logcat do sistema tem dado pessoal (o nome do dono, e-mails, o nome inteiro das redes Wi-Fi, a lista de apps).
# Não compartilhe a pasta sem filtrar (só os buffers crash e as linhas do gearhead e do com.reau.app).
set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${1:-}"

devices() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }

if [ -z "$SERIAL" ]; then
  found="$(devices)"
  count="$(printf '%s\n' "$found" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then echo "nenhum aparelho conectado e autorizado (adb devices)" >&2; exit 2; fi
  if [ "$count" -gt 1 ]; then
    echo "mais de um aparelho conectado; diga qual: $0 SERIAL" >&2
    printf '%s\n' "$found" >&2
    exit 2
  fi
  SERIAL="$found"
elif ! devices | grep -qx -- "$SERIAL"; then
  echo "o aparelho $SERIAL não está conectado e autorizado (adb devices)" >&2
  exit 2
fi

OUT="${2:-${REAU_SAIDA:-$(cd "$(dirname "$0")/../.." && pwd)/reau_registros/celular-$(date +%Y%m%d-%H%M)}}"
mkdir -p "$OUT"
no_celular() { "$ADB" -s "$SERIAL" shell "$@"; }
GEARHEAD=com.google.android.projection.gearhead
AVISOS=""
avisar() { AVISOS="${AVISOS}$1"$'\n'; echo "AVISO: $1" >&2; }

# O tamanho dos buffers do logcat, lido antes de tudo o mais (o buffer é curto e as leituras abaixo demoram): `main: ring buffer is 256 KiB (251 KiB consumed, 1 MiB readable), …`.
# Só leitura (`logcat -g` não muda nada; mudar o tamanho seria `-G`, que este script nunca usa).
BUFFERS="$("$ADB" -s "$SERIAL" logcat -g 2>&1 || true)"
LOGCAT_MIN_KIB="${LOGCAT_MIN_KIB:-1024}"
# Imprime, uma por linha, o buffer e o tamanho em KiB dos buffers `main` e `system` que estão abaixo do mínimo (e `?` se a linha não traz o tamanho).
buffers_pequenos() {
  awk -v min="$LOGCAT_MIN_KIB" '
    /^(main|system):/ {
      nome = $1; sub(/:$/, "", nome)
      if (match($0, /ring buffer is [0-9]+ [KMG]iB/)) {
        trecho = substr($0, RSTART, RLENGTH); split(trecho, p, " ")
        kib = p[4] + 0
        if (p[5] == "MiB") kib *= 1024; else if (p[5] == "GiB") kib *= 1048576
        if (kib < min) print nome, kib
      } else print nome, "?"
    }'
}
if [ -z "$(printf '%s\n' "$BUFFERS" | grep -E '^(main|system):' || true)" ]; then
  avisar "o tamanho dos buffers do logcat não pôde ser lido (logcat -g); não dá para saber quanta história o aparelho guarda"
else
  while read -r nome kib; do
    [ -n "$nome" ] || continue
    avisar "o buffer $nome do logcat é pequeno (${kib} KiB, abaixo de ${LOGCAT_MIN_KIB}): o logcat do sistema cobre pouca história; colete logo depois da corrida"
  done <<< "$(printf '%s\n' "$BUFFERS" | buffers_pequenos)"
fi

{
  echo "# coletado por adb (somente leitura) em $(date); aparelho $SERIAL"
  echo "modelo: $(no_celular getprop ro.product.model | tr -d '\r')  android: $(no_celular getprop ro.build.version.release | tr -d '\r')"
  echo "rom: $(no_celular getprop ro.build.display.id | tr -d '\r')  lineage: $(no_celular getprop ro.lineage.version | tr -d '\r')"
  for pkg in com.reau.app com.reau.app.lite "$GEARHEAD"; do
    echo "--- $pkg"
    no_celular dumpsys package "$pkg" | grep -m4 -E "versionName|versionCode|lastUpdateTime|installerPackageName" || true
  done
  echo "--- o Android Auto em segundo plano"
  no_celular cmd appops get "$GEARHEAD" RUN_ANY_IN_BACKGROUND || true
  no_celular am get-standby-bucket "$GEARHEAD" || true
  no_celular dumpsys deviceidle whitelist | grep -E "gearhead|reau" || echo "(nenhum dos dois na lista de isenção do Doze)"
  echo "--- economia de bateria e bateria"
  echo "low_power=$(no_celular settings get global low_power | tr -d '\r')"
  echo "--- o que faz o Android recusar ligar um socket a uma rede (EPERM)"
  echo "restricted_networking_mode=$(no_celular settings get global restricted_networking_mode | tr -d '\r')"
  echo "always_on_vpn_app=$(no_celular settings get secure always_on_vpn_app | tr -d '\r')"
  echo "always_on_vpn_lockdown=$(no_celular settings get secure always_on_vpn_lockdown | tr -d '\r')"
  no_celular dumpsys power | grep -iE "saver|low_power" | head -8 || true
  no_celular dumpsys battery | head -14 || true
} > "$OUT/INFO.txt" 2>&1

# Os buffers do sistema: tudo o que o aparelho ainda guarda, e o buffer das quedas.
"$ADB" -s "$SERIAL" logcat -b all -d -v threadtime > "$OUT/logcat-sistema.txt" 2>&1 || true
"$ADB" -s "$SERIAL" logcat -b crash -d -v threadtime > "$OUT/logcat-crash.txt" 2>&1 || true
# O que o SISTEMA diz do Wi-Fi do painel na hora de uma queda (wpa_supplicant, ClientModeImpl, ConnectivityService...): um app comum só lê o
# próprio logcat (sem READ_LOGS), então isto só se pega por adb (plano 25). Fica em sistema/: o estado do Wi-Fi e da conectividade
# (dumpsys wifi: o histórico de eventos do cliente Wi-Fi, com os motivos de queda; dumpsys connectivity: as redes, os pedidos e os eventos recentes),
# o tamanho dos buffers do logcat (quanta história o aparelho guarda: menos de 1 h costuma ser tudo) e as linhas de Wi-Fi/rede extraídas do
# logcat de todos os buffers. Rode logo depois da corrida: o buffer é curto. Só leitura; nenhum filtro de privacidade além da cópia mascarada.
mkdir -p "$OUT/sistema"
no_celular dumpsys wifi > "$OUT/sistema/dumpsys-wifi.txt" 2>&1 || true
no_celular dumpsys connectivity > "$OUT/sistema/dumpsys-connectivity.txt" 2>&1 || true
# Memória e mortes de processo (só leitura): os buffers `events` e `kernel` do logcat (cada um guarda coisa que o `-b all` em threadtime mostra mal ou já
# perdeu), a memória de cada processo agora e o histórico de saídas do Android (ApplicationExitInfo) do REau e do Android Auto. Rode logo depois de uma morte
# do app: o histórico guarda poucas entradas.
"$ADB" -s "$SERIAL" logcat -b events -d -v threadtime > "$OUT/sistema/logcat-events.txt" 2>&1 || true
"$ADB" -s "$SERIAL" logcat -b kernel -d -v threadtime > "$OUT/sistema/logcat-kernel.txt" 2>&1 || true
no_celular dumpsys meminfo > "$OUT/sistema/dumpsys-meminfo.txt" 2>&1 || true
no_celular dumpsys activity exit-info com.reau.app > "$OUT/sistema/exit-info-reau.txt" 2>&1 || true
no_celular dumpsys activity exit-info "$GEARHEAD" > "$OUT/sistema/exit-info-gearhead.txt" 2>&1 || true
printf '%s\n' "$BUFFERS" > "$OUT/sistema/logcat-tamanho-dos-buffers.txt"
# O que o Android diz do acesso do REau à localização (a busca de redes com o app atrás depende dela; item 1a, pedido em 08/11/2026): os
# modos de cada permissão do app (`cmd appops get`, com o `rejectTime` de uma recusa) e as linhas do `dumpsys location` que falam do REau
# (pedidos e usos de localização). Só leitura; o filtro deixa de fora o que é de outros apps.
no_celular cmd appops get com.reau.app > "$OUT/sistema/appops-reau.txt" 2>&1 || true
no_celular dumpsys location 2>&1 | grep -i "com\.reau\.app" > "$OUT/sistema/location-reau.txt" || true
grep -E "wpa_supplicant|WifiService|WifiClientModeImpl|ClientModeImpl|WifiConnectivityManager|WifiNetworkFactory|WifiNetworkSelector|WifiNative|WifiHealthMonitor|WifiScanner|ConnectivityService|NetworkMonitor|NetworkAgent|netd|RE_[A-Za-z0-9]" \
  "$OUT/logcat-sistema.txt" > "$OUT/sistema/wifi-linhas.txt" 2>/dev/null || true

# Uma cópia para compartilhar, sem o nome Wi-Fi do painel (RE_xxxx_aammdd, que prende o registro a uma moto e a um dono), sem os endereços MAC e sem os nomes das outras
# redes Wi-Fi (casa, trabalho: registro/mascarar.py); o original fica. O resto do dado pessoal do logcat (nome, e-mails, apps) continua nele: filtre antes de compartilhar.
if command -v python3 >/dev/null 2>&1; then
  python3 -I "$(dirname "$0")/registro/mascarar.py" "$OUT/logcat-sistema.txt" "$OUT/logcat-sistema.mascarado.txt" || true
  # O kernel e os eventos podem trazer o nome Wi-Fi e o MAC (o driver do Wi-Fi escreve no kernel); a memória e o histórico de saídas só falam de processos:
  # sem cópia mascarada.
  for arquivo in dumpsys-wifi dumpsys-connectivity wifi-linhas appops-reau location-reau logcat-events logcat-kernel; do
    python3 -I "$(dirname "$0")/registro/mascarar.py" "$OUT/sistema/$arquivo.txt" "$OUT/sistema/$arquivo.mascarado.txt" || true
  done
fi

# As pastas de registro do app (só a build debug deixa o run-as ler): com.reau.app (o app único desde a 0.3.0, e a antiga Completa) e, se
# ainda existir, a antiga Lite (com.reau.app.lite, só até 0.2.0); a da antiga Lite vai para a subpasta antigo-lite/ para não se misturar.
# sessions/ vem inteira: além do anel da gravação (reau_<data>_<hora>_<ms>[.1].aasession), o que a Marca do piloto congela
# (reau_<data>_<hora>_marca[.1].aasession, que o anel não sobrescreve; plano 22). Nenhum filtro por nome.
# Uma pasta por vez: a que não existe (ainda sem gravações de vídeo, por exemplo) não impede as outras.
for pkg in com.reau.app com.reau.app.lite; do
  destino="$OUT"
  if [ "$pkg" = com.reau.app.lite ]; then destino="$OUT/antigo-lite"; mkdir -p "$destino"; fi
  for pasta in diag sessions video; do
    if "$ADB" -s "$SERIAL" exec-out run-as "$pkg" tar c -C files "$pasta" 2>/dev/null | tar x -C "$destino" 2>/dev/null; then
      echo "pasta $pasta copiada de $pkg" >> "$OUT/INFO.txt"
    fi
  done
done
rmdir "$OUT/antigo-lite" 2>/dev/null || true

# O que o INFO.txt também diz (A9, 17/11/2026), só leitura: (1) quanta história cada buffer do logcat guarda, com o tamanho e a hora da primeira linha dele (só a data e a hora, nunca o
# texto: ele é de qualquer app); com o `-b all` junto não dá para saber de que buffer é cada linha, e o `main` costuma cobrir só umas 3 h; (2) o estado do processo do Android Auto que
# projeta (`:projection`): quando começou (tempo de vida do `ps`) e, do `dumpsys activity processes`, o que ele diz do registro dele (importância, se está congelado). Quem lê o INFO.txt
# prova ou descarta o Android Auto congelado sem ter de adivinhar. O `-m` pára o logcat nas primeiras linhas; um aparelho que não o aceite deixa a linha como «não lido».
{
  echo "--- história de cada buffer do logcat (tamanho; hora da primeira linha)"
  for buffer in main system crash; do
    tamanho="$(printf '%s\n' "$BUFFERS" | sed -n "s/^$buffer: ring buffer is \([0-9][0-9]* [KMG]iB\).*/\1/p" | head -1)"
    primeira="$("$ADB" -s "$SERIAL" logcat -b "$buffer" -d -v threadtime -m 3 2>/dev/null | awk '/^[0-9][0-9]-[0-9][0-9] [0-9:.]+ / {print $1, $2; exit}' || true)"
    echo "$buffer: ${tamanho:-tamanho não lido}; primeira linha ${primeira:-não lida (buffer vazio ou o aparelho não aceita o -m)}"
  done
  echo "--- o processo do Android Auto que projeta ($GEARHEAD:projection)"
  processo="$(no_celular ps -A -o PID,STAT,ETIME,NAME 2>/dev/null | tr -d '\r' | awk -v alvo="$GEARHEAD:projection" 'index($0, alvo)' || true)"
  if [ -n "$processo" ]; then echo "PID STAT ETIME(desde o início) NOME"; printf '%s\n' "$processo"; else echo "(o processo :projection não existe agora)"; fi
  no_celular dumpsys activity processes 2>/dev/null | awk -v alvo="$GEARHEAD:projection" '
    /^  \*(APP|PERS)\*/ {dentro = index($0, alvo) > 0}
    dentro && /ProcessRecord\{|[Ff]rozen|[Cc]ached|curAdj|setAdj|procState|importance|[Ss]tartTime|lastActivityTime/ {print}' | head -20 || true
} >> "$OUT/INFO.txt" 2>&1

# O espaço das gravações (sessions/ e video/), pelo `left of` que o app escreve ao apagar o mais antigo e pelo tamanho do que veio na cópia. Só lê arquivos já copiados.
AVISO_MB="${REAU_AVISO_MB:-800}"
ESPACO_PADRAO_KB=1048576
# O arquivo e a linha (separados por TAB) da última linha de total, para saber de que dia ela é.
ultima_com_arquivo="$(awk '/recordings space:/ && !/sessions over their share/ {ultima = FILENAME "\t" $0} END {print ultima}' "$OUT"/diag/ride-*.log 2>/dev/null || true)"
ultima="${ultima_com_arquivo#*$'\t'}"
ultima_arquivo="${ultima_com_arquivo%%$'\t'*}"
ocupado_kb=""; teto_kb="$ESPACO_PADRAO_KB"
if [ -n "$ultima" ]; then
  ocupado_kb="$(printf '%s\n' "$ultima" | sed -n 's/.*, \([0-9][0-9]*\) KB left of \([0-9][0-9]*\) KB.*/\1/p')"
  teto_lido="$(printf '%s\n' "$ultima" | sed -n 's/.*, \([0-9][0-9]*\) KB left of \([0-9][0-9]*\) KB.*/\2/p')"
  [ -z "$teto_lido" ] || teto_kb="$teto_lido"
fi
copiado_kb="$(du -sk "$OUT/sessions" "$OUT/video" 2>/dev/null | awk '{s += $1} END {print s + 0}' || true)"
{
  echo "--- espaço das gravações (item 11c)"
  echo "ultima linha 'recordings space:' dos registros copiados: ${ultima:-(nenhuma: o app só a escreve quando apaga algo)}"
  echo "sessions/ + video/ copiados: ${copiado_kb} KB ($(( (copiado_kb * 100 + teto_kb / 2) / teto_kb )) % do teto de ${teto_kb} KB)"
} >> "$OUT/INFO.txt"
# O que vale é o tamanho do que veio na cópia (o estado de agora). A linha `left of` é uma foto do momento em que o app apagou algo e pode ser de dias antes: a de 14/11
# disse 974 MB onde a cópia, tirada dias depois, tinha 806 MB (79 %). A linha só decide quando é mais recente que o arquivo mais novo da cópia, ou quando nada foi copiado
# (sem run-as, sem gravações). Sem como datar a linha ou os arquivos, a cópia decide.
linha_mais_nova=""
if [ -n "$ocupado_kb" ] && [ "${copiado_kb:-0}" -gt 0 ]; then
  dia_do_arquivo="$(basename "$ultima_arquivo" | sed -n 's/^ride-\([0-9]\{4\}\)\([0-9]\{2\}\)\([0-9]\{2\}\)-\([0-9]\{2\}\)\([0-9]\{2\}\)\([0-9]\{2\}\)\.log$/\1-\2-\3 \4:\5:\6/p')"
  hora_da_linha="$(printf '%s\n' "$ultima" | sed -n 's/^\([0-9][0-9]:[0-9][0-9]:[0-9][0-9]\).*/\1/p')"
  if [ -n "$dia_do_arquivo" ] && [ -n "$hora_da_linha" ]; then
    inicio_s="$(date -d "$dia_do_arquivo" +%s 2>/dev/null || true)"
    linha_s="$(date -d "${dia_do_arquivo%% *} $hora_da_linha" +%s 2>/dev/null || true)"
    # O registro passou da meia-noite: a hora da linha é menor que a da partida do arquivo, então é do dia seguinte.
    if [ -n "$inicio_s" ] && [ -n "$linha_s" ] && [ "$linha_s" -lt "$inicio_s" ]; then linha_s=$((linha_s + 86400)); fi
    copia_s="$(find "$OUT/sessions" "$OUT/video" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1 || true)"
    if [ -n "$linha_s" ] && [ -n "$copia_s" ] && [ "$linha_s" -gt "$copia_s" ]; then linha_mais_nova=1; fi
  fi
fi
kb=""; fonte=""
if [ "${copiado_kb:-0}" -gt 0 ] && [ -z "$linha_mais_nova" ]; then
  kb="$copiado_kb"; fonte="pelo tamanho de sessions/ + video/ copiados"
elif [ -n "$ocupado_kb" ]; then
  if [ -n "$linha_mais_nova" ]; then fonte="pela última linha 'recordings space' dos registros, mais recente que os arquivos da cópia"
  else fonte="pela última linha 'recordings space' dos registros (nada foi copiado)"; fi
  kb="$ocupado_kb"
fi
if [ -n "$kb" ] && [ "$kb" -ge $((AVISO_MB * 1024)) ]; then
  avisar "gravações em $((kb / 1024)) MB de $((teto_kb / 1024)) MB (livre $(( (teto_kb - kb) / 1024 )) MB, $(( (kb * 100 + teto_kb / 2) / teto_kb )) % ocupado, $fonte; aviso a partir de ${AVISO_MB} MB): o app apaga as mais antigas ao passar do teto; colete a cada 2 dias de uso ou logo depois de um evento raro"
fi
if [ -n "$AVISOS" ]; then { echo "--- avisos da coleta"; printf '%s' "$AVISOS"; } >> "$OUT/INFO.txt"; fi

echo "coletado em $OUT"
echo "logcat-sistema.mascarado.txt (e os .mascarado.txt de sistema/) são as cópias sem o nome Wi-Fi do painel, sem MACs e sem os nomes de outras redes; ainda têm o resto do dado pessoal (nome, e-mails, apps)."
echo "sistema/ guarda o que o Android disse do Wi-Fi e da conectividade, da memória e das mortes de processo (events, kernel, meminfo, exit-info);"
echo "o logcat guarda pouca história, então rode isto logo depois da corrida."
echo "ATENÇÃO: logcat-sistema.txt tem dado pessoal (nome, e-mails, redes Wi-Fi, apps), e sessions/ e video/ (se copiadas) mostram o mapa e por onde você andou: não compartilhe sem filtrar."
