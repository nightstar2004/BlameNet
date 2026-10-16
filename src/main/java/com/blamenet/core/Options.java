package com.blamenet.core;

import com.blamenet.util.TextUtil;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** 运行参数。 */
public final class Options {

    /** 界面模式。 */
    public enum UiMode {
        AUTO, GUI, CLI
    }

    public UiMode uiMode = UiMode.AUTO;

    /** 模块过滤（空集合表示运行全部 31 个模块）。 */
    public final Set<String> moduleFilter = new LinkedHashSet<>();

    /** 网关连通性测试的 ping 次数，默认 100（按设计文档）。 */
    public int pingCount = 100;

    /** 快速档：缩减 ping 次数并跳过 DISM / sfc 深度扫描，用于反复调试；默认关闭。 */
    public boolean quick = false;

    /** 结果目录覆盖（默认 安装目录\results）。 */
    public Path outputDir;

    /** 从已有 JSON 重建全部报表。 */
    public String rebuildJson;

    /** 是否请求显示帮助。 */
    public boolean help = false;

    /** 是否请求显示版本。 */
    public boolean version = false;

    /** 原始命令行，记录到报告中便于复现。 */
    public String rawArguments = "";

    /** 是否跳过报告导出（仅用于开发调试）。 */
    public boolean skipReports = false;

    public int effectivePingCount() {
        if (pingCount <= 0) {
            return 100;
        }
        return quick ? Math.min(pingCount, 10) : pingCount;
    }

    /** 深度系统扫描（sfc / DISM）是否执行。 */
    public boolean deepSystemScanEnabled() {
        return !quick;
    }

    public String profileName() {
        return quick ? "quick" : "full";
    }

    public String moduleFilterText() {
        return moduleFilter.isEmpty() ? "全部（01-31）" : String.join(",", moduleFilter);
    }

    /** 按档位缩放命令超时。 */
    public Duration scale(Duration timeout) {
        return quick ? timeout.dividedBy(2) : timeout;
    }

    /** 解析命令行参数。 */
    public static Options parse(String[] args) {
        Options options = new Options();
        options.rawArguments = String.join(" ", args);
        for (int i = 0; i < args.length; i++) {
            String arg = TextUtil.nvl(args[i]).trim();
            switch (arg) {
                case "--cli" -> options.uiMode = UiMode.CLI;
                case "--gui" -> options.uiMode = UiMode.GUI;
                case "--quick" -> options.quick = true;
                case "--help", "-h", "/?" -> options.help = true;
                case "--version" -> options.version = true;
                case "--no-report" -> options.skipReports = true;
                case "--modules" -> {
                    if (i + 1 < args.length) {
                        for (String id : args[++i].split("[,;\\s]+")) {
                            if (id.isBlank()) {
                                continue;
                            }
                            options.moduleFilter.add(normalizeModuleId(id));
                        }
                    }
                }
                case "--ping-count" -> {
                    if (i + 1 < args.length) {
                        options.pingCount = (int) TextUtil.parseLong(args[++i], options.pingCount);
                    }
                }
                case "--output" -> {
                    if (i + 1 < args.length) {
                        options.outputDir = Path.of(args[++i]);
                    }
                }
                case "--report" -> {
                    if (i + 1 < args.length) {
                        options.rebuildJson = args[++i];
                    }
                }
                default -> {
                    if (arg.startsWith("--modules=")) {
                        for (String id : arg.substring("--modules=".length()).split("[,;\\s]+")) {
                            if (!id.isBlank()) {
                                options.moduleFilter.add(normalizeModuleId(id));
                            }
                        }
                    } else if (arg.startsWith("--ping-count=")) {
                        options.pingCount = (int) TextUtil.parseLong(arg.substring("--ping-count=".length()), options.pingCount);
                    } else if (arg.startsWith("--output=")) {
                        options.outputDir = Path.of(arg.substring("--output=".length()));
                    } else if (arg.startsWith("--report=")) {
                        options.rebuildJson = arg.substring("--report=".length());
                    }
                }
            }
        }
        return options;
    }

    /** 模块编号归一化为两位数字，例如 "6" → "06"。 */
    public static String normalizeModuleId(String id) {
        String text = TextUtil.nvl(id).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                digits.append(text.charAt(i));
            }
        }
        if (digits.isEmpty()) {
            return text;
        }
        String value = digits.toString();
        if (value.length() == 1) {
            return "0" + value;
        }
        return value;
    }

    public static String helpText() {
        return """
                BlameNet —— Windows 网络故障定位工具（严格按步骤、不跳过、只读诊断、不重装系统）

                用法：
                  java -jar BlameNet.jar [选项]

                选项：
                  --gui                  强制使用图形界面（默认；无图形环境时自动降级为命令行）
                  --cli                  强制使用命令行界面
                  --quick                快速档：缩减 ping 次数并跳过 DISM / sfc 深度扫描
                  --ping-count <n>       网关连通性测试的 ping 次数（默认 100，按设计文档）
                  --modules <ids>        只运行指定模块，例如 --modules 01,06,12（调试用）
                  --output <dir>         指定结果目录（默认：安装目录\\results）
                  --report <json>        不采集数据，仅从已有 JSON 主数据源重新生成全部报表
                  --no-report            只诊断、不导出报表（调试用）
                  --version              显示版本信息
                  --help                 显示本帮助

                说明：
                  * 工具只做只读诊断，不会执行 ipconfig /release、/renew、/flushdns、netsh reset、
                    sfc 修复、DISM 修复等任何写操作，也不会自动重启系统。
                  * 建议以管理员身份运行，否则 sfc / DISM / pktmon 等只读检查无法执行，
                    报告中会如实标注“需要管理员权限”。
                  * 所有结果只写入安装目录下的 results 目录（每次诊断在 results/ 下创建「报告编号报告」子文件夹）。
                """;
    }
}
