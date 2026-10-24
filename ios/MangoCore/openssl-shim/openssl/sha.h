/* UxPlay includes <openssl/sha.h>; the iOS OpenSSL framework spells it
 * <OpenSSL/sha.h>. include_next skips this directory, which matters on
 * case-insensitive disks where both spellings name this file. */
#include_next <OpenSSL/sha.h>
