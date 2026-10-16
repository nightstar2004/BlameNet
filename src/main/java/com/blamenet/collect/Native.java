package com.blamenet.collect;

import java.time.Duration;
import java.util.List;

/**
 * Windows 原生命令行工具执行封装（ipconfig / ping / netsh / route / arp / netstat /
 * powercfg / sfc / DISM / pktmon 等）。
 * <p>
 * 通过 cmd.exe /d /s /c 启动，/d 跳过 AutoRun 脚本，/s 保证引号按预期剥离。
 * 报告中记录的 RawCommand 为该工具的原生命令行（例如 "ipconfig /all"），与设计要求示例一致。
 */
public final class Native {

    private static final String COMSPEC = resolveShell();

    private Native() {
    }

    public static String shell() {
        return COMSPEC;
    }

    /** 执行一条原生命令。 */
    public static CmdResult run(String commandLine, Duration timeout) {
        List<String> commandLineArgs = List.of(COMSPEC, "/d", "/s", "/c", commandLine);
        return Cmd.exec(commandLineArgs, commandLine, timeout, null);
    }

    private static String resolveShell() {
        String comspec = System.getenv("ComSpec");
        if (comspec != null && !comspec.isBlank()) {
            return comspec;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return systemRoot + "\\System32\\cmd.exe";
        }
        return "cmd.exe";
    }
}
