const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const ICONS = {
  download: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m0 0 4.5-4.5M12 15l-4.5-4.5M4 20h16"/></svg>',
  external: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6M20 4l-8.5 8.5M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
  folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>',
};

const state = {
  platforms: [],
  settings: {},
  result: null,
  loading: false,
  tasks: [],
  history: [],
  taskStatus: {},
  previewMode: true,
  quality: 'origin',
  lightbox: { items: [], index: 0, loaded: false },
  picker: { path: '', dirs: [], parent: null },
  pickerTarget: null,
  serviceFailures: 0,
  serviceChecking: false,
  batch: null,
  batchChecking: false,
  selectedItem: null,
  resultSource: '',
  viewer: { zoom: 1, rotation: 0, panX: 0, panY: 0, fit: 1 },
};

/* ------------------------------------------------------------------ 工具 */

async function api(path, { method = 'GET', body } = {}) {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(path === '/api/parse' || path === '/api/clean' ? 180000 : 15000),
  });
  const text = await response.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { detail: text }; }
  }
  if (!response.ok) {
    const raw = data && (data.detail || data.message);
    const detail = Array.isArray(raw) ? raw.map((item) => item.msg || JSON.stringify(item)).join('; ') : raw;
    throw new Error(detail ? String(detail) : '请求失败 (' + response.status + ')');
  }
  return data;
}

