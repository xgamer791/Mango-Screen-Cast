/* See dns_sd.h. */
#include "dns_sd.h"

#include <arpa/inet.h>
#include <stdlib.h>
#include <string.h>

struct _DNSServiceRef_t {
    char type[64];
};

typedef struct {
    uint8_t *bytes;
    uint16_t length;
    uint16_t capacity;
    int owned;
} txt_buffer_t;

static mango_register_fn g_register = NULL;
static mango_unregister_fn g_unregister = NULL;

void mango_dnssd_set_handlers(mango_register_fn on_register, mango_unregister_fn on_unregister) {
    g_register = on_register;
    g_unregister = on_unregister;
}

static txt_buffer_t *txt_of(const TXTRecordRef *ref) {
    txt_buffer_t *txt;
    memcpy(&txt, ref->PrivateData, sizeof(txt));
    return txt;
}

void TXTRecordCreate(TXTRecordRef *ref, uint16_t bufferLen, void *buffer) {
    txt_buffer_t *txt = calloc(1, sizeof(txt_buffer_t));
    if (txt) {
        if (buffer && bufferLen) {
            txt->bytes = buffer;
            txt->capacity = bufferLen;
        } else {
            txt->capacity = 512;
            txt->bytes = malloc(txt->capacity);
            txt->owned = 1;
        }
    }
    memset(ref, 0, sizeof(*ref));
    memcpy(ref->PrivateData, &txt, sizeof(txt));
}

void TXTRecordDeallocate(TXTRecordRef *ref) {
    txt_buffer_t *txt = txt_of(ref);
    if (!txt) return;
    if (txt->owned) free(txt->bytes);
    free(txt);
    memset(ref, 0, sizeof(*ref));
}

DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *ref, const char *key, uint8_t valueSize, const void *value) {
    txt_buffer_t *txt = txt_of(ref);
    if (!txt || !txt->bytes || !key) return kDNSServiceErr_BadParam;
    size_t key_len = strlen(key);
    size_t entry = key_len + (value ? 1 + valueSize : 0);
    if (entry > 255) return kDNSServiceErr_BadParam;
    if ((size_t) txt->length + 1 + entry > txt->capacity) {
        if (!txt->owned) return kDNSServiceErr_NoMemory;
        size_t capacity = (size_t) txt->capacity * 2 + entry + 1;
        if (capacity > 0xFFFF) return kDNSServiceErr_NoMemory;
        uint8_t *grown = realloc(txt->bytes, capacity);
        if (!grown) return kDNSServiceErr_NoMemory;
        txt->bytes = grown;
        txt->capacity = (uint16_t) capacity;
    }
    uint8_t *p = txt->bytes + txt->length;
    *p++ = (uint8_t) entry;
    memcpy(p, key, key_len);
    p += key_len;
    if (value) {
        *p++ = '=';
        memcpy(p, value, valueSize);
    }
    txt->length = (uint16_t) (txt->length + 1 + entry);
    return kDNSServiceErr_NoError;
}

uint16_t TXTRecordGetLength(const TXTRecordRef *ref) {
    txt_buffer_t *txt = txt_of(ref);
    return txt ? txt->length : 0;
}

const void *TXTRecordGetBytesPtr(const TXTRecordRef *ref) {
    txt_buffer_t *txt = txt_of(ref);
    return txt ? txt->bytes : NULL;
}

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags, uint32_t interfaceIndex,
                                       const char *name, const char *regtype, const char *domain,
                                       const char *host, uint16_t port, uint16_t txtLen,
                                       const void *txtRecord, DNSServiceRegisterReply callBack,
                                       void *context) {
    (void) flags; (void) interfaceIndex; (void) domain; (void) host; (void) callBack; (void) context;
    if (!sdRef || !name || !regtype) return kDNSServiceErr_BadParam;
    if (!g_register) return kDNSServiceErr_Unknown;
    struct _DNSServiceRef_t *ref = calloc(1, sizeof(*ref));
    if (!ref) return kDNSServiceErr_NoMemory;
    strncpy(ref->type, regtype, sizeof(ref->type) - 1);
    if (g_register(regtype, name, ntohs(port), txtRecord, txtLen) != 0) {
        free(ref);
        return kDNSServiceErr_Unknown;
    }
    *sdRef = ref;
    return kDNSServiceErr_NoError;
}

void DNSServiceRefDeallocate(DNSServiceRef ref) {
    if (!ref) return;
    if (g_unregister) g_unregister(ref->type);
    free(ref);
}

int DNSServiceRefSockFD(DNSServiceRef ref) {
    (void) ref;
    return -1;
}

DNSServiceErrorType DNSServiceProcessResult(DNSServiceRef ref) {
    (void) ref;
    return kDNSServiceErr_NoError;
}
