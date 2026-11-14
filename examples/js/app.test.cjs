const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');

function page() {
  const elements = new Map();
  for (const id of ['example-input', 'example-result', 'example-apply', 'project', 'status']) {
    elements.set(`#${id}`, { value: '', textContent: '', addEventListener(type, callback) { this[type] = callback; } });
  }
  const window = {};
  vm.runInNewContext(source, { window, document: { querySelector: id => elements.get(id) } });
  return { elements, window };
}

test('apply button displays trimmed input', () => {
  const { elements } = page();
  elements.get('#example-input').value = '  verified-input  ';
  elements.get('#example-apply').click();
  assert.equal(elements.get('#example-result').textContent, '已应用：verified-input');
});

test('missing host bridge is reported explicitly', async () => {
  const { elements } = page();
  await elements.get('#project').click();
  assert.match(elements.get('#status').textContent, /桥接尚未就绪/);
});

test('bridge failure is reported and never converted to success', async () => {
  const { elements, window } = page();
  window.JTools = { getProjectBasePath: async () => { throw new Error('bridge failure'); } };
  await elements.get('#project').click();
  assert.equal(elements.get('#status').textContent, '读取项目失败：bridge failure');
});
