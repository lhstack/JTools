const result = document.querySelector('#example-result');
const status = document.querySelector('#status');

function formatMessage(text) {
  return `已应用：${text.trim()}`;
}

function applyInput() {
  const text = document.querySelector('#example-input').value;
  result.textContent = formatMessage(text);
}

document.querySelector('#example-apply').addEventListener('click', applyInput);
document.querySelector('#project').addEventListener('click', async () => {
  if (!window.JTools) {
    status.textContent = 'JTools 桥接尚未就绪，请在宿主面板加载完成后重试。';
    return;
  }
  try {
    status.textContent = await window.JTools.getProjectBasePath();
  } catch (error) {
    status.textContent = `读取项目失败：${error.message || String(error)}`;
  }
});
