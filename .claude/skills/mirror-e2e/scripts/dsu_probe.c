// Asks the DSU server on 127.0.0.1:26760 for slot 0's motion, like an emulator, and prints N packets.
//   adb shell /data/local/tmp/dsu_probe [N]
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include "dsu.h"

int main(int argc, char **argv) {
    int want = argc > 1 ? atoi(argv[1]) : 10;
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    struct timeval tv = {2, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_in to = {0};
    to.sin_family = AF_INET;
    to.sin_port = htons(26760);
    to.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    uint8_t req[28] = {'D', 'S', 'U', 'C', 0xe9, 0x03, 12, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0x02, 0x00, 0x10, 0x00, 1, 0};
    if (argc > 2) {  // "info": ask for slot 0's info instead and print the reply size
        req[16] = 0x01; req[20] = 1; req[21] = 0; req[22] = 0; req[23] = 0; req[24] = 0;
    }
    uint32_t crc = dsu_crc32(req, 28);
    memcpy(req + 8, &crc, 4);
    sendto(s, req, sizeof req, 0, (struct sockaddr *)&to, sizeof to);
    for (int got = 0; got < want;) {
        uint8_t b[128];
        ssize_t n = recv(s, b, sizeof b, 0);
        if (n < 0) { puts("no reply"); return 1; }
        if (argc > 2) { printf("info reply: %zd bytes, slot state %d\n", n, b[21]); return 0; }
        if (n != 100) continue;
        float m[6];
        memcpy(m, b + 20 + 56, sizeof m);
        printf("accel %6.3f %6.3f %6.3f  gyro %8.2f %8.2f %8.2f  battery 0x%02x\n", m[0], m[1], m[2], m[3], m[4], m[5], b[30]);
        got++;
    }
    return 0;
}
