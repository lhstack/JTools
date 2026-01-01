/**
 * JTools JS Plugin SDK Type Definitions
 * 在项目中引入此文件以获得代码提示
 * 
 * 使用方法:
 * 1. 将此文件复制到你的 JS 插件项目中
 * 2. 在 tsconfig.json 或 jsconfig.json 中配置 types
 * 3. 或者在 JS 文件顶部添加: /// <reference path="./jtools-sdk.d.ts" />
 */

declare namespace JTools {
    // ==================== 帮助 ====================
    /** 获取所有 API 文档 (JSON 格式) */
    function help(): Promise<ApiDoc[]>;
    /** 获取 Markdown 格式的 API 文档 */
    function helpMarkdown(): Promise<string>;
    /** 获取所有 API 名称列表 */
    function helpList(): Promise<string[]>;

    // ==================== 日志 ====================
    namespace log {
        function debug(msg: any): Promise<string>;
        function info(msg: any): Promise<string>;
        function warn(msg: any): Promise<string>;
        function error(msg: any): Promise<string>;
    }

    // ==================== 插件信息 ====================
    /** 获取当前插件信息 */
    function getPluginInfo(): Promise<PluginInfo>;
    /** 获取 JTools 版本号 */
    function getJToolsVersion(): Promise<number>;
    /** 获取 IDE 信息 */
    function getIdeInfo(): Promise<IdeInfo>;

    // ==================== 系统 ====================
    /** 获取系统环境变量 */
    function getSysEnv(name: string): Promise<string>;
    /** 获取 Java 系统属性 */
    function getSysProperty(name: string): Promise<string>;
    /** 获取操作系统信息 */
    function getOsInfo(): Promise<OsInfo>;
    /** 获取当前时间戳 */
    function currentTimeMillis(): Promise<number>;
    /** 执行系统命令 */
    function executeCommand(command: string, workDir?: string, timeout?: number): Promise<CommandResult>;
    /** 在浏览器中打开 URL */
    function openUrl(url: string): Promise<string>;

    // ==================== 项目 ====================
    /** 获取当前项目路径 */
    function getProjectBasePath(): Promise<string>;
    /** 刷新项目文件 */
    function refreshProject(): Promise<string>;
    /** 在编辑器中打开文件 */
    function openFileInEditor(path: string): Promise<string>;

    // ==================== 通知 ====================
    /** 发送 IDE 通知 */
    function notify(title: string, content: string, type?: 'INFORMATION' | 'WARNING' | 'ERROR'): Promise<string>;
    function notifyInfo(title: string, content: string): Promise<string>;
    function notifyWarn(title: string, content: string): Promise<string>;
    function notifyError(title: string, content: string): Promise<string>;

    // ==================== 文件操作 ====================
    namespace file {
        /** 读取系统文件 */
        function read(path: string): Promise<string>;
        /** 读取插件内部文件 */
        function readPlugin(path: string): Promise<string>;
        /** 写入文件 */
        function write(path: string, content: string, append?: boolean): Promise<string>;
        /** 追加写入 */
        function append(path: string, content: string): Promise<string>;
        /** 检查是否存在 */
        function exists(path: string): Promise<boolean>;
        /** 检查是否是目录 */
        function isDirectory(path: string): Promise<boolean>;
        /** 列出目录内容 */
        function list(path: string): Promise<FileInfo[]>;
        /** 创建目录 */
        function mkdir(path: string): Promise<string>;
        /** 删除文件或目录 */
        function delete(path: string): Promise<string>;
        /** 复制文件 */
        function copy(source: string, target: string): Promise<string>;
        /** 移动/重命名文件 */
        function move(source: string, target: string): Promise<string>;
        /** 获取文件信息 */
        function info(path: string): Promise<FileDetailInfo>;
    }

    // ==================== 剪贴板 ====================
    namespace clipboard {
        /** 复制到剪贴板 */
        function copy(content: string): Promise<string>;
        /** 从剪贴板获取 */
        function paste(): Promise<string>;
    }

    // ==================== 对话框 ====================
    namespace dialog {
        /** 输入对话框 */
        function input(message?: string, title?: string, defaultValue?: string): Promise<string>;
        /** 确认对话框，返回 0=Yes, 1=No, 2=Cancel */
        function confirm(message?: string, title?: string): Promise<number>;
        /** 消息对话框 */
        function message(message: string, title?: string, type?: 'info' | 'warning' | 'error'): Promise<string>;
        /** 文件选择 */
        function chooseFile(title?: string): Promise<string>;
        /** 多文件选择 */
        function chooseFiles(title?: string): Promise<string[]>;
        /** 目录选择 */
        function chooseDirectory(title?: string): Promise<string>;
        /** 保存文件对话框 */
        function saveFile(title?: string, description?: string, defaultName?: string): Promise<string>;
    }

    // ==================== 缓存 ====================
    namespace cache {
        namespace global {
            function set(key: string, value: string): Promise<string>;
            function get(key: string): Promise<string>;
            function getOrDefault(key: string, defaultValue: string): Promise<string>;
            function getAll(): Promise<Record<string, string>>;
            function keys(): Promise<string[]>;
            function exists(key: string): Promise<boolean>;
            function size(): Promise<number>;
            function remove(key: string): Promise<string>;
            function clear(): Promise<string>;
            function setAll(data: Record<string, string>): Promise<string>;
        }
        namespace project {
            function set(key: string, value: string): Promise<string>;
            function get(key: string): Promise<string>;
            function getOrDefault(key: string, defaultValue: string): Promise<string>;
            function getAll(): Promise<Record<string, string>>;
            function keys(): Promise<string[]>;
            function exists(key: string): Promise<boolean>;
            function size(): Promise<number>;
            function remove(key: string): Promise<string>;
            function clear(): Promise<string>;
            function setAll(data: Record<string, string>): Promise<string>;
        }
    }

    // ==================== 工具方法 ====================
    /** 原始调用方法 */
    function _call(type: string, commands?: string[]): Promise<string>;
    function _callJson<T>(type: string, commands?: string[]): Promise<T>;

    // ==================== 类型定义 ====================
    interface ApiDoc {
        name: string;
        category: string;
        desc: string;
        params: string[];
        returns: string;
        example: string;
    }

    interface PluginInfo {
        pluginName: string;
        pluginVersion: string;
        pluginDesc: string;
        pluginIcon: string;
        pluginTabIcon: string;
        indexPage: string;
    }

    interface IdeInfo {
        versionName: string;
        fullVersion: string;
        apiVersion: string;
        majorVersion: string;
        minorVersion: string;
        buildBaselineVersion: number;
        fullApplicationName: string;
    }

    interface OsInfo {
        name: string;
        version: string;
        arch: string;
        userHome: string;
        userName: string;
        javaVersion: string;
        javaVendor: string;
        fileSeparator: string;
        lineSeparator: string;
        tempDir: string;
    }

    interface CommandResult {
        exitCode: number;
        output: string;
    }

    interface FileInfo {
        name: string;
        path: string;
        isDirectory: boolean;
        size: number;
        lastModified: number;
    }

    interface FileDetailInfo extends FileInfo {
        absolutePath: string;
        isFile: boolean;
        readable: boolean;
        writable: boolean;
        executable: boolean;
    }
}

// 全局声明
declare global {
    interface Window {
        JTools: typeof JTools;
    }
}

export = JTools;
export as namespace JTools;
