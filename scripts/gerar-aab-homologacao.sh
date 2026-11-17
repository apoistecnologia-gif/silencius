#!/usr/bin/env bash
# Silêncius Premium — homologação controlada pelo Play Console e GitHub Codespaces.
# Não publica no Google Play; a assinatura só é habilitada NESTE pacote de teste.
set -Eeuo pipefail
set +x
cd "$(dirname "$0")/.."
ROOT="$PWD"
BRANCH="feat/play-billing-assinatura-20261117"
VERIFY_URL="https://silencius-billing.vercel.app/api/verify-subscription"

if [[ "$(git branch --show-current)" != "$BRANCH" ]]; then
  echo "ERRO: acesse a branch de homologação ($BRANCH) antes de compilar."
  exit 2
fi

if ! grep -Eq '^[[:space:]]*versionCode[[:space:]]+101([[:space:]]|$)' android/app/build.gradle ||
   ! grep -Eq '^[[:space:]]*versionName[[:space:]]+"2\.7\.4"' android/app/build.gradle; then
  echo "ERRO: versão de desenvolvimento inesperada. Não vou gerar um AAB com versão incorreta."
  exit 2
fi

if [[ ! -f playstore/icone-512.png || ! -f android/app/src/main/res/mipmap-mdpi/ic_launcher.png ||
      ! -f www/logo.png ]]; then
  echo "ERRO: novos ícones do Silêncius ausentes. Nenhum AAB foi gerado."
  exit 2
fi

command -v node >/dev/null || { echo "Node.js não encontrado."; exit 2; }
command -v curl >/dev/null || { echo "curl não encontrado."; exit 2; }
echo "1/4 — Executando testes da verificação Google Play..."
node --test tests/billing-verification.test.js

echo "2/4 — Verificando acesso público da API, sem enviar dados de compra..."
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
status="$(curl --silent --show-error --max-time 15 --output "$body_file" --write-out '%{http_code}' "$VERIFY_URL")" || {
  echo "ERRO: o Codespaces não conseguiu acessar o servidor HTTPS. Nenhum AAB foi gerado."
  exit 2
}
if [[ "$status" != "405" ]] || ! grep -Fq '"error":"Use POST"' "$body_file"; then
  echo "ERRO: servidor não está acessível ao Android sem login; resposta HTTP $status."
  echo "A proteção Vercel Authentication pode estar impedindo o acesso."
  echo "Não inclua tokens de bypass ou credenciais Vercel no aplicativo."
  echo "Nenhum AAB foi gerado. Envie apenas esta mensagem (sem dados privados) para continuarmos."
  exit 2
fi
echo "✓ API respondeu HTTP 405 para GET (a rota aceita apenas POST)."
echo "  Isso NÃO confirma ainda uma compra; a homologação na Google Play continua necessária."

echo "3/4 — Atualizando os recursos Android com os PNGs e a interface atual..."
if [[ ! -x node_modules/.bin/cap ]]; then
  echo "Dependências do Capacitor ausentes. Instalando a partir do package-lock..."
  npm ci --no-audit --no-fund
fi
./node_modules/.bin/cap copy android

echo "4/4 — Gerando AAB assinado, apenas para teste interno..."
export SILENCIUS_INTERNAL_BILLING_TEST=1
export SILENCIUS_BILLING_VERIFY_URL="$VERIFY_URL"
bash scripts/gerar-aab-assinado.sh
echo
echo "✅ ETAPAS DE HOMOLOGAÇÃO DO CODESPACES CONCLUÍDAS."
echo "O pacote NÃO foi enviado ao Google Play, e a versão pública não mudou."
echo "Antes de instalar, confirme que o Gmail usado é testador interno E testador de licença."
