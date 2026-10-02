const $ = (id) => document.getElementById(id);

const launchUrl = new URL(window.location.href);
const scannedToken = (launchUrl.searchParams.get('token') || '').trim();
let token = scannedToken || sessionStorage.getItem('miracastToken') || '';
let latestStatus = null;
let latestConfig = null;

if (scannedToken) {
  sessionStorage.setItem('miracastToken', scannedToken);
  launchUrl.searchParams.delete('token');
  const clean = `${launchUrl.pathname}${launchUrl.search}${launchUrl.hash}` || '/';
  history.replaceState(null, '', clean);
}

function apiHeaders() {
  const h = {'Content-Type': 'application/json'};
  if (token) h['X-API-Token'] = token;
  return h;
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: {...apiHeaders(), ...(options.headers || {})},
    cache: 'no-store'
  });
  if (res.status === 401) {
    showLogin();
    throw new Error('unauthorized');
  }
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = {raw: text}; }
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function showLogin(message = '') {
  $('login').classList.remove('hidden');
  $('loginError').textContent = message;
  $('tokenInput').value = token;
  setTimeout(() => $('tokenInput').focus(), 30);
}

function hideLogin() { $('login').classList.add('hidden'); }

function toast(message) {
  $('toast').textContent = message;
  $('toast').classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => $('toast').classList.add('hidden'), 2800);
}

function boolText(v) { return v ? '已启用' : '已关闭'; }

function fmtTime(ms) {
  ms = Math.max(0, Number(ms) || 0);
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
    : `${m}:${String(sec).padStart(2, '0')}`;
}

function fmtDuration(ms) {
  const s = Math.floor((Number(ms) || 0) / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const h = Math.floor(m / 60);
  return h ? `${h}h ${m % 60}m` : `${m}m ${s % 60}s`;
}

function hostForUrl() {
  return location.hostname.includes(':') ? `[${location.hostname}]` : location.hostname;
}

function currentDirectUrl() {
  const ip = latestStatus?.device?.ip || location.hostname;
  const activePort = Number(latestStatus?.webUi?.activePort || location.port || 80);
  const base = `${location.protocol}//${ip.includes(':') ? `[${ip}]` : ip}:${activePort}/`;
  const authRequired = latestStatus?.webUi?.authRequired ?? latestConfig?.webUiAuthRequired ?? true;
  return authRequired && token ? `${base}?token=${encodeURIComponent(token)}` : base;
}

async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  document.execCommand('copy');
  area.remove();
}

async function login() {
  token = $('tokenInput').value.trim();
  sessionStorage.setItem('miracastToken', token);
  try {
    await api('/api/status');
    hideLogin();
    await loadAll();
  } catch (e) {
    showLogin('Token 无效或电视 WebUI 未就绪');
  }
}

function activatePage(name) {
  document.querySelectorAll('.nav').forEach(b => b.classList.toggle('active', b.dataset.page === name));
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  $(`page-${name}`).classList.add('active');
  if (name === 'logs') refreshLogs();
  if (name === 'system') refreshDiagnostics();
}

document.querySelectorAll('.nav').forEach(b => b.addEventListener('click', () => activatePage(b.dataset.page)));
$('loginBtn').addEventListener('click', login);
$('tokenInput').addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
$('copyAddressBtn').addEventListener('click', async () => {
  try {
    await copyText(currentDirectUrl());
    toast('已复制 WebUI 直达链接');
  } catch (_) {
    toast('复制失败，请手动复制地址');
  }
});

