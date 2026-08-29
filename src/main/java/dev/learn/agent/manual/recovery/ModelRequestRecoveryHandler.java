// 声明模型请求恢复处理器所在的包。
package dev.learn.agent.manual.recovery;

// 引入 Provider 异常类型。
import com.anthropic.errors.AnthropicServiceException;

/**
 * 定义一种模型请求异常的识别和恢复契约。
 */
public interface ModelRequestRecoveryHandler {

    /**
     * 判断当前 Handler 是否拥有该异常的恢复规则。
     *
     * @param exception 当前模型请求异常
     * @return 属于本 Handler 的错误返回 true
     */
    boolean supports(
            AnthropicServiceException exception
    );

    /**
     * 在不调用模型或工具的前提下修改恢复状态。
     *
     * @param exception 当前模型请求异常
     * @param state 当前连续错误恢复链状态
     * @return 已完成恢复、可以由 AgentLoop 重试时返回 true；否则返回 false
     */
    boolean tryRecover(
            AnthropicServiceException exception,
            ModelRequestRecoveryState state
    );
}
