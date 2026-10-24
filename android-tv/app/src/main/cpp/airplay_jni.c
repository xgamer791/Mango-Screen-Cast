/*
 * Bridge between UxPlay's AirPlay server (third_party/uxplay/lib) and the
 * Kotlin app. UxPlay does the protocol: pairing, FairPlay, decryption, RTP.
 * Everything it produces (H.264/H.265 frames, audio frames, service
 * records) is handed to com.mangoscreencast.tv.airplay.AirPlayNative's
 * callback object, which decodes it with MediaCodec.
 *
 * Copyright (C) 2026 Mango Screen Cast contributors. GPL-3.0-or-later.
 */
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "logger.h"
#include "mango_server.h"

#define TAG "MangoAirPlay"

static JavaVM *g_vm;
static pthread_key_t g_env_key;

static jobject g_callbacks;
static jmethodID m_register_service;
static jmethodID m_unregister_service;
static jmethodID m_client_request;
static jmethodID m_connections_changed;
static jmethodID m_video_codec;
static jmethodID m_video_frame;
static jmethodID m_video_paused;
static jmethodID m_video_flush;
static jmethodID m_video_reset;
static jmethodID m_audio_format;
static jmethodID m_audio_frame;
static jmethodID m_audio_flush;
static jmethodID m_volume;
static jmethodID m_connection_reset;
static jmethodID m_log;

static int g_open_connections;
/* AirPlay volume in dB: -30 (quietest) .. 0 (full), -144 = mute. */
static float g_volume_db = 0.0f;

/* ---- JNI plumbing ------------------------------------------------------ */

static void detach_thread(void *unused) {
    (void) unused;
    (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    return JNI_VERSION_1_6;
}

/* UxPlay calls back on its own threads; attach them to the VM on first use
 * and detach automatically when they exit. */
static JNIEnv *jni_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "AirPlay", NULL};
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, &args) != JNI_OK) return NULL;
    pthread_setspecific(g_env_key, env);
    return env;
}

static bool check_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

static jstring new_string(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

#define CALLBACK_ENV(env)                     \
    JNIEnv *env = jni_env();                  \
    if (!env || !g_callbacks) return

/* ---- Service registration (dns_sd shim -> NsdManager) ------------------ */

static int on_register_service(const char *type, const char *name, uint16_t port,
                               const uint8_t *txt, uint16_t txt_len) {
    JNIEnv *env = jni_env();
    if (!env || !g_callbacks) return -1;
    jstring jtype = new_string(env, type);
    jstring jname = new_string(env, name);
    jbyteArray jtxt = (*env)->NewByteArray(env, txt_len);
    if (txt_len) (*env)->SetByteArrayRegion(env, jtxt, 0, txt_len, (const jbyte *) txt);
    jint result = (*env)->CallIntMethod(env, g_callbacks, m_register_service, jtype, jname, (jint) port, jtxt);
    if (check_exception(env)) result = -1;
    (*env)->DeleteLocalRef(env, jtype);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jtxt);
    return result;
}

static void on_unregister_service(const char *type) {
    CALLBACK_ENV(env);
    jstring jtype = new_string(env, type);
    (*env)->CallVoidMethod(env, g_callbacks, m_unregister_service, jtype);
    check_exception(env);
    (*env)->DeleteLocalRef(env, jtype);
}

/* ---- raop callbacks ---------------------------------------------------- */

static void notify_connections(int open) {
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_connections_changed, (jint) open);
    check_exception(env);
}

static void cb_conn_init(void *cls) {
    (void) cls;
    notify_connections(__atomic_add_fetch(&g_open_connections, 1, __ATOMIC_SEQ_CST));
}

static void cb_conn_destroy(void *cls) {
    (void) cls;
    int open = __atomic_sub_fetch(&g_open_connections, 1, __ATOMIC_SEQ_CST);
    if (open < 0) {
        __atomic_store_n(&g_open_connections, 0, __ATOMIC_SEQ_CST);
        open = 0;
    }
    notify_connections(open);
}

static void cb_conn_feedback(void *cls) { (void) cls; }

