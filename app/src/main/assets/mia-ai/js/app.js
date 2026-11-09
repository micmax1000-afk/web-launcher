/* Mia AI — logica dell'app */
(function () {
  'use strict';

  const P = window.MiaProviders;
  const MD = window.MiaMarkdown;
  const $ = (s) => document.querySelector(s);

  const LS_SETTINGS = 'miaai_v1_settings';
  const LS_CONVS = 'miaai_v1_conversations';
  const LS_CURRENT = 'miaai_v1_current';
  const MAX_HISTORY = 40; // messaggi inviati al modello come contesto
  const MAX_TEXT_FILE = 200 * 1024; // caratteri massimi di un file di testo
  const MAX_BINARY = 8 * 1024 * 1024; // byte massimi di un PDF
  const IMAGE_MAX_SIDE = 1280; // le foto vengono ridimensionate
  const TEXT_EXT = /\.(txt|md|markdown|csv|tsv|json|xml|html?|css|js|mjs|ts|jsx|tsx|py|java|kt|c|h|cpp|cs|go|rs|rb|php|sh|sql|yaml|yml|ini|toml|log|srt)$/i;

  // ─── Stato ────────────────────────────────────────────────
  const DEFAULT_SETTINGS = {
    provider: 'gemini',
    profiles: {}, // { [provider]: { baseUrl, apiKey, model } }
    systemPrompt: 'Sei Mia, un assistente utile e cordiale. Rispondi in italiano, in modo chiaro e conciso, salvo richiesta diversa.',
    temperature: 0.7,
    theme: 'auto',
    favorites: [], // [{ provider, model }] — modelli per il cambio rapido
    autoFallback: true // se il modello è occupato, prova da solo gli altri dell'elenco
  };

  function load(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v ? JSON.parse(v) : fallback;
    } catch (_) {
      return fallback;
    }
  }
  function save(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (_) {
      toast('Memoria piena: elimina qualche chat.');
    }
  }

  let settings = Object.assign({}, DEFAULT_SETTINGS, load(LS_SETTINGS, {}));
  if (!Array.isArray(settings.favorites)) settings.favorites = [];
  let conversations = load(LS_CONVS, []);
  let currentId = load(LS_CURRENT, null);
  let busy = false;

  function profile(provider) {
    const pr = provider || settings.provider;
    const preset = P.PRESETS[pr] || P.PRESETS.custom;
    const saved = settings.profiles[pr] || {};
    return {
      baseUrl: saved.baseUrl || preset.baseUrl,
      apiKey: saved.apiKey || '',
      model: saved.model != null && saved.model !== '' ? saved.model : preset.model
    };
  }

  /** Config per la chiamata: provider/modello attivi, oppure quelli di `pick` ({provider, model}). */
  function chatConfig(pick) {
    const provider = (pick && pick.provider) || settings.provider;
    const cfg = Object.assign({ provider }, profile(provider), {
      systemPrompt: settings.systemPrompt,
      temperature: Number(settings.temperature)
    });
    if (pick && pick.model) cfg.model = pick.model;
    return cfg;
  }

  function isConfigured(pick) {
    const provider = (pick && pick.provider) || settings.provider;
    const preset = P.PRESETS[provider] || P.PRESETS.custom;
    const p = profile(provider);
    const model = (pick && pick.model) || p.model;
    return !!model && (!preset.needsKey || !!p.apiKey);
  }

  function modelLabel(provider, model) {
    const preset = P.PRESETS[provider] || P.PRESETS.custom;
    return (preset.short || preset.label) + ' · ' + model;
  }

  function addFavorite(provider, model) {
    if (!provider || !model) return;
    if (settings.favorites.some((f) => f.provider === provider && f.model === model)) return;
    settings.favorites.push({ provider, model });
    if (settings.favorites.length > 20) settings.favorites.shift();
  }

  function selectModel(provider, model) {
    settings.provider = provider;
    const prof = Object.assign({}, settings.profiles[provider] || profile(provider));
    prof.model = model;
    settings.profiles[provider] = prof;
    save(LS_SETTINGS, settings);
    updateHeader();
  }

  function current() {
    return conversations.find((c) => c.id === currentId) || null;
  }

  function persistConvs() {
    save(LS_CURRENT, currentId);
    try {
      localStorage.setItem(LS_CONVS, JSON.stringify(conversations));
      return;
    } catch (_) {}
    // Memoria piena: elimina i dati degli allegati più vecchi (resta solo il nome)
    const all = [];
    conversations.forEach((c) =>
      c.messages.forEach((m) => (m.attachments || []).forEach((a) => a.data && all.push({ a, t: c.updated })))
    );
    all.sort((x, y) => x.t - y.t);
    for (const { a } of all) {
      delete a.data;
      delete a.thumb;
      a.dropped = true;
      try {
        localStorage.setItem(LS_CONVS, JSON.stringify(conversations));
        toast('Memoria quasi piena: rimossi gli allegati più vecchi');
        return;
      } catch (_) {}
    }
    toast('Memoria piena: elimina qualche chat.');
  }

  function uid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  // ─── UI helpers ───────────────────────────────────────────
  let toastTimer = null;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), 2200);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (_) {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand('copy');
      } catch (_) {}
      ta.remove();
    }
    toast('Copiato');
  }

  function applyTheme() {
    document.documentElement.dataset.theme = settings.theme;
    const dark =
      settings.theme === 'dark' ||
      (settings.theme === 'auto' && !window.matchMedia('(prefers-color-scheme: light)').matches);
    $('#themeColor').setAttribute('content', dark ? '#0b1020' : '#f6f7fb');
    const sb = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.StatusBar;
    if (sb && window.Capacitor.isNativePlatform()) {
      try {
        sb.setStyle({ style: dark ? 'DARK' : 'LIGHT' });
        sb.setBackgroundColor({ color: dark ? '#0b1020' : '#f6f7fb' });
      } catch (_) {}
    }
  }

  function updateHeader() {
    const c = current();
    $('#chatTitle').textContent = (c && c.title) || 'Mia AI';
    const preset = P.PRESETS[settings.provider] || P.PRESETS.custom;
    const p = profile();
    $('#micBtn').hidden = !isConfigured() || (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window));
    $('#modelHint').textContent = isConfigured() ? modelLabel(settings.provider, p.model) + ' ▾' : 'Da configurare in Impostazioni';
    $('#setupHint').hidden = isConfigured();
  }

  function scrollToBottom(force) {
    const box = $('#messages');
    const near = box.scrollHeight - box.scrollTop - box.clientHeight < 140;
    if (force || near) box.scrollTop = box.scrollHeight;
  }

  // ─── Rendering messaggi ───────────────────────────────────
  function messageEl(msg, index) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + msg.role + (msg.error ? ' error' : '');
    wrap.dataset.index = index;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    if (msg.attachments && msg.attachments.length) wrap.appendChild(attachmentsEl(msg.attachments, false));
    if (msg.role === 'user' || msg.error) bubble.textContent = msg.content;
    else bubble.innerHTML = MD.render(msg.content);
    if (msg.content || msg.role !== 'user') wrap.appendChild(bubble);

    const actions = document.createElement('div');
    actions.className = 'msg-actions';
    if (!msg.error) actions.innerHTML = '<button type="button" data-act="copy">Copia</button>';
    if (msg.role === 'user') actions.innerHTML += '<button type="button" data-act="edit">Modifica</button>';
    else {
      actions.innerHTML +=
        '<button type="button" data-act="retry">Rigenera</button>' +
        '<button type="button" data-act="retry-with">Prova con…</button>';
      if (msg.model) {
        const tag = document.createElement('span');
        tag.className = 'model-tag';
        tag.textContent = msg.model;
        actions.appendChild(tag);
      }
    }
    if (msg.content && !msg.error) {
      actions.innerHTML += '<button type="button" data-act="read" aria-label="Leggi ad alta voce">🎤</button>';
    }
    wrap.appendChild(actions);
    return wrap;
  }

  /** Anteprime degli allegati: nel messaggio (removable=false) o nel composer (true). */
  function attachmentsEl(list, removable) {
    const box = document.createElement('div');
    box.className = 'attachments';
    list.forEach((a, i) => {
      const el = document.createElement('div');
      el.className = 'att ' + a.kind + (a.dropped ? ' dropped' : '');
      if (a.kind === 'image' && (a.thumb || a.data)) {
        const img = document.createElement('img');
        img.src = 'data:image/jpeg;base64,' + (a.thumb || a.data);
        img.alt = a.name;
        el.appendChild(img);
      } else {
        const icon = document.createElement('span');
        icon.className = 'att-icon';
        icon.textContent = a.kind === 'pdf' ? '📕' : a.kind === 'image' ? '🖼' : '📄';
        const name = document.createElement('span');
        name.className = 'att-name';
        name.textContent = a.name;
        el.append(icon, name);
      }
      if (removable) {
        const x = document.createElement('button');
        x.type = 'button';
        x.className = 'att-remove';
        x.dataset.removeAtt = i;
        x.setAttribute('aria-label', 'Rimuovi allegato');
        x.textContent = '✕';
        el.appendChild(x);
      }
      box.appendChild(el);
    });
    return box;
  }

  // ─── Allegati nel composer ────────────────────────────────
  let pending = []; // allegati pronti per il prossimo messaggio

  function renderPending() {
    const box = $('#pendingAtt');
    box.innerHTML = '';
    box.hidden = !pending.length;
    if (pending.length) box.appendChild(attachmentsEl(pending, true));
  }

  function readAs(file, how) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(r.error || new Error('Lettura file non riuscita'));
      if (how === 'text') r.readAsText(file);
      else r.readAsDataURL(file);
    });
  }

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error('Immagine non leggibile'));
      img.src = src;
    });
  }

  function scaleToJpeg(img, maxSide, quality) {
    const k = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight));
    const w = Math.max(1, Math.round(img.naturalWidth * k));
    const h = Math.max(1, Math.round(img.naturalHeight * k));
    const cv = document.createElement('canvas');
    cv.width = w;
    cv.height = h;
    const ctx = cv.getContext('2d');
    ctx.fillStyle = '#fff'; // sfondo bianco per PNG trasparenti
    ctx.fillRect(0, 0, w, h);
    ctx.drawImage(img, 0, 0, w, h);
    return cv.toDataURL('image/jpeg', quality).split(',')[1];
  }

  async function fileToAttachment(file) {
    const name = file.name || 'file';
    if (/^image\//.test(file.type)) {
      const img = await loadImage(await readAs(file, 'dataurl'));
      return {
        kind: 'image',
        name,
        mime: 'image/jpeg',
        data: scaleToJpeg(img, IMAGE_MAX_SIDE, 0.85),
        thumb: scaleToJpeg(img, 240, 0.7)
      };
    }
    if (file.type === 'application/pdf' || /\.pdf$/i.test(name)) {
      if (file.size > MAX_BINARY) throw new Error('PDF troppo grande (max 8 MB): ' + name);
      return { kind: 'pdf', name, mime: 'application/pdf', data: (await readAs(file, 'dataurl')).split(',')[1] };
    }
    if (/^text\//.test(file.type) || /json|xml|javascript/.test(file.type) || TEXT_EXT.test(name)) {
      let text = await readAs(file, 'text');
      if (text.length > MAX_TEXT_FILE) text = text.slice(0, MAX_TEXT_FILE) + '\n[…file troncato…]';
      return { kind: 'text', name, mime: file.type || 'text/plain', text };
    }
    throw new Error('Tipo di file non supportato: ' + name + ' (usa immagini, PDF o file di testo)');
  }

  async function addFiles(files) {
    for (const f of Array.from(files || [])) {
      if (pending.length >= 6) {
        toast('Massimo 6 allegati per messaggio');
        break;
      }
      try {
        pending.push(await fileToAttachment(f));
      } catch (e) {
        toast(e.message || 'File non leggibile');
      }
    }
    renderPending();
  }

  function renderMessages() {
    const box = $('#messages');
    const c = current();
    box.querySelectorAll('.msg').forEach((n) => n.remove());
    const msgs = (c && c.messages) || [];
    $('#emptyState').hidden = msgs.length > 0;
    msgs.forEach((m, i) => box.appendChild(messageEl(m, i)));
    // mostra «Rigenera» solo sull'ultima risposta
    ['retry', 'retry-with'].forEach((act) => {
      box.querySelectorAll('.msg.assistant [data-act="' + act + '"]').forEach((b, i, all) => {
        b.hidden = i !== all.length - 1 || busy;
      });
    });
    box.querySelectorAll('[data-act="edit"]').forEach((b) => (b.hidden = busy));
    updateHeader();
    scrollToBottom(true);
  }

  function renderConvList() {
    const ul = $('#convList');
    ul.innerHTML = '';
    if (!conversations.length) {
      ul.innerHTML = '<li class="hint" style="padding:10px">Nessuna conversazione.</li>';
      return;
    }
    [...conversations]
      .sort((a, b) => b.updated - a.updated)
      .forEach((c) => {
        const li = document.createElement('li');
        if (c.id === currentId) li.className = 'active';
        const open = document.createElement('button');
        open.type = 'button';
        open.className = 'open-conv';
        open.dataset.id = c.id;
        const title = document.createElement('span');
        title.textContent = c.title || 'Nuova chat';
        const when = document.createElement('small');
        when.textContent = new Date(c.updated).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' });
        open.append(title, when);
        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'del-conv';
        del.dataset.del = c.id;
        del.setAttribute('aria-label', 'Elimina');
        del.textContent = '🗑';
        li.append(open, del);
        ul.appendChild(li);
      });
  }

  // ─── Chat ─────────────────────────────────────────────────
  function newChat() {
    if (busy) P.abort();
    const c = current();
    if (c && !c.messages.length) {
      closeDrawer();
      $('#input').focus();
      return; // già vuota
    }
    currentId = null;
    save(LS_CURRENT, currentId);
    renderMessages();
    closeDrawer();
    $('#input').focus();
  }

  function ensureConversation(firstText) {
    let c = current();
    if (!c) {
      c = { id: uid(), title: '', messages: [], created: Date.now(), updated: Date.now() };
      conversations.push(c);
      currentId = c.id;
    }
    if (!c.title && firstText) c.title = firstText.replace(/\s+/g, ' ').trim().slice(0, 48);
    return c;
  }

  function setBusy(v) {
    busy = v;
    const btn = $('#sendBtn');
    btn.classList.toggle('stop', v);
    btn.textContent = v ? '■' : '➤';
    btn.setAttribute('aria-label', v ? 'Ferma' : 'Invia');
  }

  async function runAssistant(c, pick) {
    const cfg = chatConfig(pick);
    // rimuovi eventuali errori precedenti in coda
    while (c.messages.length && c.messages[c.messages.length - 1].error) c.messages.pop();
    const history = c.messages
      .filter((m) => !m.error)
      .slice(-MAX_HISTORY)
      .map((m) => ({ role: m.role, content: m.content, attachments: m.attachments }));

    setBusy(true);
    renderMessages();

    const box = $('#messages');
    const wrap = document.createElement('div');
    wrap.className = 'msg assistant';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = '<span class="typing"><i></i><i></i><i></i></span>';
    wrap.appendChild(bubble);
    box.appendChild(wrap);
    scrollToBottom(true);

    // Modelli da provare: quello scelto, poi (se attivo) gli altri dell'elenco ▾ già configurati
    const attempts = [cfg];
    if (settings.autoFallback !== false) {
      settings.favorites
        .filter(
          (f) =>
            !(f.provider === cfg.provider && f.model === cfg.model) &&
            isConfigured(f) &&
            (P.isSpecialized(cfg.model) || !P.isSpecialized(f.model)) // niente modelli per il codice come ripiego
        )
        .slice(0, 3)
        .forEach((f) => attempts.push(chatConfig(f)));
    }

    let tag = modelLabel(cfg.provider, cfg.model);
    let partial = '';
    let frame = 0;
    try {
      let text = null;
      for (let i = 0; i < attempts.length && text === null; i++) {
        const a = attempts[i];
        tag = modelLabel(a.provider, a.model);
        try {
          text = await P.chat(a, history, (full) => {
            partial = full;
            if (!frame) {
              frame = requestAnimationFrame(() => {
                frame = 0;
                bubble.innerHTML = partial ? MD.render(partial) : '<span class="typing"><i></i><i></i><i></i></span>';
                scrollToBottom(false);
              });
            }
          });
          addFavorite(a.provider, a.model);
        } catch (err) {
          const next = attempts[i + 1];
          if (!next || partial || !P.isBusyError(err)) throw err;
          toast(a.model + ' è occupato: provo ' + next.model);
        }
      }
      const usedFallback = tag !== modelLabel(cfg.provider, cfg.model);
      c.messages.push({ role: 'assistant', content: text, model: (usedFallback ? '↪ ' : '') + tag });
      if (usedFallback) toast('Ha risposto ' + tag + ' perché ' + cfg.model + ' era occupato');
      save(LS_SETTINGS, settings);
    } catch (e) {
      if (e && e.name === 'AbortError') {
        if (partial.trim()) c.messages.push({ role: 'assistant', content: partial + '\n\n_(interrotto)_', model: tag });
      } else {
        if (partial.trim()) c.messages.push({ role: 'assistant', content: partial, model: tag });
        const label = (P.PRESETS[cfg.provider] || {}).label + ' (' + cfg.model + ')';
        let msg = '⚠️ ' + P.friendlyError(e, label);
        if (attempts.length > 1 && P.isBusyError(e)) msg += ' Ho provato anche: ' + attempts.slice(1).map((a) => a.model).join(', ') + '.';
        else if (attempts.length === 1 && P.isBusyError(e))
          msg += ' Suggerimento: aggiungi un secondo modello (es. Gemini) e l\'app passerà da sola a quello quando questo è occupato.';
        c.messages.push({ role: 'assistant', content: msg, error: true });
      }
    } finally {
      if (frame) cancelAnimationFrame(frame);
      c.updated = Date.now();
      persistConvs();
      setBusy(false);
      renderMessages();
      renderConvList();
    }
  }

  async function send(text) {
    text = (text || '').trim();
    if ((!text && !pending.length) || busy) return;
    if (!isConfigured()) {
      toast('Configura prima il provider');
      openSettings();
      return;
    }
    const c = ensureConversation(text || pending.map((a) => a.name).join(', '));
    const msg = { role: 'user', content: text };
    if (pending.length) msg.attachments = pending;
    c.messages.push(msg);
    pending = [];
    renderPending();
    c.updated = Date.now();
    persistConvs();
    $('#input').value = '';
    autoGrow();
    await runAssistant(c);
  }

  function retryLast(pick) {
    const c = current();
    if (!c || busy) return;
    if (pick && !isConfigured(pick)) {
      toast('Manca la chiave per ' + (P.PRESETS[pick.provider] || {}).label);
      return;
    }
    while (c.messages.length && c.messages[c.messages.length - 1].role === 'assistant') c.messages.pop();
    if (!c.messages.length) return;
    runAssistant(c, pick);
  }

  // ─── Cambio rapido modello ────────────────────────────────
  let pickerMode = 'switch'; // 'switch' = cambia modello attivo, 'retry' = rigenera l'ultima risposta

  function openPicker(mode) {
    pickerMode = mode || 'switch';
    $('#pickerTitle').textContent = pickerMode === 'retry' ? 'Rigenera la risposta con…' : 'Scegli il modello';
    const ul = $('#pickerList');
    ul.innerHTML = '';
    const p = profile();
    if (isConfigured()) addFavorite(settings.provider, p.model);
    if (!settings.favorites.length) {
      ul.innerHTML = '<li class="hint" style="padding:10px">Nessun modello: aggiungine uno da Impostazioni.</li>';
    }
    settings.favorites.forEach((f, i) => {
      const li = document.createElement('li');
      const active = f.provider === settings.provider && f.model === p.model;
      if (active) li.className = 'active';
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'pick';
      b.dataset.pick = i;
      const name = document.createElement('span');
      name.textContent = f.model;
      const prov = document.createElement('small');
      const preset = P.PRESETS[f.provider] || P.PRESETS.custom;
      prov.textContent = preset.label + (isConfigured(f) ? '' : ' · manca la chiave');
      b.append(name, prov);
      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'del-conv';
      del.dataset.unfav = i;
      del.setAttribute('aria-label', 'Rimuovi');
      del.textContent = '✕';
      li.append(b, del);
      if (active) {
        const ok = document.createElement('span');
        ok.className = 'check';
        ok.textContent = '✓';
        b.appendChild(ok);
      }
      ul.appendChild(li);
    });
    $('#picker').hidden = false;
  }

  function closePicker() {
    $('#picker').hidden = true;
  }

  function editMessage(index) {
    const c = current();
    if (!c || busy) return;
    const msg = c.messages[index];
    if (!msg || msg.role !== 'user') return;
    $('#input').value = msg.content;
    pending = (msg.attachments || []).filter((a) => !a.dropped);
    renderPending();
    c.messages = c.messages.slice(0, index);
    c.updated = Date.now();
    persistConvs();
    renderMessages();
    autoGrow();
    $('#input').focus();
  }

  // ─── Drawer ───────────────────────────────────────────────
  function openDrawer() {
    renderConvList();
    $('#drawer').classList.add('open');
    $('#drawer').setAttribute('aria-hidden', 'false');
    $('#scrim').hidden = false;
  }
  function closeDrawer() {
    $('#drawer').classList.remove('open');
    $('#drawer').setAttribute('aria-hidden', 'true');
    $('#scrim').hidden = true;
  }

  // ─── Impostazioni ─────────────────────────────────────────
  function fillProviderFields() {
    const preset = P.PRESETS[$('#provider').value] || P.PRESETS.custom;
    const p = profile($('#provider').value);
    $('#baseUrl').value = p.baseUrl;
    $('#apiKey').value = p.apiKey;
    $('#model').value = p.model;
    $('#keyOptional').hidden = !!preset.needsKey;
    loadedModels = [];
    $('#modelBrowser').hidden = true;
    $('#modelsStatus').textContent = '';
    const hint = $('#providerHint');
    hint.textContent = '';
    if (preset.hint) {
      hint.textContent = preset.hint;
    } else if (preset.keyUrl) {
      hint.append('Chiave: ');
      const a = document.createElement('a');
      a.href = preset.keyUrl;
      a.target = '_blank';
      a.rel = 'noopener';
      a.textContent = preset.keyUrl.replace(/^https:\/\//, '');
      hint.appendChild(a);
    }
  }

  function openSettings() {
    closeDrawer();
    closePicker();
    $('#provider').value = settings.provider;
    fillProviderFields();
    $('#systemPrompt').value = settings.systemPrompt;
    $('#temperature').value = settings.temperature;
    $('#tempValue').textContent = Number(settings.temperature).toFixed(1);
    $('#theme').value = settings.theme;
    $('#autoFallback').checked = settings.autoFallback !== false;
    $('#settings').hidden = false;
  }

  function saveProviderFields() {
    const pr = $('#provider').value;
    settings.provider = pr;
    settings.profiles[pr] = {
      baseUrl: $('#baseUrl').value.trim(),
      apiKey: $('#apiKey').value.trim(),
      model: $('#model').value.trim()
    };
  }

  function closeSettings() {
    saveProviderFields();
    settings.systemPrompt = $('#systemPrompt').value;
    settings.temperature = Number($('#temperature').value);
    settings.theme = $('#theme').value;
    settings.autoFallback = $('#autoFallback').checked;
    if (isConfigured()) addFavorite(settings.provider, profile().model);
    save(LS_SETTINGS, settings);
    applyTheme();
    updateHeader();
    $('#settings').hidden = true;
  }

  let loadedModels = [];

  function renderModelResults() {
    const ul = $('#modelResults');
    const q = $('#modelSearch').value.trim().toLowerCase();
    const onlyFree = $('#onlyFree').checked;
    const current = $('#model').value.trim();
    const list = loadedModels.filter(
      (m) => (!onlyFree || m.free === true) && (!q || (m.id + ' ' + m.name).toLowerCase().includes(q))
    );
    ul.innerHTML = '';
    if (!list.length) {
      ul.innerHTML = '<li class="hint" style="padding:10px">Nessun modello corrisponde.</li>';
      return;
    }
    list.slice(0, 300).forEach((m) => {
      const li = document.createElement('li');
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.model = m.id;
      if (m.id === current) b.className = 'selected';
      const title = document.createElement('span');
      title.textContent = m.name || m.id;
      const id = document.createElement('small');
      id.textContent = m.name ? m.id : '';
      const tags = document.createElement('span');
      tags.className = 'tags';
      if (m.free === true) tags.innerHTML += '<i class="tag free">gratis</i>';
      if (m.vision === true) tags.innerHTML += '<i class="tag">🖼 immagini</i>';
      b.append(title, id, tags);
      li.appendChild(b);
      ul.appendChild(li);
    });
  }

  async function loadModels() {
    saveProviderFields();
    const status = $('#modelsStatus');
    status.textContent = 'Caricamento…';
    try {
      const cfg = Object.assign({ provider: settings.provider }, profile());
      loadedModels = await P.listModels(cfg);
      const nFree = loadedModels.filter((m) => m.free === true).length;
      status.textContent = loadedModels.length
        ? loadedModels.length +
          ' modelli per chattare' +
          (nFree ? ', di cui ' + nFree + ' gratuiti' : '') +
          '. Tocca un modello per sceglierlo.'
        : 'Nessun modello restituito dal server.';
      $('#onlyFree').checked = nFree > 0;
      $('#onlyFree').parentElement.hidden = nFree === 0;
      $('#modelSearch').value = '';
      $('#modelBrowser').hidden = !loadedModels.length;
      renderModelResults();
    } catch (e) {
      status.textContent = P.friendlyError(e, (P.PRESETS[settings.provider] || {}).label);
    }
  }

  function exportChats() {
    const blob = new Blob([JSON.stringify(conversations, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'mia-ai-chat-' + new Date().toISOString().slice(0, 10) + '.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  // ─── Composer ─────────────────────────────────────────────
  function autoGrow() {
    const ta = $('#input');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, window.innerHeight * 0.4) + 'px';
  }

  // ─── Eventi ───────────────────────────────────────────────
  function bind() {
    $('#composer').addEventListener('submit', (e) => {
      e.preventDefault();
      if (busy) P.abort();
      else send($('#input').value);
    });
    $('#input').addEventListener('input', autoGrow);
    $('#micBtn').addEventListener('click', toggleMicrophone);
    $('#attachBtn').addEventListener('click', () => !busy && $('#fileInput').click());
    $('#fileInput').addEventListener('change', async (e) => {
      await addFiles(e.target.files);
      e.target.value = '';
    });
    $('#pendingAtt').addEventListener('click', (e) => {
      const x = e.target.closest('[data-remove-att]');
      if (!x) return;
      pending.splice(Number(x.dataset.removeAtt), 1);
      renderPending();
    });
    $('#input').addEventListener('paste', (e) => {
      const files = Array.from((e.clipboardData && e.clipboardData.files) || []);
      if (files.length) {
        e.preventDefault();
        addFiles(files);
      }
    });
    $('#input').addEventListener('keydown', (e) => {
      // Invio = invia su desktop; su telefono Invio va a capo
      const touch = window.matchMedia('(pointer: coarse)').matches;
      if (e.key === 'Enter' && !e.shiftKey && !touch && !e.isComposing) {
        e.preventDefault();
        $('#composer').requestSubmit();
      }
    });

    $('#menuBtn').addEventListener('click', openDrawer);
    $('#scrim').addEventListener('click', closeDrawer);
    $('#newChatBtn').addEventListener('click', newChat);
    $('#drawerNew').addEventListener('click', newChat);

    $('#convList').addEventListener('click', (e) => {
      const open = e.target.closest('[data-id]');
      const del = e.target.closest('[data-del]');
      if (del) {
        if (!confirm('Eliminare questa conversazione?')) return;
        if (del.dataset.del === currentId) {
          if (busy) P.abort();
          currentId = null;
        }
        conversations = conversations.filter((c) => c.id !== del.dataset.del);
        persistConvs();
        renderConvList();
        renderMessages();
      } else if (open) {
        if (busy) P.abort();
        currentId = open.dataset.id;
        save(LS_CURRENT, currentId);
        renderMessages();
        closeDrawer();
      }
    });

    $('#messages').addEventListener('click', (e) => {
      const chip = e.target.closest('[data-prompt]');
      if (chip) return send(chip.dataset.prompt);
      const cc = e.target.closest('.copy-code');
      if (cc) return copyText(cc.closest('.code').querySelector('code').textContent);
      const act = e.target.closest('[data-act]');
      if (!act) return;
      const index = Number(act.closest('.msg').dataset.index);
      const c = current();
      if (act.dataset.act === 'copy' && c) copyText(c.messages[index].content);
      else if (act.dataset.act === 'retry') retryLast();
      else if (act.dataset.act === 'retry-with') openPicker('retry');
      else if (act.dataset.act === 'read' && c) readAloud(c.messages[index].content);
      else if (act.dataset.act === 'edit') editMessage(index);
    });

    document.addEventListener('click', (e) => {
      if (e.target.closest('[data-open-settings]')) openSettings();
    });
    $('#closeSettings').addEventListener('click', closeSettings);

    $('#modelBtn').addEventListener('click', () => (isConfigured() ? openPicker('switch') : openSettings()));
    $('#closePicker').addEventListener('click', closePicker);
    $('#picker').addEventListener('click', (e) => {
      if (e.target.id === 'picker') return closePicker();
      const un = e.target.closest('[data-unfav]');
      const pk = e.target.closest('[data-pick]');
      if (un) {
        settings.favorites.splice(Number(un.dataset.unfav), 1);
        save(LS_SETTINGS, settings);
        openPicker(pickerMode);
      } else if (pk) {
        const f = settings.favorites[Number(pk.dataset.pick)];
        if (!f) return;
        closePicker();
        if (pickerMode === 'retry') retryLast(f);
        else {
          if (!isConfigured(f)) {
            selectModel(f.provider, f.model);
            toast('Inserisci la chiave per questo provider');
            openSettings();
            return;
          }
          selectModel(f.provider, f.model);
          toast('Modello: ' + modelLabel(f.provider, f.model));
        }
      }
    });
    $('#settings').addEventListener('click', (e) => {
      if (e.target.id === 'settings') closeSettings();
    });
    $('#provider').addEventListener('change', () => {
      // salva i campi del provider precedente prima di cambiare
      const prev = settings.provider;
      settings.profiles[prev] = {
        baseUrl: $('#baseUrl').value.trim(),
        apiKey: $('#apiKey').value.trim(),
        model: $('#model').value.trim()
      };
      settings.provider = $('#provider').value;
      fillProviderFields();
    });
    $('#toggleKey').addEventListener('click', () => {
      const k = $('#apiKey');
      k.type = k.type === 'password' ? 'text' : 'password';
    });
    $('#loadModels').addEventListener('click', loadModels);
    $('#modelSearch').addEventListener('input', renderModelResults);
    $('#onlyFree').addEventListener('change', renderModelResults);
    $('#modelResults').addEventListener('click', (e) => {
      const b = e.target.closest('[data-model]');
      if (!b) return;
      $('#model').value = b.dataset.model;
      saveProviderFields();
      renderModelResults();
      toast('Modello scelto: ' + b.dataset.model);
    });
    $('#temperature').addEventListener('input', (e) => {
      $('#tempValue').textContent = Number(e.target.value).toFixed(1);
    });
    $('#exportBtn').addEventListener('click', exportChats);
    $('#clearAll').addEventListener('click', () => {
      if (!confirm('Eliminare TUTTE le conversazioni? Non si può annullare.')) return;
      if (busy) P.abort();
      conversations = [];
      currentId = null;
      persistConvs();
      renderMessages();
      toast('Chat eliminate');
    });

    window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', applyTheme);

    // Tasto «indietro» Android
    const App = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.App;
    if (App && window.Capacitor.isNativePlatform()) {
      App.addListener('backButton', () => {
        if (!$('#picker').hidden) closePicker();
        else if (!$('#settings').hidden) closeSettings();
        else if ($('#drawer').classList.contains('open')) closeDrawer();
        else if (busy) P.abort();
        else App.exitApp();
      });
    }
  }

  // ─── Avvio ────────────────────────────────────────────────
  function init() {
    const sel = $('#provider');
    Object.entries(P.PRESETS).forEach(([id, p]) => {
      const o = document.createElement('option');
      o.value = id;
      o.textContent = p.label;
      sel.appendChild(o);
    });
    if (!P.PRESETS[settings.provider]) settings.provider = 'gemini';
    if (currentId && !current()) currentId = null;
    applyTheme();
    bind();
    renderMessages();
    if (!isConfigured()) setTimeout(openSettings, 300);
    initSpeechRecognition();
  }


  // ============================================
  // VOICE FEATURES: Microfono e Sintesi Vocale
  // ============================================

  // Speech Recognition (Microfono)
  let recognition = null;
  let isListening = false;

  function initSpeechRecognition() {
    if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
      return false;
    }
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.lang = 'it-IT';
    
    recognition.onstart = function() {
      isListening = true;
      $('#micBtn').classList.add('listening');
      toast('Ascoltando...');
    };
    
    recognition.onend = function() {
      isListening = false;
      $('#micBtn').classList.remove('listening');
    };
    
    recognition.onresult = function(event) {
      const transcript = event.results[0][0].transcript;
      const input = $('#input');
      input.value = (input.value || '') + ' ' + transcript;
      input.focus();
      input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    
    recognition.onerror = function(event) {
      isListening = false;
      $('#micBtn').classList.remove('listening');
      if (event.error !== 'no-speech' && event.error !== 'aborted') {
        toast('Errore microfono: ' + event.error);
      }
    };
    
    return true;
  }

  function toggleMicrophone() {
    if (!recognition) {
      if (!initSpeechRecognition()) {
        toast('Microfono non supportato in questo browser');
        return;
      }
      $('#micBtn').hidden = false;
    }
    
    if (isListening) {
      recognition.stop();
    } else {
      try {
        recognition.start();
      } catch (e) {
        toast('Impossibile avviare il microfono');
      }
    }
  }

  // Speech Synthesis (Lettura vocale)
  function readAloud(text) {
    if (!text) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'it-IT';
    utterance.rate = 1;
    utterance.pitch = 1;
    const voices = window.speechSynthesis.getVoices();
    const italianVoice = voices.find(v => v.lang.startsWith('it')) || 
                         voices.find(v => v.lang.startsWith('en'));
    if (italianVoice) utterance.voice = italianVoice;
    utterance.onerror = function(event) {
      console.error('Errore sintesi vocale:', event);
    };
    window.speechSynthesis.speak(utterance);
  }

  // Carica le voci quando disponibili
  if (window.speechSynthesis) {
    window.speechSynthesis.onvoiceschanged = function() { };
  }

  init();
})();
