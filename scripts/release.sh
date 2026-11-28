#!/usr/bin/env bash
# Prepara a release do REau: repete, em ordem e parando no primeiro problema, os passos com que a 0.3.0 foi publicada (25/11/2026).
#   1. a árvore está limpa e a tag v<versão> existe e aponta para o HEAD (o script nunca cria a tag);
#   2. o versionName do build é a versão pedida e a seção "## [<versão>] - AAAA-MM-DD" existe nos três CHANGELOGs;
#   3. as quatro variáveis da chave (REAU_KEYSTORE, REAU_KEY_ALIAS, REAU_KEYSTORE_PASSWORD, REAU_KEY_PASSWORD) estão definidas e
#      REAU_KEYSTORE é um arquivo (só os NOMES das que faltam aparecem na tela; os valores nunca);
#   4. ./gradlew :app:assembleRelease;
#   5. apksigner verify: assinatura v2 verificada e o certificado do projeto (SHA-256 abaixo);
#   6. scripts/apk_audit.py: nada novo em relação à linha de base e a forma de release certa;
#   7. REau-<versão>.apk e SHA256SUMS na pasta de saída;
#   8. com --publicar, a release como RASCUNHO no GitHub (gh release create --draft --verify-tag), com o APK e o SHA256SUMS;
#   9. o resumo e o que fica com o mantenedor.
# Não assina por conta própria (a chave vem do build.gradle.kts, pelas variáveis acima), não cria tag, não faz push e não publica:
# o rascunho só vira release quando o mantenedor clica em "Publish".
# Com --ensaio (ou --dry-run) percorre o mesmo caminho sem efeito algum: as conferências e a compilação rodam de verdade e o que mudaria o
# repositório, a pasta de saída ou o GitHub só é impresso, como "[ensaio] faria: <comando>" (ver abaixo).
#
# Uso:  scripts/release.sh <versão> [--saida PASTA] [--publicar --notas ARQUIVO] [--ensaio]
#       scripts/release.sh <versão> --so-conferir --apk CAMINHO
#   <versão>        três números, por exemplo 0.3.1 (sem o "v").
#   --saida PASTA   onde guardar o APK e o SHA256SUMS (padrão: build/release-<versão>/, na raiz do repositório; fora de app/build).
#   --publicar      cria o rascunho no GitHub; exige --notas. Sem ele, só imprime o comando que rodaria.
#   --notas ARQUIVO o texto da release (o que a seção do CHANGELOG diz, para quem não é do projeto).
#   --so-conferir   só os passos 1 a 3, 5 e 6, sobre um APK já montado (--apk CAMINHO): não compila, não copia, não publica. Serve para teste.
#   --ensaio        (sinônimo: --dry-run) percorre todos os passos sem publicar nem gravar nada fora de app/build: as conferências 1 a 3 rodam, mas
#                   o que achariam vira "pendência" (a lista sai no fim e o código de saída é 1) em vez de parar o script; a compilação roda,
#                   mas com a chave de DEPURAÇÃO (-PreauDebugSigned=true, as REAU_* tiradas do ambiente do Gradle): a chave de release nunca é
#                   usada, e por isso o certificado do APK do ensaio não é o do projeto (o script diz o que a publicação de verdade exigiria);
#                   apksigner e apk_audit.py rodam; o passo 7 (copiar, somar) e o 8 (gh release create) só são impressos como "[ensaio] faria: ...".
#                   Vale com ou sem --publicar (com ele, exige --notas como sempre); não combina com --so-conferir.
# Variáveis de ambiente (para teste): GRADLEW (padrão: ./gradlew), APK_AUDIT (padrão: scripts/apk_audit.py), PYTHON (padrão: python3),
# GIT e GH (os executáveis de git e gh), ANDROID_HOME / ANDROID_SDK_ROOT (onde está o build-tools/*/apksigner; senão ~/Android/Sdk).
# Sai com 0 se tudo passou; 1 se uma conferência falhou ou um passo deu erro (no ensaio: se sobrou alguma pendência); 2 se o uso estiver errado.
set -euo pipefail

CERT_OFICIAL="63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
GIT="${GIT:-git}"
GH="${GH:-gh}"
PYTHON="${PYTHON:-python3}"
GRADLEW="${GRADLEW:-$RAIZ/gradlew}"
APK_AUDIT="${APK_AUDIT:-$RAIZ/scripts/apk_audit.py}"