static void cb_conn_reset(void *cls, int reason) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_connection_reset, (jint) reason);
    check_exception(env);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    *admit = true;
    CALLBACK_ENV(env);
    jstring jid = new_string(env, deviceid);
    jstring jmodel = new_string(env, model);
    jstring jname = new_string(env, name);
    jboolean ok = (*env)->CallBooleanMethod(env, g_callbacks, m_client_request, jid, jmodel, jname);
    if (!check_exception(env)) *admit = ok == JNI_TRUE;
    (*env)->DeleteLocalRef(env, jid);
    (*env)->DeleteLocalRef(env, jmodel);
    (*env)->DeleteLocalRef(env, jname);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height) {
    (void) cls; (void) x_offset; (void) y_offset;
    JNIEnv *env = jni_env();
    if (!env || !g_callbacks) return -1;
    jint result = (*env)->CallIntMethod(env, g_callbacks, m_video_codec, (jboolean) (codec == VIDEO_CODEC_H265),
                                        (jint) width_source, (jint) height_source, (jint) width, (jint) height);
    if (check_exception(env)) return -1;
    return result;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    CALLBACK_ENV(env);
    /* Zero-copy: the Kotlin side copies straight into a MediaCodec input buffer
     * before returning, so the direct buffer never outlives this call. */
    jobject buffer = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
    if (!buffer) {
        check_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, g_callbacks, m_video_frame, buffer, (jlong) data->ntp_time_local,
                           (jboolean) data->is_h265);
    check_exception(env);
    (*env)->DeleteLocalRef(env, buffer);
}

static void cb_video_pause(void *cls) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_video_paused, JNI_TRUE);
    check_exception(env);
}

static void cb_video_resume(void *cls) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_video_paused, JNI_FALSE);
    check_exception(env);
}

static void cb_video_flush(void *cls) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_video_flush);
    check_exception(env);
}

static void cb_video_reset(void *cls, reset_type_t type) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_video_reset, (jint) type);
    check_exception(env);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                                bool *is_media, uint64_t *audio_format) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_audio_format, (jint) *ct, (jint) *spf,
                           (jboolean) *using_screen, (jboolean) *is_media, (jlong) *audio_format);
    check_exception(env);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    CALLBACK_ENV(env);
    jobject buffer = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
    if (!buffer) {
        check_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, g_callbacks, m_audio_frame, buffer, (jint) data->ct, (jint) data->seqnum,
                           (jlong) data->ntp_time_local);
    check_exception(env);
    (*env)->DeleteLocalRef(env, buffer);
}

static void cb_audio_flush(void *cls) {
    (void) cls;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_audio_flush);
    check_exception(env);
}

static double cb_audio_set_client_volume(void *cls) {
    (void) cls;
    return (double) g_volume_db;
}

static void cb_audio_set_volume(void *cls, float volume_db) {
    (void) cls;
    g_volume_db = volume_db;
    CALLBACK_ENV(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_volume, (jfloat) volume_db);
    check_exception(env);
}

/* No PIN or password: connecting is just picking the TV from the list. */
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
    int priority = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
                 : level == LOGGER_WARNING ? ANDROID_LOG_WARN
                 : level <= LOGGER_INFO ? ANDROID_LOG_INFO
                 : ANDROID_LOG_DEBUG;
    __android_log_print(priority, TAG, "%s", msg);
    if (level > LOGGER_INFO) return;
    CALLBACK_ENV(env);
    jstring jmsg = new_string(env, msg);
    (*env)->CallVoidMethod(env, g_callbacks, m_log, (jint) level, jmsg);
    check_exception(env);
    (*env)->DeleteLocalRef(env, jmsg);
}

/* ---- Lifecycle ----------------------------------------------------------- */

