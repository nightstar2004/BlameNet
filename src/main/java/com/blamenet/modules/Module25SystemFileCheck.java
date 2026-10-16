package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 模块 25：运行系统文件检查（只读）。
 * <p>
 * 采集：sfc /verifyonly 与 DISM /Online /Cleanup-Image /ScanHealth，仅扫描、不修复；
 * 并读取 CBS 日志尾部作为补充证据。
 * 判定：发现完整性冲突 → Warn（建议修复）；无冲突 → Pass；权限不足 → Info 并标注需管理员权限。
 */
public final class Module25SystemFileCheck extends AbstractCheckModule {

    public Module25SystemFileCheck() {
        super("25", "运行系统文件检查（只读）", FaultLayer.SERVICE_SYSTEM, 6, true, 300);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("检查管理员权限与扫描工具可用性");
        boolean admin = context.admin();
        boolean quick = !context.options().deepSystemScanEnabled();

        if (!admin || quick) {
            CmdResult none = CmdResult.notExecuted(Probes.CMDP_SFC + " / " + Probes.CMDP_DISM,
                    quick ? "当前为快速档（--quick），按设计约定跳过 sfc 与 DISM 深度扫描；默认档位会完整执行"
                            : "当前进程没有管理员权限，sfc /verifyonly 与 DISM /ScanHealth 无法执行");
            context.step("记录跳过原因（未执行任何系统文件扫描）");
            context.step("生成结论与修复建议");
            return result()
                    .status(Status.INFO)
                    .summary(quick
                            ? "快速档未执行系统文件完整性扫描（--quick）"
                            : "系统文件完整性扫描需要管理员权限，本次未执行")
                    .diagnosis(quick
                            ? "快速档（--quick）按设计约定缩减深度扫描，用于反复调试；默认档位会执行 sfc /verifyonly 与 DISM /ScanHealth"
                            : "sfc /verifyonly 与 DISM /Online /Cleanup-Image /ScanHealth 均需要管理员权限，"
                            + "权限不足时无法判断系统文件完整性")
                    .suggestion(quick
                            ? "如需完整的完整性结论，请去掉 --quick 参数重新运行本工具"
                            : adminRequiredSuggestion("sfc /verifyonly")
                                    + "\n也可通过界面上的“以管理员身份重新启动”按钮重新运行")
                    .evidence("系统文件扫描（未执行）", none)
                    .build();
        }

        context.step("执行 sfc /verifyonly 扫描系统文件完整性（只读校验，可能耗时数分钟）");
        CmdResult sfcRaw = context.cmd(Probes.KEY_SFC, Probes.CMDP_SFC, com.blamenet.core.DiagnosticContext.TIMEOUT_HEAVY);

        context.step("执行 DISM /Online /Cleanup-Image /ScanHealth 扫描组件存储（只扫描，不修复）");
        CmdResult dismRaw = context.cmd(Probes.KEY_DISM, Probes.CMDP_DISM, com.blamenet.core.DiagnosticContext.TIMEOUT_HEAVY);

        context.step("读取 CBS 日志尾部作为补充证据");
        CmdResult cbsRaw = context.ps(Probes.KEY_CBS_LOG, Probes.CBS_LOG);

        context.step("解析扫描结论并判定系统文件完整性");
        String sfcOutput = sfcRaw.output();
        String dismOutput = dismRaw.output();
        String sfcText = sfcOutput.toLowerCase(Locale.ROOT);
        String dismText = dismOutput.toLowerCase(Locale.ROOT);

        boolean sfcClean = containsAny(sfcText, SFC_CLEAN_HINTS);
        boolean sfcCorrupt = containsAny(sfcText, SFC_CORRUPT_HINTS);
        boolean sfcBlocked = containsAny(sfcText, SFC_BLOCKED_HINTS);
        boolean sfcTimedOut = sfcRaw.timedOut();

        boolean dismClean = dismText.contains("未检测到组件存储损坏") || dismText.contains("no component store corruption detected")
                || dismText.contains("组件存储已修复") || dismText.contains("the component store has been repaired");
        boolean dismRepairable = dismText.contains("可以修复组件存储") || dismText.contains("组件存储可修复")
                || dismText.contains("the component store is repairable") || dismText.contains("可以修复");
        boolean dismTimedOut = dismRaw.timedOut();

        List<String> findings = new ArrayList<>();
        if (sfcCorrupt) {
            findings.add("sfc /verifyonly 报告 Windows 资源保护发现系统文件完整性违规");
        }
        if (dismRepairable) {
            findings.add("DISM /ScanHealth 报告组件存储存在可修复的损坏");
        }
        if (sfcTimedOut) {
            findings.add("sfc /verifyonly 超过 " + fmt(com.blamenet.core.DiagnosticContext.TIMEOUT_HEAVY) + " 未完成，已强制结束");
        }
        if (dismTimedOut) {
            findings.add("DISM /ScanHealth 超过 " + fmt(com.blamenet.core.DiagnosticContext.TIMEOUT_HEAVY) + " 未完成，已强制结束");
        }
        if (sfcBlocked) {
            findings.add("sfc 返回权限相关提示，扫描可能未真正执行：" + extractConclusion(sfcOutput));
        }

        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!findings.isEmpty()) {
            status = Status.WARN;
            summary = "系统文件完整性检查发现需关注项：" + String.join("；", findings);
            diagnosis = "系统文件或组件存储存在损坏/可修复项，可能影响网络组件的稳定性"
                    + "（例如 winsock、TCP/IP 相关系统文件被替换或损坏时会出现诡异网络故障）";
            suggestion = "1.先执行 sfc /scannow 尝试修复系统文件（需管理员权限，命令需手动执行，本工具不会自动修复）；"
                    + "2.若 sfc 无法修复，再执行 DISM /Online /Cleanup-Image /RestoreHealth 修复组件存储；"
                    + "3.修复后重启并重新运行本工具对比结果";
        } else if (sfcClean && dismClean) {
            status = Status.PASS;
            summary = "Windows 资源保护未找到任何完整性冲突，DISM 未检测到组件存储损坏";
            diagnosis = "系统文件与组件存储完整，网络故障与系统文件损坏无关";
            suggestion = "无";
        } else if (sfcClean || dismClean) {
            status = Status.PASS;
            summary = (sfcClean ? "sfc /verifyonly：未找到任何完整性冲突" : "DISM /ScanHealth：未检测到组件存储损坏")
                    + "；另一项未给出明确结论，原始输出已保留在证据中";
            diagnosis = "至少一项完整性扫描通过，未发现明确损坏证据";
            suggestion = "如需更完整结论，可在管理员命令行中手动执行 sfc /verifyonly 与 DISM /ScanHealth 核对";
        } else {
            status = Status.INFO;
            summary = "两项扫描均未给出可识别的结论文本，无法判定系统文件完整性（请人工核对证据）";
            diagnosis = "扫描输出可能因系统语言/版本差异或中途被系统策略打断，导致无法自动判定";
            suggestion = "请在管理员命令行中手动执行 sfc /verifyonly 与 DISM /Online /Cleanup-Image /ScanHealth 核对结果";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("系统文件扫描结论摘录", "BlameNet 汇总：sfc /verifyonly + DISM /ScanHealth",
                        "sfc 结论摘录：" + extractConclusion(sfcRaw.output())
                                + "\nDISM 结论摘录：" + extractConclusion(dismRaw.output())
                                + "\n发现项：\n" + (findings.isEmpty() ? "无" : String.join("\n", findings)))
                .evidence("sfc /verifyonly 原始输出", sfcRaw)
                .evidence("DISM /Online /Cleanup-Image /ScanHealth 原始输出", dismRaw)
                .evidence("CBS 日志尾部（只读读取）", cbsRaw)
                .build();
    }

