"""在当前示例目录外生成仅含显式发布资源的 ZIP；默认拒绝覆盖。"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent
output_dir = root.parent / "build"
output_dir.mkdir(exist_ok=True)
output = output_dir / "jtools-js-example-1.0.0-plugin.zip"
entries = ("pluginInfo.json", "index.html", "app.js", "style.css", "icons/plugin.png", "icons/tab.png")
for name in entries:
    path = root / name
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"发布资源缺失或为符号链接: {name}")
with ZipFile(output, "x", ZIP_DEFLATED) as archive:
    for name in entries:
        archive.write(root / name, name)
print(output)
