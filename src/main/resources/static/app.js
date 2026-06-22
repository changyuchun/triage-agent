const sessionInput = document.querySelector('#sessionId');
const form = document.querySelector('#chatForm');
const messageInput = document.querySelector('#message');
const messages = document.querySelector('#messages');
const sessionsEl = document.querySelector('#sessions');
const memoryStats = document.querySelector('#memoryStats');
const memoryGraph = document.querySelector('#memoryGraph');
const newSessionBtn = document.querySelector('#newSession');

function addMessage(role, text, response) {
  const el = document.createElement('div');
  el.className = `msg ${role}`;
  el.textContent = text || '';
  if (response) {
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.innerHTML = [
      `state=${response.state}`,
      response.route ? `domain=${response.route.domainName}/${response.route.subDomainName}` : null,
      response.waitingUserInput ? 'waiting_user_input=true' : null,
      `evidence=${response.evidence?.length || 0}`
    ].filter(Boolean).map(x => `<span class="pill">${escapeHtml(x)}</span>`).join('');
    el.appendChild(meta);
  }
  messages.appendChild(el);
  messages.scrollTop = messages.scrollHeight;
}

async function sendMessage(text) {
  addMessage('user', text);
  const res = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: sessionInput.value || 'default', message: text })
  });
  const data = await res.json();
  addMessage('assistant', data.answer || data.clarifyQuestion || '无回答', data);
  await refreshAll();
}

async function refreshAll() {
  await Promise.all([loadSessions(), loadMemory()]);
}

async function loadSessions() {
  const res = await fetch('/api/agent/sessions');
  const data = await res.json();
  if (!data.length) {
    sessionsEl.textContent = '暂无会话';
    return;
  }
  sessionsEl.innerHTML = '';
  data.forEach(s => {
    const item = document.createElement('div');
    item.className = 'session-item';
    item.innerHTML = `<strong>${escapeHtml(s.sessionId)}</strong><br><span class="muted">${escapeHtml(s.title || '')}</span>`;
    item.onclick = () => {
      sessionInput.value = s.sessionId;
      loadMemory();
    };
    sessionsEl.appendChild(item);
  });
}

async function loadMemory() {
  const sessionId = encodeURIComponent(sessionInput.value || 'default');
  const res = await fetch(`/api/agent/memory?sessionId=${sessionId}`);
  const graph = await res.json();
  memoryStats.innerHTML = `
    <div class="stat">节点 ${graph.nodes.length}</div>
    <div class="stat">关系 ${graph.edges.length}</div>
  `;
  memoryGraph.innerHTML = '';
  graph.nodes.slice(-18).forEach(node => {
    const el = document.createElement('div');
    el.className = 'node';
    el.innerHTML = `<strong>${escapeHtml(node.type)}</strong> ${escapeHtml(node.label)}`;
    memoryGraph.appendChild(el);
  });
  graph.edges.slice(-12).forEach(edge => {
    const el = document.createElement('div');
    el.className = 'edge';
    el.innerHTML = `<strong>${escapeHtml(edge.type)}</strong><br><span class="muted">${escapeHtml(edge.from)} -> ${escapeHtml(edge.to)}</span>`;
    memoryGraph.appendChild(el);
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = messageInput.value.trim();
  if (!text) return;
  messageInput.value = '';
  await sendMessage(text);
});

newSessionBtn.onclick = () => {
  sessionInput.value = `s${Date.now().toString().slice(-6)}`;
  messages.innerHTML = '';
  loadMemory();
};

messageInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
    form.requestSubmit();
  }
});

refreshAll();
