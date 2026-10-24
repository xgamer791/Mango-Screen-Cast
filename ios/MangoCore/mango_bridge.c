/* See mango_bridge.h. */
#include "mango_bridge.h"

#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

#include "logger.h"
#include "mango_server.h"

/* Set before the server starts and cleared after it stops, so the server's
 * threads only ever see a complete copy. */
static mango_bridge_callbacks_t g_cb;
static atomic_int g_open_connections;
/* AirPlay volume in dB: -30 (quietest) .. 0 (full), -144 = mute. */
static float g_volume_db = 0.0f;

/* ---- Service registration (dns_sd shim -> the app's Bonjour publisher) -- */

static int on_register_service(const char *type, const char *name, uint16_t port,
                               const uint8_t *txt, uint16_t txt_len) {
    if (!g_cb.register_service) return -1;
    return g_cb.register_service(g_cb.context, type, name, port, txt, txt_len);
}

static void on_unregister_service(const char *type) {
    if (g_cb.unregister_service) g_cb.unregister_service(g_cb.context, type);
}

/* ---- raop callbacks ---------------------------------------------------- */

static void notify_connections(int open) {
    if (g_cb.connections_changed) g_cb.connections_changed(g_cb.context, open);
}

static void cb_conn_init(void *cls) {
    (void) cls;
    notify_connections(atomic_fetch_add(&g_open_connections, 1) + 1);
}

static void cb_conn_destroy(void *cls) {
    (void) cls;
    int open = atomic_fetch_sub(&g_open_connections, 1) - 1;
    if (open < 0) {
        atomic_store(&g_open_connections, 0);
        open = 0;
    }
    notify_connections(open);
}

static void cb_conn_feedback(void *cls) { (void) cls; }

static void cb_conn_reset(void *cls, int reason) {
    (void) cls;
    if (g_cb.connection_reset) g_cb.connection_reset(g_cb.context, reason);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    *admit = true;
    if (g_cb.client_request) {
        *admit = g_cb.client_request(g_cb.context, deviceid ? deviceid : "", model ? model : "", name ? name : "");
    }
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height) {
    (void) cls; (void) x_offset; (void) y_offset;
    if (!g_cb.video_start) return -1;
    return g_cb.video_start(g_cb.context, codec == VIDEO_CODEC_H265, width_source, height_source, width, height);
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    if (g_cb.video_frame && data->data && data->data_len > 0) {
        g_cb.video_frame(g_cb.context, data->data, data->data_len, data->is_h265);
    }
}

static void cb_video_pause(void *cls) {
    (void) cls;
    if (g_cb.video_paused) g_cb.video_paused(g_cb.context, true);
}

static void cb_video_resume(void *cls) {
    (void) cls;
    if (g_cb.video_paused) g_cb.video_paused(g_cb.context, false);
}

static void cb_video_flush(void *cls) { (void) cls; }

static void cb_video_reset(void *cls, reset_type_t type) {
    (void) cls;
    if (g_cb.video_stop) g_cb.video_stop(g_cb.context, (int) type);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                                bool *is_media, uint64_t *audio_format) {
    (void) cls; (void) using_screen; (void) is_media; (void) audio_format;
    if (g_cb.audio_format) g_cb.audio_format(g_cb.context, *ct, *spf);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    if (g_cb.audio_frame && data->data && data->data_len > 0) {
        g_cb.audio_frame(g_cb.context, data->data, data->data_len, data->ct);
    }
}

static void cb_audio_flush(void *cls) {
    (void) cls;
    if (g_cb.audio_flush) g_cb.audio_flush(g_cb.context);
}

static double cb_audio_set_client_volume(void *cls) {
    (void) cls;
    return (double) g_volume_db;
}

static void cb_audio_set_volume(void *cls, float volume_db) {
    (void) cls;
    g_volume_db = volume_db;
    if (g_cb.volume) g_cb.volume(g_cb.context, volume_db);
}

/* No PIN or password: connecting is just picking the iPad from the list. */
static const char *cb_passwd(void *cls, int *len) {
    (void) cls;
    *len = 0;
    return NULL;
}
static void cb_display_pin(void *cls, char *pin) { (void) cls; (void) pin; }
static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name) {
    (void) cls; (void) device_id; (void) pk; (void) name;
}
static bool cb_check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    (void) cls; (void) active_remote; (void) dacp_id;
}
static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {
    (void) cls; (void) buffer; (void) buflen;
}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {
    (void) cls; (void) buffer; (void) buflen;
}
static void cb_audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void) cls; (void) start; (void) curr; (void) end;
}
static void cb_mirror_video_running(void *cls, bool running) { (void) cls; (void) running; }

/* HLS ("AirPlay video" URL playback) is not advertised, but keep the hooks
 * non-null in case a client tries it anyway. */
static void cb_on_video_play(void *cls, const char *location, const float start) {
    (void) cls; (void) location; (void) start;
}
static void cb_on_video_scrub(void *cls, const float position) { (void) cls; (void) position; }
static void cb_on_video_rate(void *cls, const float rate) { (void) cls; (void) rate; }
static void cb_on_video_stop(void *cls) { (void) cls; }
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void) cls;
    if (info) {
        info->duration = 0;
        info->position = 0;
        info->rate = 0;
        info->ready_to_play = false;
    }
}
static float cb_on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }
static void cb_get_custom_profile(void *cls, const char **profile) { (void) cls; if (profile) *profile = NULL; }

static void cb_log(void *cls, int level, const char *msg) {
    (void) cls;
    if (g_cb.log && msg) g_cb.log(g_cb.context, level, msg);
}

/* ---- Lifecycle ----------------------------------------------------------- */

static void clear_callbacks(void) {
    atomic_store(&g_open_connections, 0);
    memset(&g_cb, 0, sizeof(g_cb));
}

int mango_bridge_start(const mango_bridge_config_t *config, const mango_bridge_callbacks_t *callbacks) {
    mango_server_stop();
    clear_callbacks();
    g_cb = *callbacks;

    mango_server_config_t server;
    memset(&server, 0, sizeof(server));
    server.name = config->name;
    memcpy(server.hw_addr, config->device_id, sizeof(server.hw_addr));
    server.keyfile = config->keyfile;
    server.width = config->width;
    server.height = config->height;
    server.refresh_rate = config->refresh_rate;
    server.max_fps = config->max_fps;
    server.h265 = config->h265;
    server.log_level = config->log_level;

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.get_custom_profile = cb_get_custom_profile;

    int result = mango_server_start(&server, &cbs, cb_log, on_register_service, on_unregister_service);
    if (result <= 0) clear_callbacks();
    return result;
}

void mango_bridge_stop(void) {
    mango_server_stop();
    clear_callbacks();
}

bool mango_bridge_is_running(void) {
    return mango_server_is_running();
}

void mango_bridge_disconnect_clients(void) {
    mango_server_disconnect_clients();
}
