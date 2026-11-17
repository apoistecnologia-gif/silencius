#!/usr/bin/env bash
# Assina o Silêncius 2.7.1 no Codespaces, sem GitHub Actions.
# Chave e senhas ficam fora do Git; as senhas não aparecem no terminal nem nos logs.
set -Eeuo pipefail
umask 077
set +x
cd "$(dirname "$0")/.."
ROOT="$PWD"
export JAVA_HOME="/usr/local/sdkman/candidates/java/21.0.13.0+2-ms"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export SILENCIUS_ENFORCE_BILLING=false
export SILENCIUS_BILLING_VERIFY_URL=""

echo "Silêncius — gerar AAB assinado para TESTE INTERNO."
echo "A cobrança continuará DESATIVADA até a homologação."
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "Java 21 não encontrado"; exit 1; }
[[ -f android/local.properties ]] || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties

private_dir="$HOME/.silencius-private"
mkdir -p "$private_dir"
chmod 700 "$private_dir"
stored="$private_dir/apois-release.jks"
if [[ ! -f "$stored" ]]; then
  for candidate in "$ROOT/apois-release.jks" "$ROOT/android/apois-release.jks" "$ROOT/android/app/apois-release.jks"; do
    if [[ -f "$candidate" ]]; then
      mv "$candidate" "$stored"
      chmod 600 "$stored"
      echo "Chave encontrada e movida para a pasta privada do Codespaces."
      break
    fi
  done
fi
if [[ ! -f "$stored" ]]; then
  echo
  echo "ARQUIVO NECESSÁRIO: apois-release.jks"
  echo "No Explorer do Codespaces, arraste o arquivo original do seu computador"
  echo "para a pasta android do projeto e execute este mesmo comando novamente."
  echo "Não crie uma nova chave e nunca faça commit do arquivo .jks."
  exit 2
fi
export APOIS_KEYSTORE="$stored"

alias_name=""
read -r -p "Alias da chave [apois]: " alias_name </dev/tty
export APOIS_KEY_ALIAS="${alias_name:-apois}"

pass=""
read -r -s -p "Senha do arquivo apois-release.jks: " pass </dev/tty
printf '\n'
[[ -n "$pass" ]] || { echo "Senha obrigatória; AAB não gerado."; exit 1; }
export APOIS_STORE_PASS="$pass"

keypass=""
read -r -s -p "Senha da chave (Enter se igual à senha acima): " keypass </dev/tty
printf '\n'
export APOIS_KEY_PASS="${keypass:-$pass}"
unset pass keypass
trap 'unset APOIS_KEYSTORE APOIS_STORE_PASS APOIS_KEY_ALIAS APOIS_KEY_PASS' EXIT

LOG_DIR="$HOME/silencius-build-logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/aab-$(date +%Y%m%d-%H%M%S).log"
echo "Compilando AAB assinado; saída detalhada: $LOG"
if (cd android && bash ./gradlew --no-daemon --max-workers=2 --console=plain :app:bundleRelease) >"$LOG" 2>&1; then
  bundle="$ROOT/android/app/build/outputs/bundle/release/app-release.aab"
  if [[ ! -s "$bundle" ]]; then
    echo "Gradle concluiu, mas não encontramos o AAB em $bundle"
    exit 1
  fi
  if "$JAVA_HOME/bin/jarsigner" -verify "$bundle" >> "$LOG" 2>&1; then
    echo
    echo "✅ AAB ASSINADO GERADO E ASSINATURA VERIFICADA."
    echo "Arquivo: $bundle"
    ls -lh "$bundle"
    echo "Use somente TESTE INTERNO da Google Play, não Produção."
  else
    echo "AAB gerado, mas a verificação da assinatura falhou."
    tail -n 35 "$LOG"
    exit 1
  fi
else
  echo "A compilação falhou. Últimas 45 linhas do log:"
  tail -n 45 "$LOG"
  echo "Log completo: $LOG"
  exit 1
fi
