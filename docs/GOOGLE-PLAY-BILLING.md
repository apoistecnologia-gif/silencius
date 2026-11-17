# Silêncius Premium — implantação segura (R$ 14,90/mês, 3 dias gratuitos)

> **ESTÁGIO: DESENVOLVIMENTO. NÃO PUBLICAR EM PRODUÇÃO.**
> A branch `feat/play-billing-assinatura-20261117` não modifica a versão pública.
> O Google Play Billing 9.1.0 está instalado, mas as cobranças são bloqueadas por padrão.
> Não há GitHub Actions neste projeto.

## Produto cadastrado no Console

| Item | Valor |
|---|---|
| Aplicativo | `br.com.silencius.app` |
| Produto de assinatura | `silencius_premium` |
| Plano básico de renovação automática | `mensal` |
| Recorrência | 1 mês |
| Preço no Brasil | R$ 14,90 por mês |
| Oferta de teste gratuito | `teste-3-dias` |
| Fase de teste | 3 dias, preço R$ 0,00 |
| Elegibilidade | Novos assinantes (a Google Play decide elegibilidade) |

No Google Play Console: **Monetize com o Google Play → Produtos → Assinaturas**.
Criar `silencius_premium`; criar o plano `mensal` com cobrança automática mensal;
definir R$ 14,90 para o Brasil; ativar o plano. Depois adicionar oferta
`teste-3-dias`, benefício de **teste gratuito por 3 dias**, novos assinantes,
e ativar a oferta. Não alterar IDs depois de ativados.

**Nunca programar 72 horas gratuitas com o relógio do telefone.**
O período gratuito é contratado e controlado pela própria Google Play, com método
de pagamento e renovação informados ao usuário. O preço final é sempre consultado
pelo Billing, e a tela da Google Play confirma as condições antes da contratação.

## Servidor de verificação — infraestrutura criada na Vercel

- Projeto exclusivo: `silencius-billing`, equipe `mariajuvelina403-7231s-projects`.
- Projeto Vercel ID: `prj_qqFLRezQaFSDb7FqelwUO6PVTMlA`.
- Endereço reservado: `https://silencius-billing.vercel.app/api/verify-subscription`.
- Código de origem: `api/verify-subscription.js` desta branch.
- Primeiro build da Vercel: **READY**, porém proteção Vercel Authentication ainda ativa.
- `GOOGLE_PLAY_PACKAGE_NAME`: configurada como `br.com.silencius.app`.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: cadastrado no Vercel como **Secret** apenas para Production (confirmado por consulta a metadados, sem ler a chave).
- Nova implantação de produção da API em 17/11/2026: **READY**, ID `dpl_3UiH8P1Vhu1K4KgUp2zopZXGx7VZ`.
- **Atualização de 17/11/2026:** autenticação, validação e reconhecimento da assinatura foram confirmados com uma compra de teste; chamadas de voz/vídeo, cancelamento e expiração também foram testados. A versão pública permanece inalterada; renovações em segundo plano da próxima versão ainda precisam de homologação.
- Antes de habilitar o Android, restringir abuso de requests/reutilização de token e revisar política de autenticação do endpoint, depois remover proteção de acesso Vercel apenas da rota necessária para o app.

## Servidor de verificação (credencial Secret configurada)

O arquivo `api/verify-subscription.js` é uma função HTTP Node 20+ preparada
para Vercel. Não há credenciais nem chaves privadas no Git.

1. Escolher um projeto no Google Cloud e habilitar a **Google Play Developer API**.
   Conforme a documentação atual, não é necessário vincular a conta de desenvolvedor
   a um projeto Cloud por um fluxo de vínculo separado.
2. Criar uma **conta de serviço própria** em IAM > Contas de serviço. No Google Play
   Console > Usuários e permissões, convidar o e-mail dessa conta e conceder:
   - Visualizar dados financeiros, pedidos e respostas a pesquisas de cancelamento;
   - Gerenciar pedidos e assinaturas.
   Restringir o acesso ao aplicativo Silêncius sempre que a interface permitir.
3. Configurar no serviço hospedado, como variáveis privadas de ambiente:
   - `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: conteúdo completo do JSON da conta de serviço;
   - `GOOGLE_PLAY_PACKAGE_NAME=br.com.silencius.app`.
4. Publicar a API e testar seu endpoint HTTPS
   `https://<dominio-do-servidor>/api/verify-subscription`.
5. Não colocar o JSON em arquivo versionado, APK, GitHub, frontend nem texto de chat.
   Revogar qualquer chave que já tenha sido exposta.

O servidor recebe **somente o token técnico da compra**, consulta
`purchases.subscriptionsv2.get`, verifica produto, estado e vencimento, e
reconhece o primeiro pagamento legítimo via
`purchases.subscriptions.acknowledge`. Compras pendentes não dão acesso.

Para produção, endurecer a proteção contra abuso do endpoint (limitação de
requisições, associação do token a uma identidade/instalação quando aplicável,
monitoramento e tratamento de revogação/RTDN). Não disponibilizar acesso
indefinido com base em simples dados do aparelho.

