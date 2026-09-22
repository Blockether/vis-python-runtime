/* Test-only Winsock stage protocol. No claim of generic JailPolicy support. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <winsock2.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

static int cap_exchange(unsigned short port, int datagram, const char **stage) {
    SOCKET s;
    SOCKADDR_IN address = {0};
    DWORD timeout = 1200;
    u_long nonblocking = 1;
    int error = 0, length = sizeof(error), count;
    char answer = 0;
    *stage = "socket";
    s = socket(AF_INET, datagram ? SOCK_DGRAM : SOCK_STREAM, datagram ? IPPROTO_UDP : IPPROTO_TCP);
    if (s == INVALID_SOCKET) return WSAGetLastError();
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(port);
    *stage = "nonblocking";
    if (ioctlsocket(s, FIONBIO, &nonblocking)) { error = WSAGetLastError(); goto done; }
    *stage = "connect";
    if (connect(s, (SOCKADDR *)&address, sizeof(address))) {
        fd_set writable, exceptional;
        struct timeval timeout_value = {1, 200000};
        error = WSAGetLastError();
        if (error != WSAEWOULDBLOCK) goto done;
        FD_ZERO(&writable); FD_SET(s, &writable); FD_ZERO(&exceptional); FD_SET(s, &exceptional);
        *stage = "connect-select";
        count = select(0, NULL, &writable, &exceptional, &timeout_value);
        if (count <= 0) { error = count < 0 ? WSAGetLastError() : WSAETIMEDOUT; goto done; }
        *stage = "connect-status";
        if (getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&error, &length)) { error = WSAGetLastError(); goto done; }
        if (error) goto done;
    }
    nonblocking = 0; *stage = "io-setup";
    if (ioctlsocket(s, FIONBIO, &nonblocking) ||
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof(timeout)) ||
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char *)&timeout, sizeof(timeout))) { error = WSAGetLastError(); goto done; }
    *stage = "send"; count = send(s, "Q", 1, 0);
    if (count != 1) { error = count < 0 ? WSAGetLastError() : WSAECONNRESET; goto done; }
    *stage = "recv"; count = recv(s, &answer, 1, 0);
    if (count != 1) { error = count < 0 ? WSAGetLastError() : WSAECONNRESET; goto done; }
    if (answer != 'A') { *stage = "reply-bytes"; error = WSAEINVAL; goto done; }
    *stage = "complete";
 done:
    closesocket(s); return error;
}

static int cap_listener(int datagram, const char **stage) {
    SOCKADDR_IN address = {0};
    SOCKET s;
    int error = 0;
    *stage = "socket";
    s = socket(AF_INET, datagram ? SOCK_DGRAM : SOCK_STREAM, datagram ? IPPROTO_UDP : IPPROTO_TCP);
    if (s == INVALID_SOCKET) return WSAGetLastError();
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    *stage = "bind";
    if (bind(s, (SOCKADDR *)&address, sizeof(address))) { error = WSAGetLastError(); goto done; }
    if (!datagram) {
        *stage = "listen";
        if (listen(s, 1)) { error = WSAGetLastError(); goto done; }
    }
    *stage = "complete";
 done:
    closesocket(s); return error;
}

#ifndef VIS_CAP_HOST
int wmain(int argc, wchar_t **argv) {
    WSADATA data;
    unsigned long values[3];
    if (argc != 4) return 2;
    for (int i = 0; i < 3; ++i) {
        wchar_t *end = NULL;
        values[i] = wcstoul(argv[i + 1], &end, 10);
        if (!end || *end || !values[i] || values[i] > 65535) return 2;
    }
    if (WSAStartup(MAKEWORD(2, 2), &data)) return 2;
    printf("CAP_BEGIN %lu\n", values[0]);
    for (int i = 0; i < 6; ++i) {
        const char *stage;
        int error = i < 4 ? cap_exchange((unsigned short)values[1 + (i % 2)], i / 2, &stage) : cap_listener(i - 4, &stage);
        printf("CAP_RESULT %d %d %s\n", i, error, stage);
        fflush(stdout);
    }
    printf("CAP_END %lu\n", values[0]); fflush(stdout);
    WSACleanup(); return 0;
}
#endif
