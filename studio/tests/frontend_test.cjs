const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const elements = new Map();
const element = (selector) => {
  if (!elements.has(selector)) elements.set(selector, { textContent: '', innerHTML: '', dataset: {}, hidden: true });
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
  assert.equal(vm.runInContext("previewSource({kind:'image', url:'original', thumb_url:'tiny', preview_url:'hd'})", context), 'hd');
  vm.runInContext('state.previewMode = false', context);
  assert.equal(vm.runInContext("previewSource({kind:'image', url:'original', thumb_url:'tiny', preview_url:'hd'})", context), 'original');
  assert.equal(vm.runInContext("previewSource({kind:'video', url:'movie', thumb_url:'cover'})", context), 'cover');
  vm.runInContext("state.result={media:[{kind:'image', url:'https://example.com/original.jpg', thumb_url:'https://example.com/tiny.jpg', kind_label:'图片'}]}; state.previewMode=true; openLightbox(0)", context);
  assert.ok(element('#lbStage').innerHTML.includes('/api/media?'));
  assert.ok(element('#lbStage').innerHTML.includes('original.jpg'));
  assert.ok(!element('#lbStage').innerHTML.includes('tiny.jpg'));
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
  console.log('HD preview, original lightbox, service hysteresis and recovery passed');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
