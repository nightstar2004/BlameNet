package com.blamenet.collect;

import com.blamenet.util.TextUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真实进程执行器。
 * <p>
 * 关键约束（对应设计要求中的“只读诊断”）：
 * <ul>
 *     <li>只执行采集类命令，绝不执行 release / renew / flushdns / netsh reset / sfc 修复 等写操作；</li>
 *     <li>每个命令都有硬超时，超时后连同子进程树一起强制结束，避免残留进程影响用户系统；</li>
 *     <li>标准输出与标准错误并发读取，避免管道缓冲区写满导致的死锁；</li>
 *     <li>输出编码自适应：按 UTF-16 → UTF-8 → GBK 的顺序解码，正确处理中文 Windows 的控制台输出。</li>
 * </ul>
 */
public final class Cmd {

    private static final ExecutorService STREAM_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "blamenet-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;
    private static final Charset UTF_16BE = StandardCharsets.UTF_16BE;

    private Cmd() {
    }

    /** 执行一条命令，返回真实结果。 */
    public static CmdResult exec(List<String> commandLine, String displayCommand, Duration timeout, Path workingDirectory) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        Process process;
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(false);
        try {
            process = builder.start();
        } catch (IOException e) {
            return CmdResult.failure(displayCommand, "[BlameNet] 无法启动进程：" + e.getMessage());
        }

        CompletableFuture<byte[]> stdoutFuture = readAsync(process.getInputStream());
        CompletableFuture<byte[]> stderrFuture = readAsync(process.getErrorStream());

        boolean finished = false;
        try {
            finished = process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finished) {
            killTree(process);
        }
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        byte[] stdout = await(stdoutFuture);
        byte[] stderr = await(stderrFuture);

        int exitCode = finished ? process.exitValue() : -3;
        String decodedOut = decode(stdout);
        String decodedErr = cleanupStderr(decode(stderr));
        return new CmdResult(displayCommand, decodedOut, decodedErr, exitCode, !finished, durationMillis, startedAt, true);
    }

    private static CompletableFuture<byte[]> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }, STREAM_POOL);
    }

    private static byte[] await(CompletableFuture<byte[]> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return new byte[0];
        }
    }

    /** 强制结束进程及其全部子进程，确保不留下 ping / pktmon 之类的残留进程。 */
    private static void killTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Throwable ignored) {
                // 子进程可能已退出
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 编码自适应解码。
     * <p>
     * Windows 原生工具的输出编码并不统一：ipconfig / ping / netsh / DISM 等按 OEM 代码页输出
     * （简体中文为 GBK），而 <b>sfc.exe</b> 等组件按 UTF-16LE 输出（每个 ASCII 字符后夹一个 0x00 字节）。
     * 若把 UTF-16 字节直接交给 GBK 兜底解码，会得到整段乱码。因此判定顺序为：
     * UTF-16（BOM → 字节分布启发式，需通过可读性复核）→ UTF-8 严格 → GBK 兜底。
     */
    public static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length >= 2) {
            int first = data[0] & 0xFF;
            int second = data[1] & 0xFF;
            if (first == 0xFF && second == 0xFE) {
                return UTF_16LE.decode(ByteBuffer.wrap(data, 2, data.length - 2)).toString();
            }
            if (first == 0xFE && second == 0xFF) {
                return UTF_16BE.decode(ByteBuffer.wrap(data, 2, data.length - 2)).toString();
            }
        }
        Charset guessed = guessUtf16(data);
        if (guessed != null) {
            String text = guessed.decode(ByteBuffer.wrap(data)).toString().replace("\u0000", "");
            if (isPlausibleText(text)) {
                return text;
            }
        }
        byte[] clean = stripBom(data);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(clean))
                    .toString();
        } catch (CharacterCodingException ignored) {
            // 继续尝试 GBK
        }
        return GBK.decode(ByteBuffer.wrap(clean)).toString();
    }

    /**
     * 依据“隔位 0x00”的分布猜测无 BOM 的 UTF-16 字节序；不符合 UTF-16 特征时返回 null。
     * <p>
     * UTF-16 文本中，奇数位或偶数位会出现大量 0x00（取决于字节序与 ASCII 占比）；
     * 正常的多字节编码（GBK/UTF-8）不会出现这种强烈的奇偶偏置。
     */
    private static Charset guessUtf16(byte[] data) {
        int samples = Math.min(data.length, 8192);
        if (samples < 8) {
            return null;
        }
        int zeroEven = 0;
        int zeroOdd = 0;
        for (int i = 0; i < samples; i++) {
            if (data[i] == 0) {
                if ((i & 1) == 0) {
                    zeroEven++;
                } else {
                    zeroOdd++;
                }
            }
        }
        int dominant = Math.max(zeroEven, zeroOdd);
        int other = Math.min(zeroEven, zeroOdd);
        if (dominant < samples / 20) {
            return null;
        }
        if (dominant < other * 4 + 1) {
            return null;
        }
        return zeroOdd > zeroEven ? UTF_16LE : UTF_16BE;
    }

    /** 复核解码结果是否像正常文本（可打印字符占绝对多数）。 */
    private static boolean isPlausibleText(String text) {
        int total = 0;
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            total++;
            if (!Character.isISOControl(c) && c != '\uFFFD') {
                printable++;
            }
        }
        return total == 0 || printable * 10 >= total * 9;
    }

    private static byte[] stripBom(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            byte[] trimmed = new byte[data.length - 3];
            System.arraycopy(data, 3, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return data;
    }

    /** PowerShell 在重定向标准错误时会输出 CLIXML 包装，这里只保留有意义的错误文本。 */
    private static String cleanupStderr(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.contains("#< CLIXML")) {
            return TextUtil.stripAnsi(text);
        }
        List<String> kept = new ArrayList<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine;
            if (line.contains("#< CLIXML") || line.contains("<Objs ") || line.contains("</Objs>")) {
                continue;
            }
            line = line.replaceAll("^<S S=\"Error\">", "").replaceAll("</S>\\s*$", "");
            line = line.replace("_x000D__x000A_", " ");
            line = line.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
            if (TextUtil.isNotBlank(line)) {
                kept.add(line);
            }
        }
        return TextUtil.stripAnsi(String.join("\n", kept));
    }
}
