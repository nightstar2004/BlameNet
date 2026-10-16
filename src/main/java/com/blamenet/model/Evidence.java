package com.blamenet.model;

import com.blamenet.util.JsonUtil;
import com.blamenet.util.TextUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 证据条目：每个结论都必须可追溯到真实执行过的命令、真实输出与记录时间。
 * <p>
 * 字段名与设计要求保持一致；随后的扩展字段用于承载执行耗时等附加信息（设计要求允许内容更详细）。
 */
@JsonPropertyOrder({
        "EvidenceName", "RawCommand", "RawOutput", "RecordTime",
        "ExitCode", "TimedOut", "DurationMillis", "Truncated", "OriginalOutputLength"
})
public class Evidence {

    /** 证据名称，例如 "ipconfig /all 原始输出"。 */
    @JsonProperty("EvidenceName")
    public String evidenceName;

    /** 实际执行的原始命令（含完整的 PowerShell / CMD 调用形式或“本地文件读取”等只读操作描述）。 */
    @JsonProperty("RawCommand")
    public String rawCommand;

    /** 命令的原始输出，除超长截断外不做任何改写。 */
    @JsonProperty("RawOutput")
    public String rawOutput;

    /** 记录时间（UTC ISO-8601）。 */
    @JsonProperty("RecordTime")
    public String recordTime;

    /** 扩展：进程退出码；本地文件读取等无进程操作时为 null。 */
    @JsonProperty("ExitCode")
    public Integer exitCode;

    /** 扩展：是否因超时被强制结束。 */
    @JsonProperty("TimedOut")
    public Boolean timedOut;

    /** 扩展：执行耗时（毫秒）。 */
    @JsonProperty("DurationMillis")
    public Long durationMillis;

    /** 扩展：原始输出是否被截断。 */
    @JsonProperty("Truncated")
    public Boolean truncated;

    /** 扩展：截断前的原始输出长度（字符数）。 */
    @JsonProperty("OriginalOutputLength")
    public Integer originalOutputLength;

    public Evidence() {
    }

    public Evidence(String evidenceName, String rawCommand, String rawOutput, String recordTime) {
        this.evidenceName = evidenceName;
        this.rawCommand = rawCommand;
        this.rawOutput = rawOutput;
        this.recordTime = recordTime;
    }

    /** 生成“命令 + 输出”的标准证据文本，供 TXT / XLSX 等纯文本形态使用。 */
    public String commandWithOutput() {
        return "命令：" + TextUtil.nvl(rawCommand) + "\n输出：\n" + TextUtil.nvl(rawOutput);
    }

    public String toCompactJson() {
        return JsonUtil.toCompactJson(this);
    }
}
