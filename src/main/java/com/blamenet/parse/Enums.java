package com.blamenet.parse;

import com.blamenet.util.TextUtil;

import java.util.Locale;

/**
 * Windows 枚举值归一化。
 * <p>
 * 背景：Windows PowerShell 5.1 的 {@code ConvertTo-Json} 会把 CIM / .NET 枚举序列化为数字
 * （例如 {@code Get-Service} 的 Status=4 表示 Running）。采集脚本已经用 {@code [string]} 把
 * 枚举投影成枚举名，本类再做一层兜底：无论收到数字还是枚举名，都能归一化为统一文本，
 * 避免"服务在运行却被判成未运行"这类误报。
 * <p>
 * 映射取值来自微软官方枚举定义：
 * <ul>
 *     <li>{@code System.ServiceProcess.ServiceControllerStatus}</li>
 *     <li>{@code System.ServiceProcess.ServiceStartMode}</li>
 *     <li>{@code System.Net.NetworkInformation.NetworkStatus} / NeighborState（Get-NetNeighbor）</li>
 *     <li>{@code System.Net.Sockets.TcpState}</li>
 * </ul>
 */
public final class Enums {

    private Enums() {
    }

    /** ServiceControllerStatus → 文本。 */
    public static String serviceStatus(String value) {
        return switch (normalize(value)) {
            case "1" -> "Stopped";
            case "2" -> "StartPending";
            case "3" -> "StopPending";
            case "4" -> "Running";
            case "5" -> "ContinuePending";
            case "6" -> "PausePending";
            case "7" -> "Paused";
            default -> camelCase(TextUtil.nvl(value));
        };
    }

    /** ServiceStartMode → 文本。 */
    public static String serviceStartType(String value) {
        return switch (normalize(value)) {
            case "0" -> "Boot";
            case "1" -> "System";
            case "2" -> "Automatic";
            case "3" -> "Manual";
            case "4" -> "Disabled";
            default -> camelCase(TextUtil.nvl(value));
        };
    }

    /**
     * Get-NetNeighbor 的 State（MSFT_NetNeighbor.State ValueMap）→ 文本。
     * <p>
     * 取值顺序遵循 NetTCPIP 模块的 CIM 定义：
     * 0=Unreachable、1=Incomplete、2=Probe、3=Delay、4=Stale、5=Reachable、6=Permanent。
     */
    public static String neighborState(String value) {
        return switch (normalize(value)) {
            case "0" -> "Unreachable";
            case "1" -> "Incomplete";
            case "2" -> "Probe";
            case "3" -> "Delay";
            case "4" -> "Stale";
            case "5" -> "Reachable";
            case "6" -> "Permanent";
            default -> camelCase(TextUtil.nvl(value));
        };
    }

    /** Get-NetTCPConnection 的 State（TcpState）→ 文本。 */
    public static String tcpState(String value) {
        return switch (normalize(value)) {
            case "1" -> "Closed";
            case "2" -> "Listen";
            case "3" -> "SynSent";
            case "4" -> "SynReceived";
            case "5" -> "Established";
            case "6" -> "FinWait1";
            case "7" -> "FinWait2";
            case "8" -> "CloseWait";
            case "9" -> "Closing";
            case "10" -> "LastAck";
            case "11" -> "TimeWait";
            case "12" -> "DeleteTcb";
            default -> camelCase(TextUtil.nvl(value));
        };
    }

    /** 服务是否处于运行状态（兼容数字枚举与枚举名）。 */
    public static boolean isRunning(String serviceStatus) {
        return "running".equalsIgnoreCase(serviceStatus(serviceStatus));
    }

    /** 判断布尔型字段：兼容 true/True/1/是/Enabled/On。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on")
                || text.equals("enabled") || text.equals("是") || text.equals("允许");
    }

    /** 判断布尔型字段是否为“关闭 / 禁用”。 */
    public static boolean falsy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return !bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.equals("false") || text.equals("0") || text.equals("no") || text.equals("off")
                || text.equals("disabled") || text.equals("否") || text.equals("禁止");
    }

    /** 去掉空白并转小写，便于 switch 匹配。 */
    private static String normalize(String value) {
        return TextUtil.nvl(value).trim().toLowerCase(Locale.ROOT);
    }

    /** 把 "syn sent"/"syn_sent"/"SYN_SENT" 之类统一成 "SynSent" 形式。 */
    private static String camelCase(String value) {
        String text = TextUtil.nvl(value).trim().replace('_', ' ');
        if (text.isEmpty()) {
            return "";
        }
        if (text.indexOf(' ') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : text.split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
