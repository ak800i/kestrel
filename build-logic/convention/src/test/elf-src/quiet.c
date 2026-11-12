/* Test fixture for ElfReaderTest: a library that imports no networking functions. */
#include <string.h>

size_t kestrel_length(const char *text) {
    return strlen(text);
}
