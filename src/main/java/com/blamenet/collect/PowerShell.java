package com.blamenet.collect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * PowerShell 采集封装。
 * <p>
 * 统一以 powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command 方式执行脚本，
 * 并在脚本前注入两段固定前缀：
 * <ul>
 *     <li>把标准输出编码固定为 UTF-8，保证中文输出不乱码；</li>
 *     <li>关闭进度条与错误终止，保证采集过程不阻塞、不中断。</li>
 * </ul>
 * 报告中的 RawCommand 记录的是“等价的原生命令文本”（即网络工程师会在控制台里直接敲的命令），
 * 该外壳参数在报告的证据说明章节中统一披露。
 */
public final class PowerShell {

    /** 固定前缀：不是业务命令的一部分，仅为运行环境适配。 */
    public static final String PREFIX =
            "try{[Console]::OutputEncoding=[System.Text.Encoding]::UTF8}catch{};"
                    + "$ProgressPreference='SilentlyContinue';"
                    + "$ErrorActionPreference='SilentlyContinue';";

    private static final String EXE = resolveExecutable();

    private PowerShell() {
    }

    public static String executable() {
        return EXE;
    }

    /** 执行一段 PowerShell 脚本，script 即为写入证据的 RawCommand。 */
    public static CmdResult run(String script, Duration timeout) {
        String command = PREFIX + script;
        List<String> commandLine = List.of(
                EXE,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", command);
        return Cmd.exec(commandLine, script, timeout, null);
    }

    private static String resolveExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path candidate = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        List<Path> pathDirs = pathDirectories();
        for (String name : List.of("powershell.exe", "pwsh.exe")) {
            for (Path dir : pathDirs) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return "powershell.exe";
    }

    private static List<Path> pathDirectories() {
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null) {
            return dirs;
        }
        for (String entry : path.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                dirs.add(Path.of(entry.trim()));
            } catch (Exception ignored) {
                // 忽略非法路径项
            }
        }
        return dirs;
    }
}
