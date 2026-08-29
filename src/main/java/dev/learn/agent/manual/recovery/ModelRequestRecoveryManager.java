// 声明模型请求恢复管理器所在的包。
package dev.learn.agent.manual.recovery;

// 引入 Provider 异常和集合类型。
import com.anthropic.errors.AnthropicServiceException;

import java.util.List;
import java.util.Objects;

/**
 * 按显式注册顺序把模型请求异常交给第一个支持它的 Handler。
 */
public final class ModelRequestRecoveryManager {

    // 保存不可变 Handler 顺序；顺序就是多个规则同时匹配时的优先级。
    private final List<ModelRequestRecoveryHandler> handlers;

    /**
     * 创建模型请求恢复分派器。
     *
     * @param handlers 按优先级排列的 Handler
     * @throws NullPointerException 列表或 Handler 为 null 时抛出
     */
    public ModelRequestRecoveryManager(
            List<ModelRequestRecoveryHandler> handlers
    ) {
        // 固定注册快照，避免运行期间悄悄改变错误分派顺序。
        this.handlers =
                List.copyOf(
                        Objects.requireNonNull(
                                handlers,
                                "handlers 不能为空"
                        )
                );
    }

    /**
     * 尝试恢复一次模型请求异常。
     *
     * @param exception 当前模型请求异常
     * @param state 当前连续错误恢复链状态
     * @return 某个 Handler 已完成恢复时返回 true；否则返回 false
     */
    public boolean tryRecover(
            AnthropicServiceException exception,
            ModelRequestRecoveryState state
    ) {
        // 这是 Manager 的唯一职责：遍历注册列表，不承载任何 Provider 业务判断。
        for (ModelRequestRecoveryHandler handler : handlers) {
            if (handler.supports(
                    exception
            )) {
                // 第一个匹配 Handler 拥有最终决定权，false 也不继续尝试其他 Handler。
                return handler.tryRecover(
                        exception,
                        state
                );
            }
        }

        // 未注册的异常交回 AgentLoop，由它原样抛出。
        return false;
    }
}
