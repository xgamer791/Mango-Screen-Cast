/* See mango_server.h. */
#include "mango_server.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "global.h"
#include "logger.h"

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t g_ntp_once = PTHREAD_ONCE_INIT;
static raop_t *g_raop;
static dnssd_t *g_dnssd;

static void stop_locked(void) {
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
}

int mango_server_start(const mango_server_config_t *config, const raop_callbacks_t *callbacks,
                       raop_log_callback_t log_callback, mango_register_fn on_register,
                       mango_unregister_fn on_unregister) {
    pthread_once(&g_ntp_once, ntp_global_init);
    pthread_mutex_lock(&g_lock);
    stop_locked();
    mango_dnssd_set_handlers(on_register, on_unregister);

    const unsigned char *hw = config->hw_addr;
    char mac[3 * MAX_HWADDR_LEN];
    snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x", hw[0], hw[1], hw[2], hw[3], hw[4], hw[5]);

    int result;
    int error = 0;
    g_dnssd = dnssd_init(config->name, (int) strlen(config->name), (const char *) hw, MAX_HWADDR_LEN, 0, &error);
    if (!g_dnssd || error) {
        result = -2;
        goto done;
    }
    /* UxPlay's default features, plus HEVC ("ScreenMultiCodec", bit 42) when
     * the TV can decode it: that is what lets Macs and recent iPhones/iPads
     * send more than 1080p. HLS URL playback (bits 0 and 4) stays off so
     * apps fall back to screen mirroring. Bit 27 ("supports legacy pairing")
     * off, as UxPlay ships it: Macs and iPhones skip pairing entirely, so
     * there is no PIN to enter and no 5 s pairing delay. */
    dnssd_set_airplay_features(g_dnssd, 0, 0);
    dnssd_set_airplay_features(g_dnssd, 4, 0);
    dnssd_set_airplay_features(g_dnssd, 27, 0);
    dnssd_set_airplay_features(g_dnssd, 42, config->h265 ? 1 : 0);

    g_raop = raop_init((raop_callbacks_t *) callbacks);
    if (!g_raop) {
        result = -3;
        goto done;
    }
    if (log_callback) raop_set_log_callback(g_raop, log_callback, NULL);
    raop_set_log_level(g_raop, config->log_level);
    /* nohold = 1: a new device can take over the screen from the current one. */
    if (raop_init2(g_raop, 1, mac, config->keyfile)) {
        /* Half-initialised: raop_destroy() would assert on the missing httpd. */
        free(g_raop);
        g_raop = NULL;
        result = -4;
        goto done;
    }
    raop_set_plist(g_raop, "width", config->width);
    raop_set_plist(g_raop, "height", config->height);
    raop_set_plist(g_raop, "refreshRate", config->refresh_rate);
    raop_set_plist(g_raop, "maxFPS", config->max_fps);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        result = -5;
        goto done;
    }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
        result = -6;
        goto done;
    }
    result = port;

done:
    if (result < 0) stop_locked();
    pthread_mutex_unlock(&g_lock);
    return result;
}

void mango_server_stop(void) {
    pthread_mutex_lock(&g_lock);
    stop_locked();
    pthread_mutex_unlock(&g_lock);
}

bool mango_server_is_running(void) {
    pthread_mutex_lock(&g_lock);
    bool running = g_raop && raop_is_running(g_raop);
    pthread_mutex_unlock(&g_lock);
    return running;
}

void mango_server_disconnect_clients(void) {
    pthread_mutex_lock(&g_lock);
    if (g_raop) raop_remove_known_connections(g_raop);
    pthread_mutex_unlock(&g_lock);
}

void mango_server_offer_display(int width, int height, int max_fps) {
    /* No lock: see mango_server.h. The server's thread is the only reader. */
    if (!g_raop) return;
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "maxFPS", max_fps);
}
