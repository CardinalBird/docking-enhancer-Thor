#include "rumble.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

// Android tells vibrator listeners only on/off, never strength, so every rumble plays at the one
// level the user picked (Rumble strength in Additional Settings), on both motors.
static unsigned short magnitude(int percent) {
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    return (unsigned short)(percent * 0xFFFF / 100);
}

static void play(struct rumble *r, int on) {
    if (on && r->strength <= 0) return;
    if (r->effect_id < 0 || r->on == on) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_FF;
    ev.code = (unsigned short)r->effect_id;
    ev.value = on;
    if (write(r->pad_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) r->on = on;
}

static int has_rumble(int fd) {
    unsigned long ff[(FF_MAX + 8 * sizeof(long)) / (8 * sizeof(long))];
    memset(ff, 0, sizeof(ff));
    return ioctl(fd, EVIOCGBIT(EV_FF, sizeof(ff)), ff) >= 0 &&
           (ff[FF_RUMBLE / (8 * sizeof(long))] & (1UL << (FF_RUMBLE % (8 * sizeof(long)))));
}

static int read_line(const char *path, char *buf, size_t size) {
    FILE *f = fopen(path, "r");
    if (f == NULL) return -1;
    int ok = fgets(buf, (int)size, f) != NULL;
    fclose(f);
    if (!ok) return -1;
    buf[strcspn(buf, "\n")] = 0;
    return 0;
}

// The Thor's firmware re-exposes an external pad as a 2020:0111 "twin" (which is what we grab) and
// deletes the real pad's /dev node. The twin has no force feedback; the real input device still
// does. Find it by name in sysfs, make a private node for it (root only), open, and unlink.
// ponytail: first same-named match wins, so two identical pads may rumble the wrong one.
static int open_real_pad(int twin_fd) {
    char name[256] = {0};
    if (ioctl(twin_fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) return -1;
    DIR *dir = opendir("/sys/class/input");
    if (dir == NULL) return -1;
    int found = -1;
    struct dirent *e;
    while (found < 0 && (e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[256], value[256];
        snprintf(path, sizeof(path), "/sys/class/input/%s/device/name", e->d_name);
        if (read_line(path, value, sizeof(value)) < 0 || strcmp(value, name) != 0) continue;
        snprintf(path, sizeof(path), "/sys/class/input/%s/device/id/vendor", e->d_name);
        if (read_line(path, value, sizeof(value)) < 0 || strtol(value, NULL, 16) == 0x2020) continue;
        snprintf(path, sizeof(path), "/sys/class/input/%s/dev", e->d_name);
        unsigned major_no, minor_no;
        if (read_line(path, value, sizeof(value)) < 0 || sscanf(value, "%u:%u", &major_no, &minor_no) != 2) continue;
        // Outside /dev/input, so the framework's watcher never sees a new pad appear.
        char node[64];
        snprintf(node, sizeof(node), "/dev/.control_switch_rumble_%d", getpid());
        unlink(node);
        if (mknod(node, S_IFCHR | 0600, makedev(major_no, minor_no)) < 0) {
            fprintf(stderr, "Rumble: mknod for %s failed: %s\n", e->d_name, strerror(errno));
            continue;
        }
        int fd = open(node, O_RDWR | O_CLOEXEC);
        unlink(node);
        if (fd >= 0 && has_rumble(fd)) {
            found = fd;
            fprintf(stderr, "Rumble: using real pad %s behind the twin\n", e->d_name);
        } else if (fd >= 0) {
            close(fd);
        }
    }
    closedir(dir);
    return found;
}

void rumble_init(struct rumble *r) {
    memset(r, 0, sizeof(*r));
    r->pad_fd = -1;
    r->effect_id = -1;
    r->from_bridge = -1;
    r->to_bridge = -1;
    r->own_fd = -1;
    r->strength = 80;
}

void rumble_set_strength(struct rumble *r, int percent) {
    if (percent == r->strength) return;
    r->strength = percent;
    if (r->effect_id < 0) return;
    if (percent <= 0) play(r, 0);
    // Same id: the kernel updates the loaded effect in place.
    struct ff_effect effect;
    memset(&effect, 0, sizeof(effect));
    effect.type = FF_RUMBLE;
    effect.id = (short)r->effect_id;
    effect.u.rumble.strong_magnitude = magnitude(percent);
    effect.u.rumble.weak_magnitude = magnitude(percent);
    if (ioctl(r->pad_fd, EVIOCSFF, &effect) < 0) fprintf(stderr, "Rumble: strength update failed: %s\n", strerror(errno));
}

void rumble_start(struct rumble *r, int pad_fd, const char *apk_path) {
    if (apk_path == NULL) return;
    int own_fd = -1;  // opened here (the real pad behind a twin), so ours to close
    if (!has_rumble(pad_fd)) {
        own_fd = open_real_pad(pad_fd);
        if (own_fd < 0) {
            fprintf(stderr, "Rumble: pad has no FF_RUMBLE\n");
            return;
        }
        pad_fd = own_fd;
    }
    struct ff_effect effect;
    memset(&effect, 0, sizeof(effect));
    effect.type = FF_RUMBLE;
    effect.id = -1;
    effect.u.rumble.strong_magnitude = magnitude(r->strength);
    effect.u.rumble.weak_magnitude = magnitude(r->strength);
    // replay.length 0 = until stopped; the helper always follows a 1 with a 0, and its exit stops it.
    if (ioctl(pad_fd, EVIOCSFF, &effect) < 0) {
        fprintf(stderr, "Rumble: effect upload failed: %s\n", strerror(errno));
        if (own_fd >= 0) close(own_fd);
        return;
    }

    int out[2], in[2];
    if (pipe2(out, O_CLOEXEC) < 0) {
        ioctl(pad_fd, EVIOCRMFF, effect.id);
        if (own_fd >= 0) close(own_fd);
        return;
    }
    if (pipe2(in, O_CLOEXEC) < 0) {
        close(out[0]);
        close(out[1]);
        ioctl(pad_fd, EVIOCRMFF, effect.id);
        if (own_fd >= 0) close(own_fd);
        return;
    }
    pid_t pid = fork();
    if (pid == 0) {
        signal(SIGCHLD, SIG_DFL);  // the daemon ignores it to auto-reap; the JVM should not
        dup2(in[0], STDIN_FILENO);
        dup2(out[1], STDOUT_FILENO);
        setenv("CLASSPATH", apk_path, 1);
        execl("/system/bin/app_process", "app_process", "/system/bin",
              "--nice-name=control_switch_rumble", "com.controlswitch.data.RumbleBridge", (char *)NULL);
        _exit(127);
    }
    close(in[0]);
    close(out[1]);
    if (pid < 0) {
        close(in[1]);
        close(out[0]);
        ioctl(pad_fd, EVIOCRMFF, effect.id);
        if (own_fd >= 0) close(own_fd);
        return;
    }
    r->pad_fd = pad_fd;
    r->own_fd = own_fd;
    r->effect_id = effect.id;
    r->from_bridge = out[0];
    r->to_bridge = in[1];
    fprintf(stderr, "Rumble: bridge started (effect %d)\n", effect.id);
}

void rumble_on_readable(struct rumble *r) {
    char buf[256];
    ssize_t n = read(r->from_bridge, buf, sizeof(buf));
    if (n <= 0) {
        if (n < 0 && errno == EINTR) return;
        fprintf(stderr, "Rumble: bridge exited\n");
        play(r, 0);
        close(r->from_bridge);
        r->from_bridge = -1;
        return;
    }
    // Only the latest state matters; "ready" carries no digit.
    for (ssize_t i = n - 1; i >= 0; i--) {
        if (buf[i] == '0' || buf[i] == '1') {
            play(r, buf[i] == '1');
            break;
        }
    }
}

void rumble_stop(struct rumble *r) {
    play(r, 0);
    if (r->effect_id >= 0) ioctl(r->pad_fd, EVIOCRMFF, r->effect_id);
    if (r->to_bridge >= 0) close(r->to_bridge);
    if (r->from_bridge >= 0) close(r->from_bridge);
    if (r->own_fd >= 0) close(r->own_fd);
    int strength = r->strength;
    rumble_init(r);
    r->strength = strength;
}
