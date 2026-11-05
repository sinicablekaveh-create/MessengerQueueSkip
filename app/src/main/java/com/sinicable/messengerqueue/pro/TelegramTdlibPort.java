package com.sinicable.messengerqueue.pro;

/**
 * Integration point, NOT an implemented Telegram sender.
 * Production adapter requires official TDLib binary, api_id, api_hash, interactive authorization,
 * allowed recipient/chat lookup, async sendMessage, updateMessageSendSucceeded/Failed handling,
 * durable client message-id correlation and flood-wait/error handling.
 */
public interface TelegramTdlibPort extends ChannelAdapter {
    boolean isAuthorized();
    boolean hasResolvedConsentBasedChat(String phone);
    @Override default QueueProProfile.Channel channel() { return QueueProProfile.Channel.TELEGRAM; }
    @Override default boolean isReady() { return isAuthorized(); }
}
