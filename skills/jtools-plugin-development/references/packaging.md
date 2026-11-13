# 打包与交付

## Java

Maven 执行 clean package；Gradle 执行 clean shadowJar。交付模板生成的 `*-plugin.jar`，可直接由 JTools 安装。先 `jar tf` 检查：

- 根目录含 META-INF/ToolsPlugin.txt，内容指向实际存在的 public 无参入口类。
- `example/DemoPlugin.class`（改名后对应路径）、icons/plugin.png、icons/tab.png 均存在。
- 不含 `com/lhstack/tools/plugins/IPlugin.class`，不含 IntelliJ 平台类；第三方运行库所需类与资源已合并。
- 不能把 dependency/*.jar 塞进 ZIP 当作类路径，加载器不自动展开其嵌套依赖。

若交付 ZIP，先把业务类与资源展开到独立发布目录，根含 META-INF/ToolsPlugin.txt；ZIP 根不能是项目文件夹。优先使用模板生成的合并 JAR，避免多一次组装。

## JS

无需构建的 JS 模板，可在模板副本根执行标准库 ZIP 操作。下例显式列出发布文件、拒绝覆盖并把输出放在源码目录外：

```python
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
root = Path.cwd().resolve()
output = root.parent / (root.name + '-plugin.zip')
entries = ['pluginInfo.json', 'index.html', 'app.js', 'style.css', 'icons/plugin.png', 'icons/tab.png']
for name in entries:
    file = root / name
    if file.is_symlink() or not file.is_file():
        raise ValueError(f'发布文件缺失或为符号链接: {name}')
with ZipFile(output, 'x', ZIP_DEFLATED) as archive:
    for name in entries:
        archive.write(root / name, name)
print(output, output.stat().st_size)
```

在原生 JS 模板副本中执行，不在 Skill 源目录写构建产物。增加资源后显式更新 entries；使用前端构建时改为已核实的发布目录清单。覆盖旧产物前先获得用户允许或选新版本名。不要递归把 .git、SDK、node_modules、密码和构建缓存混入 ZIP。

## 验收记录

报告构建命令、实际 JDK/平台版本、返回码、产物路径与大小、入口检查和 SHA-256（如 `shasum -a 256` 或 PowerShell Get-FileHash）。用目标 JTools 安装并检查开关面板、功能、项目关闭与卸载。编译/静态检查通过不能代替现场运行结果。
