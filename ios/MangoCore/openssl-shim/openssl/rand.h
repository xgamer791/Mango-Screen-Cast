/* UxPlay includes <openssl/rand.h>; the iOS OpenSSL framework spells it
 * <OpenSSL/rand.h>. include_next skips this directory, which matters on
 * case-insensitive disks where both spellings name this file. */
#include_next <OpenSSL/rand.h>
