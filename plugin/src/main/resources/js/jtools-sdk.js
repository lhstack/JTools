/**
 * JTools JS Plugin SDK
 * 简化 cefQuery 调用，提供友好的 API 接口
 */
(function(global) {
    'use strict';

    // 基础调用封装
    function call(type, commands = []) {
        return new Promise((resolve, reject) => {
            window.cefQuery({
                request: JSON.stringify({ type, commands }),
                onSuccess: (response) => resolve(response),
                onFailure: (code, msg) => reject({ code, message: msg })
            });
        });
    }

    // JSON 解析封装
    function callJson(type, commands = []) {
        return call(type, commands).then(r => r ? JSON.parse(r) : null);
    }

    const JTools = {
        // ==================== 帮助 ====================
        /** 获取所有 API 文档 */
        help: () => callJson('help'),
        /** 获取 Markdown 格式文档 */
        helpMarkdown: () => call('help.markdown'),
        /** 获取所有 API 名称列表 */
        helpList: () => callJson('help.list'),

        // ==================== 日志 ====================
        log: {
            debug: (msg) => call('log', ['debug', String(msg)]),
            info: (msg) => call('log', ['info', String(msg)]),
            warn: (msg) => call('log', ['warn', String(msg)]),
            error: (msg) => call('log', ['error', String(msg)])
        },

        // ==================== 插件信息 ====================
        /** 获取当前插件信息 */
        getPluginInfo: () => callJson('getPluginInfo'),
        /** 获取 JTools 版本号 */
        getJToolsVersion: () => call('getJToolsVersion').then(Number),
        /** 获取 IDE 信息 */
        getIdeInfo: () => callJson('getIdeInfo'),

        // ==================== 系统 ====================
        /** 获取系统环境变量 */
        getSysEnv: (name) => call('getSysEnv', [name]),
        /** 获取 Java 系统属性 */
        getSysProperty: (name) => call('getSysProperty', [name]),
        /** 获取操作系统信息 */
        getOsInfo: () => callJson('getOsInfo'),
        /** 获取当前时间戳 */
        currentTimeMillis: () => call('currentTimeMillis').then(Number),
        /** 执行系统命令 */
        executeCommand: (command, workDir, timeout = 30000) => 
            callJson('executeCommand', [command, workDir || '', String(timeout)]),
        /** 在浏览器中打开 URL */
        openUrl: (url) => call('openUrl', [url]),

        // ==================== 项目 ====================
        /** 获取当前项目路径 */
        getProjectBasePath: () => call('getProjectBasePath'),
        /** 刷新项目文件 */
        refreshProject: () => call('refreshProject'),
        /** 在编辑器中打开文件 */
        openFileInEditor: (path) => call('openFileInEditor', [path]),

        // ==================== 通知 ====================
        /** 发送 IDE 通知 */
        notify: (title, content, type = 'INFORMATION') => call('notify', [title, content, type]),
        notifyInfo: (title, content) => call('notify', [title, content, 'INFORMATION']),
        notifyWarn: (title, content) => call('notify', [title, content, 'WARNING']),
        notifyError: (title, content) => call('notify', [title, content, 'ERROR']),

        // ==================== 文件操作 ====================
        file: {
            /** 读取系统文件 */
            read: (path) => call('readSysFile', [path]),
            /** 读取插件内部文件 */
            readPlugin: (path) => call('readPluginFile', [path]),
            /** 写入文件 */
            write: (path, content, append = false) => call('writeSysFile', [path, content, String(append)]),
            /** 追加写入 */
            append: (path, content) => call('writeSysFile', [path, content, 'true']),
            /** 检查是否存在 */
            exists: (path) => call('fileExists', [path]).then(r => r === 'true'),
            /** 检查是否是目录 */
            isDirectory: (path) => call('isDirectory', [path]).then(r => r === 'true'),
            /** 列出目录内容 */
            list: (path) => callJson('listDir', [path]),
            /** 创建目录 */
            mkdir: (path) => call('createDir', [path]),
            /** 删除文件或目录 */
            delete: (path) => call('deleteFile', [path]),
            /** 复制文件 */
            copy: (source, target) => call('copyFile', [source, target]),
            /** 移动/重命名文件 */
            move: (source, target) => call('moveFile', [source, target]),
            /** 获取文件信息 */
            info: (path) => callJson('getFileInfo', [path])
        },

        // ==================== 剪贴板 ====================
        clipboard: {
            /** 复制到剪贴板 */
            copy: (content) => call('copyToClipboard', [content]),
            /** 从剪贴板获取 */
            paste: () => call('getFromClipboard')
        },

        // ==================== 对话框 ====================
        dialog: {
            /** 输入对话框 */
            input: (message = '请输入', title = '输入', defaultValue = '') => 
                call('showInputDialog', [message, title, defaultValue]),
            /** 确认对话框，返回 0=Yes, 1=No, 2=Cancel */
            confirm: (message = '确认操作？', title = '确认') => 
                call('showConfirmDialog', [message, title]).then(Number),
            /** 消息对话框 */
            message: (message, title = '提示', type = 'info') => 
                call('showMessageDialog', [message, title, type]),
            /** 文件选择 */
            chooseFile: (title = '选择文件') => call('chooseFile', [title]),
            /** 多文件选择 */
            chooseFiles: (title = '选择文件') => callJson('chooseFiles', [title]),
            /** 目录选择 */
            chooseDirectory: (title = '选择目录') => call('chooseDirectory', [title]),
            /** 保存文件对话框 */
            saveFile: (title = '保存文件', description = '', defaultName = 'file.txt') => 
                call('saveFileDialog', [title, description, defaultName])
        },

        // ==================== 缓存 ====================
        cache: {
            // 全局缓存
            global: {
                set: (key, value) => call('global.cache.set', [key, String(value)]),
                get: (key) => call('global.cache.get', [key]),
                getOrDefault: (key, defaultValue) => call('global.cache.getOrDefault', [key, String(defaultValue)]),
                getAll: () => callJson('global.cache.getAll'),
                keys: () => callJson('global.cache.keys'),
                exists: (key) => call('global.cache.exists', [key]).then(r => r === 'true'),
                size: () => call('global.cache.size').then(Number),
                remove: (key) => call('global.cache.remove', [key]),
                clear: () => call('global.cache.clear'),
                setAll: (data) => call('global.cache.setAll', [JSON.stringify(data)])
            },
            // 项目缓存
            project: {
                set: (key, value) => call('project.cache.set', [key, String(value)]),
                get: (key) => call('project.cache.get', [key]),
                getOrDefault: (key, defaultValue) => call('project.cache.getOrDefault', [key, String(defaultValue)]),
                getAll: () => callJson('project.cache.getAll'),
                keys: () => callJson('project.cache.keys'),
                exists: (key) => call('project.cache.exists', [key]).then(r => r === 'true'),
                size: () => call('project.cache.size').then(Number),
                remove: (key) => call('project.cache.remove', [key]),
                clear: () => call('project.cache.clear'),
                setAll: (data) => call('project.cache.setAll', [JSON.stringify(data)])
            }
        },

        // ==================== 工具方法 ====================
        /** 原始调用方法 */
        _call: call,
        _callJson: callJson
    };

    // 挂载到全局
    global.JTools = JTools;

    // 兼容性：也挂载到 window
    if (typeof window !== 'undefined') {
        window.JTools = JTools;
    }

    console.log('%c[JTools SDK] Loaded successfully! Use JTools.help() to see all APIs.', 'color: #4CAF50; font-weight: bold;');

})(typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : this));
