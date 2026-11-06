/**
 * 持久层：会话记忆、登录凭证、人格状态等。
 *
 * <p>对话记忆：{@code cloudbot.persistence.type=file|jdbc}；
 * 登录凭证：{@code auth=file|redis}（{@code enabled=false} 时为内存）。
 */
package com.github.wechat.cloudbot.platform.persistence;
