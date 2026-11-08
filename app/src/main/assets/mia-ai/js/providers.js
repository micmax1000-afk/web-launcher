/* Mia AI — provider AI (Gemini + qualsiasi API compatibile OpenAI) */
(function (global) {
  'use strict';

  /**
   * Preset: type 'gemini' usa l'API Google; type 'openai' usa /chat/completions.
   * Il modello è sempre modificabile in Impostazioni (pulsante «Carica modelli»).
   */
  const PRESETS = {
    gemini: {
      label: 'Google Gemini',
      short: 'Gemini',
      type: 'gemini',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
      model: 'gemini-3.5-flash',
      needsKey: true,
      keyUrl: 'https://aistudio.google.com/apikey'
    },
    openrouter: {
      label: 'OpenRouter',
      short: 'OpenRouter',
      type: 'openai',
      baseUrl: 'https://openrouter.ai/api/v1',
      model: '',
      needsKey: true,
      keyUrl: 'https://openrouter.ai/keys',
      hint:
        'Chiave gratuita su openrouter.ai/keys. I modelli gratuiti finiscono con «:free»: ' +
        'premi «Carica modelli» e li trovi in cima all\'elenco.'
    },
    groq: {
      label: 'Groq',
      short: 'Groq',
      type: 'openai',
      baseUrl: 'https://api.groq.com/openai/v1',
      model: '',
      needsKey: true,
      keyUrl: 'https://console.groq.com/keys'
    },
    custom: {
      label: 'Personalizzato (compatibile OpenAI)',
      short: 'Custom',
      type: 'openai',
      baseUrl: 'http://localhost:3000/v1',
      model: '',
      needsKey: false,
      hint: 'Qualsiasi server con API /v1/chat/completions (es. LM Studio, vLLM, un tuo proxy).'
    }
  };

  let controller = null;

  function trimSlash(u) {
    return String(u || '').trim().replace(/\/+$/, '');
  }

  function friendlyError(e, providerLabel) {
    const m = String((e && e.message) || e || '');
    const who = providerLabel || 'Provider';
    if (e && e.attachmentError) return m;
    if (/image|vision|multimodal|image_url|inline_?data|mime|pdf|file/i.test(m) && /support|invalid|not|unsupported|allowed/i.test(m))
      return who + ': questo modello non accetta questo tipo di allegato. Usa «Prova con…» e scegli un modello che legge immagini/PDF (es. Gemini).';
    if (/402|insufficient|credit|payment required/i.test(m))
      return (
        who +
        ': crediti esauriti. Il modello scelto è a pagamento oppure la chiave ha un limite di spesa. ' +
        'Su OpenRouter scegli un modello che finisce con «:free» (Impostazioni → Carica modelli → Solo gratis).'
      );
    if (/401|403|api key|unauthori[sz]ed|permission/i.test(m)) return who + ': chiave API non valida o non autorizzata.';
    if (/per-day|per day|daily/i.test(m) && /429|limit|quota/i.test(m))
      return who + ': limite giornaliero gratuito raggiunto. Usa un altro provider (es. Gemini) o riprova domani.';
    if (/429|quota|rate|overload|capacity|503|502/i.test(m))
      return who + ': modello occupato o limite di richieste raggiunto. Riprova tra poco o usa «Prova con…».';
    if (/Failed to fetch|NetworkError|Load failed|network/i.test(m))
      return who + ': server non raggiungibile (rete, indirizzo errato o CORS non abilitato).';
    if (/404|not found/i.test(m)) return who + ': modello o indirizzo non trovato. Controlla le Impostazioni.';
    return m.length > 300 ? m.slice(0, 300) + '…' : m || 'Errore sconosciuto.';
  }

  async function readError(res, fallback) {
    let msg = fallback + ' (' + res.status + ')';
    try {
      const d = await res.json();
      msg = (d.error && (d.error.message || d.error)) || d.message || msg;
      if (typeof msg !== 'string') msg = JSON.stringify(msg);
      msg += ' (' + res.status + ')';
    } catch (_) {}
    return new Error(msg);
  }

  /** Legge uno stream SSE e chiama onData(payloadString) per ogni riga "data:". */
  async function readSSE(res, onData) {
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        const t = line.trim();
        if (!t.startsWith('data:')) continue;
        const payload = t.slice(5).trim();
        if (!payload || payload === '[DONE]') continue;
        onData(payload);
      }
    }
    const rest = buffer.trim();
    if (rest.startsWith('data:')) {
      const payload = rest.slice(5).trim();
      if (payload && payload !== '[DONE]') onData(payload);
    }
  }

  // ─── Allegati ─────────────────────────────────────────────
  // attachment: { kind: 'image'|'pdf'|'text', name, mime, data (base64) | text, dropped? }
  function attachmentError(msg) {
    const e = new Error(msg);
    e.attachmentError = true;
    return e;
  }

  /** Testo del messaggio + contenuto dei file di testo allegati. */
  function textWithFiles(m) {
    let t = m.content || '';
    (m.attachments || []).forEach((a) => {
      if (a.kind === 'text') t += '\n\n--- File allegato: ' + a.name + ' ---\n' + a.text + '\n--- Fine file ---';
      else if (a.dropped) t += '\n\n[Allegato «' + a.name + '» non più disponibile]';
    });
    return t.trim() || '(allegato)';
  }

  function binaries(m) {
    return (m.attachments || []).filter((a) => (a.kind === 'image' || a.kind === 'pdf') && a.data && !a.dropped);
  }

  // ─── Gemini ───────────────────────────────────────────────
  async function streamGemini(cfg, messages, onText, signal) {
    const contents = messages
      .filter((m) => m.role !== 'system')
      .map((m) => {
        const parts = [{ text: textWithFiles(m) }];
        binaries(m).forEach((a) => parts.push({ inlineData: { mimeType: a.mime, data: a.data } }));
        return { role: m.role === 'user' ? 'user' : 'model', parts };
      });
    const body = {
      contents,
      generationConfig: { temperature: cfg.temperature, maxOutputTokens: 8192 }
    };
    if (cfg.systemPrompt) body.systemInstruction = { parts: [{ text: cfg.systemPrompt }] };

    const url =
      trimSlash(cfg.baseUrl) + '/models/' + encodeURIComponent(cfg.model) + ':streamGenerateContent?alt=sse';
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': cfg.apiKey },
      body: JSON.stringify(body),
      signal
    });
    if (!res.ok) throw await readError(res, 'Gemini');

    let full = '';
    await readSSE(res, (payload) => {
      try {
        const json = JSON.parse(payload);
        if (json.error) throw new Error(json.error.message || 'Errore Gemini');
        const parts = (json.candidates && json.candidates[0] && json.candidates[0].content && json.candidates[0].content.parts) || [];
        for (const p of parts) {
          if (p.text && !p.thought) {
            full += p.text;
            onText(full);
          }
        }
      } catch (e) {
        if (e instanceof SyntaxError) return;
        throw e;
      }
    });
    return full;
  }

  async function listGeminiModels(cfg) {
    const res = await fetch(trimSlash(cfg.baseUrl) + '/models?pageSize=200', {
      headers: { 'x-goog-api-key': cfg.apiKey }
    });
    if (!res.ok) throw await readError(res, 'Gemini');
    const d = await res.json();
    return (d.models || [])
      .filter((m) => (m.supportedGenerationMethods || []).includes('generateContent'))
      .filter((m) => !/embed|aqa|imagen|veo|tts|native-audio/i.test(m.name))
      .map((m) => ({
        id: String(m.name).replace(/^models\//, ''),
        name: m.displayName || '',
        free: null,
        vision: true
      }));
  }

  // ─── Compatibile OpenAI ───────────────────────────────────
  function openaiHeaders(cfg) {
    const h = { 'Content-Type': 'application/json' };
    if (cfg.apiKey) h.Authorization = 'Bearer ' + cfg.apiKey;
    if (/openrouter\.ai/.test(cfg.baseUrl)) h['X-Title'] = 'Mia AI';
    return h;
  }

  async function streamOpenAI(cfg, messages, onText, signal) {
    const msgs = [];
    if (cfg.systemPrompt) msgs.push({ role: 'system', content: cfg.systemPrompt });
    const isOpenRouter = /openrouter\.ai/.test(cfg.baseUrl);
    messages.forEach((m) => {
      const bins = binaries(m);
      if (!bins.length) return msgs.push({ role: m.role, content: textWithFiles(m) });
      const content = [{ type: 'text', text: textWithFiles(m) }];
      bins.forEach((a) => {
        const url = 'data:' + a.mime + ';base64,' + a.data;
        if (a.kind === 'image') content.push({ type: 'image_url', image_url: { url } });
        else if (isOpenRouter) content.push({ type: 'file', file: { filename: a.name, file_data: url } });
        else throw attachmentError('I PDF funzionano solo con Gemini e OpenRouter. Usa «Prova con…» per cambiare modello.');
      });
      msgs.push({ role: m.role, content });
    });

    const res = await fetch(trimSlash(cfg.baseUrl) + '/chat/completions', {
      method: 'POST',
      headers: openaiHeaders(cfg),
      body: JSON.stringify({ model: cfg.model, messages: msgs, temperature: cfg.temperature, stream: true }),
      signal
    });
    if (!res.ok) throw await readError(res, 'API');

    // Alcuni server ignorano stream:true e rispondono con JSON normale
    const ctype = res.headers.get('content-type') || '';
    if (ctype.includes('application/json')) {
      const d = await res.json();
      const text = (d.choices && d.choices[0] && d.choices[0].message && d.choices[0].message.content) || '';
      onText(text);
      return text;
    }

    let full = '';
    await readSSE(res, (payload) => {
      try {
        const json = JSON.parse(payload);
        if (json.error) throw new Error(json.error.message || 'Errore API');
        const delta = json.choices && json.choices[0] && json.choices[0].delta;
        if (delta && delta.content) {
          full += delta.content;
          onText(full);
        }
      } catch (e) {
        if (e instanceof SyntaxError) return;
        throw e;
      }
    });
    return full;
  }

  async function listOpenAIModels(cfg) {
    const res = await fetch(trimSlash(cfg.baseUrl) + '/models', { headers: openaiHeaders(cfg) });
    if (!res.ok) throw await readError(res, 'API');
    const d = await res.json();
    return (d.data || d.models || [])
      .map((m) => {
        const id = m.id || m.name;
        const pr = m.pricing;
        const free = /:free$/.test(id) || (pr ? Number(pr.prompt) === 0 && Number(pr.completion) === 0 : null);
        const arch = m.architecture || {};
        const out = arch.output_modalities;
        const inp = arch.input_modalities || [];
        return {
          id,
          name: m.name && m.name !== id ? m.name : '',
          free: pr || /:free$/.test(id) ? free : null,
          vision: inp.includes('image') || /vision|-vl\b|vl-|omni/i.test(id) || null,
          // solo modelli che rispondono con testo (no embedding, rerank, moderazione, audio, immagini)
          chat: !(Array.isArray(out) && !out.includes('text')) && !/embed|rerank|guard|content-safety|moderation|whisper|tts/i.test(id)
        };
      })
      .filter((m) => m.id && m.chat);
  }

  // ─── API pubblica ─────────────────────────────────────────
  /**
   * cfg: { provider, baseUrl, apiKey, model, systemPrompt, temperature }
   * messages: [{ role: 'user'|'assistant', content }]
   * onText(fullTextSoFar) chiamata a ogni pezzo ricevuto.
   */
  async function chat(cfg, messages, onText) {
    const preset = PRESETS[cfg.provider] || PRESETS.custom;
    if (preset.needsKey && !cfg.apiKey) throw new Error('Inserisci la chiave API in Impostazioni.');
    if (!cfg.model) throw new Error('Scegli un modello in Impostazioni.');
    abort();
    controller = new AbortController();
    const signal = controller.signal;
    try {
      const fn = preset.type === 'gemini' ? streamGemini : streamOpenAI;
      const cb = onText || function () {};
      const text = cleanText(await fn(cfg, messages, (full) => cb(cleanText(full)), signal));
      if (!text.trim()) throw new Error('Risposta vuota dal modello.');
      return text;
    } finally {
      if (controller && controller.signal === signal) controller = null;
    }
  }

  async function listModels(cfg) {
    const preset = PRESETS[cfg.provider] || PRESETS.custom;
    const list = preset.type === 'gemini' ? await listGeminiModels(cfg) : await listOpenAIModels(cfg);
    // gratuiti in cima, poi in ordine alfabetico
    return list.sort((a, b) => (b.free === true) - (a.free === true) || a.id.localeCompare(b.id));
  }

  function abort() {
    if (controller) {
      try {
        controller.abort();
      } catch (_) {}
      controller = null;
    }
  }

  /**
   * Pulisce la risposta: toglie il «ragionamento» (<think>…</think>) che alcuni modelli
   * gratuiti mettono nel testo e i token tecnici di fine turno (<|im_end|>, <end_of_turn>…).
   */
  function cleanText(t) {
    let s = String(t || '');
    s = s.replace(/<(think|thinking|reasoning)>[\s\S]*?<\/\1>/gi, '');
    s = s.replace(/^\s*<(think|thinking|reasoning)>[\s\S]*$/i, ''); // ragionamento non ancora chiuso
    s = s.replace(/<\|[a-z_]+\|>|<\/?(start_of_turn|end_of_turn|eos|bos|s)>/gi, '');
    return s.replace(/^\s+/, '');
  }

  /** Modelli specializzati (codice, matematica…) da non usare come ripiego automatico. */
  function isSpecialized(model) {
    return /cod(e|er|ing)|math|embed|rerank|guard|safety|ocr|vision-only/i.test(String(model || ''));
  }

  /** Errori per cui ha senso riprovare subito con un altro modello. */
  function isBusyError(e) {
    const m = String((e && e.message) || e || '');
    return !(e && e.attachmentError) && /429|quota|rate.?limit|overload|capacity|unavailable|50[234]|no endpoints/i.test(m);
  }

  global.MiaProviders = { PRESETS, chat, listModels, abort, friendlyError, isBusyError, cleanText, isSpecialized };
})(window);
