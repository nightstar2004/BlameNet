package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.MapAccess;
import com.blamenet.util.TextUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 21：检查网卡驱动版本与状态。
 * <p>
 * 采集：Get-CimInstance Win32_PnPSignedDriver（DeviceClass = NET）。
 * 判定：驱动日期超过 3 年 → Warn；驱动未签名 → Warn；驱动较新 → Pass。
 */
public final class Module21DriverVersion extends AbstractCheckModule {

    /** 驱动“过旧”的天数阈值：3 年。 */
    private static final long STALE_DAYS = 365L * 3;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 虚拟网卡 / 软件设备的名称关键词（小写匹配）。 */
    private static final String[] VIRTUAL_KEYWORDS = {
            "wan miniport", "hyper-v virtual", "vmware", "virtualbox", "vethernet",
            "tap-windows", "tap adapter", "openvpn", "radmin", "bluetooth",
            "wi-fi direct", "microsoft km-test", "loopback", "kernel debug", "ncap",
            "npcap", "sangfor", "virtual switch", "teredo", "isatap", "6to4",
            "microsoft wi-fi direct", "letsvpn", "letstap"
    };

    public Module21DriverVersion() {
        super("21", "检查网卡驱动版本与状态", FaultLayer.SERVICE_SYSTEM, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-CimInstance Win32_PnPSignedDriver 采集网卡驱动信息");
        CmdResult raw = context.ps(Probes.KEY_NET_DRIVERS, Probes.NET_DRIVERS);
        List<Map<String, Object>> drivers = context.psJson(Probes.KEY_NET_DRIVERS, Probes.NET_DRIVERS);

        context.step("解析驱动版本、日期与签名状态（区分物理网卡与虚拟/软件设备）");
        List<String> stale = new ArrayList<>();
        List<String> unsigned = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> virtualDevices = new ArrayList<>();
        int physicalCount = 0;
        LocalDate today = LocalDate.now();

        for (Map<String, Object> driver : drivers) {
            String deviceName = MapAccess.text(driver, "DeviceName");
            String version = MapAccess.text(driver, "DriverVersion");
            String provider = MapAccess.text(driver, "DriverProviderName");
            String dateText = MapAccess.text(driver, "DriverDateText");
            String infName = MapAccess.text(driver, "InfName");
            boolean signed = MapAccess.bool(driver, "IsSigned");
            boolean virtual = isVirtualOrSoftwareDriver(deviceName);

            details.add(deviceName + "：版本=" + version + "，日期=" + (dateText.isEmpty() ? "未知" : dateText)
                    + "，提供商=" + provider + "，INF=" + infName + "，已签名=" + signed
                    + (virtual ? "　[虚拟/软件设备，不参与驱动时效判定]" : "　[物理网卡，参与判定]"));

            if (virtual) {
                virtualDevices.add(deviceName);
                continue;
            }
            physicalCount++;

            LocalDate date = parseDate(dateText);
            if (date != null) {
                long days = ChronoUnit.DAYS.between(date, today);
                if (days > STALE_DAYS) {
                    stale.add(deviceName + "（驱动日期 " + dateText + "，距今 " + days + " 天）");
                }
            }
            if (!signed) {
                unsigned.add(deviceName + "（" + provider + "）");
            }
        }

        context.step("判定驱动是否需要更新");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (drivers.isEmpty()) {
            status = Status.INFO;
            summary = "未能采集到网卡驱动的签名驱动信息";
            diagnosis = "Win32_PnPSignedDriver 中未找到 DeviceClass = NET 的记录，可能是网卡驱动以非签名方式安装，或采集被限制";
            suggestion = "以管理员身份重新运行本工具；并在“设备管理器 → 网卡 → 驱动程序”中查看驱动版本与日期";
        } else if (!unsigned.isEmpty() && !stale.isEmpty()) {
            status = Status.WARN;
            summary = "存在签名异常且版本过旧的物理网卡驱动：" + String.join("、", unsigned) + "；" + String.join("、", stale);
            diagnosis = "未签名驱动可能被系统策略阻止或加载不完整，过旧驱动则可能包含已修复的丢包与兼容性缺陷";
            suggestion = "请从笔记本/主板厂商官网下载对应型号的官方网卡驱动并安装（本工具只读，不会安装任何驱动）";
        } else if (!unsigned.isEmpty()) {
            status = Status.WARN;
            summary = "以下物理网卡驱动未通过数字签名校验：" + String.join("、", unsigned);
            diagnosis = "未签名驱动在开启驱动签名强制的系统上可能加载失败，或被安全软件拦截";
            suggestion = "更换为厂商官方签名的驱动版本（本工具只读，不会安装任何驱动）";
        } else if (!stale.isEmpty()) {
            status = Status.WARN;
            summary = "以下物理网卡驱动日期超过 3 年：" + String.join("、", stale);
            diagnosis = "驱动版本较旧，旧驱动常见问题包括：DHCP 续约异常、无线断流、丢包与节能策略缺陷";
            suggestion = "建议更新到厂商官方最新驱动；更新后重新运行本工具对比模块 04、12 的结果"
                    + "（本工具只读，不会自动更新驱动）";
        } else if (physicalCount == 0) {
            status = Status.INFO;
            summary = "未发现物理网卡驱动记录，仅有虚拟/软件设备驱动 " + virtualDevices.size() + " 个，无法评估物理网卡驱动时效";
            diagnosis = "虚拟网卡（VPN、Hyper-V、VMware、VirtualBox、TAP 等）本身不提供厂商驱动，"
                    + "其驱动日期由系统统一维护，不具备“驱动过旧”的诊断意义";
            suggestion = "若本机确有物理网卡，请确认其驱动已正确安装后再运行本工具";
        } else {
            status = Status.PASS;
            summary = "物理网卡驱动 " + physicalCount + " 个，均已签名且驱动日期在 3 年以内"
                    + (virtualDevices.isEmpty() ? ""
                    : "；另有 " + virtualDevices.size()
                    + " 个虚拟/软件设备驱动（VPN、Hyper-V、VMware、TAP 等）不参与时效判定");
            diagnosis = "物理网卡驱动签名与时效性正常，未发现明显驱动层面的隐患";
            suggestion = "无；如需与官网最新版本比对，请前往厂商官网查看对应型号的最新驱动版本";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("网卡驱动盘点", "BlameNet 汇总：Win32_PnPSignedDriver（DeviceClass = NET）只读读取",
                        details.isEmpty() ? "未采集到驱动信息" : String.join("\n", details))
                .evidence("Win32_PnPSignedDriver 原始输出", raw)
                .build();
    }

    /**
     * 是否为虚拟网卡 / 软件设备驱动。
     * <p>
     * 这类驱动（VPN 虚拟网卡、Hyper-V、VMware、VirtualBox、TAP、蓝牙 PAN、Wi-Fi Direct 等）
     * 由系统或第三方软件以统一的 INF 提供，驱动日期往往是微软通用微型端口的旧日期（如 2006-06-21），
     * 属于系统设计如此，不能据此判定“驱动过旧”。
     */
    private static boolean isVirtualOrSoftwareDriver(String deviceName) {
        String name = TextUtil.nvl(deviceName).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return false;
        }
        for (String keyword : VIRTUAL_KEYWORDS) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate parseDate(String text) {
        if (TextUtil.isBlank(text) || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10), DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }
}
