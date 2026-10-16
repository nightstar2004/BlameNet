package com.blamenet;

import com.blamenet.collect.Privilege;
import com.blamenet.core.DiagnosticEngine;
import com.blamenet.core.ModuleRegistry;
import com.blamenet.core.Options;
import com.blamenet.core.ProgressTracker;
import com.blamenet.model.Report;
import com.blamenet.report.ReportPipeline;
import com.blamenet.ui.CliRunner;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.InstallPaths;
import com.blamenet.util.TextUtil;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 程序入口。
 * <p>
 * 职责：
 * <ol>
 *     <li>解析命令行参数并输出帮助 / 版本；</li>
 *     <li>单实例锁：避免两个诊断实例同时采集导致证据互相干扰；</li>
 *     <li>选择界面：图形界面（默认）或命令行界面，无图形环境时自动降级；</li>
 *     <li>串联“诊断 → 报告流水线”，并把报告阶段的进度交给同一个进度跟踪器。</li>
 * </ol>
 * 本工具只做只读诊断，唯一可能的系统级主动动作是“以管理员身份重新运行”（由用户显式确认后触发）。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        if (options.help) {
            System.out.println(Options.helpText());
            return;
        }
        if (options.version) {
            System.out.println(BuildInfo.toolName() + " " + BuildInfo.toolVersion());
            System.out.println("构建工具：" + BuildInfo.buildToolDescription());
            System.out.println("运行 JDK：" + BuildInfo.runtimeJdk()
                    + (BuildInfo.runtimeJdkSatisfiesRequirement() ? "（满足 " + BuildInfo.requiredJdk() + " 要求）"
                    : "（低于要求的 " + BuildInfo.requiredJdk() + "）"));
            System.out.println("安装目录：" + InstallPaths.installDir());
            return;
        }

        // 仅重建报表模式：不采集、不加锁，直接从 JSON 主数据源重新生成全部形态。
        if (TextUtil.isNotBlank(options.rebuildJson)) {
            runRebuild(options);
            return;
        }

        try (InstanceLock lock = InstanceLock.takeOrNull()) {
            if (InstanceLock.busy()) {
                System.err.println("检测到已有 BlameNet 实例正在运行，为避免诊断证据相互干扰，本次启动已取消。");
                System.exit(2);
            }
            if (options.uiMode == Options.UiMode.GUI && !graphicsAvailable()) {
                System.out.println("当前环境没有可用的图形界面，已自动切换为命令行模式。");
                options.uiMode = Options.UiMode.CLI;
            }
            if (options.uiMode == Options.UiMode.AUTO) {
                options.uiMode = graphicsAvailable() ? Options.UiMode.GUI : Options.UiMode.CLI;
            }
            if (options.uiMode == Options.UiMode.GUI) {
                runGui(options);
            } else {
                runCli(options);
            }
        }
    }

    // ------------------------------------------------------------------ 各运行模式

    private static void runGui(Options options) {
        try {
            Class<?> guiClass = Class.forName("com.blamenet.ui.SwingGui");
            guiClass.getMethod("launch", Options.class).invoke(null, options);
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("图形界面初始化失败（" + e + "），已切换为命令行模式。");
            runCli(options);
        }
    }

    private static void runCli(Options options) {
        try {
            new CliRunner(options).run();
        } catch (Exception e) {
            System.err.println("命令行执行失败：" + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runRebuild(Options options) {
        CliRunner runner = new CliRunner(options);
        try {
            runner.runRebuild();
        } catch (Exception e) {
            System.err.println("重建报表失败：" + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 执行一次完整诊断并生成报告（GUI 与 CLI 共用）。
     * <p>
     * 诊断与报告共用同一个 {@link ProgressTracker}，因此界面上的进度条在“模块执行”与
     * “报告生成”两个阶段之间是连续推进的，不会跳变或回退。
     */
    public static ReportPipeline.Bundle diagnoseAndReport(Options options, ProgressTracker tracker,
                                                          com.blamenet.core.EngineListener listener,
                                                          Report reportSink) {
        List<com.blamenet.core.CheckModule> modules = ModuleRegistry.filtered(options.moduleFilter);
        tracker.plan(DiagnosticEngine.buildPlan(modules));

        DiagnosticEngine engine = new DiagnosticEngine(modules, listener);
        Report report = engine.run(options, tracker);
        if (reportSink != null) {
            // 就地更新列表内容，保持界面表格持有的列表引用有效。
            reportSink.testMeta = report.testMeta;
            reportSink.testEnvironment = report.testEnvironment;
            reportSink.summary = report.summary;
            reportSink.moduleResults.clear();
            reportSink.moduleResults.addAll(report.moduleResults);
            reportSink.manualValidationList.clear();
            reportSink.manualValidationList.addAll(report.manualValidationList);
        }
        if (options.skipReports) {
            listener.onStatus("已跳过报告导出（调试模式），仅保留内存结果");
        }
        try {
            return ReportPipeline.run(report, options, tracker, listener);
        } catch (Exception e) {
            listener.onLog("报告流水线失败：" + e.getMessage());
            listener.onStatus("报告生成失败");
            return new ReportPipeline.Bundle(null, null, null, null, List.of(),
                    List.of(new ReportPipeline.Failure("pipeline", "报告流水线", String.valueOf(e.getMessage()))),
                    false);
        } finally {
            tracker.finish();
        }
    }

    private static boolean graphicsAvailable() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        // 服务器核心版 / 无桌面环境时 Windows 图形栈不可用，这里做一次防御性判断。
        return !System.getProperty("java.awt.headless", "false").equalsIgnoreCase("true");
    }

    /** 单实例锁：锁文件放在安装目录下，随进程退出自动释放。 */
    private static final class InstanceLock implements AutoCloseable {

        private static volatile boolean busy;

        private final FileChannel channel;
        private final FileLock lock;

        private InstanceLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static boolean busy() {
            return busy;
        }

        /**
         * 获取锁。
         * <ul>
         *     <li>成功：返回锁对象，退出时自动释放；</li>
         *     <li>已有实例：置位 {@link #busy()} 并返回 null；</li>
         *     <li>锁文件不可写：返回 null 且不置位，退化为无锁运行，不阻断诊断。</li>
         * </ul>
         */
        static InstanceLock takeOrNull() {
            busy = false;
            try {
                Path lockFile = InstallPaths.installDir().resolve(".blamenet.lock");
                FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    busy = true;
                    return null;
                }
                Files.writeString(lockFile, "pid=" + ProcessHandle.current().pid() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                return new InstanceLock(channel, lock);
            } catch (OverlappingFileLockException e) {
                busy = true;
                return null;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                if (lock != null && lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
                // 释放失败不影响进程退出。
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // 同上。
            }
        }
    }

    /** 供界面提示使用：当前是否已具备管理员权限。 */
    public static boolean isAdmin() {
        return Privilege.check().admin();
    }
}
