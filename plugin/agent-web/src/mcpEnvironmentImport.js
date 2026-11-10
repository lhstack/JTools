export function parseEnvironmentImport(text) {
  const entries = text.split(/\r?\n/).flatMap((line, index) => {
    if (!line.trim()) return [];
    const separator = line.search(/[=:]/);
    if (separator < 0) throw new Error(`第 ${index + 1} 行缺少 = 或 : 分隔符`);
    const key = line.slice(0, separator).trim();
    if (!key) throw new Error(`第 ${index + 1} 行的环境变量名称不能为空`);
    return [[key, line.slice(separator + 1).trim()]];
  });
  if (!entries.length) throw new Error("请输入需要导入的环境变量");
  return Object.fromEntries(entries);
}
