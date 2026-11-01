#include "dsu.h"

#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

// Why this exists: emulators on the Thor read motion from the Thor's own gyro, which sits still in
// the dock. Eden, Dolphin and Cemu (and Citra-family emulators) can instead read a controller's
// motion over DSU, the cemuhook UDP protocol, from 127.0.0.1:26760. The Thor's hid-nintendo never
// switches a Switch pad's IMU on and makes no motion device for it, so we switch it on ourselves
// through the pad's hidraw node and read the samples out of its input reports.
//
// Protocol: github.com/v1993/cemuhook-protocol. Little-endian; 16-byte header ("DSUS", version
// 1001, length after the header, CRC32 of the whole packet with the CRC field zeroed, server id),
// then a u32 message type.

#define DSU_PORT 26760
#define DSU_VERSION 1001
#define MSG_VERSION 0x100000u
#define MSG_INFO 0x100001u
#define MSG_DATA 0x100002u
// Clients re-ask for data about once a second; drop one that stopped asking.
#define SUBSCRIPTION_MS 5000

// Tuning knobs. 4096 LSB/g is the ±8 g range (a flat pad reads ~4100 on Z); 14.2842 LSB per deg/s
// is the ±2000 deg/s range SDL uses for Switch pads. Factory calibration is not read.
// ponytail: no per-pad calibration or bias removal; read SPI calibration if a pad drifts.
#define ACCEL_LSB_PER_G 4096.0f
#define GYRO_LSB_PER_DPS 14.2842f

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

uint32_t dsu_crc32(const uint8_t *p, size_t n) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++) {
        crc ^= p[i];
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

static int16_t le16(const uint8_t *p) { return (int16_t)(p[0] | (p[1] << 8)); }
static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32(uint8_t *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (8 * i)); }
static void putf(uint8_t *p, float f) { uint32_t v; memcpy(&v, &f, 4); put32(p, v); }

int dsu_parse_imu(const uint8_t *r, size_t n, float accel[3], float gyro[3]) {
    if (n < 49 || (r[0] != 0x30 && r[0] != 0x31)) return 0;
    float a[3] = {0}, g[3] = {0};
    for (int s = 0; s < 3; s++) {
        for (int k = 0; k < 3; k++) {
            a[k] += le16(r + 13 + 12 * s + 2 * k);
            g[k] += le16(r + 19 + 12 * s + 2 * k);
        }
    }
    // An IMU that is off reports all zeros, which is not a real reading (gravity is never zero).
    if (a[0] == 0 && a[1] == 0 && a[2] == 0) return 0;
    // Switch axes -> PlayStation axes, as SDL does for these pads: x = -Y, y = Z, z = -X.
    accel[0] = -a[1] / 3 / ACCEL_LSB_PER_G;
    accel[1] = a[2] / 3 / ACCEL_LSB_PER_G;
    accel[2] = -a[0] / 3 / ACCEL_LSB_PER_G;
    gyro[0] = -g[1] / 3 / GYRO_LSB_PER_DPS;  // pitch
    gyro[1] = g[2] / 3 / GYRO_LSB_PER_DPS;   // yaw
    gyro[2] = -g[0] / 3 / GYRO_LSB_PER_DPS;  // roll
    return 1;
}

uint8_t dsu_battery(uint8_t nibble) {
    if (nibble & 1) return 0xEE;  // charging
    static const uint8_t codes[5] = {0x01, 0x02, 0x03, 0x04, 0x05};
    int level = nibble >> 1;
    return codes[level > 4 ? 4 : level];
}

static size_t header(uint8_t *b, uint32_t id, uint32_t type) {
    memcpy(b, "DSUS", 4);
    put16(b + 4, DSU_VERSION);
    put16(b + 6, 0);
    put32(b + 8, 0);
    put32(b + 12, id);
    put32(b + 16, type);
    return 20;
}

static size_t finish(uint8_t *b, size_t total) {
    put16(b + 6, (uint16_t)(total - 16));
    put32(b + 8, 0);
    put32(b + 8, dsu_crc32(b, total));
    return total;
}