    /**
     * 从扫描输出中提取结论性文本。
     * <p>
     * sfc / DISM 会把进度条（形如 {@code [====95.5%====]}）与结论文本混在同一个流里，
     * 且用 {@code \r} 原地刷新。这里先剥离进度条装饰、再把 {@code \r} 当作换行切开，
     * 然后优先返回含结论关键词的行；若一句都匹配不到，则退回尾部若干行，
     * 保证证据层永远保留真实输出、展示层不出现进度条噪声。
     */
    private static String extractConclusion(String output) {
        List<String> meaningful = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        for (String raw : TextUtil.normalize(output).replace('\r', '\n').split("\n")) {
            String text = stripProgress(raw.trim());
            if (text.isEmpty() || NOISE.matcher(text).matches()) {
                continue;
            }
            tail.add(text);
            for (String hint : CONCLUSION_HINTS) {
                if (text.contains(hint)) {
                    meaningful.add(text);
                    break;
                }
            }
        }
        List<String> chosen = !meaningful.isEmpty() ? meaningful
                : (tail.isEmpty() ? List.of() : tail.subList(Math.max(0, tail.size() - 4), tail.size()));
        if (chosen.isEmpty()) {
            return "无输出";
        }
        return String.join(" / ", new LinkedHashSet<>(chosen));
    }

