package com.blamenet.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 构建信息：读取由 Maven 资源过滤生成的 blamenet-build.properties。 */
public final class BuildInfo {

    private static final Properties PROPERTIES = load();

    private BuildInfo() {
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/blamenet-build.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // 读取失败时退回默认值，不影响诊断主流程
        }
        return properties;
    }

    public static String toolName() {
        return PROPERTIES.getProperty("tool.name", "BlameNet");
    }

    public static String toolVersion() {
        return PROPERTIES.getProperty("tool.version", "1.0.0");
    }

    public static String buildTool() {
        return PROPERTIES.getProperty("build.tool", "Apache Maven 3.9.9");
    }

    public static String requiredJdk() {
        return PROPERTIES.getProperty("build.jdk.required", "Oracle OpenJDK 22.0.1");
    }

    /** 构建工具描述，用于报告的“生成信息”章节。 */
    public static String buildToolDescription() {
        return buildTool();
    }

    /** 运行期真实 JDK 信息，例如 "OpenJDK 64-Bit Server VM 22.0.1 (Oracle Corporation)"。 */
    public static String runtimeJdk() {
        String vmName = System.getProperty("java.vm.name", "Java VM");
        String version = System.getProperty("java.version", "unknown");
        String vendor = System.getProperty("java.vendor", "unknown");
        return vmName + " " + version + " (" + vendor + ")";
    }

    /** 判断运行期是否满足要求的 JDK 主版本。 */
    public static boolean runtimeJdkSatisfiesRequirement() {
        String version = System.getProperty("java.version", "0");
        String major = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
        return TextUtil.parseLong(major, 0) >= 22L;
    }
}
