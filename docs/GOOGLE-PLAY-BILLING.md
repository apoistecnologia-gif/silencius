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
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: **pendente**, incluir APENAS como segredo privado no painel Vercel.
- Sem credenciais Google, o verificador não confirma compras; nenhuma cobrança foi habilitada no Android.
- Antes de habilitar o Android, restringir abuso de requests/reutilização de token e revisar política de autenticação do endpoint, depois remover proteção de acesso Vercel apenas da rota necessária para o app.

## Servidor de verificação (credencial pendente)

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
usar versionCode maior que o publicado; versão de desenvolvimento atual: 101 (2.7.4).
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
