/* Silêncius — interface.
   Toda a lógica de bloqueio fica no código nativo (Kotlin). Aqui só conversamos
   com o plugin "Silence". Quando a página é aberta num navegador comum, a
   interface funciona em modo demonstração, com dados de exemplo. */

const Native = (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Silence) || null;
const isNative = !!(Native && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
const Billing = (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.SilenciusBilling) || null;
let billingReadyToPurchase = false;
let lastBillingSync = 0;
let premiumVerifiedActive = null;
let premiumVerifiedExpiry = "";

function refreshPremiumPurchaseButton() {
  const button = document.querySelector("#billingStart");
  if (!button) return;
  if (premiumVerifiedActive === true) {
    billingReadyToPurchase = false;
    button.disabled = true;
    button.textContent = "Assinatura já ativa";
    document.querySelector("#billingStatus").textContent = "Assinatura ativa";
    document.querySelector("#billingText").textContent =
      "Premium ativo" + (premiumVerifiedExpiry ? " até " + premiumVerifiedExpiry : "") +
      ". Gerencie sua renovação e cancelamento pelo Google Play.";
    document.querySelector("#billingFine").textContent =
      "Sua assinatura está ativa. Gerencie a renovação ou o cancelamento na Google Play.";
  }
}


async function syncPremiumOnResume() {
  if (!Billing || !isNative || Date.now() - lastBillingSync < 60000) return;
  try {
    const config = await Billing.getConfig();
    if (!config.purchaseEnabled) return;
    lastBillingSync = Date.now();
    const entitlement = await Billing.restorePurchases();
    showVerifiedPremium(entitlement);
    if (entitlement.verified) load();
  } catch (_) {
    // Conectividade intermitente não deve bloquear a interface nem apagar o direito de uso em cache.
  }
}

/* Esta tela não altera Prefs, nem liga/desliga serviços. Só informa planos reais do Play. */
async function loadBilling() {
  const button = document.querySelector("#billingStart");
  const info = document.querySelector("#billingText");
  const price = document.querySelector("#billingPrice");
  const status = document.querySelector("#billingStatus");
  button.disabled = true;
  billingReadyToPurchase = false;
  document.querySelector("#billingTitle").textContent = "Silêncius Premium";
  if (!Billing || !isNative) {
    status.textContent = "Demonstração";
    info.textContent = "Assinaturas disponíveis apenas no aplicativo instalado pela Google Play.";
    return;
  }
  try {
    const config = await Billing.getConfig();
    let product = null;
    try { product = await Billing.getOffers(); } catch (_) {}
    const options = (product && product.offers) || [];
    const trial = options.find(o => o.eligible && o.hasThreeDayTrial && o.period === "P1M");
    const normal = options.find(o => o.eligible && o.period === "P1M");
    const selected = trial || normal;
    if (selected && selected.formattedPrice) {
      price.textContent = (trial ? "Depois, " : "") + selected.formattedPrice + " por mês";
    }
    if (trial) {
      document.querySelector("#billingTitle").textContent = "Experimente por 3 dias grátis";
      document.querySelector("#billingFine").textContent =
        "Cancele pelo Google Play antes do fim do teste para evitar a primeira mensalidade.";
    } else {
      document.querySelector("#billingFine").textContent =
        "Sem teste gratuito para esta conta. O valor e a recorrência são confirmados pela Google Play.";
    }
    if (config.purchaseEnabled && selected) {
      status.textContent = trial ? "Teste disponível" : "Assinatura mensal";
      info.textContent = trial
        ? "3 dias grátis para esta conta; depois, renovação automática no valor informado. Cancele pelo Google Play."
        : "Esta conta não tem oferta de teste disponível. O Google Play exibirá o preço e as condições antes de confirmar.";
      button.textContent = trial ? "Ativar 3 dias grátis" : "Assinar pelo Google Play";
      button.disabled = false;
      billingReadyToPurchase = true;
      refreshPremiumPurchaseButton();
      await syncPremiumOnResume();
    } else {
      status.textContent = "Em preparação";
      info.textContent = "O plano está sendo configurado. Não há cobranças habilitadas nesta versão de teste.";
      button.textContent = "Assinaturas em preparação";
    }
  } catch (e) {
    status.textContent = "Indisponível";
    info.textContent = "Não foi possível consultar as ofertas do Google Play agora.";
  }
}

function showVerifiedPremium(result) {
  const status = document.querySelector("#billingStatus");
  const info = document.querySelector("#billingText");
  if (result && result.verified && result.active) {
    premiumVerifiedActive = true;
    status.textContent = "Assinatura ativa";
    const exp = result.expiryTime ? new Date(result.expiryTime) : null;
    premiumVerifiedExpiry = exp && Number.isFinite(exp.getTime())
      ? exp.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" }) : "";
    refreshPremiumPurchaseButton();
  } else if (result && result.verified && !result.active) {
    premiumVerifiedActive = false;
    premiumVerifiedExpiry = "";
    status.textContent = "Assinatura não ativa";
    info.textContent = "Não encontramos uma assinatura ativa nesta conta Google Play.";
    // A oferta será recalculada pela Google Play ao carregar as opções.
    loadBilling();
  }
}

if (Billing && isNative && Billing.addListener) {
  Billing.addListener("subscriptionVerified", ({ active, verified, expiryTime }) => {
    showVerifiedPremium({ active, verified, expiryTime });
    load();
  });
  Billing.addListener("purchaseUpdated", ({ hasPurchased }) => {
    if (hasPurchased) {
      billingReadyToPurchase = false;
      document.querySelector("#billingStart").disabled = true;
      document.querySelector("#billingStart").textContent = "Confirmando assinatura...";
      toast("Compra recebida. Confirmando assinatura com a Google Play.");
    }
  });
  Billing.addListener("subscriptionVerificationFailed", ({ diagnosticCode }) => {
    const code = typeof diagnosticCode === "string" && /^[A-Za-z0-9_]{3,70}$/.test(diagnosticCode)
      ? diagnosticCode : "VERIFICACAO_INDISPONIVEL";
    const status = document.querySelector("#billingStatus");
    const info = document.querySelector("#billingText");
    status.textContent = "Verificação pendente";
    billingReadyToPurchase = false;
    document.querySelector("#billingStart").disabled = true;
    document.querySelector("#billingStart").textContent = "Verificação pendente";
    info.textContent = "Google Play ainda não confirmou esta assinatura. Código de suporte: " + code +
      ". Não tente contratar novamente antes de resolver a verificação.";
  });
}

document.querySelector("#billingStart").addEventListener("click", async () => {
  if (!billingReadyToPurchase || !Billing) return;
  try { await Billing.purchase(); } catch (e) { toast("Não foi possível iniciar a assinatura"); }
});
document.querySelector("#billingRestore").addEventListener("click", async () => {
  if (!Billing || !isNative) return toast("Apenas no aplicativo instalado");
  try {
    const result = await Billing.restorePurchases();
    showVerifiedPremium(result);
    if (result.verified) load();
    toast(result.active ? "Assinatura confirmada pelo Google Play." :
      result.found ? "Compra recebida; validação pendente." : "Nenhuma assinatura ativa encontrada.");
  } catch (e) {
    const match = String(e?.message || e || "").match(/(?:HTTP_[0-9]{3}_[a-z0-9_]+|TIMEOUT|SEM_DNS|FALHA_TLS|VERIFICACAO_INDISPONIVEL)/);
    const code = match ? match[0] : "VERIFICACAO_INDISPONIVEL";
    toast("Falha ao consultar a assinatura (" + code + ")");
  }
});


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
  demo: false,
  premiumRequired: false,
  premiumActive: true
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
  const active = state.enabled && p.screening && (!state.premiumRequired || state.premiumActive);

  $("#power").setAttribute("aria-pressed", String(active));
  $("#power").disabled = !!(state.premiumRequired && !state.premiumActive);
  $("#powerLabel").textContent = active ? "Ligado" : "Desligado";

  $("#statusTitle").textContent = active ? "Proteção ativa" : "Proteção desligada";
  $("#statusBody").textContent = active
    ? (state.mode === "allowlist"
        ? "Chamadas de quem não está na lista de exceções serão recusadas."
        : state.mode === "unknown"
          ? "Chamadas de números fora da agenda serão recusadas."
          : "Qualquer chamada de voz ou de vídeo será recusada antes de o celular tocar.")
    : state.premiumRequired && !state.premiumActive
      ? "Ative ou restaure sua assinatura Premium para ligar a proteção."
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
      onboarded: !!s.onboarded,
      premiumRequired: !!s.premiumRequired,
      premiumActive: s.premiumActive !== false
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
    if (state.premiumRequired && !state.premiumActive) {
      toast("Ative sua assinatura Premium para ligar a proteção.");
      return;
    }
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
    go: () => Native.openAccessibility(),
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
  if (state.premiumRequired && !state.premiumActive) {
    wz.open = false;
    $("#wizard").hidden = true;
    return;
  }
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

/* atualiza ao voltar de outra tela (ajustes, permissões) */
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) { load(); syncPremiumOnResume(); }
});
window.addEventListener("focus", () => { load(); syncPremiumOnResume(); });
try {
  window.Capacitor.Plugins.App && window.Capacitor.Plugins.App.addListener("resume", () => {
    load();
    syncPremiumOnResume();
  });
} catch (_) {}
// enquanto o assistente estiver aberto, confere a cada 1,5 s (janelas do sistema não pausam o app)
setInterval(() => { if (wz.open && !document.hidden) load(); }, 1500);

loadBilling();
load();
setInterval(() => { if (!document.hidden) load(); }, 20000);
// Renovação/restauração enquanto o aplicativo permanece aberto: consulta limitada a 1/min.
setInterval(() => { if (!document.hidden) syncPremiumOnResume(); }, 60_000);
