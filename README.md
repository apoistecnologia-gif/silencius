# APOIS — bloqueador de chamadas de voz e vídeo

App Android que recusa automaticamente chamadas de voz e de vídeo (WhatsApp e
qualquer outro app que use o sistema de chamadas do Android) antes de o celular tocar.

## Como funciona

O APOIS se registra como **serviço de triagem de chamadas** do Android
(`android.telecom.CallScreeningService`). O sistema entrega toda chamada recebida a ele
antes de tocar, e ele responde "recusar" (`setDisallowCall(true)` + `setRejectCall(true)`).
Chamadas de voz/vídeo do **WhatsApp pessoal** e do **WhatsApp Business** (e do Telegram,
Messenger etc.) chegam ao Android por `ConnectionService`, então passam por essa triagem
como qualquer outra chamada — é o único mecanismo que o Android oferece
para um app recusar chamadas de outro app.

Recursos:

- Modos: **todos**, **só desconhecidos** (quem está na agenda passa), **só a lista** (lista de exceções).
- Bloqueio de voz e de vídeo separados.
- Aviso flutuante ("Chamada de vídeo recusada — (11) 91234-5678") por 3 segundos.
- Notificação, contador e histórico de chamadas recusadas (guardados só no aparelho).
- Sem servidor, sem internet, sem analytics.

## Identidade

- Nome: **APOIS**
- Pacote: `br.com.apois.app`
- Ícone: logotipo "A" vermelho em 3D (pin), fundo branco, gerado em todas as densidades
  (48–192 px), versão redonda, foreground adaptativo e splash screen.

## Estrutura

```
www/                     interface (HTML/CSS/JS puro, pt-BR)
android/app/src/main/java/br/com/apois/app/
  ScreeningService.kt    triagem de chamadas (núcleo do bloqueio)
  BlockOverlayService.kt aviso flutuante
  SilencePlugin.kt       ponte Capacitor ↔ interface
  Prefs.kt               configurações e histórico
  MainActivity.java      atividade única (WebView)
```

## Compilar

Requisitos: JDK 21, Android SDK (compileSdk 36, build-tools 35+), Node 20+.

```bash
npm install
npx cap copy android
cd android
./gradlew assembleDebug          # APK em app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # APK release assinado (keystore em C:/Users/SAMSUNG/dev/apois-keystore)
```

Instalar no aparelho:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Primeiro uso

1. Abra o app e toque em **Desligado** (ou em "Triagem padrão").
2. O Android abre "Triagem de chamadas"; escolha **APOIS** → "Definir como padrão".
3. Aceite "sobrepor outros apps" (aviso flutuante), contatos (modo "só desconhecidos")
   e notificações.
4. Toque em **Desligado** de novo: fica **Ligado**.

Para testar de verdade, ligue de outro celular (ou pelo WhatsApp) para o aparelho: o
telefone não deve tocar e o histórico do APOIS deve registrar a chamada.

## Limites (honestos)

- Android exige que o app seja a **triagem de chamadas padrão** para recusar chamadas;
  isso é pedido na instalação e pode ser revertido nas configurações do sistema.
- MinSdk 29 (Android 10). Testado em API 36.
- Chamadas feitas pelo próprio WhatsApp *quando o WhatsApp usa a tela cheia interna*
  (alguns aparelhos com "chamadas em tela cheia" no WhatsApp) ainda passam pelo sistema
  de chamadas, então continuam bloqueadas; se o WhatsApp usar apenas notificação interna
  em alguma versão, o sistema não a entrega ao Android e o bloqueio não se aplica.
- Sem root não há como "cancelar" a chamada sem que ela apareça como recusada para quem
  ligou.