async function openExternal(url) {
  try {
    const parsed = new URL(url);
    if (!['https:', 'http:'].includes(parsed.protocol)) throw new Error('不支持的链接类型');
    await api('/api/open-url', { method: 'POST', body: { path: parsed.href } });
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function checkService() {
  if (state.serviceChecking) return;
  state.serviceChecking = true;
  const badge = $('#serviceStatus');
  const alert = $('#serviceAlert');
  try {
    const response = await fetch('/api/health', { signal: AbortSignal.timeout(12000), cache: 'no-store', priority: 'high' });
    if (!response.ok) throw new Error('服务会话已失效');
    const health = await response.json();
    state.desktop = health.desktop;
    state.health = health;
    state.serviceFailures = 0;
    badge.textContent = health.desktop ? '桌面服务就绪' : '本地服务就绪';
    badge.dataset.online = 'true';
    alert.hidden = true;
    return true;
  } catch (error) {
    state.serviceFailures += 1;
    if (state.serviceFailures < 3) return false;
    badge.textContent = '服务已断开';
    badge.dataset.online = 'false';
    alert.textContent = '无法连接本地解析服务。请关闭并重新启动 ParseHub Studio；已有下载文件不会被删除。';
    alert.hidden = false;
    return false;
  } finally {
    state.serviceChecking = false;
  }
}

const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

function debounce(fn, delay = 320) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function formatBytes(value) {
  if (!value) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
  return (value / 1024 ** index).toFixed(index === 0 ? 0 : 1) + ' ' + units[index];
}

function formatDuration(seconds) {
  if (!seconds) return '';
  const minutes = Math.floor(seconds / 60);
  const rest = Math.floor(seconds % 60);
  return minutes + ':' + String(rest).padStart(2, '0');
}

function formatTime(ts) {
  const diff = Date.now() / 1000 - (ts || 0);
  if (diff < 60) return '刚刚';
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
  return new Date((ts || 0) * 1000).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function proxied(url, options = {}) {
  const params = new URLSearchParams({ src: url });
  const referer = state.result && state.result.raw_url;
  if (referer) params.set('referer', referer);
  if (options.download) {
    params.set('download', '1');
    if (options.filename) params.set('filename', options.filename);
  }
  return '/api/media?' + params.toString();
}

function fileNameFor(media, index) {
  const base = (state.result && state.result.name) || 'media';
  const useThumb = state.quality === 'thumb' && Boolean(media.thumb_url);
  let ext = media.ext || 'bin';
  if (useThumb) {
    const matched = /\.([a-z0-9]+)(?:$|\?)/i.exec(media.thumb_url);
    ext = (matched ? matched[1] : 'jpg').toLowerCase();
  }
  const count = (state.result && state.result.media && state.result.media.length) || 1;
  const prefix = count > 1 ? base + '_' + String(index + 1).padStart(3, '0') : base;
  return prefix + (useThumb ? '_thumb' : '') + '.' + ext;
}

/** 当前下载规格对应的媒体地址 */
function qualitySource(media) {
  if (state.quality === 'thumb' && media.thumb_url) return media.thumb_url;
  return media.url;
}

function toast(message, kind = 'info', timeout = 3600) {
  const node = document.createElement('div');
  node.className = 'toast ' + kind;
  node.textContent = message;
  $('#toasts').appendChild(node);
  setTimeout(() => {
    node.style.opacity = '0';
    node.style.transform = 'translateX(18px)';
    setTimeout(() => node.remove(), 220);
  }, timeout);
}

async function copyText(text, label = '已复制') {
  try {
    await navigator.clipboard.writeText(text);
    toast(label, 'ok', 1800);
  } catch {
    const helper = document.createElement('textarea');
    helper.value = text;
    document.body.appendChild(helper);
    helper.select();
    document.execCommand('copy');
    helper.remove();
    toast(label, 'ok', 1800);
  }
}

/* ------------------------------------------------------------------ 平台 */

function renderPlatformStrip() {
  const strip = $('#platformStrip');
  if (!strip) return;
  strip.innerHTML = state.platforms
    .map((item) => {
      const ready = item.cookie_set || item.parse_proxy || item.download_proxy;
      return '<span class="plat-chip" data-id="' + esc(item.id) + '" title="' + esc(item.supported_types.join(' / ')) + '">' +
        '<i class="dot ' + (ready ? '' : 'off') + '"></i>' + esc(item.name) + '</span>';
    })
    .join('');
}

function highlightPlatform(platformId) {
  $$('#platformStrip .plat-chip').forEach((chip) => {
    chip.dataset.active = String(chip.dataset.id === platformId);
  });
}

const detect = debounce(async () => {
  const text = $('#input').value.trim();
  const chip = $('#detectChip');
  if (!text) {
    chip.textContent = '等待输入';
    chip.dataset.state = '';
    highlightPlatform(null);
    return;
  }
  try {
    const info = await api('/api/detect', { method: 'POST', body: { text } });
    if (info.platform) {
      chip.textContent = '识别到 ' + info.platform.name;
      chip.dataset.state = 'match';
      highlightPlatform(info.platform.id);
    } else {
      chip.textContent = '未识别到支持的平台';
      chip.dataset.state = '';
      highlightPlatform(null);
    }
  } catch {
    chip.textContent = '识别失败';
    chip.dataset.state = '';
  }
}, 280);

/* ------------------------------------------------------------------ 结果 */

/** 预览地址：预览模式只取缩略图，避免拉原图 */
function previewSource(media) {
  if (media.kind === 'video') return media.thumb_url || '';
  return state.previewMode ? (media.preview_url || media.url) : media.url;
}

function tileMarkup(item, index) {
  const source = previewSource(item);
  const tileState = source ? 'loading' : 'blank';
  const badges = ['<span class="badge">' + esc(item.kind_label) + '</span>'];
  if (item.kind === 'livephoto') badges.push('<span class="badge live">实况</span>');
  if (item.duration) badges.push('<span class="badge">' + formatDuration(item.duration) + '</span>');
  const play = item.kind === 'video'
    ? '<div class="play-icon"><span><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5.5v13l10-6.5z"/></svg></span></div>'
    : '';
  const mediaEl = source
    ? '<img class="tile-media" data-tile-index="' + index + '" data-src="' + esc(source) + '" src="' + esc(proxied(source)) + '" alt="第 ' + (index + 1) + ' 个媒体" loading="lazy" decoding="async" referrerpolicy="no-referrer" />'
    : '';
  return '<button class="media-tile" data-index="' + index + '" data-state="' + tileState + '" title="点击预览">' +
    mediaEl +
    play +
    '<span class="badge-row">' + badges.join('') + '</span>' +
    '<span class="tile-hint" data-action="load" data-index="' + index + '"' + (source ? ' hidden' : '') + '>无预览图 · 点击加载</span>' +
    '<span class="tile-actions">' +
      '<span class="mini" data-action="download" data-index="' + index + '" title="按当前规格下载"' + (source ? ' hidden' : '') + '>' + ICONS.download + '</span>' +
      '<span class="mini" data-action="open" data-index="' + index + '" title="新窗口打开原文件">' + ICONS.external + '</span>' +
    '</span>' +
  '</button>';
}

function renderMediaGrid(media) {
  if (!media || !media.length) {
    return '<div class="side-empty">该内容没有可下载的媒体</div>';
  }
  return '<div class="media-grid">' + media.map(tileMarkup).join('') + '</div>';
}

/** 预览成功后才会放出下载按键 */
function setTileState(index, nextState) {
  const tile = $('.media-tile[data-index="' + index + '"]');
  if (!tile) return;
  tile.dataset.state = nextState;
  const hint = $('.tile-hint', tile);
  const download = $('[data-action="download"]', tile);
  if (hint) {
    hint.hidden = nextState === 'ready' || nextState === 'loading';
    hint.textContent = nextState === 'error' ? '预览失败 · 点击重试' : '无预览图 · 点击加载';
  }
  if (download) download.hidden = nextState !== 'ready';
}

function loadTilePreview(index) {
  const media = state.result && state.result.media && state.result.media[index];
  const tile = $('.media-tile[data-index="' + index + '"]');
  if (!media || !tile) return;
  setTileState(index, 'loading');
  const image = $('img.tile-media', tile);
  const swap = (node) => {
    if (image) image.replaceWith(node);
    else tile.prepend(node);
  };
  if (media.kind === 'video') {
    const video = document.createElement('video');
    video.className = 'tile-media';
    video.dataset.tileIndex = String(index);
      video.dataset.src = media.url;
      video.src = media.url + '#t=0.1';
    video.muted = true;
    video.playsInline = true;
    video.preload = 'metadata';
    swap(video);
  } else {
    const img = document.createElement('img');
    img.className = 'tile-media';
    img.dataset.tileIndex = String(index);
    img.alt = '';
    img.dataset.src = media.url;
    img.src = media.url;
    if (image) image.replaceWith(img);
    else swap(img);
  }
  setTimeout(() => {
    if (tile.dataset.state === 'loading') setTileState(index, 'error');
  }, 10000);
}

function refreshGrid() {
  if (!state.result) return;
  const grid = $('#resultPanel .media-grid');
  if (grid) grid.outerHTML = renderMediaGrid(state.result.media);
}

function renderEmpty() {
  $('#resultPanel').innerHTML = '<div class="empty-state">' +
    '<div class="art">PH</div>' +
    '<h4>把分享链接丢进来</h4>' +
    '<p>支持小红书、抖音、微博、知乎、B 站、X、Instagram 等 17+ 平台。<br />' +
    '解析后可即时预览图片与视频，并一键保存到本地。</p>' +
  '</div>';
}

function renderLoading() {
  $('#resultPanel').innerHTML =
    '<div class="skeleton" style="height:22px;width:52%"></div>' +
    '<div class="skeleton" style="height:14px;width:78%;margin-top:14px"></div>' +
    '<div class="skeleton" style="height:14px;width:64%;margin-top:10px"></div>' +
    '<div class="media-grid" style="margin-top:20px">' +
      Array.from({ length: 4 }).map(() => '<div class="skeleton" style="aspect-ratio:1/1"></div>').join('') +
    '</div>';
}

function renderResult() {
  const panel = $('#resultPanel');
  if (state.loading) return renderLoading();
  const result = state.result;
  if (!result) return renderEmpty();

  const platformTag = result.platform ? '<span class="tag platform">' + esc(result.platform.name) + '</span>' : '';
  const content = result.html
    ? '<div class="result-content rich">' + result.html + '</div>'
    : result.content
      ? '<div class="post-caption"><div class="result-content" id="postCaption">' + esc(result.content.trim().replace(/\n{3,}/g, '\n\n')) + '</div>' +
        '<button class="btn tiny ghost" data-result-action="expand-text" aria-expanded="false" aria-controls="postCaption">展开全文</button></div>'
      : '';
  panel.innerHTML =
    '<div class="result-head">' +
      platformTag +
      '<span class="tag">' + esc(result.type_label) + '</span>' +
      '<span class="tag">' + result.media_count + ' 个媒体</span>' +
      '<h2 class="result-title">' + esc(result.title || (result.content || '').trim().split('\n')[0].slice(0, 100) || '解析结果') + '</h2>' +
    '</div>' +
    '<div class="result-toolbar">' +
      '<button class="btn primary" data-result-action="download-all">下载本作品</button>' +
      (result.raw_url ? '<button class="btn ghost" data-result-action="open-raw">打开原帖 ↗</button>' : '') +
      '<details class="result-more"><summary>下载与预览选项</summary><div class="option-row">' +
      '<label class="select-wrap" title="下载规格">规格' +
        '<select id="qualitySelect">' +
          '<option value="origin"' + (state.quality === 'origin' ? ' selected' : '') + '>原图 / 原视频</option>' +
          '<option value="thumb"' + (state.quality === 'thumb' ? ' selected' : '') + '>预览图（缩略图）</option>' +
        '</select>' +
      '</label>' +
      '<label class="check" title="网格使用高清预览；取消则网格直接加载原图。点开大图始终显示原文件"><input type="checkbox" id="previewToggle"' + (state.previewMode ? ' checked' : '') + ' /><span>高清预览</span></label>' +
      '<button class="btn" data-result-action="copy-json">复制 JSON</button>' +
      '<button class="btn ghost" data-result-action="reparse">重新解析</button>' +
      '</div></details>' +
    '</div>' +
    content +
    renderMediaGrid(result.media);
}

/* ------------------------------------------------------------------ 任务 */

function renderTasks() {
  const box = $('#tabTasks');
  const running = state.tasks.filter((task) => task.status === 'running' || task.status === 'pending').length;
  const badge = $('#taskBadge');
  badge.hidden = running === 0;
  badge.textContent = String(running);

  if (!state.tasks.length) {
    box.innerHTML = '<div class="side-empty">还没有下载任务<br />解析后点击「解析并下载」</div>';
    return;
  }

  const labels = { pending: '排队中', running: '进行中', success: '已完成', partial: '部分完成', error: '失败', cancelled: '已取消' };
  box.innerHTML = state.tasks
    .map((task) => {
      const label = labels[task.status] || task.status;
      const percent = Math.round((task.progress || 0) * 100);
      const indeterminate = task.status === 'running' && !task.total;
      let detail;
      if (task.status === 'error') {
        detail = '<span class="err">' + esc(task.error || '未知错误') + '</span>';
      } else if (task.status === 'success') {
        detail = '<span>' + task.files.length + ' 个文件</span>';
      } else if (task.status === 'partial') {
        detail = '<span>' + task.files.length + ' 个文件</span><span class="err">' + esc(task.error || '部分文件下载失败') + '</span>';
      } else {
        detail = '<span>' + esc(task.stage || '') + '</span>' +
          (task.total && task.unit === 'bytes' ? '<span>' + formatBytes(task.current) + ' / ' + formatBytes(task.total) + '</span>' : '');
      }
      const actions = [];
      if ((task.status === 'success' || task.status === 'partial') && task.output_dir) {
        actions.push('<button class="btn tiny ghost" data-task-action="reveal" data-id="' + task.id + '">打开目录</button>');
      }
      if (task.status === 'running' || task.status === 'pending') {
        actions.push('<button class="btn tiny ghost" data-task-action="cancel" data-id="' + task.id + '">取消</button>');
      }
      return '<div class="task-item">' +
        '<div class="task-top">' +
          '<span class="task-title" title="' + esc(task.text) + '">' + esc(task.title || task.text) + '</span>' +
          '<span class="status-pill ' + task.status + '">' + label + '</span>' +
        '</div>' +
        '<div class="progress ' + (indeterminate ? 'indeterminate' : '') + '"><i style="width:' + (indeterminate ? 40 : percent) + '%"></i></div>' +
        '<div class="task-sub">' + detail + actions.join('') + '</div>' +
      '</div>';
    })
    .join('');
}

async function refreshTasks() {
  try {
    const data = await api('/api/tasks');
    const tasks = data.tasks || [];
    tasks.forEach((task) => {
      const previous = state.taskStatus[task.id];
      if (previous && previous !== task.status) {
        if (task.status === 'success') toast('下载完成：' + (task.title || '内容'), 'ok');
        if (task.status === 'partial') toast('部分完成：' + (task.error || (task.title || '内容')), 'warn', 6000);
        if (task.status === 'error') toast('下载失败：' + (task.error || ''), 'err', 6000);
      }
      state.taskStatus[task.id] = task.status;
    });
    state.tasks = tasks;
    renderTasks();
  } catch (error) {
    console.error(error);
  }
}

async function createTask({ text, mediaIndex = null, silent = false }) {
  if (!text || !text.trim()) {
    toast('请先粘贴链接', 'warn');
    return;
  }
  try {
    const task = await api('/api/tasks', {
      method: 'POST',
      body: {
        text,
        media_index: mediaIndex,
        quality: state.quality,
        use_saved_config: $('#useSavedConfig').checked,
      },
    });
    state.taskStatus[task.id] = task.status;
    if (!silent) toast(mediaIndex === null ? '已加入下载队列' : '该媒体已加入下载队列', 'ok', 2200);
    await refreshTasks();
  } catch (error) {
    toast(error.message, 'err', 5000);
  }
}

/* ------------------------------------------------------------------ 历史 */

function renderHistory() {
  const box = $('#tabHistory');
  if (!state.history.length) {
    box.innerHTML = '<div class="side-empty">暂时没有历史记录<br />成功解析过的内容会出现在这里</div>';
    return;
  }
  box.innerHTML = state.history
    .map((entry) => {
      const thumb = entry.media && entry.media.length ? entry.media[0] : null;
      const source = thumb ? thumb.thumb_url || (thumb.kind === 'video' ? '' : thumb.url) : '';
      const icon = '<span>' + (thumb ? (thumb.kind === 'video' ? 'VID' : 'IMG') : 'TXT') + '</span>';
      const cover = source
        ? '<img data-src="' + esc(source) + '" src="' + esc(source) + '" alt="" loading="lazy" decoding="async" style="width:100%;height:100%;object-fit:cover" referrerpolicy="no-referrer" />'
        : icon;
      return '<div class="hist-item" data-id="' + esc(entry.id) + '">' +
        '<div class="hist-thumb">' + cover + '</div>' +
        '<div class="hist-info">' +
          '<div class="hist-title">' + esc(entry.title || entry.content || entry.raw_url || '未命名内容') + '</div>' +
          '<div class="hist-sub">' + esc((entry.platform && entry.platform.name) || '未知平台') + ' · ' + formatTime(entry.time) + ' · ' + (entry.media_count || 0) + ' 个媒体</div>' +
        '</div>' +
        '<button class="icon-btn" data-history-action="delete" data-id="' + esc(entry.id) + '" title="删除记录" style="width:28px;height:28px">&times;</button>' +
      '</div>';
    })
    .join('');
}

async function loadHistory() {
  try {
    const data = await api('/api/history');
    state.history = data.entries || [];
    renderHistory();
  } catch (error) {
    console.error(error);
  }
}

/* ------------------------------------------------------------------ 灯箱 */

function openLightbox(index) {
  const media = (state.result && state.result.media) || [];
  if (!media.length) return;
  state.lightbox.items = media;
  state.lightbox.index = Math.max(0, Math.min(index, media.length - 1));
  state.lightbox.loaded = true;
  $('#lightbox').hidden = false;
  renderLightbox();
  $('#lbClose').focus();
}

function closeLightbox() {
  $('#lightbox').hidden = true;
  $('#lbStage').innerHTML = '';
}

function renderLightbox() {
  state.viewer = { zoom: 1, rotation: 0, panX: 0, panY: 0, fit: 1 };
  const items = state.lightbox.items;
  const index = state.lightbox.index;
  const media = items[index];
  if (!media) return closeLightbox();
  const loaded = state.lightbox.loaded || !media.thumb_url;
  const previewing = !loaded;
  const raw = loaded ? media.url : media.thumb_url;
  $('#lbStage').innerHTML = loaded && media.kind === 'video'
    ? '<video data-src="' + esc(raw) + '" src="' + esc(proxied(raw)) + '" controls autoplay playsinline></video>'
    : '<img data-src="' + esc(raw) + '" src="' + esc(proxied(raw)) + '" alt="正在加载原图…" referrerpolicy="no-referrer" />';
  $$('#lightbox .lb-nav').forEach((node) => (node.hidden = items.length < 2));
  const info = [
    (index + 1) + ' / ' + items.length,
    media.kind_label,
    media.width && media.height ? media.width + '×' + media.height : '',
    media.duration ? formatDuration(media.duration) : '',
    previewing ? '预览图' : '原文件',
  ].filter(Boolean).join(' · ');
  const parts = ['<span class="tag">' + esc(info) + '</span>'];
  if (media.kind !== 'video') parts.push(
    '<button class="btn tiny" data-viewer="out" aria-label="缩小">−</button>' +
    '<span class="zoom-label" id="zoomLabel">适应</span>' +
    '<button class="btn tiny" data-viewer="in" aria-label="放大">＋</button>' +
    '<button class="btn tiny" data-viewer="actual">1:1 原尺寸</button>' +
    '<button class="btn tiny" data-viewer="fit">适应窗口</button>' +
    '<button class="btn tiny" data-viewer="rotate">旋转 ↻</button>');
  if (previewing) parts.push('<button class="btn tiny primary" data-lb-action="load">加载原文件</button>');
  parts.push('<button class="btn tiny' + (previewing ? '' : ' primary') + '" data-lb-action="save">保存到下载目录</button>');
  parts.push('<div class="lb-help">滚轮缩放 · 拖动平移 · 双击原尺寸 · ← → 切换 · Esc 关闭</div>');
  $('#lbBar').innerHTML = parts.join('');
}

/* ------------------------------------------------------------------ 设置 */

function renderSettingsGeneral() {
  const s = state.settings;
  $('#stabGeneral').innerHTML =
    '<div class="field">' +
      '<label>默认下载目录</label>' +
      '<div class="path-row">' +
        '<input type="text" id="setSaveDir" value="' + esc(s.default_save_dir || '') + '" />' +
        '<button class="btn" data-picker-for="#setSaveDir">浏览…</button>' +
      '</div>' +
      '<div class="desc">点击「下载全部」后，媒体会保存到该目录下的子文件夹中。</div>' +
    '</div>' +
    '<div class="field">' +
      '<label>单文件分片连接数</label>' +
      '<input type="number" id="setConnections" min="1" max="32" value="' + esc(s.connections ?? 4) + '" />' +
      '<div class="desc">设为 1 可禁用分片下载。</div>' +
    '</div>' +
    '<div class="field">' +
      '<label>全局网络代理</label>' +
      '<select id="setProxyMode">' +
        '<option value="system"' + (s.proxy_mode !== 'custom' && s.proxy_mode !== 'none' ? ' selected' : '') + '>跟随系统代理</option>' +
        '<option value="custom"' + (s.proxy_mode === 'custom' ? ' selected' : '') + '>自定义代理</option>' +
        '<option value="none"' + (s.proxy_mode === 'none' ? ' selected' : '') + '>直连，不使用代理</option>' +
      '</select>' +
      '<input type="text" id="setProxyUrl" placeholder="http://127.0.0.1:7890" value="' + esc(s.proxy_url || '') + '" style="margin-top:8px" />' +
      '<div class="desc">解析、下载与媒体预览都会优先使用这里的全局代理；平台代理仍可单独覆盖。</div>' +
    '</div>' +
    '<div class="field"><label class="check"><input type="checkbox" id="setMetadata" ' + (s.save_metadata ? 'checked' : '') + ' /><span>同时保存 metadata.json</span></label></div>' +
    '<div class="field"><label for="setFfmpegDir">本机 FFmpeg 目录（可选）</label>' +
      '<div class="path-row"><input id="setFfmpegDir" value="' + esc(s.ffmpeg_dir || '') + '" placeholder="留空则从系统 PATH 查找" />' +
      '<button class="btn" data-picker-for="#setFfmpegDir">浏览…</button></div>' +
      '<div class="desc">选择含 ffmpeg.exe 和 ffprobe.exe 的 bin 目录；轻量版不再重复打包 FFmpeg。</div></div>' +
    '<button class="btn primary" id="saveGeneral">保存设置</button>';

  $('#saveGeneral').addEventListener('click', async () => {
    try {
      state.settings = await api('/api/settings', {
        method: 'POST',
        body: {
          default_save_dir: $('#setSaveDir').value.trim(),
          connections: Number($('#setConnections').value) || 4,
          proxy_mode: $('#setProxyMode').value,
          proxy_url: $('#setProxyUrl').value.trim(),
          save_metadata: $('#setMetadata').checked,
          ffmpeg_dir: $('#setFfmpegDir').value.trim(),
        },
      });
      toast('设置已保存', 'ok', 1800);
    } catch (error) {
      toast(error.message, 'err');
    }
  });
}

function filterPlatforms(keyword) {
  const key = keyword.trim().toLowerCase();
  if (!key) return state.platforms;
  return state.platforms.filter((item) => item.name.toLowerCase().includes(key) || item.id.includes(key));
}

function renderSettingsProxy(keyword = '') {
  const rows = filterPlatforms(keyword)
    .map((item) =>
      '<div class="plat-row" data-platform="' + esc(item.id) + '">' +
        '<div class="plat-name">' + esc(item.name) + '<small>' + esc(item.id) + '</small></div>' +
        '<div class="plat-input">' +
          '<input type="text" data-proxy="parse" placeholder="解析代理 http://127.0.0.1:7890" value="' + esc(item.parse_proxy || '') + '" />' +
          '<input type="text" data-proxy="download" placeholder="下载代理" value="' + esc(item.download_proxy || '') + '" />' +
        '</div>' +
        '<div class="plat-input">' +
          '<button class="btn tiny" data-proxy-save>保存</button>' +
          '<button class="btn tiny ghost" data-proxy-clear>清除</button>' +
        '</div>' +
      '</div>')
    .join('');

  $('#stabProxy').innerHTML =
    '<div class="field">' +
      '<input type="text" id="proxySearch" placeholder="筛选平台…" value="' + esc(keyword) + '" />' +
      '<div class="desc">与命令行 <code>ph set proxy</code> 共用同一份配置文件，保存后立即生效。</div>' +
    '</div>' + rows;

  $('#proxySearch').addEventListener('input', debounce((event) => renderSettingsProxy(event.target.value), 220));

  $$('#stabProxy .plat-row').forEach((row) => {
    const platform = row.dataset.platform;
    row.querySelector('[data-proxy-save]').addEventListener('click', async () => {
      try {
        for (const kind of ['parse', 'download']) {
          const value = row.querySelector('[data-proxy="' + kind + '"]').value.trim();
          const path = value ? '/api/config/proxy' : '/api/config/proxy/clear';
          const body = value ? { platform, proxy: value, target: kind } : { platform, target: kind };
          await api(path, { method: 'POST', body });
        }
        toast(platform + ' 代理已保存', 'ok', 1800);
        await loadPlatforms();
        renderSettingsProxy($('#proxySearch').value);
      } catch (error) {
        toast(error.message, 'err');
      }
    });
    row.querySelector('[data-proxy-clear]').addEventListener('click', async () => {
      try {
        await api('/api/config/proxy/clear', { method: 'POST', body: { platform, target: 'all' } });
        await loadPlatforms();
        renderSettingsProxy($('#proxySearch').value);
        toast(platform + ' 代理已清除', 'ok', 1800);
      } catch (error) {
        toast(error.message, 'err');
      }
    });
  });
}

function renderSettingsCookie(keyword = '') {
  const rows = filterPlatforms(keyword)
    .map((item) =>
      '<div class="plat-row" data-platform="' + esc(item.id) + '">' +
        '<div class="plat-name">' + esc(item.name) +
          '<small class="plat-state ' + (item.cookie_set ? 'on' : 'off') + '">' + (item.cookie_set ? '已设置' : '未设置') + '</small>' +
        '</div>' +
        '<div class="plat-input">' +
          '<input type="password" data-cookie-input placeholder="' + (item.cookie_set ? esc(item.cookie_hint || '已保存') : '粘贴 Cookie…') + '" />' +
        '</div>' +
        '<div class="plat-input">' +
          '<button class="btn tiny" data-cookie-save>保存</button>' +
          '<button class="btn tiny ghost" data-cookie-clear>清除</button>' +
        '</div>' +
      '</div>')
    .join('');

  $('#stabCookie').innerHTML =
    '<div class="field">' +
      '<input type="text" id="cookieSearch" placeholder="筛选平台…" value="' + esc(keyword) + '" />' +
      '<div class="desc">支持 <code>key=value; key2=value2</code> 或 JSON 字符串，与 <code>ph set cookie</code> 共用存储。</div>' +
    '</div>' + rows;

  $('#cookieSearch').addEventListener('input', debounce((event) => renderSettingsCookie(event.target.value), 220));

  $$('#stabCookie .plat-row').forEach((row) => {
    const platform = row.dataset.platform;
    row.querySelector('[data-cookie-save]').addEventListener('click', async () => {
      const input = row.querySelector('[data-cookie-input]');
      if (!input.value.trim()) return toast('请先粘贴 Cookie', 'warn', 1800);
      try {
        await api('/api/config/cookie', { method: 'POST', body: { platform, cookie: input.value.trim() } });
        await loadPlatforms();
        renderSettingsCookie($('#cookieSearch').value);
        toast(platform + ' Cookie 已保存', 'ok', 1800);
      } catch (error) {
        toast(error.message, 'err');
      }
    });
    row.querySelector('[data-cookie-clear]').addEventListener('click', async () => {
      try {
        await api('/api/config/cookie/clear', { method: 'POST', body: { platform, cookie: '' } });
        await loadPlatforms();
        renderSettingsCookie($('#cookieSearch').value);
        toast(platform + ' Cookie 已清除', 'ok', 1800);
      } catch (error) {
        toast(error.message, 'err');
      }
    });
  });
}

async function renderSettingsAbout() {
  let info = null;
  try { info = await api('/api/config'); } catch { /* ignore */ }
  $('#stabAbout').innerHTML =
    '<div class="field">' +
      '<label>关于</label>' +
      '<div class="desc">ParseHub Studio 是 <a href="https://github.com/z-mio/ParseHub" target="_blank" rel="noreferrer">ParseHub</a> 的可视化界面，' +
      '解析与下载能力全部来自 ParseHub 本体，支持 17+ 社交平台，配置与命令行工具共用。</div>' +
      '<div class="desc">运行方式：' + (state.desktop ? 'Tauri 2 + Python sidecar' : '浏览器开发模式') +
      ' · 版本 ' + esc((state.health && state.health.version) || '0.3.0') +
      ' · FFmpeg ' + (state.health && state.health.ffmpeg ? '已就绪' : '未检测到（部分合并/转码功能不可用）') + '</div>' +
    '</div>' +
    '<div class="field">' +
      '<label>配置文件位置</label>' +
      '<div class="desc">代理配置：<code>' + esc((info && info.config_path) || '-') + '</code><br />' +
      'Cookie 配置：<code>' + esc((info && info.cookie_path) || '-') + '</code></div>' +
      '<button class="btn tiny ghost" data-reveal="' + esc((info && info.parsehub_config_dir) || '') + '" style="margin-top:8px">打开配置目录</button>' +
    '</div>' +
    '<div class="field">' +
      '<label>快捷键</label>' +
      '<div class="desc"><code>Ctrl + Enter</code> 批量解析 · <code>Esc</code> 关闭弹窗 · <code>← / →</code> 切换媒体</div>' +
    '</div><div class="field"><label>支持的平台</label><div id="platformStrip" class="platform-strip"></div></div>';
  renderPlatformStrip();
  $$('#stabAbout [data-reveal]').forEach((node) =>
    node.addEventListener('click', () => reveal(node.dataset.reveal)),
  );
}

/* ------------------------------------------------------------------ 目录选择 */

async function openPicker(targetSelector) {
  state.pickerTarget = targetSelector;
  $('#pickerModal').hidden = false;
  await browse(state.settings.default_save_dir || '');
}

async function browse(path) {
  try {
    const data = await api('/api/fs/list' + (path ? '?path=' + encodeURIComponent(path) : ''));
    state.picker = { path: data.path, dirs: data.dirs, parent: data.parent };
    $('#pickerPath').textContent = data.path || '我的电脑';
    $('#pickerList').innerHTML = data.dirs.length
      ? data.dirs.map((dir) => '<div class="picker-item" data-path="' + esc(dir.path) + '">' + ICONS.folder + '<span>' + esc(dir.name) + '</span></div>').join('')
      : '<div class="side-empty">该目录下没有子文件夹</div>';
    $('#pickerUp').disabled = !data.path;
    $$('#pickerList .picker-item').forEach((node) =>
      node.addEventListener('click', () => browse(node.dataset.path)),
    );
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function reveal(path) {
  if (!path) return toast('没有可打开的路径', 'warn', 1800);
  try {
    await api('/api/reveal', { method: 'POST', body: { path } });
  } catch (error) {
    toast(error.message, 'err');
  }
}

/* ------------------------------------------------------------------ 主流程 */

async function doParse() {
  await startBatch($('#input').value.trim(), false);
}

async function cleanInput() {
  const text = $('#input').value.trim();
  if (!text) return toast('请先粘贴分享文案或链接', 'warn', 1800);
  try {
    const info = await api('/api/clean', {
      method: 'POST',
      body: { text, use_saved_config: $('#useSavedConfig').checked },
    });
    $('#input').value = info.url;
    detect();
    toast('已清洗为：' + info.url, 'ok', 3200);
  } catch (error) {
    toast(error.message, 'err', 5000);
  }
}

async function loadPlatforms() {
  try {
    const data = await api('/api/platforms');
    state.platforms = data.platforms || [];
    renderPlatformStrip();
  } catch (error) {
    console.error(error);
  }
}

async function loadSettings() {
  try {
    state.settings = await api('/api/settings');
    state.previewMode = state.settings.preview_mode !== false;
    state.quality = state.settings.download_quality === 'thumb' ? 'thumb' : 'origin';
    document.documentElement.dataset.theme = state.settings.theme || 'dark';
  } catch (error) {
    console.error(error);
  }
}

/* ------------------------------------------------------------------ 事件绑定 */

function bindInputEvents() {
  $('#parseBtn').addEventListener('click', doParse);
  $('#downloadBtn').addEventListener('click', () => startBatch($('#input').value.trim(), true));
  $('#cleanBtn').addEventListener('click', cleanInput);
  $('#clearBtn').addEventListener('click', async () => {
    if (state.batch && state.batch.status === 'running') await api('/api/batches/' + state.batch.id + '/cancel', { method: 'POST' }).catch((error) => toast(error.message, 'err'));
    $('#input').value = '';
    state.batch = null;
    state.selectedItem = null;
    state.resultSource = '';
    state.loading = false;
    $('#batchPanel').hidden = true;
    $('#parseBtn').disabled = false;
    $('#downloadBtn').disabled = false;
    state.result = null;
    renderResult();
    detect();
  });
  $('#input').addEventListener('input', detect);
  $('#input').addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      doParse();
    }
  });
  $('#themeBtn').addEventListener('click', async () => {
    const next = (document.documentElement.dataset.theme || 'dark') === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try {
      state.settings = await api('/api/settings', { method: 'POST', body: { theme: next } });
    } catch { /* 忽略主题保存失败 */ }
  });
}

function bindModalEvents() {
  $('#settingsBtn').addEventListener('click', async () => {
    $('#settingsModal').hidden = false;
    renderSettingsGeneral();
    renderSettingsProxy();
    renderSettingsCookie();
    renderSettingsNaming();
    await renderSettingsAbout();
  });

  $$('[data-close]').forEach((node) =>
    node.addEventListener('click', () => {
      $('#' + node.dataset.close).hidden = true;
    }),
  );

  $$('.modal').forEach((modal) =>
    modal.addEventListener('click', (event) => {
      if (event.target === modal) modal.hidden = true;
    }),
  );

  $$('.tabs .tab[data-tab]').forEach((tab) =>
    tab.addEventListener('click', () => {
      $$('.tabs .tab[data-tab]').forEach((node) => node.classList.toggle('active', node === tab));
      const name = tab.dataset.tab;
      $('#tabTasks').hidden = name !== 'tasks';
      $('#tabHistory').hidden = name !== 'history';
      if (name === 'history') loadHistory();
    }),
  );

  $$('.tabs .tab[data-stab]').forEach((tab) =>
    tab.addEventListener('click', () => {
      $$('.tabs .tab[data-stab]').forEach((node) => node.classList.toggle('active', node === tab));
      const name = tab.dataset.stab;
      $('#stabGeneral').hidden = name !== 'general';
      $('#stabNaming').hidden = name !== 'naming';
      $('#stabProxy').hidden = name !== 'proxy';
      $('#stabCookie').hidden = name !== 'cookie';
      $('#stabAbout').hidden = name !== 'about';
    }),
  );
}

async function handleClick(event) {
  const mini = event.target.closest('[data-action]');
  if (mini) {
    event.stopPropagation();
    const index = Number(mini.dataset.index);
    const media = state.result && state.result.media && state.result.media[index];
    if (!media) return;
    if (mini.dataset.action === 'download') createTask({ text: currentSource(), mediaIndex: index });
    if (mini.dataset.action === 'load') loadTilePreview(index);
    if (mini.dataset.action === 'open') openExternal(media.url);
    return;
  }

  const tile = event.target.closest('.media-tile');
  if (tile && state.result) {
    openLightbox(Number(tile.dataset.index));
    return;
  }

  const resultAction = event.target.closest('[data-result-action]');
  if (resultAction) {
    const kind = resultAction.dataset.resultAction;
    if (kind === 'expand-text') {
      const caption = $('.post-caption');
      const expanded = caption.classList.toggle('expanded');
      resultAction.textContent = expanded ? '收起正文' : '展开全文';
      resultAction.setAttribute('aria-expanded', String(expanded));
    }
    if (kind === 'download-all') createTask({ text: currentSource() });
    if (kind === 'copy-json') copyText(JSON.stringify(state.result, null, 2), 'JSON 已复制');
    if (kind === 'open-raw' && state.result && state.result.raw_url) openExternal(state.result.raw_url);
    if (kind === 'reparse') startBatch(currentSource(), false);
    return;
  }

  const lbAction = event.target.closest('[data-lb-action]');
  if (lbAction) {
    const index = state.lightbox.index;
    const media = state.lightbox.items[index];
    if (!media) return;
    const kind = lbAction.dataset.lbAction;
    if (kind === 'load') {
      state.lightbox.loaded = true;
      renderLightbox();
      return;
    }
    if (kind === 'save') createTask({ text: currentSource(), mediaIndex: index });
    if (kind === 'browser') {
      if (state.desktop) createTask({ text: currentSource(), mediaIndex: index });
      else window.open(proxied(qualitySource(media), { download: true, filename: fileNameFor(media, index) }), '_blank');
    }
    if (kind === 'copy') copyText(qualitySource(media), '媒体链接已复制');
    if (kind === 'open') openExternal(media.url);
    return;
  }

  const taskAction = event.target.closest('[data-task-action]');
  if (taskAction) {
    const task = state.tasks.find((item) => item.id === taskAction.dataset.id);
    if (!task) return;
    if (taskAction.dataset.taskAction === 'reveal') reveal(task.output_dir);
    if (taskAction.dataset.taskAction === 'cancel') {
      try {
        await api('/api/tasks/' + task.id + '/cancel', { method: 'POST' });
      } catch (error) {
        toast(error.message, 'err');
      }
    }
    return;
  }

  const historyAction = event.target.closest('[data-history-action]');
  if (historyAction) {
    event.stopPropagation();
    try {
      await api('/api/history/' + historyAction.dataset.id, { method: 'DELETE' });
      await loadHistory();
    } catch (error) {
      toast(error.message, 'err');
    }
    return;
  }

  const historyItem = event.target.closest('.hist-item');
  if (historyItem) {
    const entry = state.history.find((item) => item.id === historyItem.dataset.id);
    if (entry) {
      state.result = entry;
      state.resultSource = entry.raw_url;
      setView('parse');
      highlightPlatform((entry.platform && entry.platform.id) || null);
      renderResult();
      toast('已载入历史记录', 'ok', 1600);
    }
    return;
  }

  const pickerButton = event.target.closest('[data-picker-for]');
  if (pickerButton) openPicker(pickerButton.dataset.pickerFor);
}

function bindPickerEvents() {
  $('#pickerUp').addEventListener('click', () => state.picker.parent && browse(state.picker.parent));
  $('#pickerConfirm').addEventListener('click', () => {
    const target = state.pickerTarget;
    if (target && state.picker.path) {
      const input = $(target);
      if (input) input.value = state.picker.path;
    }
    $('#pickerModal').hidden = true;
  });
}

function bindLightboxEvents() {
  const step = (delta) => {
    const total = state.lightbox.items.length;
    state.lightbox.index = Math.max(0, Math.min(total - 1, state.lightbox.index + delta));
    state.lightbox.loaded = true;
    renderLightbox();
  };
  $('#lbClose').addEventListener('click', closeLightbox);
  $('#lbPrev').addEventListener('click', () => step(-1));
  $('#lbNext').addEventListener('click', () => step(1));
  $('#lightbox').addEventListener('click', (event) => {
    if (event.target.id === 'lightbox') closeLightbox();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeLightbox();
      $$('.modal').forEach((modal) => (modal.hidden = true));
      return;
    }
    if ($('#lightbox').hidden) return;
    if (event.key === 'ArrowLeft') step(-1);
    if (event.key === 'ArrowRight') step(1);
  });
}

function bindMediaFallback() {
  const indexOf = (node) => {
    const raw = node.dataset ? node.dataset.tileIndex : undefined;
    return raw === undefined ? null : Number(raw);
  };
  // 缩略图加载完成才放出下载按键；1x1 占位图视为「无预览图」
  document.addEventListener('load', (event) => {
    const node = event.target;
    if (!(node instanceof HTMLImageElement)) return;
    const index = indexOf(node);
    if (index === null) return;
    setTileState(index, node.naturalWidth > 2 && node.naturalHeight > 2 ? 'ready' : 'blank');
  }, true);
  document.addEventListener('loadeddata', (event) => {
    const node = event.target;
    if (!(node instanceof HTMLVideoElement)) return;
    const index = indexOf(node);
    if (index !== null) setTileState(index, 'ready');
  }, true);
  document.addEventListener('error', (event) => {
    const node = event.target;
    if (!(node instanceof HTMLImageElement) && !(node instanceof HTMLVideoElement)) return;
    const direct = node.dataset && node.dataset.src;
    if (direct && node.dataset.fallback !== '1') {
      node.dataset.fallback = '1';
      node.src = proxied(direct);
      return;
    }
    const index = indexOf(node);
    if (index !== null) setTileState(index, 'error');
  }, true);
}

async function persistSettings(patch) {
  try {
    state.settings = await api('/api/settings', { method: 'POST', body: patch });
  } catch (error) {
    toast(error.message, 'err');
  }
}

function bindToolbarEvents() {
  document.addEventListener('change', async (event) => {
    const target = event.target;
    if (target.id === 'qualitySelect') {
      state.quality = target.value;
      await persistSettings({ download_quality: state.quality });
      toast(state.quality === 'thumb' ? '下载规格：预览图' : '下载规格：原图 / 原视频', 'ok', 1600);
    } else if (target.id === 'previewToggle') {
      state.previewMode = target.checked;
      await persistSettings({ preview_mode: state.previewMode });
      refreshGrid();
    }
  });
}

function currentSource() {
  return state.resultSource || (state.result && state.result.raw_url) || $('#input').value.trim();
}

function setView(view) {
  $('#workspace').dataset.view = view;
  $('#pageTitle').textContent = { parse: '解析工作台', tasks: '下载管理', history: '历史记录' }[view];
  $$('.nav-item[data-view]').forEach((button) => {
    button.classList.toggle('active', button.dataset.view === view);
    if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  $('#tabTasks').hidden = view !== 'tasks';
  $('#tabHistory').hidden = view !== 'history';
  $('#workspaceScroll').scrollTop = 0;
  if (view === 'tasks') refreshTasks();
  if (view === 'history') loadHistory();
}

async function startBatch(text, download) {
  if (state.batch && state.batch.status === 'running') return toast('当前批次仍在解析，请先完成或取消', 'warn');
  if (!text) return toast('请粘贴链接或分享文案', 'warn');
  $('#parseBtn').disabled = true;
  $('#downloadBtn').disabled = true;
  try {
    state.batch = await api('/api/batches', { method: 'POST', body: { text, download, use_saved_config: $('#useSavedConfig').checked } });
    state.selectedItem = null;
    state.result = null;
    state.resultSource = '';
    state.loading = true;
    setView('parse');
    renderBatch();
    renderResult();
  } catch (error) {
    toast(error.message, 'err');
    $('#parseBtn').disabled = false;
    $('#downloadBtn').disabled = false;
  }
}

function selectBatchItem(item) {
  if (!item || item.status !== 'success') return;
  closeLightbox();
  state.selectedItem = item.id;
  state.resultSource = item.url;
  state.result = item.result;
  state.loading = false;
  renderResult();
  renderBatch();
}

function renderBatch() {
  const batch = state.batch;
  if (!batch) return;
  const panel = $('#batchPanel');
  panel.hidden = false;
  const completed = batch.items.filter((item) => !['pending', 'running'].includes(item.status)).length;
  const failed = batch.items.filter((item) => item.status === 'error').length;
  panel.innerHTML = '<div class="batch-head"><h2>本次解析 · ' + completed + ' / ' + batch.items.length + (failed ? ' · ' + failed + ' 条失败' : '') + '</h2>' +
    (batch.status === 'running' ? '<button class="btn tiny ghost" data-batch-action="cancel">取消未完成</button>' :
      (failed ? '<button class="btn tiny" data-batch-action="retry">重试失败项</button>' : '<span class="desc">点击卡片切换作品</span>')) + '</div>' +
    '<div class="batch-list">' + batch.items.map((item, index) => {
      const title = item.result && (item.result.title || item.result.content || '').split('\n')[0];
      const status = { pending: '排队中', running: '正在解析…', success: '解析完成', error: '解析失败', cancelled: '已取消' }[item.status];
      const platform = item.result && item.result.platform;
      return '<button class="batch-item' + (state.selectedItem === item.id ? ' active' : '') + '" data-batch-item="' + item.id + '" data-status="' + item.status + '" title="' + esc(item.error || item.url) + '">' +
        '<strong>' + (index + 1) + '. ' + esc(title || item.url) + '</strong><small>' + esc(item.error || ((platform ? platform.name + ' · ' : '') + status)) + '</small></button>';
    }).join('') + '</div>';
}

async function refreshBatch() {
  if (!state.batch || state.batch.status !== 'running' || state.batchChecking) return;
  const batchId = state.batch.id;
  state.batchChecking = true;
  try {
    const batch = await api('/api/batches/' + batchId);
    if (!state.batch || state.batch.id !== batchId) return;
    state.batch = batch;
    if (!state.selectedItem) selectBatchItem(batch.items.find((item) => item.status === 'success'));
    renderBatch();
    if (batch.status !== 'running') {
      state.loading = false;
      $('#parseBtn').disabled = false;
      $('#downloadBtn').disabled = false;
      if (!state.result) renderResult();
      await loadHistory();
      await refreshTasks();
    }
  } catch (error) {
    console.warn(error.message);
  } finally {
    state.batchChecking = false;
  }
}

function bindWorkspaceEvents() {
  $$('.nav-item[data-view]').forEach((button) => button.addEventListener('click', () => setView(button.dataset.view)));
  $('#aboutBtn').addEventListener('click', () => { $('#settingsBtn').click(); $('[data-stab="about"]').click(); });
  $('#externalParserBtn').addEventListener('click', () => {
    if (state.desktop) window.location.assign('/external/douyin');
    else openExternal('https://dy.kukutool.com/');
  });
  $('#batchPanel').addEventListener('click', async (event) => {
    const item = event.target.closest('[data-batch-item]');
    if (item) selectBatchItem(state.batch.items.find((entry) => entry.id === item.dataset.batchItem));
    const action = event.target.closest('[data-batch-action]');
    if (!action) return;
    if (action.dataset.batchAction === 'retry') startBatch(state.batch.items.filter((entry) => entry.status === 'error').map((entry) => entry.url).join('\n'), false);
    else {
      try { await api('/api/batches/' + state.batch.id + '/cancel', { method: 'POST' }); await refreshBatch(); }
      catch (error) { toast(error.message, 'err'); }
    }
  });
}

function renderSettingsNaming() {
  const names = { platform: '平台', author: '作者', title: '标题', id: '作品 ID', date: '下载日期', time: '下载时间', 'index:03d': '序号 001', type: '媒体类型' };
  $('#stabNaming').innerHTML = '<div class="field"><label for="folderTemplate">作品文件夹</label><input type="text" maxlength="200" spellcheck="false" id="folderTemplate" value="' + esc(state.settings.folder_template || '{platform}_{title}_{id}') + '" /></div>' +
    '<div class="field"><label for="fileTemplate">媒体文件名</label><input type="text" maxlength="200" spellcheck="false" id="fileTemplate" value="' + esc(state.settings.file_template || '{index:03d}_{title}') + '" /></div>' +
    '<p class="desc">先点选输入框，再点击元素插入。扩展名自动保留；不支持目录分隔符。作者由平台返回，缺失时使用“未知作者”。日期/时间指下载时间。</p>' +
    '<div class="naming-elements">' + Object.entries(names).map(([key, label]) => '<button class="btn tiny" data-token="' + key + '">' + label + '</button>').join('') + '</div>' +
    '<div class="naming-preview" id="namingPreview" role="status"></div><p class="desc">标题最多 40 字，生成名称最多 100 字；非法字符自动替换，重名加序号，不覆盖已有文件。实况视频额外加 _live。</p>' +
    '<button class="btn primary" id="saveNaming">保存命名规则</button>';
  let target = $('#fileTemplate');
  let revision = 0;
  const preview = async () => {
    const current = ++revision;
    try {
      const result = await api('/api/naming/preview', { method: 'POST', body: { folder_template: $('#folderTemplate').value, file_template: $('#fileTemplate').value } });
      if (revision === current) $('#namingPreview').textContent = result.folder + '\n  └ ' + result.file;
    } catch (error) { if (revision === current) $('#namingPreview').textContent = error.message; }
  };
  ['#folderTemplate', '#fileTemplate'].forEach((selector) => {
    $(selector).addEventListener('focus', () => { target = $(selector); });
    $(selector).addEventListener('input', debounce(preview, 200));
  });
  $$('#stabNaming [data-token]').forEach((button) => button.addEventListener('click', () => {
    target.setRangeText('{' + button.dataset.token + '}', target.selectionStart, target.selectionEnd, 'end');
    target.focus(); preview();
  }));
  $('#saveNaming').addEventListener('click', async () => {
    try {
      state.settings = await api('/api/settings', { method: 'POST', body: { folder_template: $('#folderTemplate').value, file_template: $('#fileTemplate').value } });
      toast('命名规则已保存，对新下载任务生效', 'ok');
    } catch (error) { toast(error.message, 'err'); }
  });
  preview();
}

function updateViewer() {
  const image = $('#lbStage img');
  const viewport = $('#lbViewport');
  if (!image || !image.naturalWidth) return;
  const viewer = state.viewer;
  const rotated = viewer.rotation % 180 !== 0;
  const width = rotated ? image.naturalHeight : image.naturalWidth;
  const height = rotated ? image.naturalWidth : image.naturalHeight;
  viewer.fit = Math.min(1, viewport.clientWidth / width, viewport.clientHeight / height);
  const scale = viewer.fit * viewer.zoom;
  const maxX = Math.max(0, (width * scale - viewport.clientWidth) / 2);
  const maxY = Math.max(0, (height * scale - viewport.clientHeight) / 2);
  viewer.panX = Math.max(-maxX, Math.min(maxX, viewer.panX));
  viewer.panY = Math.max(-maxY, Math.min(maxY, viewer.panY));
  image.style.width = image.naturalWidth + 'px';
  image.style.height = image.naturalHeight + 'px';
  image.style.left = '50%'; image.style.top = '50%';
  image.style.transform = 'translate(-50%, -50%) translate(' + viewer.panX + 'px,' + viewer.panY + 'px) rotate(' + viewer.rotation + 'deg) scale(' + scale + ')';
  if ($('#zoomLabel')) $('#zoomLabel').textContent = Math.round(scale * 100) + '%';
}

function changeZoom(zoom) {
  state.viewer.zoom = Math.max(0.1, Math.min(Math.max(20, 2 / state.viewer.fit), zoom));
  updateViewer();
}

function bindViewerEvents() {
  const viewport = $('#lbViewport');
  let drag = null;
  $('#lbStage').addEventListener('load', updateViewer, true);
  $('#lbStage').addEventListener('dragstart', (event) => event.preventDefault());
  $('#lbBar').addEventListener('click', (event) => {
    const button = event.target.closest('[data-viewer]');
    if (!button) return;
    const viewer = state.viewer;
    if (button.dataset.viewer === 'in') changeZoom(viewer.zoom * 1.25);
    if (button.dataset.viewer === 'out') changeZoom(viewer.zoom / 1.25);
    if (button.dataset.viewer === 'actual') changeZoom(1 / viewer.fit);
    if (button.dataset.viewer === 'rotate') {
      const scale = viewer.fit * viewer.zoom;
      viewer.rotation = (viewer.rotation + 90) % 360;
      updateViewer();
      changeZoom(scale / viewer.fit);
    }
    if (button.dataset.viewer === 'fit') { viewer.zoom = 1; viewer.panX = 0; viewer.panY = 0; updateViewer(); }
  });
  viewport.addEventListener('wheel', (event) => {
    if (!$('#lbStage img')) return;
    event.preventDefault();
    changeZoom(state.viewer.zoom * (event.deltaY < 0 ? 1.15 : 1 / 1.15));
  }, { passive: false });
  viewport.addEventListener('dblclick', () => changeZoom(state.viewer.zoom === 1 ? 1 / state.viewer.fit : 1));
  viewport.addEventListener('pointerdown', (event) => {
    if (!$('#lbStage img') || event.button !== 0) return;
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, panX: state.viewer.panX, panY: state.viewer.panY };
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener('pointermove', (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    state.viewer.panX = drag.panX + event.clientX - drag.x;
    state.viewer.panY = drag.panY + event.clientY - drag.y;
    updateViewer();
  });
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((name) => viewport.addEventListener(name, () => { drag = null; }));
  window.addEventListener('resize', updateViewer);
}

async function init() {
  document.addEventListener('click', (event) => {
    const anchor = event.target.closest('a[href]');
    if (!anchor) return;
    event.preventDefault();
    openExternal(anchor.href);
  });
  bindInputEvents();
  bindModalEvents();
  bindPickerEvents();
  bindLightboxEvents();
  bindMediaFallback();
  bindToolbarEvents();
  bindWorkspaceEvents();
  bindViewerEvents();
  document.addEventListener('click', handleClick);
  renderEmpty();
  await checkService();
  setInterval(checkService, 5000);
  await Promise.all([loadPlatforms(), loadSettings(), loadHistory()]);
  await refreshTasks();
  setInterval(refreshTasks, 1200);
  setInterval(loadHistory, 20000);
  setInterval(refreshBatch, 900);
}

init().catch((error) => {
  toast('界面初始化失败：' + error.message, 'err', 8000);
  checkService();
});
