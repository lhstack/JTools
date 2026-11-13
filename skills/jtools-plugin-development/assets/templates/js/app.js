/// <reference path="./jtools-sdk.d.ts" />
const status = document.querySelector('#status');
document.querySelector('#project').addEventListener('click', async () => {
  if (!window.JTools) {
    status.textContent = 'JTools 桥接尚未就绪，请在 JTools 插件面板加载完成后重试。';
    return;
  }
  try {
    status.textContent = await JTools.getProjectBasePath();
  } catch (error) {
    status.textContent = `读取项目失败：${error.message || String(error)}`;
  }
});