// slot, state, model, connection, MAC, battery: the 11 bytes info and data packets share.
static void slot_info(uint8_t *p, uint8_t slot, int connected, uint8_t battery) {
    p[0] = slot;
    p[1] = connected ? 2 : 0;  // connected
    p[2] = connected ? 2 : 0;  // full gyro
    p[3] = connected ? 2 : 0;  // Bluetooth
    static const uint8_t mac[6] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    memcpy(p + 4, connected ? mac : (const uint8_t[6]){0}, 6);
    p[10] = connected ? battery : 0;
}

size_t dsu_build_version(uint8_t *b, uint32_t id) {
    size_t n = header(b, id, MSG_VERSION);
    put16(b + n, DSU_VERSION);
    return finish(b, n + 2);
}

size_t dsu_build_info(uint8_t *b, uint32_t id, uint8_t slot, int connected, uint8_t battery) {
    size_t n = header(b, id, MSG_INFO);
    slot_info(b + n, slot, connected, battery);
    b[n + 11] = 0;
    return finish(b, n + 12);
}

size_t dsu_build_data(uint8_t *b, uint32_t id, uint32_t packet, uint64_t ts_us, const float accel[3],
                      const float gyro[3], uint8_t battery) {
    size_t n = header(b, id, MSG_DATA);
    memset(b + n, 0, 80);
    slot_info(b + n, 0, 1, battery);
    b[n + 11] = 1;                  // connected
    put32(b + n + 12, packet);
    // Buttons, sticks and touch stay neutral: the mirror already delivers those as the built-in pad.
    b[n + 20] = b[n + 21] = b[n + 22] = b[n + 23] = 128;
    uint8_t *m = b + n + 48;
    for (int i = 0; i < 8; i++) m[i] = (uint8_t)(ts_us >> (8 * i));
    for (int i = 0; i < 3; i++) putf(m + 8 + 4 * i, accel[i]);
    for (int i = 0; i < 3; i++) putf(m + 20 + 4 * i, gyro[i]);
    return finish(b, n + 80);
}

void dsu_init(struct dsu *d) {
    memset(d, 0, sizeof(*d));
    d->hid_fd = -1;
    d->sock = -1;
    d->battery = 0x05;
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

// The source's hidraw node, by name. The twin we grab has none; the real pad behind it does, with
// the same name. Only a Nintendo vendor id: the IMU command below means nothing to other pads.
static int open_hidraw(const char *name) {
    DIR *dir = opendir("/sys/class/hidraw");
    if (dir == NULL) return -1;
    int fd = -1;
    struct dirent *e;
    while (fd < 0 && (e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "hidraw", 6) != 0) continue;
        char path[256], value[256];
        snprintf(path, sizeof(path), "/sys/class/hidraw/%s/device/uevent", e->d_name);
        FILE *f = fopen(path, "r");
        if (f == NULL) continue;
        int nintendo = 0;
        while (fgets(value, sizeof(value), f) != NULL) {
            if (strncmp(value, "HID_ID=", 7) == 0 && strstr(value, ":0000057E:") != NULL) nintendo = 1;
        }
        fclose(f);
        if (!nintendo) continue;
        DIR *inputs;
        snprintf(path, sizeof(path), "/sys/class/hidraw/%s/device/input", e->d_name);
        if ((inputs = opendir(path)) == NULL) continue;
        struct dirent *in;
        int match = 0;
        while (!match && (in = readdir(inputs)) != NULL) {
            if (strncmp(in->d_name, "input", 5) != 0) continue;
            snprintf(path, sizeof(path), "/sys/class/hidraw/%s/device/input/%s/name", e->d_name, in->d_name);
            match = read_line(path, value, sizeof(value)) == 0 && strcmp(value, name) == 0;
        }
        closedir(inputs);
        if (!match) continue;
        snprintf(path, sizeof(path), "/dev/%s", e->d_name);
        fd = open(path, O_RDWR | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) fprintf(stderr, "DSU: open %s failed: %s\n", path, strerror(errno));
    }
    closedir(dir);
    return fd;
}

