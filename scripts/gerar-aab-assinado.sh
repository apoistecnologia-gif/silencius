#!/usr/bin/env bash
# Assina o Silêncius no Codespaces, sem GitHub Actions.
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
# Por padrão, a assinatura é desativada. Somente um pedido explícito de
# homologação, com servidor HTTPS validado, habilita o fluxo neste AAB de teste.
if [[ "${SILENCIUS_INTERNAL_BILLING_TEST:-0}" == "1" ]]; then
  if [[ ! "${SILENCIUS_BILLING_VERIFY_URL:-}" =~ ^https://[^[:space:]]+/api/verify-subscription$ ]]; then
    echo "ERRO: teste Billing exige URL HTTPS completa terminada em /api/verify-subscription"
    echo "Nada foi compilado."
    exit 2
  fi
  export SILENCIUS_ENFORCE_BILLING=true
  echo "Silêncius — BUILD DE HOMOLOGAÇÃO COM GOOGLE PLAY BILLING."
  echo "Use apenas a faixa TESTE INTERNO com contas testadoras de licença."
else
  export SILENCIUS_ENFORCE_BILLING=false
  export SILENCIUS_BILLING_VERIFY_URL=""
  echo "Silêncius — AAB para teste interno com cobrança DESATIVADA."
fi
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

pass=""
read -r -s -p "Senha do arquivo apois-release.jks: " pass </dev/tty
printf '\n'
[[ -n "$pass" ]] || { echo "Senha obrigatória; AAB não gerado."; exit 1; }
export APOIS_STORE_PASS="$pass"
unset pass
trap 'unset APOIS_KEYSTORE APOIS_STORE_PASS APOIS_KEY_ALIAS APOIS_KEY_PASS' EXIT

# Conferir primeiro a senha e descobrir os aliases reais sem expor a senha no comando.
# Apenas entradas PrivateKeyEntry podem assinar o AAB.
alias_output=""
if ! alias_output=$(LC_ALL=C "$JAVA_HOME/bin/keytool" -list -v \
  -keystore "$stored" -storepass:env APOIS_STORE_PASS -J-Duser.language=en 2>&1); then
  echo "Não foi possível abrir o JKS. Confira a senha e o arquivo original."
  exit 1
fi
mapfile -t key_aliases < <(printf '%s\n' "$alias_output" | awk '
  /^Alias name: / { name = substr($0, 13) }
  /^Entry type: PrivateKeyEntry/ { if (name != "") print name }
')
unset alias_output
if (( ${#key_aliases[@]} == 0 )); then
  echo "Nenhuma entrada PrivateKeyEntry encontrada neste JKS. Não é possível assinar."
  exit 1
elif (( ${#key_aliases[@]} == 1 )); then
  export APOIS_KEY_ALIAS="${key_aliases[0]}"
  echo "Alias de assinatura detectado automaticamente: $APOIS_KEY_ALIAS"
else
  echo "Aliases de assinatura encontrados:"
  printf ' - %s\n' "${key_aliases[@]}"
  read -r -p "Digite um dos aliases acima: " APOIS_KEY_ALIAS </dev/tty
  if [[ ! " ${key_aliases[*]} " =~ [[:space:]]${APOIS_KEY_ALIAS}[[:space:]] ]]; then
    echo "Alias não encontrado neste JKS. Compilação não iniciada."
    exit 1
  fi
  export APOIS_KEY_ALIAS
fi

keypass=""
read -r -s -p "Senha da chave (Enter se igual à senha do arquivo): " keypass </dev/tty
printf '\n'
export APOIS_KEY_PASS="${keypass:-$APOIS_STORE_PASS}"
unset keypass

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
    version_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$ROOT/android/app/build.gradle" | head -n 1)
    version_name=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*/\1/p' "$ROOT/android/app/build.gradle" | head -n 1)
    [[ "$version_code" =~ ^[0-9]+$ && -n "$version_name" ]] || {
      echo "Não foi possível identificar a versão; AAB permanece em $bundle"
      exit 1
    }
    named_bundle="$ROOT/SILENCIUS-${version_name}-code${version_code}.aab"
    cp "$bundle" "$named_bundle"
    chmod 600 "$named_bundle"
    echo "Arquivo para baixar e enviar no teste interno: $named_bundle"
    ls -lh "$named_bundle"
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
