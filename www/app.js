/* Silêncius — interface.
   Toda a lógica de bloqueio fica no código nativo (Kotlin). Aqui só conversamos
   com o plugin "Silence". Quando a página é aberta num navegador comum, a
   interface funciona em modo demonstração, com dados de exemplo. */

const Native = (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Silence) || null;
const isNative = !!(Native && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());

const $ = (s) => document.querySelector(s);

const MODES = {
  all: "Toda chamada de voz ou vídeo é recusada, de qualquer contato.",
  unknown: "Chamadas de contatos salvos na agenda passam; as demais são recusadas.",
  allowlist: "Só as chamadas dos números da lista abaixo passam; todas as outras são recusadas."
};

const state = {
  enabled: false,
  mode: "all",
  blockVoice: true,
  blockVideo: true,
  showOverlay: true,
  waBlock: true,
  waRecuse: true,
  allowlist: [],
  blockedCount: 0,
  log: [],
  permissions: { screening: false, overlay: false, notifications: false, listener: false, accessibility: false },
  demo: false
};

/* ---------------- utilidades ---------------- */

let toastTimer;
function toast(msg) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2600);
}

function fmtPhone(raw) {
  const digits = String(raw || "").replace(/\D/g, "");
  if (!digits) return "";
  const d = digits.length > 11 ? digits.slice(-11) : digits;
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return d;
}

