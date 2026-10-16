package com.blamenet.report;

/**
 * PDF 完整版导出器：在简洁版版式的基础上展示全部证据原文（命令 + 输出）。
 * <p>
 * 文件名与简洁版区分：{@code BlameNet-<编号>-full.pdf}；两者均由同一 JSON 主数据源生成。
 */
public final class PdfFullExporter extends PdfExporter {

    public PdfFullExporter() {
        super(true);
    }
}
