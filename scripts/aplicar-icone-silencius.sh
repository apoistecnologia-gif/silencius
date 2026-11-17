#!/usr/bin/env bash
# Instala o pacote de ícones aprovado do Silêncius no Codespaces e envia à branch de teste.
# Não gera AAB, não publica no Google Play e não altera bloqueios ou assinaturas.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
BRANCH="feat/play-billing-assinatura-20261117"
ARCHIVE=""
for file in "$ROOT/SILENCIUS-ICONES-ANDROID-PLAY.zip" "$ROOT/android/SILENCIUS-ICONES-ANDROID-PLAY.zip" "$ROOT/playstore/SILENCIUS-ICONES-ANDROID-PLAY.zip"; do
  if [[ -f "$file" ]]; then ARCHIVE="$file"; break; fi
done
if [[ -z "$ARCHIVE" ]]; then
  echo "Não encontrei SILENCIUS-ICONES-ANDROID-PLAY.zip."
  echo "Arraste o ZIP para a pasta principal silencius no Codespaces e execute novamente."
  exit 2
fi
if [[ "$(git branch --show-current)" != "$BRANCH" ]]; then
  echo "Branch incorreta. Abra $BRANCH antes de aplicar os ícones."
  exit 2
fi
command -v python3 >/dev/null || { echo "Python 3 não encontrado."; exit 2; }
mapfile -t asset_paths < <(python3 - "$ARCHIVE" <<'PY'
import json,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    manifest=json.loads(z.read('manifest.json'))
    for path in sorted(manifest['files']): print(path)
PY
)
if (( ${#asset_paths[@]} != 17 )); then echo "Pacote incompleto."; exit 2; fi
if [[ -n "$(git status --porcelain -- "${asset_paths[@]}")" ]]; then
  echo "Há ícones modificados localmente; nada foi sobrescrito."
  git status --short -- "${asset_paths[@]}"
  exit 2
fi
python3 - "$ARCHIVE" "$ROOT" <<'PY'
from pathlib import Path
import hashlib,json,os,struct,sys,tempfile,zipfile
zpath,root=sys.argv[1],Path(sys.argv[2])
expected={
  f'android/app/src/main/res/mipmap-{density}/{name}.png'
  for density in ('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi')
  for name in ('ic_launcher','ic_launcher_round','ic_launcher_foreground')
} | {'playstore/icone-512.png','www/logo.png'}
with zipfile.ZipFile(zpath) as z:
    manifest=json.loads(z.read('manifest.json'))
    files=manifest.get('files',{})
    if manifest.get('pack')!='silencius-approved-icon-v1' or set(files)!=expected:
        raise SystemExit('Pacote de ícones inválido ou incompleto. Nada foi aplicado.')
    checked=[]
    for path in sorted(expected):
        content=z.read(path)
        if len(content)>8*1024*1024 or hashlib.sha256(content).hexdigest()!=files[path]:
            raise SystemExit('Falha de integridade: '+path)
        if content[:8]!=b'\x89PNG\r\n\x1a\n':
            raise SystemExit('Arquivo não é PNG: '+path)
        width,height=struct.unpack('>II',content[16:24])
        if width!=height or not (48<=width<=1024):
            raise SystemExit('Dimensão de PNG inválida: '+path)
        checked.append((path,content))
    for path,content in checked:
        dest=root/path
        dest.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=str(dest.parent),delete=False) as temp:
            temp.write(content)
            temp_name=temp.name
        os.replace(temp_name,dest)
        print('✓',path)
print('Imagens validadas e aplicadas:',len(checked))
PY
# Só os ícones entram no commit; nunca a chave .jks ou o ZIP.
git add -- "${asset_paths[@]}"
if git diff --cached --quiet -- "${asset_paths[@]}"; then
  echo "Os ícones já estavam atualizados."
else
  git -c commit.gpgsign=false commit --only -m "style(silencius): atualizar ícones Android, Play Store e interface" -- "${asset_paths[@]}"
  echo "✓ Commit de recursos visuais criado."
fi
if git push origin "HEAD:$BRANCH"; then
  echo "✓ Recursos visuais enviados ao GitHub."
else
  echo "O commit está no Codespaces; falhou apenas o envio ao GitHub."
  exit 1
fi
rm -f -- "$ARCHIVE"
echo "✅ Ícones atualizados na branch de testes. A versão pública NÃO foi alterada."
