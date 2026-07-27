---
name: java-code-review
description: Review Java code for correctness, trust-boundary violations, resource leaks, and unnecessary complexity. Use when the user asks to review Java source code, a Java diff, or a Java implementation.
---

# Java Code Review

对 Java 代码进行基于证据的评审。

## 评审顺序

1. 先阅读用户指定的代码和直接相关调用链。
2. 区分已经确定的问题与仍需验证的推测。
3. 优先检查正确性、信任边界、资源释放和错误处理。
4. 检查是否存在没有真实需求的抽象、兼容分支或兜底逻辑。
5. 不把个人格式偏好当作代码缺陷。

## 输出要求

每个确定问题必须包含：

- 严重级别：P0、P1、P2 或 P3。
- 准确的文件和代码位置。
- 能证明问题存在的代码证据。
- 问题可能造成的实际影响。
- 最小修复方向。

按照严重程度从高到低排列。

如果没有发现能够由当前代码证明的问题，明确说明：

“当前代码中没有发现可确定的问题。”

不要为了让评审显得丰富而编造问题。
