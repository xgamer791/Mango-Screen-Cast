/*
 * Minimal DNS-SD API for UxPlay's dns_sd backend on Android.
 *
 * Android apps can't reach a dns_sd daemon, so registrations are forwarded
 * to the app (see mango_dnssd_set_handlers), which publishes them with
 * NsdManager. TXT records are built here exactly as UxPlay builds them.
 */
#ifndef MANGO_DNS_SD_H
#define MANGO_DNS_SD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _DNSServiceRef_t *DNSServiceRef;
typedef union _TXTRecordRef_t { char PrivateData[16]; char *ForceNaturalAlignment; } TXTRecordRef;
typedef uint32_t DNSServiceFlags;
typedef int32_t DNSServiceErrorType;

enum {
    kDNSServiceErr_NoError = 0,
    kDNSServiceErr_Unknown = -65537,
    kDNSServiceErr_NoMemory = -65539,
    kDNSServiceErr_BadParam = -65540,
};

typedef void (*DNSServiceRegisterReply)(DNSServiceRef sdRef, DNSServiceFlags flags,
                                        DNSServiceErrorType errorCode, const char *name,
                                        const char *regtype, const char *domain, void *context);

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags, uint32_t interfaceIndex,
                                       const char *name, const char *regtype, const char *domain,
                                       const char *host, uint16_t port, uint16_t txtLen,
                                       const void *txtRecord, DNSServiceRegisterReply callBack,
                                       void *context);
void DNSServiceRefDeallocate(DNSServiceRef sdRef);
int DNSServiceRefSockFD(DNSServiceRef sdRef);
DNSServiceErrorType DNSServiceProcessResult(DNSServiceRef sdRef);

void TXTRecordCreate(TXTRecordRef *txtRecord, uint16_t bufferLen, void *buffer);
void TXTRecordDeallocate(TXTRecordRef *txtRecord);
DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *txtRecord, const char *key, uint8_t valueSize,
                                      const void *value);
uint16_t TXTRecordGetLength(const TXTRecordRef *txtRecord);
const void *TXTRecordGetBytesPtr(const TXTRecordRef *txtRecord);

/* Set by the JNI layer. `port` is in host byte order; `txt` is the
 * wire-format TXT record (length-prefixed "key=value" strings). */
typedef int (*mango_register_fn)(const char *type, const char *name, uint16_t port,
                                 const uint8_t *txt, uint16_t txt_len);
typedef void (*mango_unregister_fn)(const char *type);
void mango_dnssd_set_handlers(mango_register_fn on_register, mango_unregister_fn on_unregister);

#ifdef __cplusplus
}
#endif

#endif
