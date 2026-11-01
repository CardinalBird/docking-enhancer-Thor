#ifndef RUMBLE_H
#define RUMBLE_H

// Replays the Thor's own vibration as rumble on the external pad. See rumble.c.
struct rumble {
    int pad_fd;       // where rumble is written: the source, or the real pad behind its twin
    int own_fd;       // pad_fd when we opened it ourselves (the real pad), else -1
    int effect_id;    // uploaded FF_RUMBLE effect, -1 when the pad has none
    int from_bridge;  // helper's stdout; -1 when no helper runs
    int to_bridge;    // helper's stdin; closing it is what stops the helper
    int on;
};

void rumble_init(struct rumble *r);
// No-op unless the pad takes FF_RUMBLE and apk_path names the app's APK.
void rumble_start(struct rumble *r, int pad_fd, const char *apk_path);
// Call when from_bridge polls readable.
void rumble_on_readable(struct rumble *r);
void rumble_stop(struct rumble *r);

#endif
