# Novo ícone do Silêncius — atualização de novembro/2026

O novo ícone foi aprovado para substituir o antigo monograma A da APOIS, **sem texto dentro do ícone**.

**Padrão:** fundo preto e vermelho, escudo prateado, telefone bloqueado e símbolo de chamadas do WhatsApp bloqueadas. O nome **Silêncius** permanece no título do aplicativo, não na arte do ícone.

## Adoção no GitHub Codespaces

1. Baixar do ChatGPT o pacote `SILENCIUS-ICONES-ANDROID-PLAY.zip` aprovado e arrastá-lo para a pasta principal do projeto `silencius` no Codespaces. Não extrair manualmente.
2. Na branch `feat/play-billing-assinatura-20261117`, executar:

```bash
git pull --ff-only origin feat/play-billing-assinatura-20261117 && bash scripts/aplicar-icone-silencius.sh
```

O script **valida SHA-256 e dimensões** dos 17 PNGs, substitui somente ícones Android, ícone de Play Store e logotipo do cabeçalho web, cria commit e envia à branch de desenvolvimento. Ele não altera arquivos de bloqueio, não faz deploy e não publica na loja.

## Recursos

- Ícone Play Store: `playstore/icone-512.png` — 512×512, PNG 32-bit.
- Ícones Android legacy, round e adaptive: `android/app/src/main/res/mipmap-*/ic_launcher*.png`.
- Logotipo interno: `www/logo.png`.
- Background de launcher e cabeçalho web: preto, definido no código.
- Prévia externa: `silencius_previa_icones_android.png`.

A geração do próximo AAB assinado deve ocorrer **somente após** o pacote ter sido aplicado e os demais testes da assinatura estarem concluídos. A versão de trabalho seguinte é `versionCode 101` (`2.7.4`) — conferir se o código ainda está livre no Play Console.

No Google Play Console, atualizar separadamente o ícone da ficha do app usando `playstore/icone-512.png`. Essa alteração no Console é independente do AAB, embora deva utilizar a mesma arte.

**Risco de marca:** o ícone representa bloqueio de chamadas do WhatsApp, mas o Silêncius não é um produto oficial do WhatsApp. A ficha deve evitar qualquer sugestão de parceria ou afiliação.

A versão 100 (2.7.3) permanece publicada no canal de teste interno até nova atualização. A versão de produção não deve ser alterada automaticamente.
