package com.lhstack.example;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.lhstack.tools.plugins.*;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginImpl implements IPlugin {

    private final Map<String, Runnable> disables = new HashMap<>();

    public PluginImpl() {

    }

    @Override
    public JComponent createPanel(Project project) {
        return Helper.languageTextField("JAVA",project.getLocationHash(),consumer -> {
           consumer.accept("Hello World");
        },run -> disables.put(project.getLocationHash(),run),str -> {
            System.out.println("str");
        });
    }

    @Override
    public boolean supportMultiOpens() {
        return true;
    }

    @Override
    public void openProject(Project project, Logger logger, Runnable openThisPage) {
//        throw new RuntimeException("111");
    }

    @Override
    public Support support(Integer jToolsVersion, IdeInfo ideInfo) {
        return Support.NOT_SUPPORT;
    }

    @Override
    public void closeProject(String projectHash) {
        Runnable remove = disables.remove(projectHash);
        if(remove != null) {
            remove.run();
        }
    }

    @Override
    public void install() {
//        throw new RuntimeException("111");
    }



    @Override
    public void unInstall() {
//        throw new RuntimeException("111");
    }

    @Override
    public void appClose() {
//        throw new RuntimeException("111");
    }

    @Override
    public Icon pluginIcon() {
        return IconLoader.findIcon("icons/logo.svg", PluginImpl.class);
    }

    @Override
    public Icon pluginTabIcon() {
        return IconLoader.findIcon("icons/tabIcon.svg", PluginImpl.class);
    }


    @Override
    public String pluginName() {
        return "Demo插件";
    }

    @Override
    public String pluginDesc() {
        return "这是一个测试插件";
    }

    @Override
    public String pluginVersion() {
        return "0.0.2";
    }

    @Override
    public List<FunctionCalling> functionCallings(String locationHash) {
        return List.of(
                new FunctionCalling() {
                    @Override
                    public String name() {
                        return "md5_text";
                    }

                    @Override
                    public String description() {
                        return "将输入文本转换为 MD5";
                    }

                    @Override
                    public String parameters() {
                        return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"待计算MD5的文本\"}},\"required\":[\"text\"]}";
                    }

                    @Override
                    public String call(String argumentsJson) {
                        String text = getStringArg(argumentsJson, "text");
                        if (text == null) {
                            return errorJson("参数缺少 text");
                        }
                        return okJson("md5", md5Hex(text));
                    }
                },
                new FunctionCalling() {
                    @Override
                    public String name() {
                        return "fetch_url";
                    }

                    @Override
                    public String description() {
                        return "请求URL并返回内容";
                    }

                    @Override
                    public String parameters() {
                        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"请求地址\"},\"timeoutMs\":{\"type\":\"integer\",\"description\":\"超时毫秒\",\"default\":15000}},\"required\":[\"url\"]}";
                    }

                    @Override
                    public String call(String argumentsJson) {
                        String url = getStringArg(argumentsJson, "url");
                        if (url == null || url.isEmpty()) {
                            return errorJson("参数缺少 url");
                        }
                        int timeoutMs = getIntArg(argumentsJson, "timeoutMs", 15000);
                        try {
                            HttpClient client = HttpClient.newBuilder()
                                    .followRedirects(HttpClient.Redirect.NORMAL)
                                    .build();
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofMillis(timeoutMs))
                                    .GET()
                                    .build();
                            HttpResponse<String> response = client.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                            );
                            return okJson("status", response.statusCode(), "body", response.body());
                        } catch (Exception e) {
                            return errorJson(e.getMessage() == null ? "请求失败" : e.getMessage());
                        }
                    }
                }
        );
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String okJson(String key, Object value) {
        return "{\"ok\":true,\"" + key + "\":" + toJsonValue(value) + "}";
    }

    private static String okJson(String key1, Object value1, String key2, Object value2) {
        return "{\"ok\":true,\"" + key1 + "\":" + toJsonValue(value1) + ",\"" + key2 + "\":" + toJsonValue(value2) + "}";
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escapeJson(message) + "\"}";
    }

    private static String toJsonValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String getStringArg(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL)
                .matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJson(matcher.group(1));
    }

    private static int getIntArg(String json, String key, int defaultValue) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
                .matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String value) {
        return value.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