uso() { sed -n '/^# Uso:/,/^# Variáveis/p' "$0" | sed '$d;s/^# \{0,1\}//' >&2; }
passo() { echo "== $1"; }

ENSAIO=0
DESCRICAO=""     # set only on the call to fazer that needs it; never taken from the caller's environment
PENDENCIAS=()   # no ensaio: o que a publicação de verdade não deixaria passar
FARIA=()        # no ensaio: o que a publicação de verdade faria e o ensaio só imprimiu
falha() {
  echo "ERRO: $1" >&2
  if [ "$ENSAIO" -eq 1 ]; then echo "Ensaio interrompido: nada foi publicado." >&2; fi
  exit 1
}
# Uma conferência que não passou: a publicação de verdade para aqui; o ensaio anota e segue, para mostrar tudo o que falta de uma vez.
pendencia() {
  if [ "$ENSAIO" -eq 0 ]; then falha "$1"; fi
  echo "PENDÊNCIA (a publicação de verdade pararia aqui): $1" >&2
  PENDENCIAS+=("$1")
}
# Todo comando que muda o repositório, o GitHub ou arquivos fora de app/build passa por aqui: roda, ou (no ensaio) só é impresso e anotado.
fazer() {
  if [ "$ENSAIO" -eq 0 ]; then "$@"; return; fi
  local linha
  linha="${DESCRICAO:-$(printf '%q ' "$@")}"   # DESCRICAO: para o que só cabe em "bash -c" e fica ilegível se impresso assim
  echo "[ensaio] faria: ${linha% }"
  FARIA+=("${linha% }")
}

VERSAO=""
SAIDA=""
NOTAS=""
APK=""
PUBLICAR=0
SO_CONFERIR=0
while [ $# -gt 0 ]; do
  case "$1" in
    --saida) [ $# -ge 2 ] || { echo "ERRO: --saida pede uma pasta" >&2; exit 2; }; SAIDA="$2"; shift 2 ;;
    --notas) [ $# -ge 2 ] || { echo "ERRO: --notas pede um arquivo" >&2; exit 2; }; NOTAS="$2"; shift 2 ;;
    --apk) [ $# -ge 2 ] || { echo "ERRO: --apk pede um caminho" >&2; exit 2; }; APK="$2"; shift 2 ;;
    --publicar) PUBLICAR=1; shift ;;
    --so-conferir) SO_CONFERIR=1; shift ;;
    --ensaio | --dry-run) ENSAIO=1; shift ;;
    -h | --help) uso; exit 0 ;;
    -*) echo "ERRO: opção desconhecida: $1" >&2; uso; exit 2 ;;
    *)
      [ -z "$VERSAO" ] || { echo "ERRO: mais de uma versão: $VERSAO e $1" >&2; exit 2; }
      VERSAO="$1"; shift ;;
  esac
done