function timeAgo(ts) {
  const diff = Math.max(0, Date.now() - ts);
  const min = Math.round(diff / 60000);
  if (min < 1) return "agora";
  if (min < 60) return `${min} min`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h} h`;
  return new Date(ts).toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit" });
}

/* ---------------- dados de demonstração ---------------- */

function demoState() {
  const now = Date.now();
  return {
    enabled: true,
    mode: "all",
    blockVoice: true,
    blockVideo: true,
    showOverlay: true,
    waBlock: true,
    waRecuse: true,
    allowlist: ["+55 11 99876-5432"],
    blockedCount: 7,
    log: [
      { id: 1, number: "+55 11 91234-5678", kind: "video", at: now - 1000 * 60 * 4 },
      { id: 2, number: "+55 21 98877-1122", kind: "voice", at: now - 1000 * 60 * 62 },
      { id: 3, number: "+55 11 91234-5678", kind: "voice", at: now - 1000 * 60 * 60 * 26 }
    ],
    permissions: { screening: true, overlay: true, notifications: true, listener: true, accessibility: true }
  };
}

/* ---------------- render ---------------- */

function render() {
  const p = state.permissions;
  const active = state.enabled && p.screening;

  $("#power").setAttribute("aria-pressed", String(active));
  $("#powerLabel").textContent = active ? "Ligado" : "Desligado";

  $("#statusTitle").textContent = active ? "Proteção ativa" : "Proteção desligada";
  $("#statusBody").textContent = active
    ? (state.mode === "allowlist"
        ? "Chamadas de quem não está na lista de exceções serão recusadas."
        : state.mode === "unknown"
          ? "Chamadas de números fora da agenda serão recusadas."
          : "Qualquer chamada de voz ou de vídeo será recusada antes de o celular tocar.")
    : "Ligue a proteção para que as chamadas de voz e de vídeo sejam recusadas automaticamente.";

  const count = state.blockedCount || 0;
  $("#blockedCount").textContent = count > 999 ? "999+" : String(count);
  const pct = Math.min(1, count / 20);
  $("#bar").style.strokeDashoffset = String(264 - 264 * pct);
  $("#bar").style.stroke = active ? "var(--accent)" : "#2b3441";

  // alerta: sem notificações/acessibilidade a recusa do WhatsApp falha com a tela apagada
  const alertMsg = !p.listener
    ? "As chamadas do WhatsApp não estão sendo recusadas. Falta o acesso a notificações."
    : !p.accessibility
      ? "Com a tela apagada, as chamadas do WhatsApp vão passar. Falta ligar a Acessibilidade."
      : "";
  $("#waAlert").hidden = state.demo || !alertMsg;
  $("#waAlertText").textContent = alertMsg;

  // permissões
  const perms = [
    { key: "screening", label: "Triagem de chamadas padrão", action: "Abrir ajustes" },
    { key: "overlay", label: "Aviso sobre outros apps", action: "Permitir" }
  ];
  $("#permList").innerHTML = perms.map((x) => `
    <li class="${p[x.key] ? "ok" : "no"}">
      <span class="ico">${p[x.key] ? "✓" : "!"}</span>
      <span>${x.label}</span>
      ${p[x.key] ? "" : `<button data-perm="${x.key}">${x.action}</button>`}
    </li>`).join("");

  // modo
  document.querySelectorAll(".seg").forEach((b) => {
    b.classList.toggle("active", b.dataset.mode === state.mode);
  });
  $("#modeHint").textContent = MODES[state.mode] || MODES.all;

  // toggles
  $("#blockVoice").checked = state.blockVoice;
  $("#blockVideo").checked = state.blockVideo;
  $("#showOverlay").checked = state.showOverlay;
  $("#waBlock").checked = state.waBlock;
  $("#waPerm").innerHTML = `
    <li class="${p.listener ? "ok" : "no"}">
      <span class="ico">${p.listener ? "✓" : "!"}</span>
      <span>Acesso a notificações</span>
      ${p.listener ? "" : `<button data-perm="listener">Ativar</button>`}
    </li>
    <li class="${p.accessibility ? "ok" : "no"}">
      <span class="ico">${p.accessibility ? "✓" : "!"}</span>
      <span>Acessibilidade (tela apagada)</span>
      ${p.accessibility ? "" : `<button data-perm="accessibility">Ativar</button>`}
    </li>`;

  // lista
  const chips = $("#allowList");
  chips.innerHTML = state.allowlist.length
    ? state.allowlist.map((n) => `<li>${fmtPhone(n)}<button data-remove="${n}" aria-label="Remover">×</button></li>`).join("")
    : "";
  chips.style.display = state.allowlist.length ? "flex" : "none";

  // histórico
  const log = state.log || [];
  $("#logEmpty").style.display = log.length ? "none" : "block";
  $("#log").innerHTML = log.map((e) => `
    <li>
      <span class="kind ${e.kind === "video" ? "video" : ""}">${e.kind === "video" ? "▣" : "☏"}</span>
      <span class="who">
        <strong>${fmtPhone(e.number) || "Número desconhecido"}</strong>
        <small>${e.kind === "video" ? "Chamada de vídeo" : "Chamada de voz"} · recusada</small>
      </span>
      <time>${timeAgo(e.at)}</time>
    </li>`).join("");
}

/* ---------------- comunicação com o nativo ---------------- */

async function load() {
  if (!isNative) {
    state.demo = true;
    Object.assign(state, demoState());
    render();
    return;
  }
  try {
    const s = await Native.getState();
    Object.assign(state, {
      enabled: s.enabled,
      mode: s.mode,
      blockVoice: s.blockVoice,
      blockVideo: s.blockVideo,
      showOverlay: s.showOverlay,
      waBlock: s.waBlock !== false,
      waRecuse: s.waRecuse !== false,
      allowlist: s.allowlist || [],
      blockedCount: s.blockedCount || 0,
      log: s.log || [],
      permissions: s.permissions || state.permissions,
      onboarded: !!s.onboarded
    });
  } catch (e) {
    toast("Não foi possível ler as configurações");
  }
  render();
  wizardRefresh();
  // mantém o serviço de proteção vivo (hibernação da Infinix/Tecno)
  try { if (state.enabled) await Native.startGuard(); } catch (e) {}
}

async function save(partial) {
  Object.assign(state, partial);
  if (state.demo) { render(); return; }
  try {
    await Native.save({ settings: {
      enabled: state.enabled,
      mode: state.mode,
      blockVoice: state.blockVoice,
      blockVideo: state.blockVideo,
      showOverlay: state.showOverlay,
      waBlock: state.waBlock,
      waRecuse: state.waRecuse,
      allowlist: state.allowlist
    }});
  } catch (e) {
    toast("Falha ao salvar");
  }
  render();
}

/* ---------------- eventos ---------------- */

$("#power").addEventListener("click", async () => {
  if (state.demo) {
    state.enabled = !state.enabled;
    return save({});
  }
  if (!state.enabled) {
    // ligar: precisa da triagem de chamadas padrão
    if (!state.permissions.screening) {
      toast("Escolha o Silêncius como triagem de chamadas");
      await Native.openScreeningSettings();
      return;
    }
    if (state.showOverlay && !state.permissions.overlay) {
      await Native.requestOverlay();
    }
    await save({ enabled: true });
    toast("Proteção ligada");
    return;
  }
  await save({ enabled: false });
  toast("Proteção desligada");
});

document.querySelectorAll(".seg").forEach((b) =>
  b.addEventListener("click", () => save({ mode: b.dataset.mode }))
);

$("#blockVoice").addEventListener("change", (e) => save({ blockVoice: e.target.checked }));
$("#blockVideo").addEventListener("change", (e) => save({ blockVideo: e.target.checked }));
$("#showOverlay").addEventListener("change", async (e) => {
  if (e.target.checked && isNative && !state.permissions.overlay) {
    await Native.requestOverlay();
    setTimeout(load, 800);
  }
  save({ showOverlay: e.target.checked });
});

$("#waBlock").addEventListener("change", (e) => save({ waBlock: e.target.checked }));

$("#allowForm").addEventListener("submit", (e) => {
  e.preventDefault();
  const input = $("#allowInput");
  const digits = input.value.replace(/\D/g, "");
  if (digits.length < 8) { toast("Digite o número com DDD"); return; }
  const norm = digits.length > 11 ? digits.slice(-11) : digits;
  if (state.allowlist.includes(norm)) { toast("Esse número já está na lista"); return; }
  input.value = "";
  save({ allowlist: [...state.allowlist, norm] });
  toast("Número liberado");
});

$("#allowList").addEventListener("click", (e) => {
  const btn = e.target.closest("[data-remove]");
  if (!btn) return;
  const n = btn.dataset.remove;
  save({ allowlist: state.allowlist.filter((x) => x !== n) });
});

$("#waPerm").addEventListener("click", (e) => {
  if (!e.target.closest("[data-perm]")) return;
  $("#btnSetup").click();
});

$("#permList").addEventListener("click", async (e) => {
  const btn = e.target.closest("[data-perm]");
  if (!btn) return;
  if (state.demo) { toast("Disponível apenas no app instalado"); return; }
  if (btn.dataset.perm === "screening") await Native.openScreeningSettings();
  else if (btn.dataset.perm === "listener") await Native.openNotificationAccess();
  else if (btn.dataset.perm === "accessibility") await Native.openAccessibility();
  else await Native.requestOverlay();
  setTimeout(load, 1200);
});

$("#clearLog").addEventListener("click", async () => {
  state.log = [];
  state.blockedCount = 0;
  if (!state.demo) { try { await Native.clearLog(); } catch (_) {} }
  render();
  toast("Histórico limpo");
});

$("#btnDefaults").addEventListener("click", async () => {
  if (state.demo) return toast("Disponível apenas no app instalado");
  await Native.openScreeningSettings();
});


$("#btnInfo").addEventListener("click", async () => {
  if (state.demo) {
    return toast("Modo demonstração: o bloqueio real funciona no app instalado");
  }
  const d = await Native.diagnose();
  toast(d.summary);
});

/* ---------------- assistente de primeira configuração ----------------
 * Um passo por vez, cada um com um botão que abre exatamente a tela do sistema certa.
 * Ao voltar ao app, o passo é conferido de novo e o assistente avança sozinho.
 */
const STEPS = [
  {
    key: "basics", icon: "🔔",
    title: "Avisos e contatos",
    body: "O Silêncius usa os avisos para mostrar que a proteção está ligada, e os contatos para o modo \"Só desconhecidos\". Toque em Permitir nas duas janelas.",
    done: (p) => p.notifications,
    go: () => Native.requestBasics()
  },
  {
    key: "screening", icon: "☎",
    title: "Bloquear ligações comuns",
    body: "Escolha o Silêncius como app de triagem de chamadas. Assim as ligações telefônicas são recusadas antes de tocar.",
    done: (p) => p.screening,
    go: () => Native.openScreeningSettings()
  },
  {
    key: "listener", icon: "💬",
    title: "Recusar chamadas do WhatsApp",
    body: "Na tela que vai abrir, ligue a chave do Silêncius e confirme. É assim que ele vê a chamada do WhatsApp chegando e toca em Recusar.",
    done: (p) => p.listener,
    go: () => Native.openNotificationAccess(),
    restricted: true
  },
  {
    key: "accessibility", icon: "🛡",
    title: "Recusar com a tela apagada",
    body: "Toque em \"Silêncius\" (em alguns celulares ele fica em \"Aplicativos instalados\" ou \"Apps baixados\"), ligue a chave e confirme. Sem isso, o celular coloca o app para dormir e as chamadas passam com a tela apagada. O Silêncius só olha a tela de chamada do WhatsApp.",
    done: (p) => p.accessibility,
    go: () => openA11yDisclosure(),
    restricted: true
  },
  {
    key: "battery", icon: "🔋",
    title: "Funcionar com a tela apagada",
    body: "Toque em Permitir para o celular não desligar o Silêncius para economizar bateria.",
    done: (p) => p.battery,
    go: () => Native.requestIgnoreBattery()
  },
  {
    key: "overlay", icon: "🪟",
    title: "Aviso de chamada recusada",
    body: "Opcional: ligue \"Permitir sobreposição\" para ver por alguns segundos quem tentou ligar.",
    done: (p) => p.overlay,
    go: () => Native.requestOverlay(),
    optional: true
  }
];

const wz = { open: false, skipped: new Set(), restrictedTried: new Set(), bypass: new Set(), forced: false };

function wizardNext() {
  const p = state.permissions || {};
  return STEPS.find((st) => !st.done(p) && !wz.skipped.has(st.key));
}

function wizardRefresh() {
  if (state.demo) return;
  const shouldOpen = wz.forced || !state.onboarded;
  const step = shouldOpen ? wizardNext() : null;
  if (!step) {
    if (wz.open) finishWizard();
    return;
  }
  wz.open = true;
  const idx = STEPS.indexOf(step);
  const p = state.permissions || {};
  $("#wizard").hidden = false;
  $("#wzStep").textContent = `Passo ${idx + 1} de ${STEPS.length}`;
  $("#wzDots").innerHTML = STEPS.map((st, i) =>
    `<i class="${i === idx ? "on" : st.done(p) ? "done" : ""}"></i>`).join("");
  $("#wzIcon").textContent = step.icon;
  $("#wzTitle").textContent = step.title;
  $("#wzBody").textContent = step.body;

  // Android 13+ com app instalado fora da Play Store: a chave fica bloqueada ("Configuração restrita").
  // O guia aparece ANTES de abrir os ajustes, para a pessoa liberar primeiro.
  const blocked = step.restricted && p.restricted && !wz.bypass.has(step.key);
  const note = $("#wzNote");
  if (blocked) {
    note.hidden = false;
    note.innerHTML = `<strong>Primeiro, libere o Silêncius.</strong> O Android bloqueia esta chave em apps
      instalados fora da Play Store (aparece "Configuração restrita"). Para liberar:
      <ol><li>Toque em <b>Abrir informações do app</b> abaixo</li>
      <li>Toque nos <b>três pontinhos ⋮</b> no canto de cima</li>
      <li>Toque em <b>Permitir configurações restritas</b> e confirme</li>
      <li>Volte para o Silêncius</li></ol>
      <button class="linkBtn" id="wzBypass">Já liberei, continuar</button>`;
    $("#wzBypass").onclick = () => { wz.bypass.add(step.key); wizardRefresh(); };
    $("#wzGo").textContent = "Abrir informações do app";
    $("#wzGo").dataset.mode = "appinfo";
  } else {
    note.hidden = true;
    $("#wzGo").textContent = wz.restrictedTried.has(step.key) ? "Tentar de novo" : "Permitir";
    $("#wzGo").dataset.mode = "go";
  }
  $("#wzSkip").textContent = step.optional ? "Pular este passo" : "Fazer depois";
}

async function finishWizard() {
  wz.open = false;
  wz.forced = false;
  $("#wizard").hidden = true;
  if (!state.onboarded) {
    state.onboarded = true;
    try { await Native.setOnboarded({ value: true }); } catch (_) {}
    // tudo pronto: liga a proteção sozinho
    if (state.permissions.screening && !state.enabled) {
      await save({ enabled: true });
      toast("Pronto! Proteção ligada");
    }
  }
}

$("#wzGo").addEventListener("click", async () => {
  const step = wizardNext();
  if (!step) return finishWizard();
  if ($("#wzGo").dataset.mode === "appinfo") {
    await Native.openAppInfo();
    return;
  }
  if (step.restricted) wz.restrictedTried.add(step.key);
  try { await step.go(); } catch (_) {}
  setTimeout(load, 900);
});

$("#wzSkip").addEventListener("click", () => {
  const step = wizardNext();
  if (step) wz.skipped.add(step.key);
  wizardRefresh();
  if (!wizardNext()) finishWizard();
});

$("#waAlertBtn").addEventListener("click", () => $("#btnSetup").click());

$("#btnSetup").addEventListener("click", () => {
  if (state.demo) return toast("Disponível apenas no app instalado");
  wz.skipped.clear();
  wz.forced = true;
  if (!wizardNext()) { wz.forced = false; return toast("Tudo configurado ✓"); }
  wizardRefresh();
});


/* Consentimento explícito exibido ANTES da tela oficial do Android.
   Apenas interface: não altera o serviço nativo que recusa as chamadas. */
function openA11yDisclosure() {
  if (state.demo) return toast("Disponível apenas no app instalado");
  $("#a11yDisclosure").hidden = false;
  $("#a11yConsentAccept").focus();
}

$("#btnA11yDisclosure").addEventListener("click", openA11yDisclosure);

$("#a11yConsentDecline").addEventListener("click", () => {
  $("#a11yDisclosure").hidden = true;
  toast("Você pode autorizar depois em Configurar.");
});

$("#a11yConsentAccept").addEventListener("click", async () => {
  $("#a11yDisclosure").hidden = true;
  try {
    // A autorização permanece sob controle do usuário nas configurações do Android.
    await Native.openAccessibility();
    setTimeout(load, 1000);
  } catch (_) {
    toast("Não foi possível abrir as configurações de Acessibilidade.");
  }
});

/* atualiza ao voltar de outra tela (ajustes, permissões) */
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) load();
});
window.addEventListener("focus", () => load());
try {
  window.Capacitor.Plugins.App && window.Capacitor.Plugins.App.addListener("resume", () => load());
} catch (_) {}
// enquanto o assistente estiver aberto, confere a cada 1,5 s (janelas do sistema não pausam o app)
setInterval(() => { if (wz.open && !document.hidden) load(); }, 1500);

load();
setInterval(() => { if (!document.hidden) load(); }, 20000);
