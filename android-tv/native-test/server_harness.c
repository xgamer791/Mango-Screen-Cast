/*
 * Starts the TV's AirPlay server exactly as the app does (mango_server_start)
 * and prints what it would advertise. Usage:
 *   server_harness KEYFILE SECONDS WIDTH HEIGHT HEVC(0|1) [FPS]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "logger.h"
#include "mango_server.h"

static int on_register(const char *type, const char *name, uint16_t port, const uint8_t *txt, uint16_t len) {
    printf("REGISTER %s %u %s\n", type, port, name);
    for (int i = 0; i < len;) {
        int l = txt[i++];
        printf("TXT %s %.*s\n", type, l, (const char *) txt + i);
        i += l;
    }
    fflush(stdout);
    return 0;
}

static void on_unregister(const char *type) {
    printf("UNREGISTER %s\n", type);
    fflush(stdout);
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {}
static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {}
static double client_volume(void *cls) { return 0.0; }
static void log_message(void *cls, int level, const char *msg) { fprintf(stderr, "[%d] %s\n", level, msg); }

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: %s KEYFILE SECONDS WIDTH HEIGHT HEVC [FPS]\n", argv[0]);
        return 2;
    }
    mango_server_config_t config;
    memset(&config, 0, sizeof(config));
    config.name = "Mango Test TV";
    const unsigned char hw[6] = {0x02, 0x11, 0x22, 0x33, 0x44, 0x55};
    memcpy(config.hw_addr, hw, sizeof(hw));
    config.keyfile = argv[1];
    config.width = atoi(argv[3]);
    config.height = atoi(argv[4]);
    /* The TV advertises its refresh rate and frame rate as the same number. */
    config.refresh_rate = argc > 6 ? atoi(argv[6]) : 60;
    config.max_fps = config.refresh_rate;
    config.h265 = atoi(argv[5]) != 0;
    config.log_level = LOGGER_INFO;

    raop_callbacks_t callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.audio_process = audio_process;
    callbacks.video_process = video_process;
    callbacks.audio_set_client_volume = client_volume;

    int port = mango_server_start(&config, &callbacks, log_message, on_register, on_unregister);
    printf("PORT %d\n", port);
    fflush(stdout);
    if (port <= 0) return 1;
    sleep((unsigned) atoi(argv[2]));
    mango_server_stop();
    printf("STOPPED\n");
    return 0;
}
