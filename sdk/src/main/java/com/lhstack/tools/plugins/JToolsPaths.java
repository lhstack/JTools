package com.lhstack.tools.plugins;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** 启动参数只在进程启动时读取；沙箱配置错误绝不回退到日常数据目录。 */
public final class JToolsPaths {
    private JToolsPaths() {}

    public static Path home(Properties properties) {
        Path normal = Paths.get(properties.getProperty("user.home"), ".jtools").toAbsolutePath().normalize();
        String configured = properties.getProperty("jtools.home");
        boolean sandbox = Boolean.parseBoolean(properties.getProperty("jtools.sandbox", "false"));
        if (configured == null) {
            if (sandbox) throw new IllegalArgumentException("沙箱必须指定 jtools.home");
            return normal;
        }
        if (configured.trim().isEmpty()) throw new IllegalArgumentException("jtools.home 不能为空");
        Path path = Paths.get(configured);
        if (!path.isAbsolute()) throw new IllegalArgumentException("jtools.home 必须为绝对路径");
        try {
            path = path.toFile().getCanonicalFile().toPath();
            if (sandbox) {
                String rootValue = properties.getProperty("jtools.sandbox.root");
                if (rootValue == null) throw new IllegalArgumentException("沙箱必须指定 jtools.sandbox.root");
                Path root = new File(rootValue).getCanonicalFile().toPath();
                Path canonicalNormal = normal.toFile().getCanonicalFile().toPath();
                if (!path.startsWith(root) || path.equals(root) || path.startsWith(canonicalNormal) || canonicalNormal.startsWith(path)) {
                    throw new IllegalArgumentException("沙箱数据目录必须位于沙箱根内且与日常 JTools 数据目录隔离");
                }
            }
            return path;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("无法解析 jtools.home", error);
        }
    }
}
