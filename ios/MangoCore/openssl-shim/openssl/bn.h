/* UxPlay includes <openssl/bn.h>; the iOS OpenSSL framework spells it
 * <OpenSSL/bn.h>. include_next skips this directory, which matters on
 * case-insensitive disks where both spellings name this file. */
#include_next <OpenSSL/bn.h>
