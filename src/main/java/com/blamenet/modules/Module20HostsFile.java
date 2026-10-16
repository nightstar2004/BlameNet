package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.TextUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 20：检查 hosts 文件。
 * <p>
 * 采集：只读读取 C:\Windows\System32\drivers\etc\hosts
 * 判定：存在将正常域名映射到 127.0.0.1、0.0.0.0 或错误 IP 的条目 → Warn。
 */
public final class Module20HostsFile extends AbstractCheckModule {

    private static final Path HOSTS = Path.of("C:\\Windows\\System32\\drivers\\etc\\hosts");

    /** 属于正常本地回环语义的主机名，不视为异常。 */
    private static final List<String> LOCAL_NAMES = List.of("localhost", "localhost.localdomain",
            "broadcasthost", "ip6-localhost", "ip6-loopback", "ip6-allnodes", "ip6-allrouters", "local");

    public Module20HostsFile() {
        super("20", "检查hosts文件", FaultLayer.SECURITY_PROXY, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("只读读取 hosts 文件内容");
        CmdResult hosts = context.file("hostsFile", HOSTS, Probes.HOSTS_FILE_DISPLAY);

        context.step("解析生效条目并筛查异常映射");
        List<String> mappings = new ArrayList<>();
        List<String> suspicious = new ArrayList<>();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        int effective = 0;

        for (String rawLine : TextUtil.normalize(hosts.output()).split("\n")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String ip = parts[0];
            for (int i = 1; i < parts.length; i++) {
                String name = parts[i].trim();
                if (name.isEmpty()) {
                    continue;
                }
                effective++;
                String record = ip + " → " + name;
                mappings.add(record);
                byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(ip);
                boolean localName = LOCAL_NAMES.contains(name.toLowerCase(Locale.ROOT));
                boolean loopback = ip.startsWith("127.") || ip.equals("0.0.0.0") || ip.equals("::1");
                if (loopback && !localName) {
                    suspicious.add(record + "（正常域名被解析到本机回环地址，通常为广告屏蔽或人为屏蔽）");
                } else if (!loopback && !isPrivate(ip) && !localName) {
                    suspicious.add(record + "（域名被强制指向公网固定 IP，需确认是否为人为劫持）");
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                suspicious.add(entry.getKey() + " 被重复映射到：" + String.join("、", entry.getValue()));
            }
        }

        context.step("判定 hosts 是否影响正常解析");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (suspicious.isEmpty() && effective == 0) {
            status = Status.PASS;
            summary = "hosts 文件未配置任何生效条目（全部为空行或注释），不会影响域名解析";
            diagnosis = "hosts 文件保持默认状态，排除 hosts 劫持";
            suggestion = "无";
        } else if (suspicious.isEmpty()) {
            status = Status.PASS;
            summary = "hosts 文件共 " + effective + " 条生效映射，均为正常条目（未发现指向回环或公网固定 IP 的异常映射）";
            diagnosis = "hosts 文件内容正常，不影响域名解析";
            suggestion = "无";
        } else {
            status = Status.WARN;
            summary = "hosts 文件中发现 " + suspicious.size() + " 条需关注的映射：" + String.join("；", suspicious);
            diagnosis = "hosts 条目优先级高于 DNS，异常条目会导致对应域名被强制解析到指定地址："
                    + "若是广告屏蔽属于有意配置，若涉及你需要访问的站点则需要移除";
            suggestion = "1.用管理员权限的记事本打开 C:\\Windows\\System32\\drivers\\etc\\hosts 核对上述行；"
                    + "2.删除不需要的映射并保存；3.保存后手动执行 ipconfig /flushdns 清理缓存"
                    + "（本工具只读，不会修改 hosts 文件）";
        }

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("hosts 生效条目盘点", "BlameNet 汇总：只读解析 hosts 文件",
                        "生效映射条数：" + effective + "\n条目：\n" + (mappings.isEmpty() ? "无" : String.join("\n", mappings))
                                + "\n\n需关注的映射：\n" + (suspicious.isEmpty() ? "无" : String.join("\n", suspicious)))
                .evidence("hosts 文件原始内容（只读读取）", hosts)
                .build();
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.16.")
                || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.2") || ip.startsWith("172.30.") || ip.startsWith("172.31.");
    }
}
