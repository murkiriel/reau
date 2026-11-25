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
#
# Uso:  scripts/release.sh <versão> [--saida PASTA] [--publicar --notas ARQUIVO]
#       scripts/release.sh <versão> --so-conferir --apk CAMINHO
#   <versão>        três números, por exemplo 0.3.1 (sem o "v").
#   --saida PASTA   onde guardar o APK e o SHA256SUMS (padrão: build/release-<versão>/, na raiz do repositório; fora de app/build).
#   --publicar      cria o rascunho no GitHub; exige --notas. Sem ele, só imprime o comando que rodaria.
#   --notas ARQUIVO o texto da release (o que a seção do CHANGELOG diz, para quem não é do projeto).
#   --so-conferir   só os passos 1 a 3, 5 e 6, sobre um APK já montado (--apk CAMINHO): não compila, não copia, não publica. Serve para teste.
# Variáveis de ambiente (para teste): GRADLEW (padrão: ./gradlew), APK_AUDIT (padrão: scripts/apk_audit.py), PYTHON (padrão: python3),
# GIT e GH (os executáveis de git e gh), ANDROID_HOME / ANDROID_SDK_ROOT (onde está o build-tools/*/apksigner; senão ~/Android/Sdk).
# Sai com 0 se tudo passou; 1 se uma conferência falhou ou um passo deu erro; 2 se o uso estiver errado.
set -euo pipefail

CERT_OFICIAL="63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
GIT="${GIT:-git}"
GH="${GH:-gh}"
PYTHON="${PYTHON:-python3}"
GRADLEW="${GRADLEW:-$RAIZ/gradlew}"
APK_AUDIT="${APK_AUDIT:-$RAIZ/scripts/apk_audit.py}"

uso() { sed -n '/^# Uso:/,/^# Variáveis/p' "$0" | sed '$d;s/^# \{0,1\}//' >&2; }
falha() { echo "ERRO: $1" >&2; exit 1; }
passo() { echo "== $1"; }

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

# --- 1. árvore limpa e tag no HEAD ------------------------------------------------------------------------------------------------
passo "1. árvore limpa e tag $TAG no HEAD"
cd "$RAIZ"
SUJEIRA="$("$GIT" status --porcelain)"
if [ -n "$SUJEIRA" ]; then
  echo "$SUJEIRA" | head -20 >&2
  falha "a árvore de trabalho não está limpa (git status --porcelain acima): faça o commit ou desfaça antes; o código do APK é o código da tag"
fi
if ! TAG_COMMIT="$("$GIT" rev-parse -q --verify "refs/tags/$TAG^{commit}")"; then
  falha "a tag $TAG não existe. Crie no commit que vai virar a release e rode de novo:  git tag -a $TAG -m \"REau $VERSAO\""
fi
COMMIT="$("$GIT" rev-parse HEAD)"
if [ "$TAG_COMMIT" != "$COMMIT" ]; then
  falha "a tag $TAG aponta para ${TAG_COMMIT:0:12}, mas o HEAD é ${COMMIT:0:12}: faça checkout da tag ($GIT checkout $TAG) ou crie a tag no commit certo; o código da tag é o código do APK"
fi
echo "ok: árvore limpa; $TAG = ${COMMIT:0:12}"

# --- 2. versionName e CHANGELOGs ------------------------------------------------------------------------------------------------
passo "2. versionName e CHANGELOGs"
VERSION_NAME="$(sed -n 's/^val appVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [ -z "$VERSION_NAME" ]; then falha "não achei 'val appVersionName = \"...\"' em app/build.gradle.kts"; fi
if [ "$VERSION_NAME" != "$VERSAO" ]; then
  falha "o versionName do build é $VERSION_NAME, não $VERSAO (app/build.gradle.kts: appVersionName e releaseVersionCode); ajuste, faça o commit e mova a tag"
fi
SECAO="^## \\[${VERSAO//./\\.}\\] - [0-9]{4}-[0-9]{2}-[0-9]{2}\$"
for changelog in CHANGELOG.md CHANGELOG.pt-br.md CHANGELOG.es.md; do
  if [ ! -f "$changelog" ] || ! grep -Eq "$SECAO" "$changelog"; then
    falha "$changelog não tem a seção \"## [$VERSAO] - AAAA-MM-DD\""
  fi