async function refreshStatus() {
  try {
    const s = await api('/api/status');
    latestStatus = s;
    $('connectionBadge').innerHTML = '<i></i>在线';
    $('connectionBadge').className = 'badge ok';

    $('deviceName').textContent = s.device.name || '-';
    $('deviceMeta').textContent = `${s.device.manufacturer || ''} ${s.device.model || ''} · Android ${s.device.android || ''}`;
    $('deviceIp').textContent = s.device.ip || '无 IP';
    $('serviceState').textContent = s.service.running ? '接收服务运行中' : '接收服务已停止';
    $('serviceUptime').textContent = `运行 ${fmtDuration(s.service.uptimeMs)}`;

    const activePort = Number(s.webUi?.activePort || location.port || 0);
    const preferredPort = Number(s.webUi?.preferredPort || latestConfig?.webUiPort || 0);
    const fallback = !!s.webUi?.fallback;
    const address = s.device.ip && activePort ? `http://${s.device.ip}:${activePort}` : '-';
    $('webAddress').textContent = address;
    $('activeWebUiPort').textContent = activePort || '-';
    $('preferredWebUiPort').textContent = preferredPort || '-';
    $('systemActivePort').textContent = activePort || '-';
    $('portFallbackBadge').classList.toggle('hidden', !fallback);
    $('portFallbackState').textContent = fallback
      ? `首选 ${preferredPort} 被占用，自动回退到 ${activePort}`
      : (activePort ? `使用首选端口 ${activePort}` : '等待 WebUI 监听');
    $('portFallbackState').classList.toggle('warning-text', fallback);

    $('playbackState').textContent = s.playback.state || 'IDLE';
    $('playbackTitle').textContent = s.playback.title || '当前没有播放';
    $('airplayState').textContent = `${s.airplay.state}${s.airplay.sender ? ' · ' + s.airplay.sender : ''}`;
    $('miracastState').textContent = `${s.miracast.state}${s.miracast.client ? ' · ' + s.miracast.client : ''}`;
    $('decoderState').textContent = s.playback.decoder
      ? `${s.playback.decoder}${s.playback.hardwareDecoder ? ' · 硬解' : ' · 软件/回退'}`
      : '等待视频流';
    $('overviewSource').textContent = s.playback.source || '空闲';
    $('overviewRtp').textContent = s.miracast.rtpPort ? `RTP ${s.miracast.rtpPort}` : '无活动 RTP';

    $('rootState').textContent = s.privileged.magisk ? 'Magisk Root' : (s.privileged.root ? 'Root' : '不可用');
    $('shizukuState').textContent = s.privileged.shizukuAuthorized ? '已授权' : (s.privileged.shizukuAlive ? '等待授权' : '未连接');
    $('bootState').textContent = s.privileged.bootScriptInstalled ? 'Magisk service.d 已安装' : 'Android BootReceiver';
    $('lastError').textContent = s.service.lastError || '无';

    $('nowTitle').textContent = s.playback.title || '当前没有播放';
    $('nowSource').textContent = s.playback.source || s.playback.uri || '-';
    $('progressText').textContent = `${fmtTime(s.playback.positionMs)} / ${fmtTime(s.playback.durationMs)}`;
    const pct = s.playback.durationMs > 0
      ? Math.min(100, Math.max(0, s.playback.positionMs / s.playback.durationMs * 100))
      : 0;
    $('progressFill').style.width = `${pct}%`;
    $('activeDecoder').textContent = s.playback.decoder || '-';
    $('activeHardware').textContent = s.playback.decoder ? (s.playback.hardwareDecoder ? '是 · 硬件加速' : '否 · 软件/回退') : '-';
  } catch (e) {
    if (e.message !== 'unauthorized') {
      $('connectionBadge').textContent = '离线';
      $('connectionBadge').className = 'badge bad';
    }
  }
}

async function loadConfig() {
  const c = await api('/api/config');
  latestConfig = c;
  $('airPlayEnabled').checked = !!c.airPlayEnabled;
  $('dlnaEnabled').checked = !!c.dlnaEnabled;
  $('miracastEnabled').checked = !!c.miracastEnabled;
  $('customMdnsEnabled').checked = !!c.customMdnsEnabled;
  $('airPlayAudioEnabled').checked = !!c.airPlayAudioEnabled;
  $('autoLaunchPlayer').checked = !!c.autoLaunchPlayer;
  $('mirrorMaxHeight').value = String(c.mirrorMaxHeight || 0);
  $('upnpPort').value = c.upnpPort;
  $('deviceNameInput').value = c.deviceName || '';
  $('connectionCode').value = c.connectionCode || '';
  $('webUiEnabled').checked = !!c.webUiEnabled;
  $('webUiAuthRequired').checked = !!c.webUiAuthRequired;
  $('autoStartOnBoot').checked = !!c.autoStartOnBoot;
  $('webUiPort').value = c.webUiPort;
  $('dlnaState').textContent = boolText(c.dlnaEnabled);
  $('systemActivePort').textContent = c.webUiActivePort || location.port || '-';
  if (c.webUiPortFallback) {
    $('portFallbackState').textContent = `首选 ${c.webUiPort} 被占用，正在使用 ${c.webUiActivePort}`;
    $('portFallbackState').classList.add('warning-text');
  }
}

function collectConfig() {
  return {
    airPlayEnabled: $('airPlayEnabled').checked,
    dlnaEnabled: $('dlnaEnabled').checked,
    miracastEnabled: $('miracastEnabled').checked,
    customMdnsEnabled: $('customMdnsEnabled').checked,
    airPlayAudioEnabled: $('airPlayAudioEnabled').checked,
    autoLaunchPlayer: $('autoLaunchPlayer').checked,
    mirrorMaxHeight: Number($('mirrorMaxHeight').value),
    upnpPort: Number($('upnpPort').value),
    deviceName: $('deviceNameInput').value.trim(),
    webUiEnabled: $('webUiEnabled').checked,
    webUiAuthRequired: $('webUiAuthRequired').checked,
    autoStartOnBoot: $('autoStartOnBoot').checked,
    webUiPort: Number($('webUiPort').value)
  };
}