void dsu_start(struct dsu *d, int source_fd) {
    char name[256] = {0};
    if (ioctl(source_fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) return;
    d->hid_fd = open_hidraw(name);
    if (d->hid_fd < 0) return;

    // Output report 0x01: packet counter, neutral rumble, subcommand 0x40 (IMU) with argument 1 (on).
    uint8_t cmd[49] = {0x01, 0x00, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40, 0x40, 0x01};
    if (write(d->hid_fd, cmd, sizeof(cmd)) != (ssize_t)sizeof(cmd)) {
        fprintf(stderr, "DSU: IMU enable failed: %s\n", strerror(errno));
    }

    d->server_id = (uint32_t)getpid() ^ (uint32_t)now_ms();
    fprintf(stderr, "DSU: %s found; IMU on\n", name);
    dsu_tick(d);
}

void dsu_tick(struct dsu *d) {
    if (d->hid_fd < 0 || d->sock >= 0) return;
    long long now = now_ms();
    if (now < d->next_bind_ms) return;
    d->next_bind_ms = now + 1000;
    int sock = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (sock < 0) return;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(DSU_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sock);  // still the app's; retried in a second
        return;
    }
    d->sock = sock;
    fprintf(stderr, "DSU: serving the pad's motion on 127.0.0.1:%d\n", DSU_PORT);
}

void dsu_on_hid(struct dsu *d) {
    uint8_t r[64];
    ssize_t n = read(d->hid_fd, r, sizeof(r));
    if (n <= 0) {
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return;
        fprintf(stderr, "DSU: pad gone\n");
        dsu_stop(d);
        return;
    }
    if (n >= 3 && (r[0] == 0x30 || r[0] == 0x31 || r[0] == 0x21)) d->battery = dsu_battery(r[2] >> 4);
    float accel[3], gyro[3];
    if (!dsu_parse_imu(r, (size_t)n, accel, gyro)) return;
    long long now = now_ms();
    uint8_t b[100];
    size_t len = 0;
    for (int i = 0; i < DSU_MAX_CLIENTS; i++) {
        struct dsu_client *c = &d->clients[i];
        if (c->until_ms <= now) continue;
        if (len == 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t us = (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
            len = dsu_build_data(b, d->server_id, d->packet++, us, accel, gyro, d->battery);
        }
        sendto(d->sock, b, len, 0, (struct sockaddr *)c->addr, sizeof(struct sockaddr_in));
    }
}

static void subscribe(struct dsu *d, const struct sockaddr_in *from) {
    long long now = now_ms();
    struct dsu_client *slot = NULL;
    for (int i = 0; i < DSU_MAX_CLIENTS; i++) {
        struct dsu_client *c = &d->clients[i];
        if (memcmp(c->addr, from, sizeof(*from)) == 0) { slot = c; break; }
        if (slot == NULL && c->until_ms <= now) slot = c;
    }
    if (slot == NULL) return;  // ponytail: four live clients at most; a fifth is ignored
    memcpy(slot->addr, from, sizeof(*from));
    slot->until_ms = now + SUBSCRIPTION_MS;
}

void dsu_on_sock(struct dsu *d) {
    uint8_t in[256], out[100];
    struct sockaddr_in from;
    socklen_t fromlen = sizeof(from);
    ssize_t n = recvfrom(d->sock, in, sizeof(in), 0, (struct sockaddr *)&from, &fromlen);
    if (n < 20 || memcmp(in, "DSUC", 4) != 0) return;
    uint32_t type = (uint32_t)in[16] | (uint32_t)in[17] << 8 | (uint32_t)in[18] << 16 | (uint32_t)in[19] << 24;
    if (type == MSG_VERSION) {
        size_t len = dsu_build_version(out, d->server_id);
        sendto(d->sock, out, len, 0, (struct sockaddr *)&from, fromlen);
    } else if (type == MSG_INFO && n >= 24) {
        int32_t count = (int32_t)((uint32_t)in[20] | (uint32_t)in[21] << 8 | (uint32_t)in[22] << 16 | (uint32_t)in[23] << 24);
        for (int i = 0; i < count && i < 4 && 24 + i < n; i++) {
            uint8_t slot = in[24 + i];
            if (slot > 3) continue;
            size_t len = dsu_build_info(out, d->server_id, slot, slot == 0, d->battery);
            sendto(d->sock, out, len, 0, (struct sockaddr *)&from, fromlen);
        }
    } else if (type == MSG_DATA) {
        subscribe(d, &from);
    }
}

void dsu_stop(struct dsu *d) {
    if (d->hid_fd >= 0) close(d->hid_fd);
    if (d->sock >= 0) close(d->sock);
    dsu_init(d);
}
