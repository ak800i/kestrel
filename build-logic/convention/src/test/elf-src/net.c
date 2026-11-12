/* Test fixture for ElfReaderTest: a library that imports networking functions. */
#include <netdb.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int kestrel_open(const char *host) {
    struct addrinfo hints;
    struct addrinfo *result = 0;
    memset(&hints, 0, sizeof hints);
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, "80", &hints, &result) != 0) return -1;
    int fd = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
    if (fd >= 0 && connect(fd, result->ai_addr, result->ai_addrlen) != 0) {
        close(fd);
        fd = -1;
    }
    freeaddrinfo(result);
    return fd;
}
