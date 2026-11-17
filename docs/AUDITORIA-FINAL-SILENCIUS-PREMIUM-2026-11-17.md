# Auditoria de homologação — Silêncius Premium
**Data:** 17/11/2026  
**Branch de desenvolvimento:** `feat/play-billing-assinatura-20261117`  
**Pacote Android:** `br.com.silencius.app`  
**Versão testada no aparelho:** 2.7.4, `versionCode 101`  
**Próxima versão em código, ainda NÃO publicada:** 2.7.5, `versionCode 102`  
**Servidor isolado:** `silencius-billing` na Vercel.

## 1. Resultado real da homologação 101

Com uma conta configurada como testadora interna e testadora de licença:
- A assinatura `silencius_premium`, plano `mensal`, oferta `teste-3-dias` apareceu na Google Play.
- Oferta real: 3 dias gratuitos e R$ 14,90/mês. A Play Store acelerou o teste para aproximadamente 3 minutos e renovação em 5 minutos.
- Compra de teste confirmada com cartão de teste, sem cobrança real.
- Após a correção da comparação incorreta de `packageName` em `SubscriptionPurchaseV2`, o backend retornou sucesso e reconheceu a compra; registro de Vercel: `verification_result active acknowledged`.
- Proteção foi ativada, chamadas de voz e vídeo testadas foram bloqueadas.
- Cancelamento na Play Store foi confirmado e, após o término, o aplicativo desligou a proteção e as chamadas voltaram a chegar.
- Nova compra/renovação automática com app permanentemente em segundo plano, restauração pós-reinstalação, falha de cobrança e período de carência **não foram homologados**.

## 2. Código revisado

- `api/verify-subscription.js`: valida token com `purchases.subscriptionsv2.get` e reconhece compras elegíveis via `purchases.subscriptions.acknowledge`.
- `SilenciusBillingPlugin.kt`: consulta ofertas elegíveis, abre fluxo Play Billing, restaura compras da Play, valida no servidor, atualiza o direito local.
- `SubscriptionAccess.kt`: restringe a proteção à validade confirmada pelo backend; nunca concede 3 dias por temporizador local.
- `ScreeningService.kt`, `CallNotificationListener.kt`, `CallAccessibilityService.kt`, `GuardService.kt` e `WatchdogReceiver.kt`: respeitam o direito Premium em suas verificações.
- `www/app.js`: apresenta condições retornadas pela Play Store e impede ativar a proteção quando não há direito.
- `android/app/build.gradle`: `com.android.billingclient:billing:9.1.0`; código 102 preparado na branch de testes.

## 3. Correções aplicadas nesta rodada (somente branch/teste e servidor isolado)

1. **API:** limite defensivo de 20 consultas/minuto por hash de token e 300/minuto por IP, por instância serverless. Reduz abuso simples sem registrar tokens ou contratar um banco de dados; NÃO fornece rate limiting global distribuído.
2. **API:** continua bloqueando método incorreto, body excessivo, MIME inválido e tokens malformados; retorna erro de taxa 429 com Retry-After.
3. **Interface Android/web:** sincroniza assinatura ao retomar a Activity e periodicamente enquanto aberta, respeitando intervalo mínimo de um minuto.
4. **Android nativo:** criado `BillingRenewalSync.kt` para consultar Google Play em segundo plano em intervalos adaptativos (até 4 horas quando distante do vencimento; 1 minuto próximo da expiração), sempre validando remotamente e preservando acesso apenas até a expiração em caso de falha de rede.
5. **Android/vigia:** recovery temporário com alarme inexato após um vencimento não confirmado, por no máximo 24 horas, para recuperar eventual renovação processada sem o app aberto; não concede Premium sem servidor.
6. **Reinicialização:** `BootReceiver` solicita consulta segura ao Google Play e programa recuperação se a validade local acabou recentemente.

**Validação:** casos de limite e rejeição HTTP foram exercitados em revisão com funções mockadas. A Vercel implantou a API em `dpl_De2QnwQZfua2dDBahd9ArGJQDRLF`, estado READY. **O Kotlin da versão 102 ainda precisa compilar e ser instalado em aparelho antes de considerarmos as novas alterações homologadas.**

## 4. Pendências críticas antes de lançar em produção

- **Vínculo da compra:** o backend comprova que o token existe e está válido no Google, mas ainda não associa o token a uma identidade interna autenticada. Quem obtiver um token legítimo poderia tentar reutilizá-lo em outro cliente. Avaliar Play Integrity, vínculo de compra com conta autenticada, armazenamento e políticas de recuperação sem criar experiências invasivas.
- **Revogação e eventos:** implementar notificações de tempo real da Google Play (RTDN/Pub/Sub) e um armazenamento seguro do estado, com idempotência, revogações, reembolsos, `linkedPurchaseToken`, troca de plano e atualização do acesso. O polling do aparelho NÃO substitui uma arquitetura de servidor consistente para escala.
- **Proteção distribuída:** o limite atual é por instância; configurar rate limit global/WAF e orçamento/alertas antes do público, considerando eventuais custos.
- **Teste nativo:** compilar e homologar v2.7.5 no aparelho real. Verificar renovação em segundo plano, troca de conta Play, restauração após reinstalação, falha de rede, cancelamento antes de expirar, período de carência, on-hold, expiração, reinicialização do Android e novo bloqueio de voz/vídeo.
- **Risco operacional:** o vigia existente agenda alarmes frequentes para evitar hibernação do WhatsApp. Medir consumo de bateria, comportamento em Infinix e outros fabricantes e política Android/Google Play.
- **Políticas e publicação:** revisar informações de assinatura, declaração de AccessibilityService, privacidade, suporte, exclusão de dados e lançamento na Play Console.

## 5. Como validar a versão 102

No **Codespaces**, com a branch de testes ativa:

```bash
git pull --ff-only origin feat/play-billing-assinatura-20261117 && bash scripts/gerar-aab-homologacao.sh
```

O comando roda testes Node, confere acesso HTTPS, atualiza a interface Android e tenta compilar um AAB assinado. Se a compilação passar, usar apenas **Teste interno** da Google Play. O AAB esperado é `SILENCIUS-2.7.5-code102.aab`. Nenhuma publicação é automática.

### Decisão

**Status:** HOMOLOGAÇÃO FUNCIONAL DA VERSÃO 101 APROVADA; VERSÃO 102 EM PREPARAÇÃO.  
**GO-LIVE PÚBLICO:** **BLOQUEADO** até executar os testes pendentes e concluir as proteções críticas descritas acima.

Não utilizar GitHub Actions e não misturar este servidor com os projetos de mobilidade da APOIS.
