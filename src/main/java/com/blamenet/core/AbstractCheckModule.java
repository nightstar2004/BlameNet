package com.blamenet.core;

import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.TimeUtil;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 检查模块基类。
 * <p>
 * 统一承担三件事，保证 31 个模块行为一致：
 * <ol>
 *     <li>异常隔离：任何模块内部异常都不会中断整体诊断，而是转成 Fail 结果并保留堆栈证据；</li>
 *     <li>元数据回填：模块编号、名称、分类、耗时段与真实执行命令条数；</li>
 *     <li>结果契约：即使模块未显式赋值，也保证 Status / Summary / Suggestion 字段完整。</li>
 * </ol>
 */
public abstract class AbstractCheckModule implements CheckModule {

    private final String id;
    private final String name;
    private final FaultLayer layer;
    private final int expectedSteps;
    private final boolean requiresAdmin;
    private final int referenceSeconds;

    protected AbstractCheckModule(String id, String name, FaultLayer layer, int expectedSteps) {
        this(id, name, layer, expectedSteps, false, 5);
    }

    protected AbstractCheckModule(String id, String name, FaultLayer layer, int expectedSteps, boolean requiresAdmin) {
        this(id, name, layer, expectedSteps, requiresAdmin, 5);
    }

    protected AbstractCheckModule(String id, String name, FaultLayer layer, int expectedSteps,
                                  boolean requiresAdmin, int referenceSeconds) {
        this.id = id;
        this.name = name;
        this.layer = layer;
        this.expectedSteps = Math.max(1, expectedSteps);
        this.requiresAdmin = requiresAdmin;
        this.referenceSeconds = Math.max(1, referenceSeconds);
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final FaultLayer layer() {
        return layer;
    }

    @Override
    public final int expectedSteps() {
        return expectedSteps;
    }

    @Override
    public final int referenceSeconds() {
        return referenceSeconds;
    }

    @Override
    public final boolean requiresAdmin() {
        return requiresAdmin;
    }

    /** 子类实现真正的检查逻辑。 */
    protected abstract ModuleResult run(DiagnosticContext context);

    @Override
    public final ModuleResult execute(DiagnosticContext context) {
        long startNanos = System.nanoTime();
        ModuleResult result;
        try {
            result = run(context);
            if (result == null) {
                result = result().status(Status.INFO)
                        .summary("模块未返回结果")
                        .diagnosis("模块实现未产生结果对象，请检查实现")
                        .suggestion("无")
                        .build();
            }
        } catch (Throwable throwable) {
            result = result().status(Status.FAIL)
                    .summary("模块执行异常：" + throwable.getClass().getSimpleName() + "：" + throwable.getMessage())
                    .diagnosis("模块内部抛出异常，已隔离，未影响其他模块执行")
                    .suggestion("请查看异常堆栈证据并在项目仓库中反馈；本工具不会因此中断整体诊断")
                    .evidenceRaw("异常堆栈", "ModuleExecutionError", stackTrace(throwable))
                    .build();
        }
        if (result.moduleId == null) {
            result.moduleId = id;
        }
        if (result.moduleName == null) {
            result.moduleName = name;
        }
        if (result.status == null) {
            result.status = Status.INFO.sourceValue();
        }
        if (result.category == null) {
            result.category = layer.chinese();
        }
        result.setDurationMillis((System.nanoTime() - startNanos) / 1_000_000L);
        result.executedCommandCount = context.drainCommandCount();
        return result;
    }

    /** 结果构造器入口。 */
    protected ResultBuilder result() {
        return ResultBuilder.of(this);
    }

    /** 权限不足时的统一说明前缀。 */
    protected String adminRequiredNote(DiagnosticContext context) {
        return context.admin() ? "" : "（当前进程没有管理员权限）";
    }

    /** 权限不足时的统一建议。 */
    protected String adminRequiredSuggestion(String manualCommand) {
        return "该检查需要管理员权限。请以管理员身份重新运行本工具后重试"
                + (manualCommand == null || manualCommand.isBlank() ? "" : "；如需手动确认，可在管理员命令行中执行：" + manualCommand)
                + "（本工具不会自动提权、不会自动执行任何命令）";
    }

    protected static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    protected static String now() {
        return TimeUtil.isoNow();
    }
}