## Verificações concluídas em 17/11/2026

- A conta de serviço na Vercel foi consultada apenas por código de servidor, sem exibir a chave.
- A Vercel obteve um token OAuth válido da conta de serviço.
- `monetization.subscriptions.get` do Google Play retornou o produto `silencius_premium` e o plano `mensal` em estado `ACTIVE`.
- O verificador HTTP respondeu `405` para um GET na rota de verificação (comportamento esperado).
- O endpoint diagnóstico temporário `/api/check-play-config` foi **retirado** do deploy e voltou a responder `404`.
- A API foi endurecida contra corpo excessivo, MIME incorreto, pacote divergente e token malformado.
- As 13 verificações de lógica executadas no contexto de revisão passaram; o teste Node em Codespaces ainda deve ser repetido após as mudanças.
- Deploy de produção do servidor de verificação atualizado com proteção inicial contra abuso: `dpl_De2QnwQZfua2dDBahd9ArGJQDRLF`, estado `READY`, URL `https://silencius-billing.vercel.app/api/verify-subscription`.
- O endpoint de produção HTTPS foi liberado somente no projeto isolado `silencius-billing`; as URLs de preview permanecem protegidas por Vercel Authentication. O servidor exige token de compra e verifica cada token no Google.
- Há defesa limitada por instância (20 verificações/min por token e 300/min por IP), sem consumo de serviço WAF pago novo; **não substitui** WAF distribuído, vínculo de token a uma conta nem monitoramento RTDN.
- Servidor ainda não validou token de compra real. Não declarar integração pronta até testes com testadores de licença, ack, cancelamento, revogação e renovação.
- O deploy da Vercel é atualmente independente do GitHub; alterações futuras na branch não entram automaticamente no serviço. Não criar GitHub Actions.

### Para testar a integração de verdade (pendente)

1. No Google Play Console, adicionar o Gmail do testador a **Teste de licença**, além do grupo de **Teste interno** do Silêncius.
2. Preparar teste de proteção do endpoint público (rate limit, associação de token e gestão de revogações) e somente então liberar comunicação do aplicativo.
3. Gerar AAB de **teste interno** com `SILENCIUS_INTERNAL_BILLING_TEST=1` e endpoint HTTPS revisado; usar código de versão ainda não empregado.
4. Instalar pelo link de Teste interno da Google Play, contratar com instrumento de teste e verificar o retorno de `purchases.subscriptionsv2.get` e reconhecimento da compra.
5. Testar 3 dias grátis elegíveis, oferta inelegível, renovação, cancelamento, on-hold e restauração; validar os serviços de bloqueio em aparelho real.
6. Apenas após homologação completa analisar migração da assinatura para produção. A versão pública continua intocada.

## Correção de reconhecimento da compra — 17/11/2026 (homologação 101)

Após a contratação de teste do Silêncius Premium, o Android exibiu "Falha ao consultar a assinatura" e o Google Play informou que o desenvolvedor não reconheceu a compra.

**Causa identificada no código:** o backend rejeitava indiscriminadamente respostas de `purchases.subscriptionsv2.get` por testar `sub.packageName !== packageName`. O recurso oficial `SubscriptionPurchaseV2` **não inclui** um campo `packageName` na resposta; portanto a comparação era sempre falsa e impedia `purchases.subscriptions.acknowledge`. A consulta v2 já é feita no caminho `/applications/br.com.silencius.app/...`, que vincula o token ao aplicativo. Também é validado o `productId` da linha da assinatura.

- Falha corrigida em `api/verify-subscription.js` e implantada no projeto isolado `silencius-billing`.
- Implantação Vercel `dpl_3SNXDw6hKryQNqon47s7Zh965UMQ` em estado `READY`; GET à API retorna 405 esperado (aceita POST).
- Teste simulado sem segredos, com resposta v2 sem `packageName`: resultado HTTP 200, `active=true`, `acknowledged=true` e requisição de reconhecimento executada.
- Foram adicionados códigos de diagnóstico seguros ao backend e Android; no código de desenvolvimento a próxima versão é **2.7.5 (102)**; código 101 já publicado no teste interno.
- A versão **101 instalada pode consultar o backend corrigido sem novo AAB**, pois o erro principal estava no servidor. Se a compra de teste tiver sido cancelada automaticamente após três minutos sem reconhecimento, será necessário outro teste; nunca utilizar cartão real.
- O teste simulado não comprova autenticação de uma nova compra real. Após teste no aparelho, verificar registros da Vercel sem tokens e confirmar status do Google Play.
- **Não enviar à produção** até testar renovação, cancelamento, restauração, avisos e proteções da API.

## Preparação da homologação com testador de licença (17/11/2026)