static bool lookup_methods(JNIEnv *env, jobject callbacks) {
    jclass cls = (*env)->GetObjectClass(env, callbacks);
#define METHOD(var, name, sig)                                  \
    var = (*env)->GetMethodID(env, cls, name, sig);             \
    if (!var) { check_exception(env); return false; }
    METHOD(m_register_service, "onRegisterService", "(Ljava/lang/String;Ljava/lang/String;I[B)I");
    METHOD(m_unregister_service, "onUnregisterService", "(Ljava/lang/String;)V");
    METHOD(m_client_request, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    METHOD(m_connections_changed, "onConnectionsChanged", "(I)V");
    METHOD(m_video_codec, "onVideoCodec", "(ZIIII)I");
    METHOD(m_video_frame, "onVideoFrame", "(Ljava/nio/ByteBuffer;JZ)V");
    METHOD(m_video_paused, "onVideoPaused", "(Z)V");
    METHOD(m_video_flush, "onVideoFlush", "()V");
    METHOD(m_video_reset, "onVideoReset", "(I)V");
    METHOD(m_audio_format, "onAudioFormat", "(IIZZJ)V");
    METHOD(m_audio_frame, "onAudioFrame", "(Ljava/nio/ByteBuffer;IIJ)V");
    METHOD(m_audio_flush, "onAudioFlush", "()V");
    METHOD(m_volume, "onVolume", "(F)V");
    METHOD(m_connection_reset, "onConnectionReset", "(I)V");
    METHOD(m_log, "onLog", "(ILjava/lang/String;)V");
#undef METHOD
    (*env)->DeleteLocalRef(env, cls);
    return true;
}

static void release_callbacks(JNIEnv *env) {
    __atomic_store_n(&g_open_connections, 0, __ATOMIC_SEQ_CST);
    if (g_callbacks) {
        jobject old = g_callbacks;
        g_callbacks = NULL;
        (*env)->DeleteGlobalRef(env, old);
    }
}

/*
 * Starts the AirPlay server and advertises it. Returns the TCP port, or a
 * negative error code (see mango_server.h). width/height/refresh/maxFps are
 * what the TV tells Apple devices it can show; they size their stream to fit.
 */
JNIEXPORT jint JNICALL
Java_com_mangoscreencast_tv_airplay_AirPlayNative_nativeStart(JNIEnv *env, jclass clazz, jobject callbacks,
                                                              jstring jname, jbyteArray jhw_addr,
                                                              jstring jkeyfile, jint width, jint height,
                                                              jint refresh_rate, jint max_fps, jboolean h265,
                                                              jint log_level) {
    (void) clazz;
    mango_server_stop();
    release_callbacks(env);
    if (!lookup_methods(env, callbacks)) return -1;
    g_callbacks = (*env)->NewGlobalRef(env, callbacks);

    mango_server_config_t config;
    memset(&config, 0, sizeof(config));
    config.name = (*env)->GetStringUTFChars(env, jname, NULL);
    config.keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    (*env)->GetByteArrayRegion(env, jhw_addr, 0, (jsize) sizeof(config.hw_addr), (jbyte *) config.hw_addr);
    config.width = width;
    config.height = height;
    config.refresh_rate = refresh_rate;
    config.max_fps = max_fps;
    config.h265 = h265 == JNI_TRUE;
    config.log_level = log_level;

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

    int result = mango_server_start(&config, &cbs, cb_log, on_register_service, on_unregister_service);
    if (result > 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "AirPlay server '%s' on port %d (%dx%d@%d, max %d fps, %s)",
                            config.name, result, width, height, refresh_rate, max_fps,
                            config.h265 ? "H.264+HEVC" : "H.264");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "AirPlay server failed to start: %d", result);
        release_callbacks(env);
    }
    (*env)->ReleaseStringUTFChars(env, jname, config.name);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, config.keyfile);
    return result;
}

JNIEXPORT void JNICALL
Java_com_mangoscreencast_tv_airplay_AirPlayNative_nativeStop(JNIEnv *env, jclass clazz) {
    (void) clazz;
    mango_server_stop();
    release_callbacks(env);
}

JNIEXPORT jboolean JNICALL
Java_com_mangoscreencast_tv_airplay_AirPlayNative_nativeIsRunning(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return mango_server_is_running() ? JNI_TRUE : JNI_FALSE;
}

/* Drops any connected device (used when the user presses Back on the TV). */
JNIEXPORT void JNICALL
Java_com_mangoscreencast_tv_airplay_AirPlayNative_nativeDisconnectClients(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    mango_server_disconnect_clients();
}
