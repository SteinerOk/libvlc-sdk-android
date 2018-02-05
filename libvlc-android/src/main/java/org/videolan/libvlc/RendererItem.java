package org.videolan.libvlc;

@SuppressWarnings("unused, JniMissingFunction")
public class RendererItem extends VLCObject<RendererItem.Event> {

    /**
     * The renderer can render audio
     */
    public static final int LIBVLC_RENDERER_CAN_AUDIO = 0x0001;
    /**
     * The renderer can render video
     */
    public static final int LIBVLC_RENDERER_CAN_VIDEO = 0x0002;

    final public String name;
    final public String displayName;
    final String type;
    final String iconUrl;
    final int flags;
    private final long ref;

    RendererItem(RendererDiscoverer rd, long ref) {
        super(rd);
        final RendererItem item = nativeNewItem(rd, ref);
        name = item == null ? null : item.name;
        displayName = item == null ? null : item.displayName;
        this.type = item == null ? null : item.type;
        this.iconUrl = item == null ? null : item.iconUrl;
        this.flags = item == null ? 0 : item.flags;
        this.ref = item == null ? ref : item.ref;
    }

    RendererItem(String name, String type, String iconUrl, int flags, long ref) {
        final int index = name.lastIndexOf('-');
        this.name = name;
        this.displayName = index == -1 ? name : name.substring(0, index).replace('-', ' ');
        this.type = type;
        this.iconUrl = iconUrl;
        this.flags = flags;
        this.ref = ref;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RendererItem && ref == ((RendererItem) obj).ref;
    }

    @Override
    protected Event onEventNative(int eventType, long arg1, long arg2, float argf1) {
        return new Event(eventType);
    }

    @Override
    protected void onReleaseNative() {
        nativeReleaseItem();
    }

    private native RendererItem nativeNewItem(RendererDiscoverer rd, long ref);

    private native void nativeReleaseItem();

    public static class Event extends VLCEvent {
        protected Event(int type) {
            super(type);
        }
    }
}