- O usuário confirmou que o Gmail foi adicionado à lista **Silêncius — Testadores de Licença** e também ao grupo de **Teste interno** na Play Console.
- O projeto dedicado `silencius-billing` está **READY** na Vercel, com `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` do tipo **Secret**.
- Foi alterada a proteção do projeto Vercel **somente para esta API**, de `all_except_custom_domains` para `preview`: o endpoint HTTPS de produção pode receber requisições do aplicativo Android; os previews permanecem protegidos por Vercel Authentication.
- Apenas o firewall padrão está ativo; **não habilitar produção do aplicativo** antes de implementar rate limiting apropriado, associar token de compra à identidade e monitorar abusos e revogações.
- A conta de serviço já consultou o catálogo Google Play com êxito, mas **nenhuma compra real foi validada**.
- O script `scripts/gerar-aab-homologacao.sh` valida os testes Node, confirma resposta pública da API sem passar dados de compra, sincroniza `www` com Android e gera o AAB assinado. Ao falhar a checagem HTTPS, não gera AAB.
- Com o Codespaces na branch `feat/play-billing-assinatura-20261117`, executar **um único comando**:

```bash
git pull --ff-only origin feat/play-billing-assinatura-20261117 && bash scripts/gerar-aab-homologacao.sh
```

- O AAB de homologação **a gerar** após novas correções é `SILENCIUS-2.7.5-code102.aab` (o código 101 já foi utilizado no Teste interno).
- **Somente** publicar em **Teste interno**, jamais na produção. O script não envia para loja automaticamente.
- Em compra com testador de licença, conferir no modal Google Play que aparece pagamento de teste e nenhum débito real. Um teste gratuito configurado como 3 dias poderá durar **cerca de 3 minutos** em homologação devido aos períodos acelerados do Google Play.
- Fazer teste prático de compra, reconhecimento, renovação, cancelamento e restauração. Não promover a produção antes da conclusão e mitigação da reutilização de token.

## Compilação em nuvem pelo GitHub Codespaces

Abrir um Codespace nesta branch, com JDK 21 e Android SDK API 36.
O script `scripts/gerar-aab-assinado.sh` **desativa a cobrança por padrão**.

Depois da implantação e validação do servidor HTTPS, para uma versão
**exclusivamente de teste interno**, configurar no mesmo terminal:

```bash
export SILENCIUS_INTERNAL_BILLING_TEST=1
export SILENCIUS_BILLING_VERIFY_URL="https://<dominio-do-servidor>/api/verify-subscription"
bash scripts/gerar-aab-assinado.sh
```

O script só ativa a compra se as duas variáveis estiverem preenchidas
corretamente. Use uma conta de **testador de licença** para evitar cobranças reais
em homologação. Nunca envie para produção sem verificar cancelamento, renovação,
estados de pagamento e permissões de proteção.

Sem essa configuração explícita, o aplicativo **não inicia cobrança e preserva
o comportamento dos serviços de bloqueio**.
Quando a cobrança estiver habilitada, somente uma assinatura validada permitirá
a proteção.

O AAB de teste deve manter o mesmo applicationId `br.com.silencius.app` e
usar versionCode maior que o publicado; versão de desenvolvimento atual: 102 (2.7.5).
Assinar com a **mesma chave de upload cadastrada na Google Play**, fornecida de
forma segura no Codespaces, sem versionar a chave e sem imprimir suas senhas.

### Testes obrigatórios antes de publicar

- Conta de teste da licença Google Play, instalada pelo track de teste interno.
- Mostrar oferta de **3 dias grátis somente** para conta elegível.
- Conta inelegível: mostrar valor mensal e não prometer teste gratuito.
- Compra `PURCHASED`: servidor valida, reconhece e libera o bloqueio.
- Compra `PENDING`: não liberar funcionalidades.
- Cancelamento durante teste: acesso até o fim do período, sem primeira cobrança.
- Expiração, revogação, suspensão e troca de conta Google Play: chamadas passam
  normalmente; o aplicativo não pode recusar chamadas sem direito de uso.
- Sem internet: respeitar o último vencimento confirmado, sem contar três dias no aparelho.
- Fluxo `Restaurar compra`, reabertura, atualização, reinício do celular e renovação.
- Quatro chamadas reais: telefone comum, WhatsApp voz/vídeo pessoal e Business,
  inclusive com tela apagada.

Testes Node locais **no ambiente em nuvem** (sem GitHub Actions):
`node --test tests/billing-verification.test.js`.

**Privacidade e Console:** antes de publicar, atualizar a política de privacidade
no URL público informado no Play Console, rever o formulário de Segurança dos
dados e revisar o texto da ficha do app para divulgar preço/assinatura.

## Dependências para dar por concluído

- Plano `mensal` e oferta `teste-3-dias` ativos no Play Console (confirmado em 17/11/2026);
- servidor HTTPS publicado e conta de serviço com autorização;
- assinatura do AAB no Codespaces;
- compilação Android validada, compras de teste homologadas;
- revisões de privacidade e publicação aprovadas pelo Google Play.

Não promover esta branch para `main` ou `produção` antes de cumprir tudo acima.
