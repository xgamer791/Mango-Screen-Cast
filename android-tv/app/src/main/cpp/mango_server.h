/*
 * Starts and stops UxPlay's AirPlay server with the TV's settings. Plain C,
 * so the same code runs in the app (via airplay_jni.c) and in host tests.
 *
 * Copyright (C) 2026 Mango Screen Cast contributors. GPL-3.0-or-later.
 */
#ifndef MANGO_SERVER_H
#define MANGO_SERVER_H

#include <stdbool.h>

#include "dns_sd.h"
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *name;               /* shown in Screen Mirroring menus */
    unsigned char hw_addr[6];       /* AirPlay device id */
    const char *keyfile;            /* where the pairing key persists */
    int width, height;              /* display size offered to senders */
    int refresh_rate, max_fps;
    bool h265;                      /* offer HEVC (needed above 1080p) */
    int log_level;                  /* LOGGER_* from logger.h */
} mango_server_config_t;

/* Returns the TCP port the server listens on, or a negative error:
 * -2 dnssd_init, -3 raop_init, -4 raop_init2, -5 no port, -6 registration. */
int mango_server_start(const mango_server_config_t *config, const raop_callbacks_t *callbacks,
                       raop_log_callback_t log_callback, mango_register_fn on_register,
                       mango_unregister_fn on_unregister);
void mango_server_stop(void);
bool mango_server_is_running(void);
void mango_server_disconnect_clients(void);

#ifdef __cplusplus
}
#endif

#endif