    /** 去掉行内的进度条片段，如 {@code [====== 95.5% ======]}。 */
    private static String stripProgress(String line) {
        return PROGRESS.matcher(line).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    }

    /** 判定为进度条/装饰符号而非结论的行。 */
    private static final java.util.regex.Pattern NOISE =
            java.util.regex.Pattern.compile("^[\\[\\]=%.·\\-–—\\s\\d/\\\\|]+$");

    /** 行内进度条片段。 */
    private static final java.util.regex.Pattern PROGRESS =
            java.util.regex.Pattern.compile("\\[[=\\s]*\\d+(?:\\.\\d+)?%[=\\s]*\\]|\\[=+\\s*\\]");

    /** 结论关键词（中英文），用于从混排输出中挑出真正的结论行。 */
    private static final String[] CONCLUSION_HINTS = {
            "未找到任何完整性冲突", "找到了完整性违规", "完整性违规", "found corrupt files",
            "integrity violations", "did not find any integrity violations",
            "找到了损坏文件", "无法修复", "unable to fix",
            "未检测到组件存储损坏", "组件存储可修复", "组件存储已修复", "可以修复组件存储",
            "no component store corruption detected", "the component store is repairable",
            "the component store has been repaired",
            "操作成功完成", "the operation completed successfully",
            "拒绝访问", "access is denied", "必须以管理员", "错误", "error"};

    /**
     * sfc /verifyonly 判定“未发现完整性冲突”的实际措辞。
     * <p>
     * 注意：不同 Windows 版本的中文措辞不同，旧版为“未找到任何完整性冲突”，
     * 新版为“未找到完整性违规”，必须同时覆盖，否则会把正常结果误判为“无法判定”。
     */
    private static final String[] SFC_CLEAN_HINTS = {
            "未找到任何完整性冲突", "未找到完整性违规", "did not find any integrity violations"};

    /**
     * sfc /verifyonly 判定“发现完整性冲突”的实际措辞。
     * <p>
     * 新版 Windows 11 中文输出为“Windows 资源保护找到了完整性违规。”，
     * 与旧版“找到了损坏文件”不同，故需同时覆盖这两种措辞。
     */
    private static final String[] SFC_CORRUPT_HINTS = {
            "找到了完整性违规", "发现完整性违规", "found integrity violations",
            "找到了损坏文件", "found corrupt files", "无法修复", "unable to fix"};

    /** sfc 因权限不足而未真正执行时的提示措辞（仅在已提权流程中做兜底复核）。 */
    private static final String[] SFC_BLOCKED_HINTS = {
            "必须以管理员", "以管理员身份", "拒绝访问", "access is denied", "you must be an administrator"};

    /** 判断文本（已转小写）是否包含任一句。 */
    private static boolean containsAny(String text, String[] hints) {
        for (String hint : hints) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(java.time.Duration duration) {
        return duration.toMinutes() + " 分钟";
    }
}
