package com.blamenet.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 安装目录与结果目录解析。
 * <p>
 * 设计要求：测试结果只保存在“安装目录下的 results 目录”中（每次诊断在 results/ 下创建
 * 「BlameNet-yyyy-MM-dd-HH-mm-ss报告」子文件夹）。因此：
 * <ul>
 *     <li>以 exe 安装包（jpackage 打包）运行时，安装目录 = 启动器 exe 所在目录
 *         （即安装时选择的「…\BlameNet-&lt;版本号&gt;」）；</li>
 *     <li>以可执行 jar 运行时，安装目录 = jar 所在目录；</li>
 *     <li>在 IDE / target/classes 中运行时，安装目录 = 工程根目录；</li>
 *     <li>可通过 -Dblamenet.home=... 显式指定；</li>
 *     <li>可通过命令行 --output 覆盖结果目录（仅用于调试，默认不改变安装目录）。</li>
 * </ul>
 */
public final class InstallPaths {

    private static final String HOME_PROPERTY = "blamenet.home";

    private InstallPaths() {
    }

    /** 解析安装目录。 */
    public static Path installDir() {
        String override = System.getProperty(HOME_PROPERTY);
        if (TextUtil.isNotBlank(override)) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path code = codeSourceDir();
        if (code == null) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        if (Files.isRegularFile(code)) {
            // 以 jar 方式运行
            Path parent = code.getParent();
            // jpackage 打包后主 jar 位于 <安装根>/app/ 下，真正的“安装目录”是其父目录
            // （即安装时选择的 …\BlameNet-<版本号>）。
            if (parent != null && "app".equals(fileName(parent))) {
                Path root = parent.getParent();
                return root == null ? parent : root;
            }
            return parent == null ? code : parent;
        }
        // 以 classes 目录方式运行，向上寻找构建输出目录
        Path cursor = code;
        while (cursor != null) {
            String name = fileName(cursor);
            if ("target".equals(name) || "out".equals(name) || "build".equals(name)) {
                Path install = cursor.getParent();
                return install == null ? cursor : install;
            }
            cursor = cursor.getParent();
        }
        return code;
    }

    /** 结果目录：安装目录\results，按需创建。 */
    public static Path resultsDir() throws IOException {
        return ensureDir(installDir().resolve("results"));
    }

    /** 覆盖结果目录（命令行 --output 使用）。 */
    public static Path ensureDir(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        return normalized;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static Path codeSourceDir() {
        try {
            var source = InstallPaths.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
