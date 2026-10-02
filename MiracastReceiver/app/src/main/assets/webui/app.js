const $ = (id) => document.getElementById(id);

let token = sessionStorage.getItem('miracastToken') || '';
let latestStatus = null;
let configLoaded = false;

// TV QR codes use a URL fragment so the token is never sent in the HTTP request or server logs.
(function consumeQrToken(){
  try {
    const params = new URLSearchParams(location.hash.replace(/^#/, ''));
    const scannedToken = (params.get('token') || '').trim();
    if (scannedToken) {
      token = scannedToken;
      sessionStorage.setItem('miracastToken', token);
      history.replaceState(null, document.title, location.pathname + location.search);
    }
  } catch (_) {}
})();

function apiHeaders() {
  const h = {'Content-Type':'application/json'};
  if (token) h['X-API-Token'] = token;
  return h;
}

async function api(path, options={}) {
  const res = await fetch(path, {...options, headers:{...apiHeaders(), ...(options.headers||{})}, cache:'no-store'});
  if (res.status === 401) {
    showLogin();
    throw new Error('unauthorized');
  }
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = {raw:text}; }
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function showLogin(message='') {
  $('login').classList.remove('hidden');
  $('loginError').textContent = message;
  $('tokenInput').value = token;
  setTimeout(()=>$('tokenInput').focus(), 30);
}
function hideLogin(){ $('login').classList.add('hidden'); }
function toast(message){
  $('toast').textContent = message;
  $('toast').classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer=setTimeout(()=>$('toast').classList.add('hidden'), 2800);
}
function boolText(v){ return v ? '已启用' : '已关闭'; }
function fmtTime(ms){
  ms = Math.max(0, Number(ms)||0);
  const s = Math.floor(ms/1000), h=Math.floor(s/3600), m=Math.floor((s%3600)/60), sec=s%60;
  return h>0 ? `${h}:${String(m).padStart(2,'0')}:${String(sec).padStart(2,'0')}` : `${m}:${String(sec).padStart(2,'0')}`;
}
function fmtDuration(ms){
  const s=Math.floor((Number(ms)||0)/1000);
  if(s<60) return `${s}s`;
  const m=Math.floor(s/60), h=Math.floor(m/60);
  return h ? `${h}h ${m%60}m` : `${m}m ${s%60}s`;
}
function hostWithPort(port, includeAuth=true){
  const host = location.hostname.includes(':') ? `[${location.hostname}]` : location.hostname;
  const base = `${location.protocol}//${host}:${port}/`;
  return includeAuth && token ? `${base}#token=${encodeURIComponent(token)}` : base;
}

async function login(){
  token = $('tokenInput').value.trim();
  sessionStorage.setItem('miracastToken', token);
  try {
    await api('/api/status');
    hideLogin();
    await loadAll();
  } catch(e) {
    showLogin('Token 无效或电视 WebUI 未就绪');
  }
}

const pageTitles={overview:'接收中心概览',protocols:'协议与发现',playback:'远程播放器',system:'系统与后台',logs:'实时日志'};
function activatePage(name){
  document.querySelectorAll('.nav').forEach(b=>b.classList.toggle('active', b.dataset.page===name));
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  $(`page-${name}`).classList.add('active');
  $('pageTitle').textContent=pageTitles[name]||'MiracastReceiver';
  if(name==='logs') refreshLogs();
  if(name==='system') refreshDiagnostics();
}

document.querySelectorAll('.nav').forEach(b=>b.addEventListener('click',()=>activatePage(b.dataset.page)));
$('loginBtn').addEventListener('click', login);
$('tokenInput').addEventListener('keydown', e=>{ if(e.key==='Enter') login(); });

function injectUrlPlaybackCard(){
  const page=$('page-playback');
  if(!page || $('directUrlCard')) return;
  const card=document.createElement('article');
  card.id='directUrlCard';
  card.className='panel';
  card.innerHTML=`
    <div class="panel-title"><div><p class="eyebrow">DIRECT STREAM</p><h2>HTTP / HLS / DASH 直接播放</h2><p>支持 m3u8、mpd、MP4、TS、WebM、MP3/AAC 等 Media3 可识别的 HTTP/HTTPS 媒体。</p></div></div>
    <div class="form-grid">
      <label style="grid-column:1/-1">媒体 URL<input id="directMediaUrl" type="url" maxlength="4096" placeholder="https://example.com/live/index.m3u8"></label>
      <label>标题（可选）<input id="directMediaTitle" type="text" maxlength="160" placeholder="例如 CCTV 1"></label>
      <label>User-Agent（可选）<input id="directUserAgent" type="text" maxlength="2048" placeholder="留空使用 MiracastReceiver"></label>
      <label>Referer（可选）<input id="directReferer" type="text" maxlength="2048" placeholder="https://example.com/"></label>
      <label>Authorization（可选）<input id="directAuthorization" type="text" maxlength="2048" placeholder="Bearer ..."></label>
    </div>
    <div class="actions"><button id="directPlayBtn" class="primary">▶ 立即播放</button></div>
    <div class="muted">仅接受 http:// 与 https://。网络临时异常会按 1/2/4/8/16 秒自动重试，恢复网络后立即重新连接。</div>`;
  const intro=page.querySelector('.page-intro');
  if(intro && intro.nextSibling) page.insertBefore(card,intro.nextSibling); else page.appendChild(card);
  $('directPlayBtn').addEventListener('click', openDirectUrl);
  $('directMediaUrl').addEventListener('keydown', e=>{ if(e.key==='Enter') openDirectUrl(); });
}

async function openDirectUrl(){
  const url=$('directMediaUrl').value.trim();
  if(!/^https?:\/\//i.test(url)) return toast('请输入 http:// 或 https:// 媒体地址');
  const headers={};
  const ua=$('directUserAgent').value.trim();
  const referer=$('directReferer').value.trim();
  const authorization=$('directAuthorization').value.trim();
  if(ua) headers['User-Agent']=ua;
  if(referer) headers['Referer']=referer;
  if(authorization) headers['Authorization']=authorization;
  try{
    await api('/api/actions/open-url',{
      method:'POST',
      body:JSON.stringify({url,title:$('directMediaTitle').value.trim(),headers})
    });
    toast('已发送到电视，正在打开播放器');
    setTimeout(refreshStatus,500);
  }catch(e){ toast(`URL 播放失败：${e.message}`); }
}

injectUrlPlaybackCard();

async function refreshStatus(){
  try {
    const s = await api('/api/status');
    latestStatus = s;
    $('connectionBadge').innerHTML='<i></i>在线'; $('connectionBadge').className='badge ok';
    $('deviceName').textContent=s.device.name || '-';
    $('deviceMeta').textContent=`${s.device.manufacturer || ''} ${s.device.model || ''} · Android ${s.device.android || ''}`;
    $('deviceIp').textContent=s.device.ip || '无 IP';

    const runtimePort = Number(s.webui?.port || location.port || 80);
    const preferredPort = Number(s.webui?.preferredPort || runtimePort);
    const address = s.device.ip ? `http://${s.device.ip}:${runtimePort}` : '-';
    $('webAddress').textContent=address;
    $('sidebarAddress').textContent=address;
    $('runtimePort').textContent=runtimePort ? String(runtimePort) : '-';
    $('runtimePortInput').value=runtimePort || '';
    $('portMode').textContent=s.webui?.fallback ? `自动备用 · 首选 ${preferredPort}` : `WebUI · ${runtimePort}`;
    $('portNotice').textContent=s.webui?.fallback
      ? `首选端口 ${preferredPort} 被占用，当前临时监听 ${runtimePort}`
      : `当前监听 ${runtimePort}；端口冲突时会临时选择备用端口。`;

    $('serviceState').textContent=s.service.running ? '● 接收服务运行中' : '接收服务已停止';
    $('serviceUptime').textContent=`运行 ${fmtDuration(s.service.uptimeMs)}`;
    $('playbackState').textContent=s.playback.state || 'IDLE';
    $('playbackTitle').textContent=s.playback.title || '当前没有播放';
    $('airplayState').textContent=`${s.airplay.state}${s.airplay.sender ? ' · '+s.airplay.sender : ''}`;
    $('miracastState').textContent=`${s.miracast.state}${s.miracast.client ? ' · '+s.miracast.client : ''}`;
    $('decoderState').textContent=s.playback.decoder ? `${s.playback.decoder}${s.playback.hardwareDecoder?' · 硬解':' · 软件/回退'}` : '等待视频流';
    $('rootState').textContent=s.privileged.magisk ? 'Magisk Root' : (s.privileged.root ? 'Root' : '不可用');
    $('shizukuState').textContent=s.privileged.shizukuAuthorized ? '已授权' : (s.privileged.shizukuAlive ? '等待授权' : '未连接');
    $('bootState').textContent=s.privileged.bootScriptInstalled ? 'Magisk service.d 已安装' : 'Android BootReceiver';
    $('lastError').textContent=s.service.lastError || '无';

    $('nowTitle').textContent=s.playback.title || '当前没有播放';
    const retryText=s.playback.retryAttempt ? ` · 重试 ${s.playback.retryAttempt}/5` : '';
    $('nowSource').textContent=(s.playback.source || s.playback.uri || '-') + retryText;
    $('progressText').textContent=`${fmtTime(s.playback.positionMs)} / ${fmtTime(s.playback.durationMs)}`;
    const pct=s.playback.durationMs>0 ? Math.min(100, Math.max(0, s.playback.positionMs/s.playback.durationMs*100)) : 0;
    $('progressFill').style.width=`${pct}%`;
    $('activeDecoder').textContent=s.playback.decoder || '-';
    $('activeHardware').textContent=s.playback.decoder ? (s.playback.hardwareDecoder?'是':'否 / 回退') : '-';
  } catch(e) {
    if(e.message!=='unauthorized'){
      $('connectionBadge').innerHTML='<i></i>离线'; $('connectionBadge').className='badge bad';
    }
  }
}

async function loadConfig(){
  const c=await api('/api/config');
  $('airPlayEnabled').checked=!!c.airPlayEnabled;
  $('dlnaEnabled').checked=!!c.dlnaEnabled;
  $('miracastEnabled').checked=!!c.miracastEnabled;
  $('customMdnsEnabled').checked=!!c.customMdnsEnabled;
  $('airPlayAudioEnabled').checked=!!c.airPlayAudioEnabled;
  $('autoLaunchPlayer').checked=!!c.autoLaunchPlayer;
  $('mirrorMaxHeight').value=String(c.mirrorMaxHeight||0);
  $('upnpPort').value=c.upnpPort;
  $('deviceNameInput').value=c.deviceName||'';
  $('connectionCode').value=c.connectionCode||'';
  $('webUiEnabled').checked=!!c.webUiEnabled;
  $('webUiAuthRequired').checked=!!c.webUiAuthRequired;
  $('autoStartOnBoot').checked=!!c.autoStartOnBoot;
  $('webUiPort').value=c.webUiPort;
  $('runtimePortInput').value=c.webUiRuntimePort || location.port || c.webUiPort;
  $('portNotice').textContent=c.webUiFallbackActive
    ? `首选端口 ${c.webUiPort} 被占用；当前临时使用 ${c.webUiRuntimePort}`
    : `首选端口 ${c.webUiPort || 18090}；被占用时临时选择可用高位端口，首选配置保持不变。`;
  $('dlnaState').textContent=boolText(c.dlnaEnabled);
  configLoaded=true;
}

function collectConfig(){
  return {
    airPlayEnabled:$('airPlayEnabled').checked,
    dlnaEnabled:$('dlnaEnabled').checked,
    miracastEnabled:$('miracastEnabled').checked,
    customMdnsEnabled:$('customMdnsEnabled').checked,
    airPlayAudioEnabled:$('airPlayAudioEnabled').checked,
    autoLaunchPlayer:$('autoLaunchPlayer').checked,
    mirrorMaxHeight:Number($('mirrorMaxHeight').value),
    upnpPort:Number($('upnpPort').value),
    deviceName:$('deviceNameInput').value.trim(),
    webUiEnabled:$('webUiEnabled').checked,
    webUiAuthRequired:$('webUiAuthRequired').checked,
    autoStartOnBoot:$('autoStartOnBoot').checked,
    webUiPort:Number($('webUiPort').value)
  };
}

async function saveConfig(e){
  e?.preventDefault();
  const next = collectConfig();
  const oldPort = Number(location.port || 80);
  try {
    const result=await api('/api/config',{method:'POST',body:JSON.stringify(next)});
    const reconnectPort=Number(result.reconnectPort || result.config?.webUiRuntimePort || next.webUiPort || oldPort);
    if (!next.webUiEnabled) {
      toast('配置已保存，WebUI 已关闭；需要重新开启后再访问');
      $('connectionBadge').textContent='WebUI 已关闭';
      $('connectionBadge').className='badge';
      return;
    }

    if(reconnectPort!==next.webUiPort){
      toast(`首选端口 ${next.webUiPort} 被占用，继续使用临时端口 ${reconnectPort}`);
    }else{
      toast('配置已保存，接收服务正在重载');
    }
    setTimeout(()=>{
      if (reconnectPort !== oldPort) location.href = hostWithPort(reconnectPort, true);
      else location.reload();
    }, 1300);
  } catch(e){ toast(`保存失败：${e.message}`); }
}
$('configForm').addEventListener('submit', saveConfig);
$('systemForm').addEventListener('submit', saveConfig);

async function playerAction(action, extra={}){
  try { await api('/api/actions/player',{method:'POST',body:JSON.stringify({action,...extra})}); toast(`播放器：${action}`); }
  catch(e){ toast(`操作失败：${e.message}`); }
}
document.querySelectorAll('[data-player]').forEach(b=>b.addEventListener('click',()=>playerAction(b.dataset.player)));
$('seekBtn').addEventListener('click',()=>playerAction('seek',{positionMs:Number($('seekSeconds').value)*1000}));
$('volumeBtn').addEventListener('click',()=>playerAction('volume',{value:Number($('volumeValue').value)}));
$('speedBtn').addEventListener('click',()=>playerAction('speed',{value:Number($('speedValue').value)}));

$('restartBtn').addEventListener('click',async()=>{ try{await api('/api/actions/restart',{method:'POST',body:'{}'});toast('接收服务正在重载');}catch(e){toast(e.message);} });
$('regenCodeBtn').addEventListener('click',async()=>{ try{const r=await api('/api/actions/regenerate-code',{method:'POST',body:'{}'});$('connectionCode').value=r.connectionCode;toast('连接码已更新');}catch(e){toast(e.message);} });
$('optimizeBtn').addEventListener('click',async()=>{ try{const r=await api('/api/actions/background-optimize',{method:'POST',body:'{}'});toast(`${r.channel}: ${r.succeeded}/${r.attempted} 项成功`);}catch(e){toast(e.message);} });
$('rotateTokenBtn').addEventListener('click',async()=>{ try{const r=await api('/api/actions/rotate-token',{method:'POST',body:'{}'});token=r.token;sessionStorage.setItem('miracastToken',token);toast('Token 已轮换；电视二维码会自动更新');}catch(e){toast(e.message);} });
$('copyAddressBtn').addEventListener('click',async()=>{
  const address=$('webAddress').textContent.trim();
  if(!address || address==='-') return toast('当前没有可复制的 WebUI 地址');
  try{ await navigator.clipboard.writeText(address); toast('WebUI 地址已复制'); }
  catch(_){ toast(address); }
});

async function refreshDiagnostics(){
  try { const d=await api('/api/diagnostics'); $('diagnostics').textContent=JSON.stringify(d,null,2); }
  catch(e){ $('diagnostics').textContent=`诊断读取失败：${e.message}`; }
}
async function refreshLogs(){
  try{
    const d=await api('/api/logs?limit=400');
    $('logs').textContent=(d.entries||[]).map(x=>`${x.timestamp} ${x.level}/${x.tag||'-'}: ${x.message}`).join('\n') || '暂无日志';
    $('logs').scrollTop=$('logs').scrollHeight;
  }catch(e){ $('logs').textContent=`日志读取失败：${e.message}`; }
}
$('refreshLogsBtn').addEventListener('click',refreshLogs);
$('clearLogsBtn').addEventListener('click',async()=>{try{await api('/api/logs/clear',{method:'POST',body:'{}'});refreshLogs();toast('日志已清空');}catch(e){toast(e.message);}});

async function loadAll(){ await Promise.all([loadConfig(), refreshStatus(), refreshDiagnostics()]); }

(async function boot(){
  try{
    const ping=await fetch('/api/ping',{cache:'no-store'}).then(r=>r.json());
    if(ping.authRequired && !token){ showLogin(); return; }
    hideLogin();
    await loadAll();
  }catch(e){ showLogin('无法连接 WebUI，请确认服务已启动'); }
})();

setInterval(refreshStatus, 2000);
setInterval(()=>{ if($('page-logs').classList.contains('active')) refreshLogs(); }, 3500);
