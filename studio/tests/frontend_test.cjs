const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const elements = new Map();
const element = (selector) => {
  if (!elements.has(selector)) elements.set(selector, { textContent: '', innerHTML: '', dataset: {}, hidden: true, focus() {}, style: {}, clientWidth: 1000, clientHeight: 700 });
  return elements.get(selector);
};
const context = vm.createContext({
  document: { querySelector: element, querySelectorAll: () => [] },
  URL, URLSearchParams, AbortSignal, console,
  setTimeout, clearTimeout,
  fetch: async () => { throw new Error('temporary delay'); },
});
const source = fs.readFileSync(path.join(__dirname, '../src/parsehub_studio/web/app.js'), 'utf8');
vm.runInContext(source.slice(0, source.lastIndexOf('\ninit().catch')), context);

async function main() {
  assert.equal(vm.runInContext("groupedMarkup([{p:'微博',v:'one'},{p:'抖音',v:'two'},{p:'微博',v:'three'}], item=>item.p, item=>item.v).includes('onethree')", context), true);
  assert.ok(vm.runInContext("tileMarkup({kind:'video',kind_label:'视频',width:1920,height:1080},0)", context).includes('1920 × 1080'));
  vm.runInContext("state.result={raw_url:'https://douyin.com/wrong'};state.history=[{id:'h1',platform:{name:'微博'},raw_url:'https://weibo.com/right',media:[{kind:'image',url:'https://example.com/original.jpg',preview_url:'https://example.com/preview.jpg'}]}];renderHistory()", context);
  assert.ok(element('#tabHistory').innerHTML.includes('preview.jpg'));
  assert.ok(element('#tabHistory').innerHTML.includes('weibo.com%2Fright'));
  assert.ok(!element('#tabHistory').innerHTML.includes('douyin.com'));
  vm.runInContext("state.tasks=[{id:'t1',status:'running',text:'test',stage:'当前文件下载中',current:50,total:100,unit:'bytes',progress:0.5}];renderTasks()", context);
  assert.equal(element('#downloadDock').hidden, false);
  assert.ok(element('#downloadDock').innerHTML.includes('50%'));
  assert.equal(vm.runInContext("previewSource({kind:'image', url:'original', thumb_url:'tiny', preview_url:'hd'})", context), 'hd');
  vm.runInContext('state.previewMode = false', context);
  assert.equal(vm.runInContext("previewSource({kind:'image', url:'original', thumb_url:'tiny', preview_url:'hd'})", context), 'original');
  assert.equal(vm.runInContext("previewSource({kind:'video', url:'movie', thumb_url:'cover'})", context), 'cover');
  vm.runInContext("state.result={media:[{kind:'image', url:'https://example.com/original.jpg', thumb_url:'https://example.com/tiny.jpg', kind_label:'图片'}]}; state.previewMode=true; openLightbox(0)", context);
  assert.ok(element('#lbStage').innerHTML.includes('/api/media?'));
  assert.ok(element('#lbStage').innerHTML.includes('original.jpg'));
  assert.ok(!element('#lbStage').innerHTML.includes('tiny.jpg'));
  assert.ok(element('#lbBar').innerHTML.includes('data-viewer="actual"'));
  element('#lbStage img').naturalWidth = 2000;
  element('#lbStage img').naturalHeight = 1400;
  vm.runInContext('updateViewer()', context);
  assert.equal(element('#zoomLabel').textContent, '50%');
  vm.runInContext('changeZoom(1 / state.viewer.fit)', context);
  assert.equal(element('#zoomLabel').textContent, '100%');
  vm.runInContext('state.viewer.panX=9999; state.viewer.panY=-9999; updateViewer()', context);
  assert.equal(vm.runInContext('state.viewer.panX', context), 500);
  assert.equal(vm.runInContext('state.viewer.panY', context), -350);
  vm.runInContext("state.resultSource='https://weibo.com/correct';", context);
  element('#input').value = 'https://douyin.com/different';
  assert.equal(vm.runInContext('currentSource()', context), 'https://weibo.com/correct');
  await vm.runInContext('checkService()', context);
  assert.equal(element('#serviceAlert').hidden, true);
  await vm.runInContext('checkService()', context);
  assert.equal(element('#serviceAlert').hidden, true);
  await vm.runInContext('checkService()', context);
  assert.equal(element('#serviceAlert').hidden, false);
  context.fetch = async () => ({ ok: true, json: async () => ({ desktop: true, version: '0.2.0' }) });
  await vm.runInContext('checkService()', context);
  assert.equal(element('#serviceAlert').hidden, true);
  assert.equal(vm.runInContext('state.serviceFailures', context), 0);
  console.log('HD preview, original lightbox, zoom/pan, selected source and service recovery passed');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
