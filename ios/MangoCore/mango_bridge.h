/*
 * The iOS app's view of the AirPlay server: plain C types only, so Swift can
 * use it through the bridging header without seeing any UxPlay headers.
 *
 * The AirPlay core (UxPlay, libplist, mango_server.c) is the same one the
 * Android TV app builds; this file plays the part airplay_jni.c plays there.
 *
 * Copyright (C) 2026 Mango Screen Cast contributors. GPL-3.0-or-later.
 */
#ifndef MANGO_BRIDGE_H
#define MANGO_BRIDGE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Log levels, as in UxPlay's logger.h. */
enum {
    MANGO_LOG_ERR = 3,
    MANGO_LOG_WARNING = 4,
    MANGO_LOG_NOTICE = 5,
    MANGO_LOG_INFO = 6,
    MANGO_LOG_DEBUG = 7,
};

/* Audio compression types (`ct`). */
enum {
    MANGO_AUDIO_PCM = 1,
    MANGO_AUDIO_ALAC = 2,
    MANGO_AUDIO_AAC_LC = 4,
    MANGO_AUDIO_AAC_ELD = 8,
};

/*
 * Everything the server reports. Each function gets `context` back. They run
 * on the server's own threads (network, mirroring, audio), never the main
 * thread, and must not block for long: a receiver that reads slowly makes
 * Apple devices lower their bitrate.
 */
typedef struct {
    void *context;

    /* Publish / withdraw a Bonjour service. `txt` is the wire-format TXT
     * record. Return 0 if publishing was started. */
    int (*register_service)(void *context, const char *type, const char *name, uint16_t port,
                            const uint8_t *txt, uint16_t txt_length);
    void (*unregister_service)(void *context, const char *type);

    /* A device connects. Return false to turn it away. */
    bool (*client_request)(void *context, const char *device_id, const char *model, const char *name);
    void (*connections_changed)(void *context, int open_connections);
    /* The connection dropped (reason 1: the device went away without saying). */
    void (*connection_reset)(void *context, int reason);

    /* Mirroring starts. Return 0 to accept the stream. */
    int (*video_start)(void *context, bool h265, int source_width, int source_height, int width, int height);
    /* One frame: Annex B NAL units, each after a 4-byte start code. Keyframes
     * carry their parameter sets (VPS/SPS/PPS) in front. Only valid during
     * the call. */
    void (*video_frame)(void *context, const uint8_t *data, int length, bool h265);
    void (*video_paused)(void *context, bool paused);
    /* Mirroring stopped, or another device took over. */
    void (*video_stop)(void *context, int reason);

    void (*audio_format)(void *context, int compression_type, int samples_per_frame);
    void (*audio_frame)(void *context, const uint8_t *data, int length, int compression_type);
    void (*audio_flush)(void *context);
    /* AirPlay volume: -30 (quietest) .. 0 dB, -144 = mute. */
    void (*volume)(void *context, float db);

    void (*log)(void *context, int level, const char *message);
} mango_bridge_callbacks_t;

typedef struct {
    const char *name;           /* shown in Screen Mirroring menus */
    uint8_t device_id[6];       /* AirPlay device id (MAC-style) */
    const char *keyfile;        /* where the pairing key persists */
    int width, height;          /* display size offered to senders */
    int refresh_rate, max_fps;
    bool h265;                  /* offer HEVC (needed above 1080p) */
    int log_level;              /* MANGO_LOG_* */
} mango_bridge_config_t;

/* Starts the server and advertises it. Returns the TCP port, or a negative
 * error: -2 dnssd_init, -3 raop_init, -4 raop_init2, -5 no port,
 * -6 registration. Restarts it if it's already running. */
int mango_bridge_start(const mango_bridge_config_t *config, const mango_bridge_callbacks_t *callbacks);
/* Stops the server; no callbacks arrive after it returns. */
void mango_bridge_stop(void);
bool mango_bridge_is_running(void);
/* Drops the connected device. */
void mango_bridge_disconnect_clients(void);

#ifdef __cplusplus
}
#endif

#endif
