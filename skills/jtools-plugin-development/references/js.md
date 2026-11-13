# JS / JCEF 模板

复制 `assets/templates/js` 的完整目录内容。无需 Maven、Gradle、Java SDK 或 IPlugin。入口为包根 `pluginInfo.json`，配置 `pluginName/pluginDesc/pluginVersion/pluginIcon/pluginTabIcon/indexPage`，本地资源用相对路径；支持外部 HTTP(S) 页面。

本模板是原生 HTML/CSS/JS，无依赖安装和前端构建。增加 Vue/Vite 等时依照项目自己的 package.json、锁文件和构建命令，把配置、入口、静态产物和图标组装到发布目录再 ZIP，不打包 node_modules、缓存、凭据。

## 宿主 SDK

宿主在页面加载时注入 `window.JTools`（注意大小写），模板附真实 `jtools-sdk.d.ts`；不要猜成 `jtools` 或引入前端假的成功实现。

```js
if (!window.JTools) throw new Error('请在 JTools 内打开，或等待 SDK 注入完成');
const basePath = await JTools.getProjectBasePath();
await JTools.notifyInfo('Demo', basePath);
const apis = await JTools.help();
```

模板在按钮点击时访问 SDK；未就绪显示明确提示并允许用户重试，不假装已连接。普通浏览器只能验证页面和静态资源，无法验证宿主桥接。每次对照 d.ts 与 `JTools.help()` 使用实际 API。SDK 由宿主注入，不随扩展复制整个主插件。

打包时 ZIP 根直接含配置、index.html、app.js、style.css 和 icons；见 packaging.md。图标分别 48×48、14×14。JCEF 的媒体编解码由 JBR 提供，前端标签无法补 H.264/H.265/AAC 解码器；需要媒体时在目标 IDE 验证，可选 WebM VP8/VP9 + Opus。
