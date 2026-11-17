#!/usr/bin/env bash
# Silêncius Premium — preparação e compilação em nuvem, sem GitHub Actions.
# NÃO publica no Google Play e NÃO habilita cobrança no app.
set -Eeuo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
LOG_DIR="$HOME/silencius-build-logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/build-$(date +%Y%m%d-%H%M%S).log"
: > "$LOG"

step() { printf '\n▶ %s\n' "$1"; }
fail() { printf '\n✗ %s\n' "$1"; tail -n 55 "$LOG" || true; printf '\nLog completo: %s\n' "$LOG"; exit 1; }
run() {
  local label="$1"
  shift
  step "$label"
  if "$@" >> "$LOG" 2>&1; then
    printf '✓ Concluído\n'
  else
    fail "$label falhou. Veja as últimas linhas acima."
  fi
}

echo "SILÊNCIUS PREMIUM — PREPARAÇÃO E BUILD (Codespaces)"
echo "Projeto: $ROOT"
echo "Log: $LOG"
echo "Nenhum dado será enviado ao Google Play automaticamente."

export JAVA_HOME="/usr/local/sdkman/candidates/java/21.0.13.0+2-ms"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# Modo de segurança: instalar/compilar sem ativar paywall/cobranças neste build.
export SILENCIUS_ENFORCE_BILLING=false
export SILENCIUS_BILLING_VERIFY_URL=""

[[ -x "$JAVA_HOME/bin/java" ]] || fail "Java 21 não encontrado em $JAVA_HOME"
[[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]] || fail "sdkmanager não encontrado em $ANDROID_HOME"
command -v node >/dev/null || fail "Node.js não está disponível neste Codespaces"
command -v npm >/dev/null || fail "npm não está disponível neste Codespaces"
[[ -f "$ROOT/android/gradlew" ]] || fail "Gradle Wrapper não encontrado"
[[ -f "$ROOT/package-lock.json" ]] || fail "package-lock.json não encontrado"

run "Verificar Java 21" java -version
run "Instalar Android SDK API 36, Build Tools 36 e Platform Tools" \
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"

# local.properties é específico deste Codespaces e não deve ser versionado.
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/android/local.properties"
run "Instalar dependências do projeto" npm ci --no-audit --no-fund
run "Executar testes de assinaturas" node --test tests/billing-verification.test.js
run "Sincronizar Capacitor com Android" npx --no-install cap sync android
run "Compilar APK de teste sem publicar" \
  bash -c 'cd android && bash ./gradlew --no-daemon --max-workers=2 --console=plain :app:assembleDebug'

echo
echo "✅ Compilação de teste concluída."
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

# Se o usuário disponibilizou a chave de upload como secrets, compilar AAB assinado.
if [[ -n "${APOIS_KEYSTORE:-}" && -n "${APOIS_STORE_PASS:-}" && \
      -n "${APOIS_KEY_ALIAS:-}" && -n "${APOIS_KEY_PASS:-}" ]]; then
  [[ -f "$APOIS_KEYSTORE" ]] || fail "APOIS_KEYSTORE configurado, mas arquivo não encontrado"
  run "Gerar AAB assinado para TESTE INTERNO" \
    bash -c 'cd android && bash ./gradlew --no-daemon --max-workers=2 --console=plain :app:bundleRelease'
  echo "AAB: $ROOT/android/app/build/outputs/bundle/release/app-release.aab"
  echo "⚠️ Não envie a produção: verificação de assinaturas ainda não foi homologada."
else
  echo
  echo "AAB assinado: não gerado. Falta configurar a chave de upload em secrets privados."
  echo "Não coloque arquivo .jks, alias ou senhas no repositório."
fi
echo "Log completo: $LOG"
