(() => {
  const getPlayback = () => (typeof latestStatus !== 'undefined' && latestStatus?.playback) ? latestStatus.playback : {};
  const byId = id => document.getElementById(id);
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const activeStates = new Set(['PLAYING', 'READY', 'BUFFERING', 'RETRYING', 'LAUNCHING', 'PAUSED']);
  const pauseStates = new Set(['PLAYING', 'READY', 'BUFFERING', 'RETRYING', 'LAUNCHING']);

  const seek = byId('seekRange');
  const currentLabel = byId('currentTimeLabel');
  const durationLabel = byId('durationLabel');
  const playToggle = byId('playToggleBtn');
  const back10 = byId('back10Btn');
  const forward10 = byId('forward10Btn');
  const liveBadge = byId('liveBadge');
  const statePill = byId('playerStatePill');
  const volume = byId('volumeValue');
  const volumeLabel = byId('volumeLabel');
  const mute = byId('muteBtn');
  const speedValue = byId('speedValue');
  const speedTrigger = byId('speedMenuBtn');
  const speedMenu = byId('speedMenu');
  const progressFill = byId('progressFill');

  if (!seek || !playToggle || !volume || !speedTrigger) return;

  let seeking = false;
  let volumeBusyUntil = 0;
  let volumeTimer = 0;
  let lastNonZeroVolume = 100;

  const translated = (key, fallback) => {
    try { return typeof t === 'function' ? t(key) : fallback; } catch (_) { return fallback; }
  };

  const callPlayer = (action, extra = {}) => {
    if (typeof playerAction === 'function') return playerAction(action, extra);
  };

  const setVolumeVisual = value => {
    const v = clamp(Number(value) || 0, 0, 100);
    volume.style.setProperty('--volume-progress', `${v}%`);
    if (volumeLabel) volumeLabel.textContent = `${Math.round(v)}%`;
    if (mute) {
      mute.textContent = v <= 0 ? '🔇' : v < 45 ? '🔈' : '🔊';
      mute.setAttribute('aria-label', v <= 0 ? 'Unmute' : 'Mute');
      mute.title = v <= 0 ? 'Unmute' : 'Mute';
    }
    if (v > 0) lastNonZeroVolume = v;
  };

  const setSpeedVisual = value => {
    let v = Number(value);
    if (!Number.isFinite(v) || v <= 0) v = 1;
    if (speedValue) speedValue.value = String(v);
    speedTrigger.textContent = `${Number(v.toFixed(2))}×`;
    speedMenu?.querySelectorAll('[data-speed]').forEach(button => {
      button.classList.toggle('active', Math.abs(Number(button.dataset.speed) - v) < 0.001);
    });
  };

  const setSeekVisual = (position, duration) => {
    const d = Math.max(0, Number(duration) || 0);
    const p = clamp(Number(position) || 0, 0, d || Number.MAX_SAFE_INTEGER);
    if (d > 0) {
      seek.max = String(d);
      if (!seeking) seek.value = String(clamp(p, 0, d));
      seek.disabled = false;
      if (currentLabel) currentLabel.textContent = fmtTime(seeking ? Number(seek.value) : p);
      if (durationLabel) durationLabel.textContent = fmtTime(d);
      back10.disabled = false;
      forward10.disabled = false;
      liveBadge?.classList.add('hidden');
    } else {
      seek.max = '1';
      if (!seeking) seek.value = '0';
      seek.disabled = true;
      if (currentLabel) currentLabel.textContent = fmtTime(p);
      if (durationLabel) durationLabel.textContent = activeStates.has(String(getPlayback().state || '').toUpperCase()) ? translated('live', 'LIVE') : '0:00';
      back10.disabled = true;
      forward10.disabled = true;
      liveBadge?.classList.toggle('hidden', !activeStates.has(String(getPlayback().state || '').toUpperCase()));
    }
  };

  const render = () => {
    const p = getPlayback();
    const state = String(p.state || 'IDLE').toUpperCase();
    const duration = Number(p.durationMs) || 0;
    const position = Number(p.positionMs) || 0;
    const active = activeStates.has(state);

    if (statePill) {
      statePill.textContent = state;
      statePill.classList.toggle('active', active);
    }

    const shouldPause = pauseStates.has(state);
    playToggle.textContent = shouldPause ? 'Ⅱ' : '▶';
    playToggle.dataset.action = shouldPause ? 'pause' : 'play';
    playToggle.setAttribute('aria-label', shouldPause ? translated('pause', 'Pause') : translated('play', 'Play'));
    playToggle.title = shouldPause ? translated('pause', 'Pause') : translated('play', 'Play');

    setSeekVisual(position, duration);

    if (Date.now() >= volumeBusyUntil) {
      const remoteVolume = Number(p.volume);
      const v = Number.isFinite(remoteVolume) ? clamp(remoteVolume, 0, 100) : Number(volume.value || 100);
      volume.value = String(v);
      setVolumeVisual(v);
    }

    setSpeedVisual(Number(p.speed) || Number(speedValue?.value) || 1);

    const decoder = byId('activeDecoder');
    const hardware = byId('activeHardware');
    const decoderChip = byId('decoderChipValue');
    const hardwareChip = byId('hardwareChipValue');
    if (decoderChip && decoder) decoderChip.textContent = decoder.textContent || '—';
    if (hardwareChip && hardware) hardwareChip.textContent = hardware.textContent || '—';
  };

  playToggle.addEventListener('click', () => callPlayer(playToggle.dataset.action || 'play'));

  const relativeSeek = deltaMs => {
    const p = getPlayback();
    const duration = Number(p.durationMs) || 0;
    if (duration <= 0) return;
    const target = clamp((Number(p.positionMs) || 0) + deltaMs, 0, duration);
    seek.value = String(target);
    setSeekVisual(target, duration);
    callPlayer('seek', { positionMs: target });
  };
  back10.addEventListener('click', () => relativeSeek(-10000));
  forward10.addEventListener('click', () => relativeSeek(10000));

  seek.addEventListener('pointerdown', () => { seeking = true; });
  seek.addEventListener('input', () => {
    seeking = true;
    const duration = Number(getPlayback().durationMs) || Number(seek.max) || 0;
    const value = clamp(Number(seek.value) || 0, 0, duration || 0);
    if (currentLabel) currentLabel.textContent = fmtTime(value);
    if (progressFill && duration > 0) progressFill.style.width = `${value / duration * 100}%`;
  });
  seek.addEventListener('change', () => {
    const duration = Number(getPlayback().durationMs) || Number(seek.max) || 0;
    const target = clamp(Number(seek.value) || 0, 0, duration || 0);
    seeking = false;
    if (duration > 0) callPlayer('seek', { positionMs: target });
  });
  seek.addEventListener('pointerup', () => { seeking = false; });
  seek.addEventListener('pointercancel', () => { seeking = false; });

  volume.addEventListener('input', () => {
    const value = clamp(Number(volume.value) || 0, 0, 100);
    volumeBusyUntil = Date.now() + 900;
    setVolumeVisual(value);
    clearTimeout(volumeTimer);
    volumeTimer = setTimeout(() => callPlayer('volume', { value }), 90);
  });
  volume.addEventListener('change', () => {
    const value = clamp(Number(volume.value) || 0, 0, 100);
    volumeBusyUntil = Date.now() + 900;
    clearTimeout(volumeTimer);
    callPlayer('volume', { value });
  });

  mute?.addEventListener('click', () => {
    const current = clamp(Number(volume.value) || 0, 0, 100);
    const next = current > 0 ? 0 : clamp(lastNonZeroVolume || 70, 1, 100);
    if (current > 0) lastNonZeroVolume = current;
    volume.value = String(next);
    volumeBusyUntil = Date.now() + 900;
    setVolumeVisual(next);
    callPlayer('volume', { value: next });
  });

  speedTrigger.addEventListener('click', event => {
    event.stopPropagation();
    speedMenu?.classList.toggle('hidden');
    speedTrigger.setAttribute('aria-expanded', String(!speedMenu?.classList.contains('hidden')));
  });
  speedMenu?.querySelectorAll('[data-speed]').forEach(button => {
    button.addEventListener('click', () => {
      const value = Number(button.dataset.speed) || 1;
      setSpeedVisual(value);
      speedMenu.classList.add('hidden');
      speedTrigger.setAttribute('aria-expanded', 'false');
      callPlayer('speed', { value });
    });
  });
  document.addEventListener('click', event => {
    if (!event.target.closest('.speed-menu-wrap')) {
      speedMenu?.classList.add('hidden');
      speedTrigger.setAttribute('aria-expanded', 'false');
    }
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      speedMenu?.classList.add('hidden');
      speedTrigger.setAttribute('aria-expanded', 'false');
    }
  });

  setVolumeVisual(volume.value);
  setSpeedVisual(speedValue?.value || 1);
  render();
  setInterval(render, 300);
})();
