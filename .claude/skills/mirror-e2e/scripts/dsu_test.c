// DSU server unit check, run on the device like mapping_test.c. From the repo root:
//   $NDK_CLANG -std=c11 -D_GNU_SOURCE -Iandroid/app/src/main/native .claude/skills/mirror-e2e/scripts/dsu_test.c \
//     android/app/src/main/native/dsu.c -o /tmp/dsu_test && adb push /tmp/dsu_test /data/local/tmp/ && adb shell /data/local/tmp/dsu_test
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "dsu.h"

static int near(float a, float b) { return fabsf(a - b) < 0.01f; }

int main(void) {
    assert(dsu_crc32((const uint8_t *)"123456789", 9) == 0xCBF43926u);

    // A real report from an 8BitDo Ultimate 2 (Switch mode) lying flat, IMU on: three samples of
    // accel (-487, 151, 4142) and gyro (3, -1, -5).
    uint8_t r[49] = {0x30, 0xf4, 0x60, 0, 0, 0, 0xf0, 0x07, 0x80, 0xf0, 0x07, 0x80, 0x0b};
    const uint8_t s[12] = {0x19, 0xfe, 0x97, 0x00, 0x2e, 0x10, 0x03, 0x00, 0xff, 0xff, 0xfb, 0xff};
    for (int i = 0; i < 3; i++) memcpy(r + 13 + 12 * i, s, 12);
    float a[3], g[3];
    assert(dsu_parse_imu(r, sizeof r, a, g));
    // Flat: gravity on PlayStation +Y.
    assert(near(a[1], 4142 / 4096.0f) && near(a[0], -151 / 4096.0f) && near(a[2], 487 / 4096.0f));
    assert(near(g[0], 1 / 14.2842f) && near(g[1], -5 / 14.2842f) && near(g[2], -3 / 14.2842f));

    // IMU off: all zeros is not a reading.
    uint8_t off[49] = {0x30, 0x01, 0x60};
    assert(!dsu_parse_imu(off, sizeof off, a, g));
    assert(dsu_battery(0x6) == 0x04 && dsu_battery(0x9) == 0xEE && dsu_battery(0x8) == 0x05);

    // Every packet: magic, length after the header, and a CRC that checks out.
    uint8_t b[100];
    size_t sizes[3] = {dsu_build_version(b, 7), 0, 0};
    assert(sizes[0] == 22);
    size_t n = dsu_build_info(b, 7, 0, 1, 0x05);
    assert(n == 32 && b[20] == 0 && b[21] == 2);
    n = dsu_build_data(b, 7, 42, 123456, a, g, 0x04);
    assert(n == 100 && memcmp(b, "DSUS", 4) == 0 && (b[6] | b[7] << 8) == 84);
    uint32_t crc = (uint32_t)b[8] | (uint32_t)b[9] << 8 | (uint32_t)b[10] << 16 | (uint32_t)b[11] << 24;
    memset(b + 8, 0, 4);
    assert(dsu_crc32(b, n) == crc);
    float ay;
    memcpy(&ay, b + 20 + 56 + 4, 4);
    assert(near(ay, a[1]));
    puts("dsu_test: all checks passed");
    return 0;
}
