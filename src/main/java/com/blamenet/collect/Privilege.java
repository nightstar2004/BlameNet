package com.blamenet.collect;

import com.blamenet.util.TextUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 权限与提权相关能力。
 * <p>
 * 工具要求以管理员权限运行（部分只读检查如 sfc /verifyonly、DISM /ScanHealth、pktmon 需要管理员）。
 * 权限不足时不会强行绕过，而是如实记录并在报告中标注；界面提供用户主动触发的提权重启入口。
 */
public final class Privilege {

    /** 真实判定当前进程是否具备管理员权限。 */
    private static final String ADMIN_SCRIPT =
            "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())"
                    + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)";

    private Privilege() {
    }

    /** 权限检查结果：判定值 + 可作为证据的原始输出。 */
    public record AdminCheck(boolean admin, CmdResult evidence) {
    }

    /** 执行管理员权限判定，返回判定值与证据。 */
    public static AdminCheck check() {
        CmdResult result = PowerShell.run(ADMIN_SCRIPT, Duration.ofSeconds(30));
        String output = result.output().trim();
        boolean admin = output.toLowerCase().contains("true");
        return new AdminCheck(admin, result);
    }

    /**
     * 以管理员身份重新启动本工具（需用户在弹出的 UAC 中确认）。
     * 这是唯一一处“主动发起”的动作，且必须由用户在界面上显式点击触发；工具本身不会自动提权。
     *
     * @return 是否成功发起了提权请求
     */
    public static boolean relaunchElevated(String extraArgument) {
        String javaExecutable = ProcessHandle.current().info().command().orElse(null);
        if (javaExecutable == null) {
            return false;
        }
        Path jar = currentJar();
        String argumentList;
        if (jar != null) {
            argumentList = "'-jar','" + jar + "'" + (extraArgument == null ? "" : ",'" + extraArgument + "'");
        } else {
            String classPath = System.getProperty("java.class.path", "");
            argumentList = "'-cp','" + classPath + "','com.blamenet.Launcher'"
                    + (extraArgument == null ? "" : ",'" + extraArgument + "'");
        }
        String script = "Start-Process -FilePath '" + javaExecutable + "' -ArgumentList " + argumentList + " -Verb RunAs";
        CmdResult result = PowerShell.run(script, Duration.ofSeconds(30));
        return result.executed() && !result.output().toLowerCase().contains("error");
    }

    /** 当前运行体：以 jar 运行时返回 jar 路径，以 classes 运行时返回 null。 */
    public static Path currentJar() {
        try {
            var source = Privilege.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只读读取文本文件并生成证据（例如 hosts 文件）。 */
    public static CmdResult readTextFile(Path file, String evidenceCommand) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            long cost = (System.nanoTime() - startNanos) / 1_000_000L;
            return new CmdResult(evidenceCommand, content, "", 0, false, cost, startedAt, true);
        } catch (IOException e) {
            String fallback = null;
            try {
                // 部分系统上的 hosts 文件并非 UTF-8（可能为 ANSI/GBK），回退解码
                byte[] raw = Files.readAllBytes(file);
                fallback = Cmd.decode(raw);
            } catch (IOException ignored) {
                // 读取失败，保持 null
            }
            long cost = (System.nanoTime() - startNanos) / 1_000_000L;
            if (TextUtil.isNotBlank(fallback)) {
                return new CmdResult(evidenceCommand, fallback, "", 0, false, cost, startedAt, true);
            }
            return new CmdResult(evidenceCommand, "", "[BlameNet] 读取失败：" + e.getMessage(), -1, false, cost, startedAt, true);
        }
    }
}