async function saveConfig(e) {
  e?.preventDefault();
  const next = collectConfig();
  const oldPort = Number(location.port || 80);
  try {
    const result = await api('/api/config', {method: 'POST', body: JSON.stringify(next)});
    if (!next.webUiEnabled) {
      toast('配置已保存，WebUI 已关闭；重新开启后再访问此页面');
      $('connectionBadge').textContent = 'WebUI 已关闭';
      $('connectionBadge').className = 'badge';
      return;
    }

    const reconnectPort = Number(result.reconnectPort || next.webUiPort || oldPort);
    toast('配置已保存，接收服务正在重载');
    setTimeout(() => {
      if (reconnectPort !== oldPort) {
        const authQuery = next.webUiAuthRequired && token ? `?token=${encodeURIComponent(token)}` : '';
        location.href = `${location.protocol}//${hostForUrl()}:${reconnectPort}/${authQuery}`;
      } else {
        location.reload();
      }
    }, 1300);
  } catch (e) {
    toast(`保存失败：${e.message}`);
  }
}

$('configForm').addEventListener('submit', saveConfig);
$('systemForm').addEventListener('submit', saveConfig);

async function playerAction(action, extra = {}) {
  try {
    await api('/api/actions/player', {method: 'POST', body: JSON.stringify({action, ...extra})});
    toast(`播放器：${action}`);
  } catch (e) {
    toast(`操作失败：${e.message}`);
  }
}

document.querySelectorAll('[data-player]').forEach(b => b.addEventListener('click', () => playerAction(b.dataset.player)));
$('seekBtn').addEventListener('click', () => playerAction('seek', {positionMs: Number($('seekSeconds').value) * 1000}));
$('volumeBtn').addEventListener('click', () => playerAction('volume', {value: Number($('volumeValue').value)}));
$('speedBtn').addEventListener('click', () => playerAction('speed', {value: Number($('speedValue').value)}));

$('restartBtn').addEventListener('click', async () => {
  try {
    await api('/api/actions/restart', {method: 'POST', body: '{}'});
    toast('接收服务正在重载');
  } catch (e) { toast(e.message); }
});

$('regenCodeBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/actions/regenerate-code', {method: 'POST', body: '{}'});
    $('connectionCode').value = r.connectionCode;
    toast('连接码已更新');
  } catch (e) { toast(e.message); }
});

$('optimizeBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/actions/background-optimize', {method: 'POST', body: '{}'});
    toast(`${r.channel}: ${r.succeeded}/${r.attempted} 项成功`);
  } catch (e) { toast(e.message); }
});

$('rotateTokenBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/actions/rotate-token', {method: 'POST', body: '{}'});
    token = r.token;
    sessionStorage.setItem('miracastToken', token);
    toast('WebUI Token 已轮换；电视二维码会自动刷新');
  } catch (e) { toast(e.message); }
});

async function refreshDiagnostics() {
  try {
    const d = await api('/api/diagnostics');
    $('diagnostics').textContent = JSON.stringify(d, null, 2);
  } catch (e) {
    $('diagnostics').textContent = `诊断读取失败：${e.message}`;
  }
}

async function refreshLogs() {
  try {
    const d = await api('/api/logs?limit=400');
    $('logs').textContent = (d.entries || [])
      .map(x => `${x.timestamp} ${x.level}/${x.tag || '-'}: ${x.message}`)
      .join('\n') || '暂无日志';
    $('logs').scrollTop = $('logs').scrollHeight;
  } catch (e) {
    $('logs').textContent = `日志读取失败：${e.message}`;
  }
}

$('refreshDiagnosticsBtn').addEventListener('click', refreshDiagnostics);
$('refreshLogsBtn').addEventListener('click', refreshLogs);
$('clearLogsBtn').addEventListener('click', async () => {
  try {
    await api('/api/logs/clear', {method: 'POST', body: '{}'});
    refreshLogs();
    toast('日志已清空');
  } catch (e) { toast(e.message); }
});

async function loadAll() {
  await loadConfig();
  await Promise.all([refreshStatus(), refreshDiagnostics()]);
}

(async function boot() {
  try {
    const ping = await fetch('/api/ping', {cache: 'no-store'}).then(r => r.json());
    if (ping.authRequired && !token) {
      showLogin();
      return;
    }
    await loadAll();
  } catch (_) {
    showLogin('无法连接 WebUI，请确认服务已启动');
  }
})();

setInterval(refreshStatus, 2000);
setInterval(() => {
  if ($('page-logs').classList.contains('active')) refreshLogs();
}, 3500);
