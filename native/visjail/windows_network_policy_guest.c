/* Direct Winsock client for the package-scoped WFP prerequisite fixture. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <winsock2.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

static int exchange(unsigned short port, const char **stage) {
    SOCKET s;
    *stage = "socket";
    s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    SOCKADDR_IN address = {0};
    u_long nonblocking = 1;
    DWORD timeout = 1500;
    int error = 0, length = sizeof(error);
    char reply = 0;
    if (s == INVALID_SOCKET) return WSAGetLastError();
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    *stage = "nonblocking";
    if (ioctlsocket(s, FIONBIO, &nonblocking)) { error = WSAGetLastError(); goto done; }
    *stage = "connect";
    if (connect(s, (SOCKADDR *)&address, sizeof(address))) {
        fd_set writable, exceptional;
        struct timeval wait = {2, 0};
        error = WSAGetLastError();
        if (error != WSAEWOULDBLOCK) goto done;
        FD_ZERO(&writable); FD_SET(s, &writable);
        FD_ZERO(&exceptional); FD_SET(s, &exceptional);
        *stage = "connect-select";
        {
            int selected = select(0, NULL, &writable, &exceptional, &wait);
            if (selected <= 0) { error = selected == SOCKET_ERROR ? WSAGetLastError() : WSAETIMEDOUT; goto done; }
        }
        *stage = "connect-status";
        if (getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&error, &length)) { error = WSAGetLastError(); goto done; }
        if (error) goto done;
    }
    *stage = "io-setup";
    nonblocking = 0;
    if (ioctlsocket(s, FIONBIO, &nonblocking) ||
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout)) ||
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout))) {
        error = WSAGetLastError(); goto done;
    }
    {
        int count;
        *stage = "send";
        count = send(s, "Q", 1, 0);
        if (count != 1) { error = count == SOCKET_ERROR ? WSAGetLastError() : WSAECONNRESET; goto done; }
        *stage = "recv";
        count = recv(s, &reply, 1, 0);
        if (count != 1) error = count == SOCKET_ERROR ? WSAGetLastError() : WSAECONNRESET;
        else if (reply != 'A') { *stage = "reply-bytes"; error = WSAEINVAL; }
        else *stage = "complete";
    }
 done:
    closesocket(s);
    return error;
}

int wmain(int argc, wchar_t **argv) {
    WSADATA data;
    wchar_t *end = NULL;
    unsigned long port;
    int command;
    if (argc != 2) return 2;
    port = wcstoul(argv[1], &end, 10);
    if (!end || *end || !port || port > 65535 || WSAStartup(MAKEWORD(2, 2), &data)) return 2;
    puts("READY"); fflush(stdout);
    while ((command = getchar()) != EOF && command != 'X') {
        const char *stage;
        int error;
        if (command < '0' || command > '3') { WSACleanup(); return 2; }
        error = exchange((unsigned short)port, &stage);
        printf("RESULT %c %d %s\n", command, error, stage);
        fflush(stdout);
    }
    WSACleanup();
    return 0;
}