if [ -z "$VERSAO" ]; then echo "ERRO: faltou a versão (por exemplo 0.3.1)" >&2; uso; exit 2; fi
if ! [[ "$VERSAO" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERRO: a versão são três números separados por ponto, sem o \"v\" (por exemplo 0.3.1); veio: $VERSAO" >&2; exit 2
fi
if [ "$SO_CONFERIR" -eq 1 ]; then
  [ "$ENSAIO" -eq 0 ] || { echo "ERRO: --ensaio não combina com --so-conferir (que já não compila, não copia nem publica)" >&2; exit 2; }
  [ -n "$APK" ] || { echo "ERRO: --so-conferir pede --apk CAMINHO (o APK já montado)" >&2; exit 2; }
  [ "$PUBLICAR" -eq 0 ] || { echo "ERRO: --so-conferir não publica; tire o --publicar" >&2; exit 2; }
elif [ -n "$APK" ]; then
  echo "ERRO: --apk só vale com --so-conferir (a release compila o seu próprio APK)" >&2; exit 2
fi
if [ "$PUBLICAR" -eq 1 ]; then
  [ -n "$NOTAS" ] || { echo "ERRO: --publicar pede --notas ARQUIVO (o texto da release)" >&2; exit 2; }
  [ -s "$NOTAS" ] || { echo "ERRO: o arquivo de notas não existe ou está vazio: $NOTAS" >&2; exit 2; }
fi
TAG="v$VERSAO"
if [ "$ENSAIO" -eq 1 ]; then echo "ENSAIO: nada será publicado, copiado nem gravado fora de app/build; a chave de release não será usada."; fi

# --- 1. árvore limpa e tag no HEAD ------------------------------------------------------------------------------------------------
passo "1. árvore limpa e tag $TAG no HEAD"
cd "$RAIZ"
SUJEIRA="$("$GIT" status --porcelain)"
if [ -n "$SUJEIRA" ]; then
  echo "$SUJEIRA" | head -20 >&2
  pendencia "a árvore de trabalho não está limpa (git status --porcelain acima): faça o commit ou desfaça antes; o código do APK é o código da tag"
fi
COMMIT="$("$GIT" rev-parse HEAD)"
if ! TAG_COMMIT="$("$GIT" rev-parse -q --verify "refs/tags/$TAG^{commit}")"; then
  pendencia "a tag $TAG não existe. Crie no commit que vai virar a release e rode de novo:  git tag -a $TAG -m \"REau $VERSAO\""
elif [ "$TAG_COMMIT" != "$COMMIT" ]; then
  pendencia "a tag $TAG aponta para ${TAG_COMMIT:0:12}, mas o HEAD é ${COMMIT:0:12}: faça checkout da tag ($GIT checkout $TAG) ou crie a tag no commit certo; o código da tag é o código do APK"
else
  echo "ok: árvore limpa; $TAG = ${COMMIT:0:12}"
fi

# --- 2. versionName e CHANGELOGs ------------------------------------------------------------------------------------------------
passo "2. versionName e CHANGELOGs"
ANTES="${#PENDENCIAS[@]}"
VERSION_NAME="$(sed -n 's/^val appVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [ -z "$VERSION_NAME" ]; then
  pendencia "não achei 'val appVersionName = \"...\"' em app/build.gradle.kts"
elif [ "$VERSION_NAME" != "$VERSAO" ]; then
  pendencia "o versionName do build é $VERSION_NAME, não $VERSAO (app/build.gradle.kts: appVersionName e releaseVersionCode); ajuste, faça o commit e mova a tag"
fi
SECAO="^## \\[${VERSAO//./\\.}\\] - [0-9]{4}-[0-9]{2}-[0-9]{2}\$"
for changelog in CHANGELOG.md CHANGELOG.pt-br.md CHANGELOG.es.md; do
  if [ ! -f "$changelog" ] || ! grep -Eq "$SECAO" "$changelog"; then
    pendencia "$changelog não tem a seção \"## [$VERSAO] - AAAA-MM-DD\""
  fi
done
if [ "${#PENDENCIAS[@]}" -eq "$ANTES" ]; then echo "ok: versionName $VERSION_NAME; seção $VERSAO nos três CHANGELOGs"; fi

# --- 3. a chave (só os nomes, nunca os valores) ----------------------------------------------------------------------------------
passo "3. variáveis da chave de assinatura"
FALTAM=""
for nome in REAU_KEYSTORE REAU_KEY_ALIAS REAU_KEYSTORE_PASSWORD REAU_KEY_PASSWORD; do
  if [ -z "${!nome:-}" ]; then FALTAM="$FALTAM $nome"; fi
done
if [ -n "$FALTAM" ]; then
  pendencia "variáveis da chave sem valor:$FALTAM (o build.gradle.kts explica, no trecho \"Release signing\")"
elif [[ "$REAU_KEYSTORE" != /* ]]; then
  pendencia "REAU_KEYSTORE precisa ser o caminho ABSOLUTO do arquivo da chave (um caminho relativo não é achado pelo Gradle)"
elif [ ! -f "$REAU_KEYSTORE" ]; then
  pendencia "REAU_KEYSTORE não é um arquivo"
else
  echo "ok: as quatro variáveis estão definidas e REAU_KEYSTORE é um arquivo"
fi

# --- 4. compilar ------------------------------------------------------------------------------------------------------------------
if [ "$SO_CONFERIR" -eq 1 ]; then
  passo "4. compilar: pulado (--so-conferir)"
  [ -f "$APK" ] || falha "o APK não existe: $APK"
else
  passo "4. :app:assembleRelease"
  APK="$RAIZ/app/build/outputs/apk/release/app-release.apk"
  rm -f "$APK"   # nunca copiar um APK velho de uma compilação que falhou
  if [ "$ENSAIO" -eq 1 ]; then
    # Só grava em app/build. Sem a chave de release: o build.gradle.kts cai na chave de depuração com -PreauDebugSigned=true, e as REAU_*
    # saem do ambiente do Gradle para que nem por engano a chave real assine o APK do ensaio.
    FARIA+=("$GRADLEW :app:assembleRelease  (assinado com a chave de release; no ensaio foi compilado com -PreauDebugSigned=true)")
    echo "[ensaio] compilando com a chave de depuração (-PreauDebugSigned=true), sem as variáveis REAU_*"
    env -u REAU_KEYSTORE -u REAU_KEY_ALIAS -u REAU_KEYSTORE_PASSWORD -u REAU_KEY_PASSWORD \
      "$GRADLEW" -PreauDebugSigned=true :app:assembleRelease || falha "o Gradle falhou"
  else
    "$GRADLEW" :app:assembleRelease || falha "o Gradle falhou"
  fi
  [ -f "$APK" ] || falha "o Gradle terminou mas não gerou $APK"
fi

# --- 5. assinatura ----------------------------------------------------------------------------------------------------------------
passo "5. apksigner verify"
SDK=""
APKSIGNER=""
for candidato in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
  [ -n "$candidato" ] || continue
  # A versão de build-tools mais nova (ordem de versão, não de texto: "9.0.0" vem depois de "35.0.0" como texto).
  achado="$(find "$candidato/build-tools" -mindepth 2 -maxdepth 2 -name apksigner 2>/dev/null | awk -F/ '{print $(NF-1) "\t" $0}' | sort -V | tail -1 | cut -f2- || true)"
  if [ -n "$achado" ]; then SDK="$candidato"; APKSIGNER="$achado"; break; fi
done
[ -n "$APKSIGNER" ] || falha "não achei o apksigner: defina ANDROID_HOME (ou ANDROID_SDK_ROOT) para um SDK com build-tools"
export ANDROID_HOME="$SDK"   # o apk_audit.py procura o mesmo SDK
SAIDA_SIGNER="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || {
  echo "$SAIDA_SIGNER" | head -20 >&2
  falha "o apksigner recusou a assinatura do APK"
}
if ! echo "$SAIDA_SIGNER" | grep -Eq 'Verified using v2 scheme.*: true'; then
  echo "$SAIDA_SIGNER" | head -20 >&2
  falha "o apksigner não confirma a assinatura v2 (esperado: \"Verified using v2 scheme ... true\")"
fi
CERTS="$(echo "$SAIDA_SIGNER" | sed -n 's/.*certificate SHA-256 digest: *\([0-9A-Fa-f]\{64\}\).*/\1/p' | tr 'A-F' 'a-f')"
if [ -z "$CERTS" ]; then falha "o apksigner não mostrou o SHA-256 do certificado"; fi
if [ "$ENSAIO" -eq 1 ] && echo "$CERTS" | grep -qx "$CERT_OFICIAL"; then
  # O ensaio nunca deve ver a chave de release: se o certificado do projeto aparece aqui, ela chegou ao Gradle por outro caminho.
  falha "o APK do ensaio saiu assinado com o certificado do projeto ($CERT_OFICIAL): a chave de release chegou ao Gradle no ensaio; descubra por onde antes de publicar"
elif [ "$ENSAIO" -eq 1 ]; then
  echo "ok: assinatura v2 verificada. Certificado do APK do ensaio (chave de depuração): $(echo "$CERTS" | sort -u | paste -sd' ')"
  echo "[ensaio] a publicação de verdade exige o certificado do projeto: $CERT_OFICIAL (não conferível sem a chave de release)"
elif [ "$(echo "$CERTS" | sort -u)" != "$CERT_OFICIAL" ]; then
  falha "o certificado do APK não é o do projeto. Esperado $CERT_OFICIAL; no APK: $(echo "$CERTS" | sort -u | tr '\n' ' ')(assinado com outra chave: não publicar)"
else
  echo "ok: assinatura v2 verificada; certificado $CERT_OFICIAL"
fi

# --- 6. auditoria -----------------------------------------------------------------------------------------------------------------
passo "6. apk_audit.py"
"$PYTHON" -B "$APK_AUDIT" "$APK" || pendencia "a auditoria do APK acusou algo (saída acima): veja o que é e, se for esperado, aceite com 'python3 -B scripts/apk_audit.py <apk> --gravar' em outro commit, e refaça a tag"
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"

if [ "$SO_CONFERIR" -eq 1 ]; then
  echo
  echo "Conferido (sem compilar, copiar nem publicar): REau $VERSAO, commit $COMMIT, APK sha256 $APK_SHA, certificado $CERT_OFICIAL"
  exit 0
fi

# --- 7. copiar e somar ------------------------------------------------------------------------------------------------------------
passo "7. APK e SHA256SUMS"
SAIDA="${SAIDA:-$RAIZ/build/release-$VERSAO}"
case "$SAIDA" in /*) ;; *) SAIDA="$RAIZ/$SAIDA" ;; esac
fazer mkdir -p "$SAIDA"
if [ "$ENSAIO" -eq 0 ]; then SAIDA="$(cd "$SAIDA" && pwd)"; fi
fazer cp -f "$APK" "$SAIDA/REau-$VERSAO.apk"
# shellcheck disable=SC2016  # as aspas simples são de propósito: quem expande $1 e $2 é o bash -c
DESCRICAO="(cd $SAIDA && sha256sum REau-$VERSAO.apk > SHA256SUMS)" fazer bash -c 'cd "$1" && sha256sum "$2" > SHA256SUMS' _ "$SAIDA" "REau-$VERSAO.apk"
if [ "$ENSAIO" -eq 0 ]; then echo "ok: $SAIDA/REau-$VERSAO.apk e $SAIDA/SHA256SUMS"; fi

# --- 8. rascunho no GitHub ----------------------------------------------------------------------------------------------------
passo "8. rascunho da release"
COMANDO=("$GH" release create "$TAG" --draft --verify-tag --title "REau $VERSAO" --notes-file "${NOTAS:-NOTAS.md}" "$SAIDA/REau-$VERSAO.apk" "$SAIDA/SHA256SUMS")
if [ "$PUBLICAR" -eq 1 ]; then
  command -v "$GH" >/dev/null 2>&1 || pendencia "não achei o gh (GitHub CLI); instale e faça 'gh auth login'"
  fazer "${COMANDO[@]}" || falha "o gh não criou o rascunho"
  if [ "$ENSAIO" -eq 0 ]; then echo "ok: rascunho criado (ainda não publicado)"; fi
else
  echo "Sem --publicar: nada foi enviado. O comando que --publicar rodaria:"
  printf '  '; printf '%q ' "${COMANDO[@]}"; echo
fi

# --- 9. resumo --------------------------------------------------------------------------------------------------------------------
if [ "$ENSAIO" -eq 1 ]; then
  echo
  echo "== Resumo do ensaio"
  echo "  versão:       REau $VERSAO (tag $TAG), commit $COMMIT"
  echo "  APK do ensaio sha256: $APK_SHA (chave de depuração; não é o que seria publicado)"
  echo "  $APK é o APK do ensaio, assinado com a chave de depuração: não copie nem publique esse arquivo (a rodada de verdade o apaga e refaz)"
  echo "O que a publicação de verdade faria e o ensaio não fez:"
  for item in "${FARIA[@]}"; do echo "  - $item"; done
  if [ "${#PENDENCIAS[@]}" -gt 0 ]; then
    echo "Pendências (a publicação de verdade pararia nelas):"
    for item in "${PENDENCIAS[@]}"; do echo "  - $item"; done
  fi
  echo
  echo "Ensaio terminado: nada foi publicado."
  [ "${#PENDENCIAS[@]}" -eq 0 ] || exit 1
  exit 0
fi

cat <<EOF

== Resumo
  versão:       REau $VERSAO (tag $TAG)
  commit:       $COMMIT
  APK sha256:   $APK_SHA
  certificado:  $CERT_OFICIAL
  arquivos:     $SAIDA/REau-$VERSAO.apk
                $SAIDA/SHA256SUMS

Fica com o mantenedor:
  - revisar o rascunho no GitHub (título, texto, os dois arquivos) e publicar;
  - baixar o que foi publicado e conferir:  gh release download $TAG -D conferir && (cd conferir && sha256sum -c SHA256SUMS)
    e o certificado do APK baixado com apksigner verify --print-certs;
  - avançar a main até a tag ($GIT merge --ff-only $TAG, depois o push), se a tag estiver à frente dela.
EOF