done
echo "ok: versionName $VERSION_NAME; seção $VERSAO nos três CHANGELOGs"

# --- 3. a chave (só os nomes, nunca os valores) ----------------------------------------------------------------------------------
passo "3. variáveis da chave de assinatura"
FALTAM=""
for nome in REAU_KEYSTORE REAU_KEY_ALIAS REAU_KEYSTORE_PASSWORD REAU_KEY_PASSWORD; do
  if [ -z "${!nome:-}" ]; then FALTAM="$FALTAM $nome"; fi
done
if [ -n "$FALTAM" ]; then falha "variáveis da chave sem valor:$FALTAM (o build.gradle.kts explica, no trecho \"Release signing\")"; fi
case "$REAU_KEYSTORE" in
  /*) ;;
  *) falha "REAU_KEYSTORE precisa ser o caminho ABSOLUTO do arquivo da chave (um caminho relativo não é achado pelo Gradle)" ;;
esac
[ -f "$REAU_KEYSTORE" ] || falha "REAU_KEYSTORE não é um arquivo"
echo "ok: as quatro variáveis estão definidas e REAU_KEYSTORE é um arquivo"

# --- 4. compilar ------------------------------------------------------------------------------------------------------------------
if [ "$SO_CONFERIR" -eq 1 ]; then
  passo "4. compilar: pulado (--so-conferir)"
  [ -f "$APK" ] || falha "o APK não existe: $APK"
else
  passo "4. :app:assembleRelease"
  APK="$RAIZ/app/build/outputs/apk/release/app-release.apk"
  rm -f "$APK"   # nunca copiar um APK velho de uma compilação que falhou
  "$GRADLEW" :app:assembleRelease || falha "o Gradle falhou"
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
if [ "$(echo "$CERTS" | sort -u)" != "$CERT_OFICIAL" ]; then
  falha "o certificado do APK não é o do projeto. Esperado $CERT_OFICIAL; no APK: $(echo "$CERTS" | sort -u | tr '\n' ' ')(assinado com outra chave: não publicar)"
fi
echo "ok: assinatura v2 verificada; certificado $CERT_OFICIAL"

# --- 6. auditoria -----------------------------------------------------------------------------------------------------------------
passo "6. apk_audit.py"
"$PYTHON" -B "$APK_AUDIT" "$APK" || falha "a auditoria do APK acusou algo (saída acima): veja o que é e, se for esperado, aceite com 'python3 -B scripts/apk_audit.py <apk> --gravar' em outro commit, e refaça a tag"
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"

if [ "$SO_CONFERIR" -eq 1 ]; then
  echo
  echo "Conferido (sem compilar, copiar nem publicar): REau $VERSAO, commit $COMMIT, APK sha256 $APK_SHA, certificado $CERT_OFICIAL"
  exit 0
fi

# --- 7. copiar e somar ------------------------------------------------------------------------------------------------------------
passo "7. APK e SHA256SUMS"
SAIDA="${SAIDA:-$RAIZ/build/release-$VERSAO}"
mkdir -p "$SAIDA"
SAIDA="$(cd "$SAIDA" && pwd)"
cp -f "$APK" "$SAIDA/REau-$VERSAO.apk"
(cd "$SAIDA" && sha256sum "REau-$VERSAO.apk" > SHA256SUMS)
echo "ok: $SAIDA/REau-$VERSAO.apk e $SAIDA/SHA256SUMS"

# --- 8. rascunho no GitHub ----------------------------------------------------------------------------------------------------
passo "8. rascunho da release"
COMANDO=("$GH" release create "$TAG" --draft --verify-tag --title "REau $VERSAO" --notes-file "${NOTAS:-NOTAS.md}" "$SAIDA/REau-$VERSAO.apk" "$SAIDA/SHA256SUMS")
if [ "$PUBLICAR" -eq 1 ]; then
  command -v "$GH" >/dev/null 2>&1 || falha "não achei o gh (GitHub CLI); instale e faça 'gh auth login'"
  "${COMANDO[@]}" || falha "o gh não criou o rascunho"
  echo "ok: rascunho criado (ainda não publicado)"
else
  echo "Sem --publicar: nada foi enviado. O comando que --publicar rodaria:"
  printf '  '; printf '%q ' "${COMANDO[@]}"; echo
fi

# --- 9. resumo --------------------------------------------------------------------------------------------------------------------
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
