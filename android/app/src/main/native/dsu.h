#ifndef DSU_H
#define DSU_H

#include <stddef.h>
#include <stdint.h>

// A DSU (cemuhook) motion server on 127.0.0.1:26760, fed by a Switch-protocol pad's gyro. See dsu.c.

#define DSU_MAX_CLIENTS 4

struct dsu_client {
    uint8_t addr[16];  // struct sockaddr_in, kept opaque so this header needs no socket headers
    long long until_ms;
};

struct dsu {
    int hid_fd;    // the pad's /dev/hidrawN, -1 when the pad is not Switch-protocol
    int sock;      // UDP server socket, -1 when not serving
    uint32_t server_id;
    uint32_t packet;
    uint8_t battery;  // DSU battery code
    long long next_bind_ms;  // when to retry the port while the app still holds it
    struct dsu_client clients[DSU_MAX_CLIENTS];
};

void dsu_init(struct dsu *d);
// Finds the pad's hidraw node by the source's name; for a Nintendo pad, switches its IMU on and
// starts serving. A no-op for any other pad.
void dsu_start(struct dsu *d, int source_fd);
void dsu_on_hid(struct dsu *d);   // hid_fd readable
// Call every loop: takes the port once the app has let it go (the app serves the Thor's own gyro
// there while the mirror is off and releases it as the mirror starts).
void dsu_tick(struct dsu *d);
void dsu_on_sock(struct dsu *d);  // sock readable
void dsu_stop(struct dsu *d);

// Pure parts, for dsu_test.c.
uint32_t dsu_crc32(const uint8_t *p, size_t n);
// One 0x30/0x31 input report -> accel (g) and gyro (deg/s) in DSU's PlayStation axes, averaged over
// its three samples. Returns 0 when the report carries no IMU data.
int dsu_parse_imu(const uint8_t *report, size_t n, float accel[3], float gyro[3]);
// Switch battery nibble (byte 2 >> 4) -> DSU battery code.
uint8_t dsu_battery(uint8_t nibble);
size_t dsu_build_version(uint8_t *b, uint32_t id);
size_t dsu_build_info(uint8_t *b, uint32_t id, uint8_t slot, int connected, uint8_t battery);
size_t dsu_build_data(uint8_t *b, uint32_t id, uint32_t packet, uint64_t ts_us, const float accel[3],
                      const float gyro[3], uint8_t battery);

#endif
