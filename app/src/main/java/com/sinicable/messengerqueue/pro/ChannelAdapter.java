package com.sinicable.messengerqueue.pro;

/** Contract for connecting the five-lane scheduler to messaging implementations. */
public interface ChannelAdapter {
    QueueProProfile.Channel channel();
    boolean isReady();
    /**
     * The caller must persist IN_FLIGHT before invoking send. Implementations must not claim delivery
     * from a tapped button, opened chat, or accepted API request.
     * No silent fallback to another channel and no blind retry on uncertain outcome.
     */
    Result send(String phone, String text, String imageUri);
    enum Result {
        UI_CONFIRMED_NOT_DELIVERED,
        SERVER_ACCEPTED_NOT_DELIVERED,
        FAILED_BEFORE_SEND,
        REVIEW_UNCERTAIN,
        MANUAL_APPROVAL_REQUIRED
    }
}
