package com.blamenet.collect;

import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import java.time.Duration;
import java.time.Instant;

/**
 * 一次真实命令执行的结果。
 * <p>
 * 这里保存的 command / stdout / stderr 都是运行时真实产生的内容，
 * 会被原样写入报告的 RawCommand / RawOutput 字段，不做任何改写。
 */
public final class CmdResult {

    private final String command;
    private final String stdout;
    private final String stderr;
    private final int exitCode;
    private final boolean timedOut;
    private final long durationMillis;
    private final Instant startedAt;
    private final boolean executed;

    CmdResult(String command, String stdout, String stderr, int exitCode, boolean timedOut,
              long durationMillis, Instant startedAt, boolean executed) {
        this.command = command;
        this.stdout = TextUtil.normalize(stdout);
        this.stderr = TextUtil.normalize(stderr);
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.durationMillis = durationMillis;
        this.startedAt = startedAt;
        this.executed = executed;
    }

    /** 实际执行的命令文本（PowerShell 脚本本体或 CMD 命令行）。 */
    public String command() {
        return command;
    }

    /** 标准输出。 */
    public String stdout() {
        return stdout;
    }

    /** 标准错误（已清理 PowerShell 的 CLIXML 噪声）。 */
    public String stderr() {
        return stderr;
    }

    /**
     * 证据使用的“原始输出”：默认取标准输出；
     * 若标准输出为空而标准错误非空，则回退为标准错误，避免丢失有价值的错误信息。
     */
    public String output() {
        if (TextUtil.isNotBlank(stdout)) {
            return stderr.isBlank() ? stdout : stdout + "\n[stderr]\n" + stderr;
        }
        return stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String recordTime() {
        return TimeUtil.iso(startedAt);
    }

    /** 是否有输出可供解析。 */
    public boolean hasOutput() {
        return TextUtil.isNotBlank(output());
    }

    /** 是否执行成功（进程正常结束且退出码为 0）。 */
    public boolean ok() {
        return !timedOut && exitCode == 0;
    }

    /** 构造一个“未真正执行”的结果，用于前置校验失败时的证据占位（会明确说明未执行）。 */
    public static CmdResult notExecuted(String command, String reason) {
        return new CmdResult(command, "[BlameNet] 未执行：" + reason, "", -1, false, 0L, Instant.now(), false);
    }

    /** 构造一次采集失败的结果（例如可执行文件不存在）。 */
    public static CmdResult failure(String command, String message) {
        return new CmdResult(command, "", message, -2, false, 0L, Instant.now(), true);
    }

    /** 本次取值是否触发了真实执行（false 表示命中缓存）。 */
    public boolean executed() {
        return executed;
    }

    public String describe() {
        return "命令=" + command + " 退出码=" + exitCode + " 耗时=" + TimeUtil.duration(Duration.ofMillis(durationMillis))
                + (timedOut ? " [超时]" : "");
    }
}
