package dev.learn.agent.manual.systemprompt;

/**
 * System Prompt Item 的刷新生命周期。
 *
 * 枚举按生命周期从长到短排列；刷新某一级时，
 * 同时刷新它以及排列在它之后的更短生命周期。
 */
public enum RefreshScope {
    APPLICATION,
    USER,
    SESSION,
    TURN,
    MODEL_CALL
}
