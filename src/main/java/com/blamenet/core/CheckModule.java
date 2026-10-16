package com.blamenet.core;

import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;

/** 检查模块统一契约（对应设计文档中的 ICheckModule）。 */
public interface CheckModule {

    /** 模块编号，例如 "01"。 */
    String id();

    /** 模块名称。 */
    String name();

    /** 所属故障层分类。 */
    com.blamenet.model.FaultLayer layer();

    /** 执行本模块预计产生的进度步骤数，用于进度条总量估算。 */
    int expectedSteps();

    /**
     * 模块参考时长（秒）。
     * <p>
     * 用于界面在测试过程中展示“当前模块已用时长 vs 参考时长”，帮助用户判断还要等待多久。
     * 大多数只读检查很快（默认 5 秒）；耗时模块（sfc/DISM 扫描、网关 100 次 ping、pktmon 采样、
     * MTU 逐级探测、丢弃计数器双采样等）应覆盖为更接近真实耗时的参考值。
     */
    default int referenceSeconds() {
        return 5;
    }

    /** 是否需要管理员权限才能完整执行。 */
    boolean requiresAdmin();

    /** 执行检查。实现方不应抛出异常——异常由基类统一兜底。 */
    ModuleResult execute(DiagnosticContext context);

    /** 便捷方法：按编号判断。 */
    default boolean is(String moduleId) {
        return id().equals(moduleId);
    }
}
