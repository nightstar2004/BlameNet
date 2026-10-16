package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.TextUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 模块 26：使用 Pktmon 捕获并归因丢包。
 * <p>
 * 采集：pktmon comp list → pktmon start --capture --comp nics --etw（持续 10 秒）→ pktmon stop
 * → pktmon etl2txt 解析捕获文件。
 * 判定：发现丢包归因 → Warn（VLAN 过滤等属于正常丢弃，已单独说明）；无丢包 → Pass；不可用/无权限 → Info。
 * 说明：捕获文件生成在本工具的临时目录中，诊断结束后立即清理，不会写入安装目录与 results 目录。
 */
public final class Module26PktmonCapture extends AbstractCheckModule {

    /** 捕获持续时长（秒），按设计文档约定为 10 秒。 */
    private static final int CAPTURE_SECONDS = 10;

    /** 证据中保留的抓包文本最大字符数。 */
    private static final int CAPTURE_TEXT_LIMIT = 40_000;

    public Module26PktmonCapture() {
        super("26", "使用Pktmon捕获并归因丢包", FaultLayer.DEEP_PACKET, 6, true, 30);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("检查管理员权限与 pktmon 组件可用性");
        if (!context.admin()) {
            CmdResult none = CmdResult.notExecuted("pktmon start --capture --comp nics --etw",
                    "当前进程没有管理员权限，pktmon 需要管理员权限才能启动捕获");
            context.step("记录未执行原因");
            context.step("生成结论与修复建议");
            return result()
                    .status(Status.INFO)
                    .summary("Pktmon 抓包归因需要管理员权限，本次未执行")
                    .diagnosis("pktmon 是内核级数据包监视器，必须由管理员权限启动；权限不足时无法完成丢包归因分析")
                    .suggestion(adminRequiredSuggestion("pktmon start --capture --comp nics --etw --file-name capture.etl")
                            + "\n也可通过界面上的“以管理员身份重新启动”按钮重新运行")
                    .evidence("Pktmon 抓包（未执行）", none)
                    .build();
        }

        CmdResult components = context.cmd(Probes.KEY_PKTMON_COMPONENTS, Probes.CMDP_PKTMON_COMPONENTS,
                DiagnosticContext.TIMEOUT_SHORT);
        if (isUnavailable(components)) {
            context.step("记录 pktmon 不可用的原因");
            context.step("生成结论与修复建议");
            return result()
                    .status(Status.INFO)
                    .summary("系统中未找到可用的 pktmon 工具（Windows 10 1809 及以上版本提供），无法进行抓包归因")
                    .diagnosis("pktmon 组件不存在或不可执行，因此跳过前无法完成数据包级丢包归因；"
                            + "其余 30 个模块的结论不受影响")
                    .suggestion("如需数据包级归因，可在系统具备 pktmon 后重新运行本工具；"
                            + "也可用 Wireshark 手动抓包对比（本工具只读，不会安装任何组件）")
                    .evidence("pktmon comp list 原始输出", components)
                    .build();
        }

        Path tempDir = null;
        try {
            context.step("启动 Pktmon 捕获网络组件数据包（仅捕获，不修改任何配置）");
            tempDir = Files.createTempDirectory("blamenet-pktmon-");
            String etlName = "blamenet-capture.etl";
            CmdResult start = context.cmdNoCache(
                    "pktmon start --capture --comp nics --etw --file-name " + etlName,
                    DiagnosticContext.TIMEOUT_MEDIUM, tempDir);

            context.step("等待 " + CAPTURE_SECONDS + " 秒完成数据包采样");
            sleep(CAPTURE_SECONDS);

            context.step("停止捕获并采集丢包计数器");
            CmdResult stop = context.cmdNoCache("pktmon stop", DiagnosticContext.TIMEOUT_MEDIUM, tempDir);

            context.step("转换捕获文件为文本并解析丢包归因");
            CmdResult etl2txt = context.cmdNoCache("pktmon etl2txt " + etlName,
                    DiagnosticContext.TIMEOUT_MEDIUM, tempDir);
            String captureText = readCaptureText(tempDir);

            List<String> dropLines = extractDropLines(stop.output() + "\n" + captureText);
            boolean captureSucceeded = start.exitCode() == 0 && stop.exitCode() == 0;
            boolean hasDrop = !dropLines.isEmpty();
            boolean vlanOnly = hasDrop && dropLines.stream().allMatch(Module26PktmonCapture::looksLikeVlanDrop);

            Status status;
            String summary;
            String diagnosis;
            String suggestion;
            if (!captureSucceeded) {
                status = Status.INFO;
                summary = "Pktmon 捕获未能正常完成（start 退出码 " + start.exitCode() + "，stop 退出码 " + stop.exitCode() + "）";
                diagnosis = "捕获过程中出现错误，可能已被其他抓包工具占用、驱动未就绪或系统策略限制；原始输出已保留在证据中";
                suggestion = "关闭可能占用 pktmon 的抓包工具后重试；如仍失败，可用 Wireshark 手动抓包对比";
            } else if (hasDrop) {
                status = Status.WARN;
                summary = "Pktmon 捕获到 " + dropLines.size() + " 条丢包/丢弃归因记录"
                        + (vlanOnly ? "（全部为 VLAN/未知以太类型的正常丢弃）" : "：" + String.join("；", first(dropLines, 5)));
                diagnosis = vlanOnly
                        ? "抓包显示丢弃的是带 VLAN 标签或未知以太类型的帧，网卡不认识这些帧时丢弃属于正常行为，不构成故障"
                        : "抓包显示网卡或协议栈层面存在真实丢弃，可能由 MTU 不匹配、硬件校验和卸载缺陷、"
                        + "驱动缺陷或上层过滤驱动（VPN/防火墙）造成";
                suggestion = vlanOnly
                        ? "无需处理；如仍有网络异常，请结合模块 12、27、28 的结论继续排查"
                        : "1.结合模块 28 的 MTU 探测结果确认是否存在分片问题；"
                        + "2.更新网卡驱动；3.临时退出 VPN/代理类软件与第三方安全软件后重测；"
                        + "4.如为虚拟化环境，检查虚拟交换机的卸载设置（本工具只读，不会修改任何配置）";
            } else {
                status = Status.PASS;
                summary = "Pktmon 在 " + CAPTURE_SECONDS + " 秒采样窗口内未捕获到丢包记录，"
                        + "共采集到 " + countPackets(captureText) + " 行数据包明细";
                diagnosis = "数据包层面未发现被丢弃的流量，网络故障与丢包无关（可能位于上层应用或远端服务）";
                suggestion = "无";
            }

            context.step("生成结论与修复建议");
            return result()
                    .status(status)
                    .summary(summary)
                    .diagnosis(diagnosis)
                    .suggestion(suggestion)
                    .evidenceRaw("Pktmon 丢包归因摘录", "BlameNet 汇总：pktmon stop + pktmon etl2txt",
                            "采样窗口：" + CAPTURE_SECONDS + " 秒\n丢弃记录：" + dropLines.size() + " 条\n"
                                    + (dropLines.isEmpty() ? "未发现丢弃记录" : String.join("\n", dropLines)))
                    .evidence("pktmon comp list 原始输出", components)
                    .evidence("pktmon start 原始输出", start)
                    .evidence("pktmon stop 原始输出（含计数器）", stop)
                    .evidence("pktmon etl2txt 原始输出", etl2txt)
                    .evidenceRaw("捕获文本摘录（etl2txt 转换结果）",
                            "pktmon etl2txt blamenet-capture.etl（临时目录内执行，结束后清理）",
                            captureText, com.blamenet.util.TimeUtil.isoNow(), CAPTURE_TEXT_LIMIT)
                    .build();
        } catch (IOException e) {
            context.step("记录临时目录创建失败的原因");
            context.step("生成结论与修复建议");
            return result()
                    .status(Status.INFO)
                    .summary("无法创建捕获临时目录，Pktmon 捕获未执行：" + e.getMessage())
                    .diagnosis("系统临时目录不可写，导致无法安全地生成捕获文件（本工具坚持把捕获文件放在临时目录并即时清理）")
                    .suggestion("检查系统环境变量 TEMP 指向的目录是否可写（本工具不会写入安装目录以外的固定路径）")
                    .evidenceRaw("临时目录创建失败", "File.createTempDirectory", e.toString())
                    .build();
        } finally {
            cleanup(tempDir);
        }
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isUnavailable(CmdResult result) {
        String text = TextUtil.nvl(result.output()).toLowerCase(Locale.ROOT);
        return text.contains("不是内部或外部命令") || text.contains("not recognized")
                || text.contains("无法将") || text.contains("不是可运行的")
                || (TextUtil.isBlank(text) && result.exitCode() != 0);
    }

    private static String readCaptureText(Path tempDir) {
        if (tempDir == null) {
            return "";
        }
        try (Stream<Path> files = Files.list(tempDir)) {
            Path txt = files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .findFirst().orElse(null);
            if (txt == null) {
                return "";
            }
            // pktmon etl2txt 的文本编码随系统而异（可能是 OEM 代码页或 UTF-16），
            // 统一走 Cmd.decode 的编码自适应，避免证据出现乱码。
            // 返回完整文本：截断与 Truncated/OriginalOutputLength 标记由证据构造时统一处理，
            // 避免在读取阶段提前截断导致截断标记失真。
            return com.blamenet.collect.Cmd.decode(Files.readAllBytes(txt));
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> extractDropLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : TextUtil.normalize(text).split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean dropKeyword = lower.contains("丢弃") || lower.contains("drop") || lower.contains("discard");
            if (!dropKeyword) {
                continue;
            }
            if (line.chars().noneMatch(Character::isDigit)) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.length() > 300) {
                trimmed = trimmed.substring(0, 300);
            }
            lines.add(trimmed);
            if (lines.size() >= 40) {
                break;
            }
        }
        return lines;
    }

    private static boolean looksLikeVlanDrop(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("vlan") || lower.contains("802.1q") || lower.contains("unknown ethertype")
                || lower.contains("ethernet type") || lower.contains("未知以太");
    }

    private static int countPackets(String captureText) {
        int count = 0;
        for (String line : TextUtil.normalize(captureText).split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static List<String> first(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }

    /** 清理本次捕获的临时目录。 */
    private static void cleanup(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响诊断结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
