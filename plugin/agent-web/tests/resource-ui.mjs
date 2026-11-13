import { createRequire } from "node:module";
const require = createRequire(
  process.env.JTOOLS_BROWSER_MODULE_BASE || import.meta.url,
);
const { chromium } = require("playwright-core");
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
const root = new URL("../../src/main/resources/agent-web/", import.meta.url)
  .pathname;
const server = createServer(async (req, res) => {
  try {
    const path = req.url.split("?")[0];
    res.setHeader(
      "content-type",
      path.endsWith(".js")
        ? "application/javascript"
        : path.endsWith(".css")
          ? "text/css"
          : "text/html",
    );
    res.end(
      await readFile(
        resolve(root, path === "/" ? "index.html" : path.slice(1)),
      ),
    );
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const browser = await chromium.launch({
  executablePath: process.env.BROWSER_EXECUTABLE,
  headless: true,
});
const errors = [];
const artifacts = process.env.VISUAL_ARTIFACTS;
if (artifacts) await mkdir(artifacts, { recursive: true });
async function pageFor(kind, theme = "dark", width = 1124) {
  const page = await browser.newPage({
    viewport: { width, height: 774 },
  });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.addInitScript(
    ({ kind }) => {
      window.__JTOOLS_PAGE__ = kind;
      if (kind === "chat") {
        localStorage.setItem("jtools:selected-session:v1", "999999");
        localStorage.setItem(
          "jtools:chat-messages:v1:999999",
          JSON.stringify([
            { id: 1, persisted: true, content: "OTHER_PROJECT_MESSAGE" },
          ]),
        );
      }
      window.testCalls = [];
      const env = {
        id: 1,
        name: "Test environment",
        enabled: true,
        config: {
          tools: { bash: false },
          tools_include_new: true,
          resources: {
            mcp: { 1: false },
            mcp_include_new: true,
            skills: { off: false },
            skills_include_new: true,
          },
          contexts: {},
          subagents: { enabled: false, agents: [] },
        },
      };
      const agent = {
        id: 1,
        name: "Test agent",
        enabled: true,
        providerId: 1,
        modelId: 1,
        toolOverrides: { bash: false },
        skillOverrides: { off: false },
        pluginOverrides: {},
        toolsIncludeNew: true,
        skillsIncludeNew: true,
        pluginFunctionsIncludeNew: true,
        viewResources: {},
        persona: {},
      };
      const mcps = {
        global: [
          {
            id: 1,
            name: "Global MCP",
            transport: "stdio",
            command: "node",
            args: [],
            env: {},
            headers: {},
            enabled: true,
          },
        ],
        project: [
          {
            id: "project-docs",
            name: "Project MCP",
            transport: "stdio",
            command: "node",
            args: [],
            env: {},
            headers: {},
          },
        ],
      };
      const skills = {
        global: [
          { name: "same", description: "Global", available: true },
          { name: "off", description: "Off", available: true },
          { name: "new", description: "New", available: true },
        ],
        project: [{ name: "same", description: "Project", available: true }],
      };
      const subs = {
        reviewer: {
          name: "Review",
          model: { provider_id: 1, model_id: 1 },
          system_prompt: "Read only",
          tools: ["read_project_files"],
          skills: [],
          access_mode: "read_only",
          bash_enabled: false,
        },
      };
      window.jtoolsInvoke = async (raw) => {
        const { type, payload } = JSON.parse(raw);
        window.testCalls.push({
          type,
          payload: JSON.parse(JSON.stringify(payload)),
        });
        let data;
        const catalog = {
          agents: [agent],
          providers: [
            {
              id: 1,
              name: "Provider",
              models: [{ id: 1, name: "Model", alias: "model-alias" }],
            },
            {
              id: 2,
              name: "Second provider",
              models: [{ id: 2, name: "Model", alias: "model-alias" }],
            },
          ],
          prompts: [],
          tools: ["read_project_files", "bash", "skills_view"],
          skills: ["same", "off", "new"],
          pluginFunctionGroups: [],
        };
        if (type === "resource.open") data = { path: payload.reference };
        else if (type === "image.copy") data = { copied: true };
        else if (type === "image.save")
          data = { saved: true, path: "/tmp/saved-image.png" };
        else if (type === "attachment.preview")
          data = {
            url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZp8AAAAASUVORK5CYII=",
          };
        else if (type === "resource.media") data = {url: window.testMediaUrl, mime: "video/webm"};
        else if (type === "resource.preview")
          data = {
            url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZp8AAAAASUVORK5CYII=",
            revision: "1",
          };
        else if (type === "catalog.get")
          data = {
            providers: [
              {
                provider: {
                  id: 1,
                  name: "Provider",
                  kind: "openai",
                  api: "completions",
                  enabled: 1,
                },
                models: [
                  {
                    id: 1,
                    providerId: 1,
                    alias: "model",
                    modelId: "model",
                    modalities: ["text", "image"],
                    modelParams: "{}",
                    executionParams: "{}",
                    additionalParams: "{}",
                    enabled: 1,
                  },
                ],
              },
            ],
          };
        else if (type === "agents.catalog") data = catalog;
        else if (type === "agent.save") {
          Object.assign(agent, payload);
          data = null;
        } else if (type === "environments.catalog")
          data = {
            environments: [env],
            agents: [],
            skills: skills.global,
            tools: catalog.tools,
            pluginFunctionGroups: [],
            mcpServers: mcps.global.concat([
              {
                id: 2,
                name: "Inherited MCP",
                transport: "stdio",
                enabled: true,
              },
            ]),
          };
        else if (type === "environment.save") {
          env.config = payload.config;
          data = env;
        } else if (type === "environment.enabled") {
          env.enabled = payload.enabled;
          data = env;
        } else if (type === "mcp.list") data = mcps[payload.scope];
        else if (type === "mcp.save") {
          const list = mcps[payload.scope];
          const entry = {
            ...payload,
            id: payload.id ?? (payload.scope === "global" ? 2 : payload.name),
          };
          delete entry.scope;
          const i = list.findIndex((x) => String(x.id) === String(entry.id));
          if (i < 0) list.push(entry);
          else list[i] = entry;
          data = entry;
        } else if (type === "mcp.test") data = { jobId: "job" };
        else if (type === "mcp.tools") {
          await new Promise((resolve) => setTimeout(resolve, 500));
          data = [{ name: "example", description: "Example tool" }];
        } else if (type === "mcp.poll") {
          data = { done: !window.holdMcpTest, result: { connected: true } };
        } else if (type === "mcp.delete") {
          mcps[payload.scope] = mcps[payload.scope].filter(
            (x) => x.id !== payload.id,
          );
          data = null;
        } else if (type === "skills.list") data = skills[payload.scope];
        else if (type === "skill.files")
          data = {
            name: "same",
            path: "",
            kind: "dir",
            children: [
              {
                name: "SKILL.md",
                path: "SKILL.md",
                kind: "file",
                children: [],
              },
            ],
          };
        else if (type === "skill.read")
          data = { path: "SKILL.md", content: `${payload.scope} description` };
        else if (type === "subagents.list") data = subs;
        else if (type === "subagent.save") {
          subs[payload.id] = payload.definition;
          data = null;
        } else data = null;
        return JSON.stringify({ ok: true, data });
      };
    },
    { kind },
  );
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.evaluate((theme) => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    const colors =
      theme === "dark"
        ? {
            bg: "#181a1b",
            panel: "#1e2228",
            input: "#181a1b",
            text: "#d4d7de",
            muted: "#979da8",
            border: "#353a43",
            accent: "#548af7",
          }
        : {
            bg: "#f6f7f9",
            panel: "#ffffff",
            input: "#ffffff",
            text: "#252a34",
            muted: "#687080",
            border: "#d9dde5",
            accent: "#3574f0",
          };
    for (const [key, value] of Object.entries(colors))
      document.documentElement.style.setProperty(`--jb-${key}`, value);
    const mapping = {
      "bg-color": "panel",
      "bg-color-page": "bg",
      "bg-color-overlay": "panel",
      "fill-color-blank": "panel",
      "fill-color-light": "input",
      "text-color-primary": "text",
      "text-color-regular": "text",
      "text-color-secondary": "muted",
      "border-color": "border",
      "border-color-light": "border",
      "color-primary": "accent",
    };
    for (const [key, source] of Object.entries(mapping))
      document.documentElement.style.setProperty(`--el-${key}`, colors[source]);
  }, theme);
  return page;
}
async function verifyLayout(page, label) {
  const failures = await page.evaluate(() => {
    const failed = [];
    if (document.documentElement.scrollWidth > innerWidth + 1)
      failed.push("page horizontal overflow");
    const font = getComputedStyle(document.documentElement).fontFamily;
    if (!font.includes("Segoe") && !font.includes("apple"))
      failed.push(`invalid font: ${font}`);
    for (const actions of document.querySelectorAll(".resource-actions")) {
      const visible = [...actions.children]
        .map((x) => x.getBoundingClientRect())
        .filter((x) => x.width && x.height);
      for (let i = 0; i < visible.length; i++)
        for (let j = i + 1; j < visible.length; j++) {
          const a = visible[i],
            b = visible[j];
          if (
            Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
            Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
          )
            failed.push("resource actions overlap");
        }
    }
    for (const element of document.querySelectorAll(".resource-dialog")) {
      const r = element.getBoundingClientRect();
      if (
        r.width &&
        (r.right > innerWidth + 1 || r.left < 0 || r.bottom > innerHeight + 1)
      )
        failed.push("dialog outside viewport");
    }
    return failed;
  });
  if (failures.length) throw Error(`${label}: ${failures.join(", ")}`);
  if (artifacts)
    await page.screenshot({ path: resolve(artifacts, `${label}.png`) });
}
async function verifyImageViewer(page, expectedSource) {
  const viewer = page.locator(".el-image-viewer__wrapper");
  await viewer.waitFor();
  if ((await viewer.count()) !== 1)
    throw Error("multiple image viewers opened");
  if (await page.getByRole("dialog", { name: "图片预览", exact: true }).count())
    throw Error("duplicate image dialog blocks toolbar");
  const image = viewer.locator(".el-image-viewer__img");
  await image.evaluate((img) => img.decode());
  const fitted = await image.evaluate(img => ({width:img.clientWidth,height:img.clientHeight,naturalWidth:img.naturalWidth,fit:getComputedStyle(img).objectFit}));
  if(fitted.width < 300 || fitted.height < 300 || fitted.fit !== 'contain') throw Error(`preview does not fill fixed viewport: ${JSON.stringify(fitted)}`);
  if (
    !(await page.evaluate(() =>
      testCalls.some(
        (x) => x.type === "ui.popupState" && x.payload.open === true,
      ),
    ))
  )
    throw Error("image viewer not reported to host popup state");
  await viewer.getByRole("button", { name: "放大", exact: true }).click();
  await page.waitForFunction(() =>
    document
      .querySelector(".el-image-viewer__img")
      ?.style.transform.includes("scale(1.2)"),
  );
  await viewer.getByRole("button", { name: "缩小", exact: true }).click();
  await page.waitForFunction(() =>
    document
      .querySelector(".el-image-viewer__img")
      ?.style.transform.includes("scale(1)"),
  );
  await viewer.getByRole("button", { name: "向右旋转", exact: true }).click();
  await page.waitForFunction(() =>
    document
      .querySelector(".el-image-viewer__img")
      ?.style.transform.includes("rotate(90deg)"),
  );
  await viewer.getByRole("button", { name: "向左旋转", exact: true }).click();
  await page.waitForFunction(() =>
    document
      .querySelector(".el-image-viewer__img")
      ?.style.transform.includes("rotate(0deg)"),
  );
  await viewer
    .getByRole("button", { name: "原始尺寸／适应窗口", exact: true })
    .click();
  await viewer.getByRole("button", { name: "复制图片", exact: true }).click();
  await viewer.getByRole("button", { name: "下载图片", exact: true }).click();
  const calls = await page.evaluate(() => testCalls);
  for (const type of ["image.copy", "image.save"]) {
    const payload = calls.filter((x) => x.type === type).at(-1)?.payload;
    if (
      !payload ||
      Object.entries(expectedSource).some(
        ([key, value]) => payload[key] !== value,
      )
    )
      throw Error(
        `${type} has incorrect image source: ${JSON.stringify(payload)}`,
      );
  }
  await page.keyboard.press("Escape");
  await viewer.waitFor({ state: "detached" });
}

try {
  let page = await pageFor("mcp");
  await page.getByText("Global MCP", { exact: true }).waitFor();
  await page.getByText("当前项目", { exact: true }).click();
  await page.getByText("Project MCP", { exact: true }).waitFor();
  await page.getByRole("button", { name: "新建 MCP", exact: true }).click();
  await page.getByLabel("名称 / 项目 key", { exact: true }).fill("another");
  await page.getByLabel("可执行命令", { exact: true }).fill("python3");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.getByText("MCP 已保存", { exact: true }).waitFor();
  let calls = await page.evaluate(() => testCalls);
  const saved = calls.find((x) => x.type === "mcp.save");
  if (saved.payload.scope !== "project" || "enabled" in saved.payload)
    throw Error("project MCP scope/enabled invalid");
  await page
    .getByRole("button", { name: "测试连接", exact: true })
    .first()
    .click();
  await page.getByText("Project MCP：连接测试通过", { exact: true }).waitFor();
  await page.evaluate(() => (window.holdMcpTest = true));
  await page
    .getByRole("button", { name: "测试连接", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "编辑", exact: true }).first().click();
  const dialog = page.getByRole("dialog", {
    name: "编辑 MCP 服务",
    exact: true,
  });
  const saveButton = dialog.getByRole("button", { name: "保存", exact: true });
  if (
    (await saveButton.isDisabled()) ||
    (await saveButton.getAttribute("class")).includes("is-loading")
  )
    throw Error("test blocks save");
  await dialog.getByRole("button", { name: "批量导入", exact: true }).click();
  const importer = page.getByRole("dialog", {
    name: "批量导入环境变量",
    exact: true,
  });
  await importer
    .locator("textarea")
    .fill(" KEY = value \n URL : https://example.com?q=a=b \n KEY : replaced ");
  await importer.getByRole("button", { name: "导入", exact: true }).click();
  const envText = await dialog
    .locator(".config-code textarea")
    .nth(2 - 1)
    .inputValue();
  const imported = JSON.parse(envText);
  if (
    imported.KEY !== "replaced" ||
    imported.URL !== "https://example.com?q=a=b"
  )
    throw Error("environment import trim/merge failed");
  await saveButton.click();
  await page.waitForFunction(
    () => testCalls.filter((x) => x.type === "mcp.save").length === 2,
  );
  await page.evaluate(() => (window.holdMcpTest = false));
  await page
    .getByRole("button", { name: "工具列表", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "编辑", exact: true }).first().click();
  const toolsSave = page
    .getByRole("dialog", { name: "编辑 MCP 服务", exact: true })
    .getByRole("button", { name: "保存", exact: true });
  if (
    (await toolsSave.isDisabled()) ||
    (await toolsSave.getAttribute("class")).includes("is-loading")
  )
    throw Error("tool listing blocks save");
  await toolsSave.click();
  await page
    .getByRole("dialog", { name: "MCP 检查结果", exact: true })
    .waitFor();
  const toolsCalls = await page.evaluate(() =>
    testCalls.filter((x) => x.type === "mcp.tools"),
  );
  if (toolsCalls.length !== 1) throw Error("tools list not a direct request");
  if (artifacts) await page.screenshot({ path: resolve(artifacts, "mcp.png") });
  await page.close();
  page = await pageFor("environments");
  await page.getByText("MCP 服务", { exact: true }).click();
  const panel = page
    .locator(".el-collapse-item")
    .filter({ has: page.getByText("MCP 服务", { exact: true }) });
  const inherited = panel
    .locator(".el-checkbox")
    .filter({ hasText: "Inherited MCP" });
  if (!(await inherited.locator("input").isChecked()))
    throw Error("new MCP not inherited");
  const off = panel.locator(".el-checkbox").filter({ hasText: "Global MCP" });
  if (await off.locator("input").isChecked())
    throw Error("explicit false selected");
  await inherited.click();
  await page.getByText("多模态", { exact: true }).click();
  await page.locator(".view-resource-fields .el-switch__core").first().click();
  await page
    .getByRole("spinbutton", { name: "工具超时（秒）" })
    .first()
    .fill("345");
  await page.getByRole("button", { name: "保存环境", exact: true }).click();
  calls = await page.evaluate(() => testCalls);
  const envSave = calls.filter((x) => x.type === "environment.save").at(-1);
  if (envSave.payload.config.view_resources.image.timeout_secs !== 345)
    throw Error("environment timeout was not saved");
  if (
    (await page
      .getByRole("spinbutton", { name: "工具超时（秒）" })
      .first()
      .inputValue()) !== "345"
  )
    throw Error("environment timeout reload failed");
  if (
    envSave.payload.config.resources.mcp["1"] !== false ||
    envSave.payload.config.resources.mcp["2"] !== false
  )
    throw Error("MCP overrides wrong");
  await panel.getByRole("button", { name: "恢复继承" }).click();
  if (!(await off.locator("input").isChecked()))
    throw Error("restore inheritance failed");
  await page.getByText("技能", { exact: true }).first().click();
  const skillsPanel = page
    .locator(".el-collapse-item")
    .filter({ has: page.getByText("技能", { exact: true }) });
  if (
    !(await skillsPanel
      .locator(".el-checkbox")
      .filter({ hasText: "new" })
      .locator("input")
      .isChecked())
  )
    throw Error("new skill not inherited");
  if (artifacts)
    await page.screenshot({ path: resolve(artifacts, "environment.png") });
  await page.close();
  page = await pageFor("agents");
  await page.getByText("工具、插件函数与 Skills", { exact: true }).click();
  const resourcePanel = page
    .locator(".resource-panel")
    .filter({ has: page.getByText("内置工具", { exact: true }) });
  if (
    !(await resourcePanel
      .locator(".el-checkbox")
      .filter({ hasText: "read_project_files" })
      .locator("input")
      .isChecked())
  )
    throw Error("new tool not inherited");
  await page.getByText("多模态资源处理", { exact: true }).click();
  await page.locator(".resource-row .el-checkbox").first().click();
  await page
    .getByRole("spinbutton", { name: "工具超时（秒）" })
    .first()
    .fill("456");
  await page.getByRole("button", { name: "保存 Agent", exact: true }).click();
  calls = await page.evaluate(() => testCalls);
  if (
    JSON.stringify(
      calls.find((x) => x.type === "agent.save").payload.toolOverrides,
    ) !== JSON.stringify({ bash: false })
  )
    throw Error("untouched inheritance materialized");
  if (
    calls.find((x) => x.type === "agent.save").payload.viewResources.image
      .timeoutSecs !== 456
  )
    throw Error("agent timeout was not saved");
  if (
    (await page
      .getByRole("spinbutton", { name: "工具超时（秒）" })
      .first()
      .inputValue()) !== "456"
  )
    throw Error("agent timeout reload failed");
  await page.close();
  page = await pageFor("skills");
  await page.getByText("当前项目 .jtools/skills", { exact: true }).click();
  await page.getByText("Project", { exact: true }).waitFor();
  calls = await page.evaluate(() => testCalls);
  if (
    calls.filter((x) => x.type === "skills.list").at(-1).payload.scope !==
    "project"
  )
    throw Error("skill scope invalid");
  await page.close();
  page = await pageFor("subagents");
  await page.getByRole("button", { name: "编辑", exact: true }).click();
  await page.getByLabel("名称", { exact: true }).fill("Review changed");
  await page
    .getByLabel("模型", { exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]')
    .click();
  const selectedModels = page.getByRole("option", { selected: true });
  if ((await selectedModels.count()) !== 1)
    throw Error("duplicate-name models selected together");
  await page
    .getByRole("option", {
      name: "Second provider / Model (model-alias)",
      exact: true,
    })
    .click();
  const childDialog = page.getByRole("dialog", {
    name: "编辑项目 SubAgent",
    exact: true,
  });
  await childDialog
    .getByLabel("包含历史", { exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-switch")]')
    .click();
  await childDialog
    .getByLabel("角色设定", { exact: true })
    .locator("textarea")
    .fill("Project reviewer persona");
  await childDialog
    .locator(".project-resource-field .el-switch")
    .first()
    .click();
  await childDialog
    .getByRole("spinbutton", { name: "工具超时（秒）" })
    .first()
    .fill("567");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.getByText("项目 SubAgent 已保存", { exact: true }).waitFor();
  calls = await page.evaluate(() => testCalls);
  if (calls.find((x) => x.type === "subagent.save").payload.scope !== "project")
    throw Error("subagent scope invalid");
  const subSave = calls.find((x) => x.type === "subagent.save").payload
    .definition;
  if (
    subSave.model.provider_id !== 2 ||
    subSave.model.model_id !== 2 ||
    !subSave.runtime_params.include_history ||
    subSave.persona.soul !== "Project reviewer persona" ||
    subSave.view_resources.image.timeout_secs !== 567
  )
    throw Error("incomplete subagent config");
  await page.close();
  page = await pageFor("chat");
  await page.getByTitle("更多", { exact: true }).click();
  const menuNames = await page
    .locator(".chat-more-menu button")
    .allTextContents();
  const expectedMenu = [
    "模型配置",
    "Agent 管理",
    "提示词管理",
    "Skills 管理",
    "MCP 管理",
    "项目子代理",
    "编码环境",
    "通用设置",
    "运行日志",
  ];
  if (JSON.stringify(menuNames) !== JSON.stringify(expectedMenu))
    throw Error(`unexpected menu labels: ${menuNames}`);
  await page.getByTitle("更多", { exact: true }).click();

  const emptySessionSelect = page.locator(".chat-top .el-select").first();
  await emptySessionSelect.waitFor();
  if (
    (await emptySessionSelect.innerText()).includes("999999") ||
    (await page.locator("body").innerText()).includes("OTHER_PROJECT_MESSAGE")
  )
    throw Error("new project restores foreign session cache");
  await page.evaluate(() =>
    window.jtoolsAgent.replace({
      sessions: [],
      currentSessionId: null,
      currentSession: null,
      messages: [],
      events: [],
      tasks: [],
      queue: [],
      drafts: [],
      providers: [],
      prompts: [],
      skills: [],
      environments: [],
    }),
  );
  if (!(await emptySessionSelect.innerText()).includes("暂无会话"))
    throw Error("empty session placeholder missing");
  await page.evaluate(() =>
    window.jtoolsAgent.replace({
      sessions: [{ id: 44, name: "新会话" }],
      currentSessionId: 44,
      currentSession: null,
      events: [],
    }),
  );
  await emptySessionSelect
    .getByText("[项目] 新会话", { exact: true })
    .waitFor();
  await page.close();
  page = await pageFor("chat");
  await page.evaluate(() =>
    window.jtoolsAgent.replace({
      sessions: [{ id: 44, name: "Resources" }],
      currentSessionId: 44,
      events: [
        {
          id: 1,
          session_id: 44,
          turn_id: "t1",
          event_type: "model_reply",
          status: "completed",
          summary: "reply",
          context: {
            response:
              "[源码](src/App.kt#L2C3)\n![本地图](images/demo.png)\n![文件图](file:///tmp/test.png)\n<img alt=\"wide-test\" src=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='1200' height='50'%3E%3C/svg%3E\">\n<img alt=\"tall-test\" src=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='50' height='1200'%3E%3C/svg%3E\">\n<video controls width=\"900\" height=\"600\"></video>\n<audio controls></audio>",
          },
        },
      ],
    }),
  );
  await page.getByRole("link", { name: "源码", exact: true }).click();
  await page.locator(".local-image img").first().waitFor();
  const media=await page.locator('.markdown-body').evaluateAll(roots=>roots.flatMap(root=>[...root.querySelectorAll('img,video,audio')].map(node=>({tag:node.tagName,width:node.getBoundingClientRect().width,height:node.getBoundingClientRect().height,fit:getComputedStyle(node).objectFit}))));
  if(media.length!==6 || media.some(x=>Math.abs(x.width-320)>1 || Math.abs(x.height-(x.tag==='AUDIO'?54:180))>1 || (x.tag==='IMG' && x.fit!=='fill') || (x.tag==='VIDEO' && x.fit!=='contain')))throw Error(`inconsistent Markdown media frames: ${JSON.stringify(media)}`);
  const resourceCalls = await page.evaluate(() => testCalls);
  if (
    !resourceCalls.some(
      (x) =>
        x.type === "resource.open" &&
        x.payload.reference === "src/App.kt#L2C3" &&
        x.payload.sessionId === "44",
    )
  )
    throw Error("local reference was not sent to IDE bridge");
  if (
    !resourceCalls.some(
      (x) =>
        x.type === "resource.preview" &&
        x.payload.reference === "file:///tmp/test.png",
    )
  )
    throw Error("file URI image not previewed");
  await page.locator(".local-image img").first().click();
  await verifyImageViewer(page, {
    sessionId: "44",
    reference: "images/demo.png",
  });
  await page.locator(".local-image img").nth(1).click();
  await verifyImageViewer(page, {
    sessionId: "44",
    reference: "file:///tmp/test.png",
  });
  await page.evaluate(() =>
    window.jtoolsAgent.patch({
      drafts: [
        {
          id: "draft-image",
          name: "sample.png",
          path: "/tmp/sample.png",
          kind: "image",
          size: 64,
          previewUrl:
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZp8AAAAASUVORK5CYII=",
        },
      ],
    }),
  );
  await page.locator(".draft-thumb").first().click();
  await verifyImageViewer(page, { path: "/tmp/sample.png" });
  // A local filesystem source must be removed before Chromium can resolve it against the chat origin.
  await page.evaluate(async () => {
    const canvas = document.createElement('canvas'); canvas.width=160; canvas.height=90;
    const ctx=canvas.getContext('2d'); ctx.fillStyle='#0088cc';ctx.fillRect(0,0,160,90);
    const stream=canvas.captureStream(10);const recorder=new MediaRecorder(stream,{mimeType:'video/webm'});
    const chunks=[];recorder.ondataavailable=e=>chunks.push(e.data);
    const recording=new Promise(resolve=>recorder.onstop=resolve);recorder.start();
    await new Promise(resolve=>setTimeout(resolve,350));recorder.stop();await recording;stream.getTracks().forEach(t=>t.stop());
    window.testMediaUrl=URL.createObjectURL(new Blob(chunks,{type:'video/webm'}));
    window.jtoolsAgent.replace({sessions:[{id:44,name:'Media'}],currentSessionId:44,events:[{
      id:2,session_id:44,turn_id:'media',event_type:'model_reply',status:'completed',summary:'media',
      context:{response:'<video src="/workspace/clip.webm" controls></video>\n<video><source src="data:video/mp4;base64,AAAA" type="video/mp4"></video>'}
    }]});
  });
  await page.waitForFunction(()=>document.querySelector('.markdown-body video')?.readyState>=2);
  if(!(await page.evaluate(()=>testCalls.some(x=>x.type==='resource.media'&&x.payload.reference==='/workspace/clip.webm')))) throw Error('local video did not use host authorization');
  await page.getByText('当前 IDE 无法解码此媒体格式',{exact:false}).waitFor();
  const sources=await page.locator('.markdown-body video').evaluateAll(nodes=>nodes.map(v=>v.src));
  if(sources.some(x=>x.includes('/workspace/clip.webm'))) throw Error('local video path leaked to browser URL');

  await page.evaluate(() =>
    window.jtoolsAgent.patch({
      events: [
        {
          id: 2,
          session_id: 44,
          turn_id: "t2",
          event_type: "user_message",
          status: "completed",
          summary: "user image",
          context: {
            content: "image",
            attachments: [
              {
                id: 12,
                file_name: "user.png",
                kind: "image",
                content_type: "image/png",
                size: 64,
              },
            ],
          },
        },
      ],
    }),
  );
  await page.locator(".user-attachment").first().click();
  await verifyImageViewer(page, { sessionId: "44", id: "12" });
  await page.close();
  for (const width of [1124, 760]) {
    page = await pageFor("catalog", "dark", width);
    await page.locator(".provider-card").first().click();
    await page.getByText("模型管理", { exact: false }).first().click();
    await page.locator(".model-item").first().click();
    const group = page.locator(".model-modality-field .modality-checks");
    await group.waitFor();
    const layout = await group.evaluate((element) => {
      const boxes = [...element.children].map((x) => x.getBoundingClientRect());
      const bound = element.getBoundingClientRect();
      const field = element
        .closest(".model-modality-field")
        .getBoundingClientRect();
      const context = element
        .closest(".model-capability-row")
        .querySelector(".el-input-number")
        .closest(".el-form-item")
        .getBoundingClientRect();
      const row=element.closest('.model-capability-row');
      const identity=[...row.previousElementSibling.children].map(x=>x.getBoundingClientRect());
      const capability=[...row.children].map(x=>x.getBoundingClientRect());
      return {
        matchingColumns:capability.every((x,i)=>Math.abs(x.left-identity[i].left)<1 && Math.abs(x.width-identity[i].width)<1),
        besideContext:
          Math.abs(field.top - context.top) < 1 && field.left > context.right,
        count: boxes.length,
        compact: boxes.every((x) => x.width <= 55),
        aligned: boxes.every((x) => Math.abs(x.top - boxes[0].top) < 1),
        contained: boxes.every(
          (x) => x.right <= bound.right + 1 && x.left >= bound.left - 1,
        ),
      };
    });
    if (
      layout.count !== 5 ||
      !layout.matchingColumns ||
      !layout.besideContext ||
      !layout.compact ||
      !layout.aligned ||
      !layout.contained
    )
      throw Error(
        `model modality layout failed at ${width}: ${JSON.stringify(layout)}`,
      );
    if (artifacts)
      await page.screenshot({
        path: resolve(artifacts, `model-modalities-${width}.png`),
      });
    await page.close();
  }
  for (const theme of ["dark", "light"]) {
    for (const width of [1124, 760]) {
      page = await pageFor("environments", theme, width);
      await page.getByText("MCP 服务", { exact: true }).click();
      await page.getByText("技能", { exact: true }).click();
      await page.getByText("多模态", { exact: true }).click();
      await page.getByText("技能", { exact: true }).scrollIntoViewIfNeeded();
      await verifyLayout(page, `environment-${theme}-${width}`);
      await page.close();
      page = await pageFor("agents", theme, width);
      await page.getByText("工具、插件函数与 Skills", { exact: true }).click();
      await verifyLayout(page, `agents-${theme}-${width}`);
      await page.close();
      for (const kind of ["mcp", "subagents"]) {
        page = await pageFor(kind, theme, width);
        if (kind === "mcp")
          await page.getByText("当前项目", { exact: true }).click();
        await page
          .getByRole("button", { name: "编辑", exact: true })
          .first()
          .waitFor();
        await verifyLayout(page, `${kind}-${theme}-${width}`);
        await page
          .getByRole("button", { name: "编辑", exact: true })
          .first()
          .click();
        await verifyLayout(page, `${kind}-editor-${theme}-${width}`);
        await page.close();
      }
    }
  }
  if (errors.length) throw Error(errors.join("\n"));
  console.log(
    "UI contract checks passed: MCP scope/CRUD/poll, environment MCP/skills inheritance, agent tool inheritance, project Skills/SubAgent editing; no browser errors.",
  );
} catch (error) {
  console.error(error);
  for (const context of browser.contexts())
    for (const page of context.pages()) {
      console.error((await page.locator("body").innerText()).slice(-6000));
      if (artifacts)
        await page.screenshot({ path: resolve(artifacts, "failure.png") });
    }
  process.exitCode = 1;
} finally {
  await browser.close();
  server.closeAllConnections();
  await new Promise((r) => server.close(r));
}
