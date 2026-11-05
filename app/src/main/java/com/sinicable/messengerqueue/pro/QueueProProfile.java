package com.sinicable.messengerqueue.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Five-channel configuration proposal; does not modify the existing three-channel production queue. */
public final class QueueProProfile {
    public enum Channel { BALE, RUBIKA, EITAA, TELEGRAM, WHATSAPP }
    public enum Transport { EXISTING_ACCESSIBILITY, TELEGRAM_TDLIB, WHATSAPP_USER_HANDOFF }
    public enum Readiness { BASE_UI_PRESENT_NEEDS_DEVICE_CHECK, INTEGRATION_NOT_INSTALLED, MANUAL_ONLY }

    public static final class Entry {
        public final Channel channel;
        public final Transport transport;
        public final Readiness readiness;
        public final int delayMinutes;
        public final boolean selected;
        public Entry(Channel channel, Transport transport, Readiness readiness, int delayMinutes, boolean selected) {
            if (channel == null || transport == null || readiness == null) throw new IllegalArgumentException("null config");
            if (delayMinutes < 1 || delayMinutes > 1440) throw new IllegalArgumentException("delay must be 1..1440");
            this.channel = channel;
            this.transport = transport;
            this.readiness = readiness;
            this.delayMinutes = delayMinutes;
            this.selected = selected;
        }
        public Entry withDelayMinutes(int minutes) { return new Entry(channel, transport, readiness, minutes, selected); }
        public Entry withSelected(boolean value) { return new Entry(channel, transport, readiness, delayMinutes, value); }
    }

    private final EnumMap<Channel, Entry> config;
    private QueueProProfile(EnumMap<Channel, Entry> config) { this.config = new EnumMap<>(config); }
    public static QueueProProfile requestedDefaults() {
        EnumMap<Channel, Entry> e = new EnumMap<>(Channel.class);
        e.put(Channel.BALE, new Entry(Channel.BALE, Transport.EXISTING_ACCESSIBILITY, Readiness.BASE_UI_PRESENT_NEEDS_DEVICE_CHECK, 5, true));
        e.put(Channel.RUBIKA, new Entry(Channel.RUBIKA, Transport.EXISTING_ACCESSIBILITY, Readiness.BASE_UI_PRESENT_NEEDS_DEVICE_CHECK, 5, true));
        e.put(Channel.EITAA, new Entry(Channel.EITAA, Transport.EXISTING_ACCESSIBILITY, Readiness.BASE_UI_PRESENT_NEEDS_DEVICE_CHECK, 5, true));
        e.put(Channel.TELEGRAM, new Entry(Channel.TELEGRAM, Transport.TELEGRAM_TDLIB, Readiness.INTEGRATION_NOT_INSTALLED, 10, true));
        e.put(Channel.WHATSAPP, new Entry(Channel.WHATSAPP, Transport.WHATSAPP_USER_HANDOFF, Readiness.MANUAL_ONLY, 10, true));
        return new QueueProProfile(e);
    }
    public Entry get(Channel channel) { return config.get(channel); }
    public List<Entry> entries() { return Collections.unmodifiableList(new ArrayList<>(config.values())); }
    public Map<Channel, Entry> asMap() { return Collections.unmodifiableMap(config); }
    public QueueProProfile with(Entry entry) {
        if (entry == null || !config.containsKey(entry.channel)) throw new IllegalArgumentException("unknown entry");
        EnumMap<Channel, Entry> copy = new EnumMap<>(config);
        copy.put(entry.channel, entry);
        return new QueueProProfile(copy);
    }
    /** Existing UI route is present in original source, but it still needs device-specific validation. */
    public boolean hasSourceForExistingUi(Channel channel) {
        Entry e = get(channel);
        return e != null && e.transport == Transport.EXISTING_ACCESSIBILITY;
    }
    public boolean canRunUnattended(Channel channel, boolean liveConnectorReady) {
        Entry e = get(channel);
        if (e == null || !e.selected || !liveConnectorReady) return false;
        return e.transport != Transport.WHATSAPP_USER_HANDOFF;
    }
}
