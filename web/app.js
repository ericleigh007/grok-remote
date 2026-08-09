(() => {
  const TOKEN_KEY = "grok_remote_token";
  const TTS_KEY = "grok_remote_tts";
  const TTS_VOICE_KEY = "grok_remote_tts_voice";

  const $ = (id) => document.getElementById(id);
  const authScreen = $("auth-screen");
  const app = $("app");
  const tokenInput = $("token-input");
  const authBtn = $("auth-btn");
  const authError = $("auth-error");
  const chat = $("chat");
  const input = $("input");
  const sendBtn = $("send-btn");
  const micBtn = $("mic-btn");
  const cancelBtn = $("cancel-btn");
  const newSessionBtn = $("new-session");
  const ttsToggle = $("tts-toggle");
  const ttsVoiceSelect = $("tts-voice");
  const connStatus = $("conn-status");
  const sessionTabs = $("session-tabs");
  const projectSelect = $("project-select");
  const cwdLabel = $("cwd-label");
  const toolLine = $("tool-line");

  /** @type {Map<string, any>} */
  const sessions = new Map();
  let activeSessionId = null;
  let ws = null;
  let token = localStorage.getItem(TOKEN_KEY) || "";
  let ttsOn = localStorage.getItem(TTS_KEY) !== "0";
  let reconnectTimer = null;
  let speaking = false;
  let pendingSpeak = "";

  // QR / deep-link pairing: http://host:8787/?token=...
  // Phone scans QR from PC /pair — token is stored; no manual typing.
  (function ingestTokenFromUrl() {
    try {
      const params = new URLSearchParams(location.search);
      const q =
        params.get("token") ||
        params.get("t") ||
        params.get("server-key") ||
        "";
      if (q) {
        token = q.trim();
        localStorage.setItem(TOKEN_KEY, token);
        // Strip secret from address bar / history
        params.delete("token");
        params.delete("t");
        params.delete("server-key");
        const qs = params.toString();
        const clean = location.pathname + (qs ? "?" + qs : "") + location.hash;
        history.replaceState(null, "", clean);
      }
    } catch (_) { /* ignore */ }
  })();

  // --- Speech recognition (STT) ---
  // Chrome/Android require a secure context (HTTPS or localhost). Plain
  // http://100.x:8787 over Tailscale will not get mic permission — use
  // Tailscale Serve HTTPS (https://<machine>.ts.net/) then re-pair via QR.
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recognition = null;
  let listening = false;
  const secureOk = window.isSecureContext === true;

  function micStatus(msg, isError) {
    if (isError) {
      appendMessageEl("system", msg);
      chat.scrollTop = chat.scrollHeight;
    }
    toolLine.textContent = msg;
    toolLine.classList.toggle("hidden", !msg);
  }

  if (!secureOk) {
    micBtn.disabled = true;
    micBtn.title = "Mic needs HTTPS — open via Tailscale Serve URL, not http://100.x";
    micBtn.setAttribute("aria-disabled", "true");
  } else if (SpeechRecognition) {
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = navigator.language || "en-US";
    recognition.onstart = () => {
      listening = true;
      micBtn.classList.add("listening");
      micStatus("Listening… tap mic again to stop", false);
    };
    recognition.onresult = (ev) => {
      let interim = "";
      let finalText = "";
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const t = ev.results[i][0].transcript;
        if (ev.results[i].isFinal) finalText += t;
        else interim += t;
      }
      if (finalText) {
        input.value = (input.value ? input.value + " " : "") + finalText.trim();
        autosize();
      } else if (interim) {
        input.placeholder = interim;
      }
    };
    recognition.onend = () => {
      listening = false;
      micBtn.classList.remove("listening");
      input.placeholder = "Message Grok… (or use mic)";
      toolLine.classList.add("hidden");
      // Auto-send if we got text via voice
      if (input.value.trim()) {
        sendPrompt();
      }
    };
    recognition.onerror = (ev) => {
      listening = false;
      micBtn.classList.remove("listening");
      const err = (ev && ev.error) || "unknown";
      const hints = {
        "not-allowed": "Mic permission denied — allow microphone for this site in Chrome ⋮ → Site settings.",
        "service-not-allowed": "Speech blocked — site must be HTTPS (use Tailscale Serve URL).",
        "network": "Speech service needs network (Google STT). Check data/Wi‑Fi.",
        "no-speech": "No speech heard — try again closer to the mic.",
        "aborted": "",
        "audio-capture": "No microphone available.",
      };
      const msg = hints[err] || ("Mic error: " + err);
      if (msg) micStatus(msg, true);
    };
  } else {
    micBtn.disabled = true;
    micBtn.title = "Speech recognition not supported — use Chrome on Android";
  }

  function setTtsUi() {
    ttsToggle.textContent = ttsOn ? "TTS on" : "TTS off";
    if (ttsVoiceSelect) ttsVoiceSelect.disabled = !ttsOn || !window.speechSynthesis;
  }
  setTtsUi();

  /** Score voices so Edge/Chrome pick something less awful than the default robot. */
  function scoreVoice(v) {
    const name = (v.name || "").toLowerCase();
    const lang = (v.lang || "").toLowerCase();
    const pref = (navigator.language || "en-US").toLowerCase();
    let s = 0;
    // Language match
    if (lang === pref) s += 50;
    else if (lang.startsWith(pref.split("-")[0])) s += 30;
    else if (lang.startsWith("en")) s += 15;
    else s -= 20;
    // Prefer neural / natural / online Microsoft voices (Edge)
    if (/natural|neural|online|premium|enhanced|wavenet|journey|studio/.test(name)) s += 40;
    if (/microsoft/.test(name) && /online/.test(name)) s += 25;
    // Known decent Edge/Windows names
    if (/aria|guy|jenny|ryan|sonia|natasha|davis|tony|sara|michelle|andrew|emma|brian/.test(name)) s += 20;
    // Penalize bad defaults
    if (/compact|mobile|espeak|robot|silent|dummy/.test(name)) s -= 40;
    if (/microsoft david|microsoft zira|microsoft mark|microsoft hazel/.test(name)) s -= 15; // older desktop
    if (v.localService === false) s += 10; // cloud/online often better quality
    if (v.default) s += 2;
    return s;
  }

  function listVoices() {
    if (!window.speechSynthesis) return [];
    return speechSynthesis.getVoices() || [];
  }

  function pickBestVoice(voices) {
    if (!voices.length) return null;
    const saved = localStorage.getItem(TTS_VOICE_KEY);
    if (saved) {
      const hit = voices.find((v) => v.voiceURI === saved || v.name === saved);
      if (hit) return hit;
    }
    return [...voices].sort((a, b) => scoreVoice(b) - scoreVoice(a))[0];
  }

  function populateVoiceSelect() {
    if (!ttsVoiceSelect || !window.speechSynthesis) return;
    const voices = listVoices();
    const prev = ttsVoiceSelect.value || localStorage.getItem(TTS_VOICE_KEY) || "";
    ttsVoiceSelect.innerHTML = "";
    if (!voices.length) {
      const o = document.createElement("option");
      o.value = "";
      o.textContent = "No voices yet";
      ttsVoiceSelect.appendChild(o);
      return;
    }
    const sorted = [...voices].sort((a, b) => scoreVoice(b) - scoreVoice(a));
    for (const v of sorted) {
      const o = document.createElement("option");
      o.value = v.voiceURI || v.name;
      const tag = v.localService === false ? "cloud" : "local";
      o.textContent = `${v.name} (${v.lang}, ${tag})`;
      ttsVoiceSelect.appendChild(o);
    }
    const best = pickBestVoice(voices);
    const want = prev || (best && (best.voiceURI || best.name)) || "";
    if (want && [...ttsVoiceSelect.options].some((o) => o.value === want)) {
      ttsVoiceSelect.value = want;
    } else if (best) {
      ttsVoiceSelect.value = best.voiceURI || best.name;
    }
    if (ttsVoiceSelect.value) {
      localStorage.setItem(TTS_VOICE_KEY, ttsVoiceSelect.value);
    }
  }

  function currentVoice() {
    const voices = listVoices();
    if (!voices.length) return null;
    const key = (ttsVoiceSelect && ttsVoiceSelect.value) || localStorage.getItem(TTS_VOICE_KEY);
    if (key) {
      const hit = voices.find((v) => v.voiceURI === key || v.name === key);
      if (hit) return hit;
    }
    return pickBestVoice(voices);
  }

  if (window.speechSynthesis) {
    populateVoiceSelect();
    // Edge/Chrome load voices asynchronously
    speechSynthesis.addEventListener("voiceschanged", populateVoiceSelect);
    // Some Edge builds only populate after a tick
    setTimeout(populateVoiceSelect, 250);
    setTimeout(populateVoiceSelect, 1000);
  }

  function speak(text) {
    if (!ttsOn || !text || !window.speechSynthesis) return;
    const clean = text.replace(/```[\s\S]*?```/g, " code block ").trim();
    if (!clean) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(clean.slice(0, 1200));
    const voice = currentVoice();
    if (voice) {
      u.voice = voice;
      u.lang = voice.lang || navigator.language || "en-US";
    } else {
      u.lang = navigator.language || "en-US";
    }
    // Slightly slower than before — Edge defaults sound better less rushed
    u.rate = 1.0;
    u.pitch = 1.0;
    speaking = true;
    u.onend = () => { speaking = false; };
    u.onerror = () => { speaking = false; };
    window.speechSynthesis.speak(u);
  }

  function setConn(state, label) {
    connStatus.textContent = label;
    connStatus.className = "pill " + state;
  }

  function showApp() {
    authScreen.classList.add("hidden");
    app.classList.remove("hidden");
  }

  function showAuth(err) {
    app.classList.add("hidden");
    authScreen.classList.remove("hidden");
    if (err) {
      authError.textContent = err;
      authError.classList.remove("hidden");
    }
  }

  function autosize() {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 140) + "px";
  }

  function getActive() {
    return activeSessionId ? sessions.get(activeSessionId) : null;
  }

  function renderTabs() {
    sessionTabs.innerHTML = "";
    for (const s of sessions.values()) {
      const btn = document.createElement("button");
      btn.className = "tab" + (s.sessionId === activeSessionId ? " active" : "");
      btn.innerHTML = (s.busy ? '<span class="busy-dot"></span>' : "") + escapeHtml(s.title || "Session");
      btn.onclick = () => {
        activeSessionId = s.sessionId;
        renderTabs();
        renderChat();
        updateMeta();
      };
      sessionTabs.appendChild(btn);
    }
  }

  function renderChat() {
    const s = getActive();
    chat.innerHTML = "";
    if (!s) {
      chat.innerHTML = '<div class="msg system">No session yet. Tap + Session.</div>';
      return;
    }
    for (const m of s.messages || []) {
      appendMessageEl(m.role, m.text, m.streaming);
    }
    chat.scrollTop = chat.scrollHeight;
  }

  function sanitizeHtml(html) {
    try {
      if (typeof DOMPurify !== "undefined" && DOMPurify.sanitize) {
        return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
      }
    } catch (_) { /* ignore */ }
    // Minimal tag allowlist if purify missing
    return String(html).replace(/<(?!\/?(?:p|br|strong|em|b|i|code|pre|h[1-6]|ul|ol|li|blockquote|a|hr|table|thead|tbody|tr|th|td|del|span)(?:\s|>|\/))/gi, "&lt;");
  }

  /** Built-in GFM-ish renderer — always works even if marked.js fails to load. */
  function simpleMarkdown2(src) {
    let s = String(src || "").replace(/\r\n/g, "\n");
    const store = [];
    const save = (html) => {
      store.push(html);
      return `\uE000${store.length - 1}\uE001`;
    };

    // Fenced code
    s = s.replace(/```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```/g, (_, lang, code) =>
      save(
        `<pre><code class="language-${escapeHtml(lang || "")}">${escapeHtml(
          code.replace(/\n$/, "")
        )}</code></pre>`
      )
    );
    // Inline code
    s = s.replace(/`([^`\n]+)`/g, (_, code) => save(`<code>${escapeHtml(code)}</code>`));

    const lines = s.split("\n");
    const html = [];
    let i = 0;
    let listKind = null;

    const closeList = () => {
      if (listKind) {
        html.push(`</${listKind}>`);
        listKind = null;
      }
    };

    const formatInline = (t) => {
      // Escape HTML first, but keep our placeholders intact
      const parts = [];
      t = t.replace(/\uE000(\d+)\uE001/g, (_, n) => {
        parts.push(store[Number(n)]);
        return `\uE000${parts.length - 1}\uE001`;
      });
      // placeholders now index into parts temporarily — actually store global store is fine
      t = escapeHtml(t.replace(/\uE000(\d+)\uE001/g, "\uE000$1\uE001"));
      // escapeHtml may leave private-use chars alone
      t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
      t = t.replace(/__([^_]+)__/g, "<strong>$1</strong>");
      t = t.replace(/\*([^*\n]+)\*/g, "<em>$1</em>");
      t = t.replace(/_([^_\n]+)_/g, "<em>$1</em>");
      t = t.replace(/~~([^~\n]+)~~/g, "<del>$1</del>");
      t = t.replace(
        /\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
      );
      t = t.replace(/\uE000(\d+)\uE001/g, (_, n) => store[Number(n)] || "");
      return t;
    };

    while (i < lines.length) {
      const line = lines[i];
      // Token-only line (code block)
      if (/^\uE000\d+\uE001$/.test(line.trim()) && store[Number((line.match(/\d/) || [])[0])]) {
        closeList();
        html.push(line.trim().replace(/\uE000(\d+)\uE001/g, (_, n) => store[Number(n)]));
        i++;
        continue;
      }
      if (/^\s*$/.test(line)) {
        closeList();
        i++;
        continue;
      }
      const h = /^(#{1,6})\s+(.+)$/.exec(line);
      if (h) {
        closeList();
        const level = h[1].length;
        html.push(`<h${level}>${formatInline(h[2])}</h${level}>`);
        i++;
        continue;
      }
      if (/^---+$/.test(line.trim()) || /^\*\*\*+$/.test(line.trim())) {
        closeList();
        html.push("<hr>");
        i++;
        continue;
      }
      const bq = /^>\s?(.*)$/.exec(line);
      if (bq) {
        closeList();
        const chunks = [bq[1]];
        i++;
        while (i < lines.length) {
          const m = /^>\s?(.*)$/.exec(lines[i]);
          if (!m) break;
          chunks.push(m[1]);
          i++;
        }
        html.push(`<blockquote><p>${formatInline(chunks.join(" "))}</p></blockquote>`);
        continue;
      }
      const ul = /^[-*+]\s+(.+)$/.exec(line);
      if (ul) {
        if (listKind !== "ul") {
          closeList();
          listKind = "ul";
          html.push("<ul>");
        }
        html.push(`<li>${formatInline(ul[1])}</li>`);
        i++;
        continue;
      }
      const ol = /^\d+\.\s+(.+)$/.exec(line);
      if (ol) {
        if (listKind !== "ol") {
          closeList();
          listKind = "ol";
          html.push("<ol>");
        }
        html.push(`<li>${formatInline(ol[1])}</li>`);
        i++;
        continue;
      }
      // Paragraph: gather until blank
      closeList();
      const p = [line];
      i++;
      while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^(#{1,6})\s+/.test(lines[i]) && !/^[-*+]\s+/.test(lines[i]) && !/^\d+\.\s+/.test(lines[i]) && !/^>\s?/.test(lines[i]) && !/^```/.test(lines[i])) {
        if (/^\uE000\d+\uE001$/.test(lines[i].trim())) break;
        p.push(lines[i]);
        i++;
      }
      html.push(`<p>${formatInline(p.join("\n")).replace(/\n/g, "<br>")}</p>`);
    }
    closeList();
    return html.join("\n");
  }

  function renderMarkdown(text) {
    const src = text || "";
    // Prefer marked when present
    try {
      const lib =
        (typeof marked !== "undefined" && marked) ||
        (typeof window !== "undefined" && window.marked) ||
        null;
      const parse =
        lib &&
        (typeof lib.parse === "function"
          ? lib.parse.bind(lib)
          : typeof lib === "function"
            ? lib
            : null);
      if (parse) {
        const rawHtml = parse(src, { async: false, gfm: true, breaks: true });
        return sanitizeHtml(typeof rawHtml === "string" ? rawHtml : String(rawHtml));
      }
    } catch (e) {
      console.warn("marked failed, using built-in renderer", e);
    }
    return sanitizeHtml(simpleMarkdown2(src));
  }

  function appendMessageEl(role, text, streaming) {
    const el = document.createElement("div");
    el.className = "msg " + role + (streaming ? " streaming" : "");
    if (role === "tool") {
      el.textContent = text;
    } else if (role === "system") {
      el.textContent = text || "";
    } else {
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = role === "user" ? "You" : role === "assistant" ? "Grok" : role;
      el.appendChild(label);
      const body = document.createElement("div");
      body.className = "md";
      body.innerHTML = renderMarkdown(text || "");
      el.appendChild(body);
    }
    chat.appendChild(el);
    return el;
  }

  function updateMeta() {
    const s = getActive();
    cwdLabel.textContent = s ? s.cwd : "";
    cancelBtn.classList.toggle("hidden", !(s && s.busy));
    sendBtn.disabled = !!(s && s.busy);
  }

  function ensureSessionLocal(data) {
    const id = data.sessionId;
    if (!sessions.has(id)) {
      sessions.set(id, {
        sessionId: id,
        cwd: data.cwd || "",
        title: data.title || "Session",
        busy: !!data.busy,
        messages: data.messages || [],
      });
    } else {
      const s = sessions.get(id);
      Object.assign(s, {
        cwd: data.cwd ?? s.cwd,
        title: data.title ?? s.title,
        busy: data.busy ?? s.busy,
      });
      if (data.messages) s.messages = data.messages;
    }
    return sessions.get(id);
  }

  function connect() {
    if (!token) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url = `${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`;
    setConn("warn", "connecting…");
    ws = new WebSocket(url);

    ws.onopen = () => {
      setConn("ok", "online");
      showApp();
    };

    ws.onclose = (ev) => {
      setConn("err", "offline");
      if (ev.code === 4401) {
        localStorage.removeItem(TOKEN_KEY);
        token = "";
        showAuth("Invalid token");
        return;
      }
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(connect, 1500);
    };

    ws.onerror = () => setConn("err", "error");

    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      handleEvent(msg);
    };
  }

  function handleEvent(msg) {
    switch (msg.type) {
      case "hello": {
        sessions.clear();
        (msg.sessions || []).forEach((s) => ensureSessionLocal(s));
        if (!activeSessionId && sessions.size) {
          activeSessionId = [...sessions.keys()][0];
        }
        fillProjects(msg.projects || [], msg.default_cwd);
        renderTabs();
        renderChat();
        updateMeta();
        break;
      }
      case "session_created":
      case "session_loaded": {
        ensureSessionLocal(msg);
        if (!activeSessionId || msg.type === "session_loaded") {
          // Prefer a resumed ongoing project when it appears
          activeSessionId = msg.sessionId;
        }
        renderTabs();
        renderChat();
        updateMeta();
        break;
      }
      case "user_message": {
        const s = ensureSessionLocal({ sessionId: msg.sessionId });
        s.busy = true;
        // Avoid dup if we already appended optimistically
        const last = s.messages[s.messages.length - 1];
        if (!(last && last.role === "user" && last.text === msg.text)) {
          s.messages.push({ role: "user", text: msg.text });
        }
        if (msg.sessionId === activeSessionId) {
          renderChat();
          updateMeta();
        } else {
          renderTabs();
        }
        break;
      }
      case "session_update": {
        const s = ensureSessionLocal({ sessionId: msg.sessionId });
        s.busy = true;
        if (msg.updateType === "agent_message_chunk" && msg.text) {
          const last = s.messages[s.messages.length - 1];
          if (last && last.role === "assistant" && last.streaming) {
            last.text += msg.text;
          } else {
            s.messages.push({ role: "assistant", text: msg.text, streaming: true });
          }
          pendingSpeak = (s.messages[s.messages.length - 1] || {}).text || "";
          if (msg.sessionId === activeSessionId) {
            // Efficient update: re-render active only
            renderChat();
          }
        } else if (msg.updateType === "tool_call" && msg.tool) {
          const title = msg.tool.title || msg.tool.kind || "tool";
          toolLine.textContent = `⚙ ${title}…`;
          toolLine.classList.remove("hidden");
          if (msg.sessionId === activeSessionId) {
            appendMessageEl("tool", `⚙ ${title}`);
            chat.scrollTop = chat.scrollHeight;
          }
        } else if (msg.updateType === "tool_call_update" && msg.tool) {
          if (msg.tool.status === "completed" || msg.tool.status === "failed") {
            // keep line until turn complete
          }
        } else if (msg.updateType === "agent_thought_chunk") {
          // optional: ignore or show subtle thinking
        }
        if (msg.sessionId === activeSessionId) updateMeta();
        else renderTabs();
        break;
      }
      case "turn_complete": {
        const s = sessions.get(msg.sessionId);
        if (s) {
          s.busy = false;
          const last = s.messages[s.messages.length - 1];
          if (last && last.role === "assistant") last.streaming = false;
          if (msg.sessionId === activeSessionId) {
            toolLine.classList.add("hidden");
            renderChat();
            updateMeta();
            if (pendingSpeak) {
              speak(pendingSpeak);
              pendingSpeak = "";
            }
          } else {
            renderTabs();
          }
        }
        break;
      }
      case "error": {
        if (msg.sessionId && sessions.has(msg.sessionId)) {
          sessions.get(msg.sessionId).busy = false;
        }
        if (!activeSessionId || msg.sessionId === activeSessionId) {
          appendMessageEl("system", "Error: " + (msg.message || "unknown"));
          updateMeta();
        }
        break;
      }
      default:
        break;
    }
  }

  function fillProjects(projects, defaultCwd) {
    projectSelect.innerHTML = "";
    const list = projects.length ? projects : [{ name: "default", cwd: defaultCwd || "" }];
    for (const p of list) {
      const opt = document.createElement("option");
      opt.value = p.cwd;
      opt.textContent = p.name || p.cwd;
      projectSelect.appendChild(opt);
    }
  }

  function sendPrompt() {
    const text = input.value.trim();
    const s = getActive();
    if (!text || !s || !ws || ws.readyState !== WebSocket.OPEN || s.busy) return;
    // optimistic UI
    s.messages.push({ role: "user", text });
    s.busy = true;
    renderChat();
    updateMeta();
    input.value = "";
    autosize();
    window.speechSynthesis?.cancel();
    ws.send(JSON.stringify({ type: "prompt", sessionId: s.sessionId, text }));
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // --- UI events ---
  authBtn.onclick = () => {
    token = tokenInput.value.trim();
    if (!token) {
      authError.textContent = "Token required";
      authError.classList.remove("hidden");
      return;
    }
    localStorage.setItem(TOKEN_KEY, token);
    authError.classList.add("hidden");
    connect();
  };

  tokenInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") authBtn.click();
  });

  sendBtn.onclick = sendPrompt;
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendPrompt();
    }
  });
  input.addEventListener("input", autosize);

  cancelBtn.onclick = () => {
    const s = getActive();
    if (s && ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "cancel", sessionId: s.sessionId }));
    }
  };

  newSessionBtn.onclick = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const cwd = projectSelect.value;
    const title = projectSelect.selectedOptions[0]?.textContent || "Session";
    ws.send(JSON.stringify({ type: "new_session", cwd, title }));
  };

  ttsToggle.onclick = () => {
    ttsOn = !ttsOn;
    localStorage.setItem(TTS_KEY, ttsOn ? "1" : "0");
    setTtsUi();
    if (!ttsOn) window.speechSynthesis?.cancel();
  };

  if (ttsVoiceSelect) {
    ttsVoiceSelect.addEventListener("change", () => {
      if (ttsVoiceSelect.value) {
        localStorage.setItem(TTS_VOICE_KEY, ttsVoiceSelect.value);
      }
      // Quick sample so you hear the change immediately
      if (ttsOn && window.speechSynthesis) {
        speak("Voice selected.");
      }
    });
  }

  micBtn.onclick = () => {
    if (!secureOk) {
      micStatus(
        "Mic needs HTTPS. On the PC run: tailscale serve --bg http://127.0.0.1:8787 — then re-scan QR from http://127.0.0.1:8787/pair",
        true
      );
      return;
    }
    if (!recognition) {
      micStatus("Speech recognition not available in this browser. Use Chrome.", true);
      return;
    }
    if (listening) {
      try { recognition.stop(); } catch (_) {}
      return;
    }
    try {
      window.speechSynthesis?.cancel();
      recognition.start(); // toggle: tap once to start, tap again to stop
    } catch (e) {
      listening = false;
      micBtn.classList.remove("listening");
      micStatus("Could not start mic: " + (e && e.message ? e.message : e), true);
    }
  };

  // Boot
  if (token) {
    tokenInput.value = token;
    connect();
    if (!secureOk) {
      // Defer until chat is visible
      setTimeout(() => {
        micStatus(
          "Note: this page is not HTTPS — mic/STT disabled. Re-pair via PC /pair after enabling Tailscale Serve.",
          true
        );
      }, 800);
    }
  } else {
    showAuth();
  }
})();
