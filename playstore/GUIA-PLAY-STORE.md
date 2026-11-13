# Silêncius: publicar na Google Play

Versão 2.7 (versionCode 18), pacote `br.com.silencius.app`.

## Arquivos desta pasta

| Arquivo | Onde usar no Play Console |
|---|---|
| `SILENCIUS-2.7.aab` | Versões > Produção (ou Teste interno) > Criar versão > enviar o pacote |
| `icone-512.png` | Presença na loja > Ficha principal > Ícone do app |
| `banner-1024x500.png` | Ficha principal > Recurso gráfico |
| `captura-*.png` | Ficha principal > Capturas de tela do telefone (mínimo 2) |
| `politica-de-privacidade.html` | Já publicada em **https://apoistecnologia-gif.github.io/silencius-privacidade/**. Cole esse link em Conteúdo do app > Política de privacidade |

Código do projeto (privado): https://github.com/apoistecnologia-gif/silencius

## Passo a passo

1. Crie a conta de desenvolvedor em play.google.com/console. O Google cobra uma taxa única de US$ 25. A conta é **PJ (organização)**, então não precisa do teste fechado com 12 testadores por 14 dias. Essa exigência vale só para contas pessoais.
2. Criar app: nome **Silêncius**, idioma Português (Brasil), App, Gratuito ou Pago.
3. **Assinatura:** aceite a "Assinatura de apps do Google Play". O AAB já vem assinado com a sua chave de upload:
   - arquivo: `C:/Users/SAMSUNG/dev/apois-keystore/apois-release.jks`
   - alias: `apois`
   - **Guarde essa chave com cópia de segurança.** Sem ela você não consegue enviar atualizações.
4. Preencha **Conteúdo do app**, usando as respostas abaixo.
5. Preencha a **Ficha da loja** com os textos abaixo.
6. Envie o AAB em **Teste interno** primeiro, instale pelo link da Play e refaça o teste das 4 chamadas. Veja a seção "Criar versão de Teste interno" abaixo.
7. Depois envie para **Produção**. A análise de apps com Acessibilidade costuma levar de alguns dias a algumas semanas.

## Criar versão de Teste interno: o que preencher em cada campo

Caminho: **Testar e lançar > Testes > Teste interno**.

**A. Aba "Testadores" (faça antes de criar a versão)**
- Clique em **Criar lista de e-mails**. Nome: `Equipe APOIS`.
- Adicione os e-mails das contas Google que vão testar, incluindo a conta Google logada no seu Infinix.
- Salve e marque a lista.
- **E-mail ou URL de feedback:** o seu e-mail de contato.
- Salve.

**B. Aba "Versões" > Criar nova versão**
1. **Assinatura de apps do Google Play:** se aparecer, clique em **Continuar** para o Google gerenciar a chave do app. Sua `apois-release.jks` vira a chave de upload.
2. **Pacotes de apps:** clique em **Enviar** e escolha `SILENCIUS-2.7.aab`. Deve aparecer *versionCode 18 (2.7)*.
3. **Nome da versão:** deixe o que o Console preenche (`18 (2.7)`) ou escreva `2.7`. Só você vê esse nome.
4. **Notas da versão** (até 500 caracteres; o usuário vê). Cole entre as marcações de idioma:

```
<pt-BR>
Primeira versão do Silêncius.
• Recusa automática de chamadas de voz e vídeo do WhatsApp e do WhatsApp Business
• Recusa de ligações telefônicas comuns
• Modos: todas, só desconhecidos ou todas menos uma lista de exceções
• Funciona com a tela apagada
• Configuração guiada, passo a passo
</pt-BR>
```

5. Clique em **Próxima** e depois em **Salvar e publicar** (ou **Iniciar lançamento para o teste interno**).

**Avisos que podem aparecer na revisão da versão:**
- *"Este pacote não tem arquivo de desofuscação"*: é só um aviso, pode ignorar.
- *"O app usa a permissão SCHEDULE_EXACT_ALARM / Acessibilidade / serviço em primeiro plano"*: o Console manda preencher as declarações em **Conteúdo do app**. Use as respostas da seção "Conteúdo do app" abaixo.
- *"Erros: não é possível lançar porque…"*: quase sempre é algum item de **Conteúdo do app** ou da **Ficha da loja** ainda pendente. O painel lista qual é.

**C. Instalar no celular**
- Volte em **Teste interno > Testadores > Como os testadores participam do teste > Copiar link**.
- **No Infinix, desinstale antes o Silêncius instalado pelo cabo.** Ele tem outra assinatura, então a Play não consegue atualizar por cima.
- Abra o link no celular, toque em **Aceitar convite** e depois em **Baixar na Google Play**.
- O app pode levar alguns minutos para aparecer na Play depois de publicado.

**D. Testar**
Siga o assistente do app e faça as 4 chamadas com a tela apagada: voz e vídeo, no pessoal e no Business. Se der certo: **Teste interno > Promover versão > Produção**.

## Para cada nova versão

Notas da versão de exemplo para atualizações:
```
<pt-BR>
Melhorias de estabilidade e recusa mais rápida das chamadas.
</pt-BR>
```
Antes de gerar o novo AAB, aumente `versionCode` (17 → 18 → 19…) e `versionName` em `android/app/build.gradle`.

## Ficha da loja

**Nome (até 30 caracteres):** Silêncius: Bloqueio de Chamadas

**Descrição curta (até 80):**
Recusa automática de chamadas de voz e vídeo do WhatsApp e do telefone.

**Descrição completa:**
O Silêncius recusa sozinho as chamadas de voz e de vídeo que você não quer atender, para você trabalhar, dormir ou dirigir em paz.

