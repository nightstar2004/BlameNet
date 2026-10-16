package com.blamenet.core;

/**
 * 报告阶段的进度计划常量。
 * <p>
 * 放在 core 包中，供调度引擎（构建进度计划）与报告流水线（推进进度）共同引用，
 * 避免 core 与 report 两个包互相依赖。
 */
public final class ReportPhase {

    /** 报告阶段编号。 */
    public static final String ID = "REPORT";

    /** 报告阶段名称（显示在进度条上方）。 */
    public static final String NAME = "报告生成与打包（JSON 同源导出）";

    /** 报告阶段固定步骤数：结果目录 + JSON 主数据源 + 5 种报表 + README + manifest + ZIP。 */
    /** 报告阶段预估步数：6 种形态 + README + manifest + ZIP + 前置 2 步。 */
    public static final int PLANNED_STEPS = 11;

    private ReportPhase() {
    }
}
