// Chord engine unit check, run on the device (no host linux/input.h). From the repo root:
//   $NDK_CLANG -std=c11 -D_GNU_SOURCE -Iandroid/app/src/main/native .claude/skills/mirror-e2e/scripts/mapping_test.c \
//     android/app/src/main/native/mapping.c -o /tmp/mapping_test && adb push /tmp/mapping_test /data/local/tmp/ && adb shell /data/local/tmp/mapping_test
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "mapping.h"

static struct axis_range src[ABS_CNT], dst[ABS_CNT];
static struct mapping_table t;
static struct input_event out[MAX_MAPPED_EVENTS];
static unsigned char fl[MAX_MAPPED_EVENTS];

static int feed(int type, int code, int value) {
    struct input_event in; memset(&in, 0, sizeof in);
    in.type = type; in.code = code; in.value = value;
    return mapping_apply(&t, src, dst, &in, out, fl, MAX_MAPPED_EVENTS);
}
static struct bind_ref key(int c) { struct bind_ref r = {BIND_KEY, (unsigned short)c, 0}; return r; }

int main(void) {
    src[ABS_Z] = (struct axis_range){1, 0, 255, 0}; dst[ABS_Z] = src[ABS_Z];
    struct binding b[1] = {{BIND_KEY, BTN_WEST, 0, BIND_KEY, BTN_NORTH, 0}};
    struct chord c[2];
    memset(c, 0, sizeof c);
    c[0].count = 2; c[0].members[0] = key(BTN_SOUTH); c[0].members[1] = key(BTN_EAST); c[0].target = key(KEY_HOME);
    c[1].count = 2; c[1].members[0] = key(BTN_TL); c[1].members[1] = (struct bind_ref){BIND_HALF, ABS_Z, 1}; c[1].target = key(KEY_BACK);
    mapping_load(&t, b, 1, c, 2);

    // A alone leaks through untouched.
    int n = feed(EV_KEY, BTN_SOUTH, 1);
    assert(n == 1 && out[0].code == BTN_SOUTH && out[0].value == 1 && fl[0] == 0);
    // B completes: A taken back (leak), then Home pressed (bound). B itself never appears.
    n = feed(EV_KEY, BTN_EAST, 1);
    assert(n == 2);
    assert(out[0].code == BTN_SOUTH && out[0].value == 0 && fl[0] == MAPPED_LEAK);
    assert(out[1].code == KEY_HOME && out[1].value == 1 && fl[1] == MAPPED_BOUND);
    // Repeats while held are swallowed.
    assert(feed(EV_KEY, BTN_SOUTH, 2) == 0);
    // Releasing B releases Home; B's release swallowed.
    n = feed(EV_KEY, BTN_EAST, 0);
    assert(n == 1 && out[0].code == KEY_HOME && out[0].value == 0);
    // A's release: still suppressed, swallowed.
    assert(feed(EV_KEY, BTN_SOUTH, 0) == 0);
    // Back to normal: B alone passes through.
    n = feed(EV_KEY, BTN_EAST, 1);
    assert(n == 1 && out[0].code == BTN_EAST && out[0].value == 1);
    assert(feed(EV_KEY, BTN_EAST, 0) == 1);
    // The single binding is untouched by chords.
    n = feed(EV_KEY, BTN_WEST, 1);
    assert(n == 1 && out[0].code == BTN_NORTH && fl[0] == MAPPED_BOUND);
    feed(EV_KEY, BTN_WEST, 0);

    // Axis member: LB then pull ZR-style trigger past 55% fires Back.
    n = feed(EV_KEY, BTN_TL, 1);
    assert(n == 1 && out[0].code == BTN_TL);
    n = feed(EV_ABS, ABS_Z, 100);           // 39%: not held yet, passes through
    assert(n == 1 && out[0].type == EV_ABS && fl[0] == 0);
    n = feed(EV_ABS, ABS_Z, 200);           // 78%: completes
    assert(n == 2 && out[0].code == BTN_TL && out[0].value == 0 && fl[0] == MAPPED_LEAK);
    assert(out[1].code == KEY_BACK && out[1].value == 1);
    assert(feed(EV_ABS, ABS_Z, 130) == 0);  // 51%: hysteresis keeps it held, swallowed
    n = feed(EV_ABS, ABS_Z, 50);            // 20%: released -> Back up
    assert(n == 1 && out[0].code == KEY_BACK && out[0].value == 0);
    assert(feed(EV_KEY, BTN_TL, 0) == 0);   // LB still suppressed until its release
    n = feed(EV_ABS, ABS_Z, 10);            // trigger events flow again
    assert(n == 1 && out[0].type == EV_ABS);

    // reset forgets held members.
    feed(EV_KEY, BTN_SOUTH, 1);
    mapping_reset_state(&t);
    n = feed(EV_KEY, BTN_EAST, 1);
    assert(n == 1 && out[0].code == BTN_EAST);
    puts("chord tests passed");
    return 0;
}