O que ele faz:
• Recusa chamadas de voz e de vídeo do WhatsApp e do WhatsApp Business
• Recusa ligações telefônicas comuns
• Três modos: recusar todas, só de números fora da agenda, ou todas menos uma lista de exceções
• Mostra quem tentou ligar e guarda um histórico das chamadas recusadas
• Funciona com a tela apagada e reinicia sozinho quando o celular liga

Configuração guiada: na primeira vez, o app mostra um passo de cada vez e abre direto a tela certa de cada permissão.

Privacidade: tudo fica no seu celular. O Silêncius não envia seus contatos, chamadas ou notificações para nenhum servidor.

Uso da Acessibilidade: o Silêncius usa o serviço de Acessibilidade apenas para identificar a tela de chamada recebida do WhatsApp e tocar no botão "Recusar" por você, inclusive com a tela apagada. Ele não lê mensagens nem qualquer outro conteúdo, e não coleta nem compartilha dados.

**Categoria:** Comunicação.

## Conteúdo do app: respostas

### Política de privacidade
Cole o link onde publicar o `politica-de-privacidade.html`. Pode ser no site da APOIS, no GitHub Pages ou no Google Sites.

### Segurança dos dados
- O app coleta ou compartilha dados? **Não.** Tudo é processado no aparelho e nada é enviado.
- Os dados são criptografados em trânsito? Não se aplica.
- Pode solicitar exclusão? Os dados ficam só no aparelho e são apagados ao desinstalar.

### Declaração da API de Acessibilidade (obrigatória)
- **O app é uma ferramenta de acessibilidade?** **Não.** O manifesto já declara `isAccessibilityTool=false`.
- **Funcionalidade principal que usa a API:** recusar automaticamente chamadas recebidas do WhatsApp.
- **Justificativa (cole):**
  > O Silêncius é um bloqueador de chamadas. O WhatsApp não oferece nenhuma API pública para recusar chamadas, e no Android o sistema congela apps em segundo plano, então o app não recebe a notificação da chamada a tempo. O serviço de Acessibilidade é configurado só para os pacotes com.whatsapp e com.whatsapp.w4b. Ele identifica a tela de chamada recebida e aciona o botão "Recusar". Nenhum conteúdo da tela é armazenado, registrado ou transmitido. O usuário vê uma explicação clara antes de ser enviado à tela do sistema para ativar o serviço, e pode desativá-lo a qualquer momento.
- **Divulgação em destaque:** o passo 4 do assistente mostra o texto antes de abrir os ajustes.
- **Vídeo:** veja o roteiro abaixo. Envie pelo YouTube como "Não listado" e cole o link.

### Acesso a notificações (NotificationListenerService)
Usado para detectar a notificação de chamada do WhatsApp e acionar o botão "Recusar" da própria notificação. Não lê o conteúdo de outras notificações nem as guarda.

### Serviço em primeiro plano: tipo "specialUse" (obrigatório)
- **Tipo:** Uso especial.
- **Justificativa (cole):**
  > O serviço em primeiro plano mantém ativa a detecção de chamadas recebidas do WhatsApp para que elas possam ser recusadas no momento em que chegam. Ele precisa rodar continuamente enquanto a proteção está ligada, porque a chamada pode chegar a qualquer hora. Nenhum outro tipo de serviço em primeiro plano cobre esse caso. O usuário vê uma notificação permanente e pode desligar a proteção pelo app.
- Vídeo: o mesmo roteiro, mostrando a notificação "Proteção ativa".

### Alarmes exatos (SCHEDULE_EXACT_ALARM)
Se o Console perguntar, responda: o alarme reinicia o serviço de proteção caso o sistema o encerre. Se a permissão for negada, o app usa um alarme comum e continua funcionando.

### Papel de triagem de chamadas
O app usa o `CallScreeningService` (papel CALL_SCREENING) para recusar ligações telefônicas comuns. Não é um app de discador, e não pede permissões de SMS nem de registro de chamadas.

### Outras respostas
- Público-alvo: 18 anos ou mais.
- Anúncios: não.
- Acesso ao app: todas as funções ficam disponíveis sem login.
- Classificação de conteúdo: responda que não há conteúdo sensível, o que dá classificação Livre.
- App de notícias, saúde ou governo: não.

## Roteiro do vídeo para o Google (30 a 60 s, gravação de tela)

Grave com o gravador de tela do próprio celular.

1. Abra o Silêncius. Mostre a tela principal ("Proteção ativa").
2. Toque em **Configurar**, ou mostre o assistente de primeira instalação. Pare no passo **"Recusar com a tela apagada"** e deixe o texto visível por 3 segundos.
3. Toque em **Permitir**, mostre a tela do sistema com a chave do Silêncius, ligue e confirme.
4. Volte ao app. Mostre o ✓ "Acessibilidade (tela apagada)".
5. De outro celular, faça uma chamada de WhatsApp. Mostre a chamada chegando e sendo recusada sozinha.
6. Mostre o histórico com a chamada recusada e a notificação permanente "Proteção ativa".

## Antes de cada atualização
- Aumente `versionCode` e `versionName` em `callblocker/android/app/build.gradle`.
- Gere o AAB: `cd C:/Users/SAMSUNG/dev/callblocker/android && ./gradlew bundleRelease`. O arquivo sai em `app/build/outputs/bundle/release/app-release.aab`.

## Riscos (seja honesto com os clientes)
- O Google pode recusar o uso da Acessibilidade. Se recusar, responda à revisão com o vídeo e a justificativa. Se mesmo assim não aprovar, distribua o APK por fora.
- O app depende do botão "Recusar" do WhatsApp. Se o WhatsApp mudar a tela ou a notificação, será preciso uma atualização.
- Em celulares com economia de bateria agressiva (Infinix, Tecno, Xiaomi), a chamada pode aparecer por 1 a 2 segundos antes de ser recusada.
