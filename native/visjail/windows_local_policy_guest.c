/* Test-only local-channel measurement, not a JailPolicy implementation.
 * The host includes the bounded exchange helpers; compile this file separately for LPAC. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <winsock2.h>
#include <windows.h>
#include <afunix.h>
#include <wincred.h>
#include <stdio.h>
#include <string.h>
#include <stddef.h>

static const BYTE fixture_blob[] = "Vis synthetic local credential control";

static int endpoint(const char *name, SOCKADDR_UN *address) {
    size_t length = strlen(name);
    if (!length || length >= sizeof(address->sun_path)) return 0;
    ZeroMemory(address, sizeof(*address));
    address->sun_family = AF_UNIX;
    memcpy(address->sun_path, name, length + 1);
    if (name[0] == '@') address->sun_path[0] = 0;
    return (int)(offsetof(SOCKADDR_UN, sun_path) + length + (name[0] == '@' ? 0 : 1));
}

/* Nonblocking connect bounds even an unreachable local endpoint. */
static int exchange(const char *name, int *error) {
    SOCKADDR_UN address;
    SOCKET client = INVALID_SOCKET;
    u_long nonblocking = 1;
    DWORD timeout = 1500;
    int length = endpoint(name, &address), ok = 0;
    char answer = 0;
    *error = WSAEINVAL;
    if (!length) return 0;
    client = socket(AF_UNIX, SOCK_STREAM, 0);
    if (client == INVALID_SOCKET) { *error = WSAGetLastError(); return 0; }
    if (ioctlsocket(client, FIONBIO, &nonblocking)) { *error = WSAGetLastError(); goto done; }
    if (connect(client, (SOCKADDR *)&address, length)) {
        fd_set writable, exceptional;
        struct timeval wait = {1, 500000};
        int pending = 0, bytes = sizeof(pending);
        *error = WSAGetLastError();
        if (*error != WSAEWOULDBLOCK) goto done;
        FD_ZERO(&writable); FD_ZERO(&exceptional);
        FD_SET(client, &writable); FD_SET(client, &exceptional);
        if (select(0, NULL, &writable, &exceptional, &wait) <= 0) { *error = WSAETIMEDOUT; goto done; }
        if (getsockopt(client, SOL_SOCKET, SO_ERROR, (char *)&pending, &bytes)) { *error = WSAGetLastError(); goto done; }
        if (pending) { *error = pending; goto done; }
    }
    nonblocking = 0;
    if (ioctlsocket(client, FIONBIO, &nonblocking) ||
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof(timeout)) ||
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, (const char *)&timeout, sizeof(timeout))) { *error = WSAGetLastError(); goto done; }
    if (send(client, "Q", 1, 0) != 1 || recv(client, &answer, 1, 0) != 1) { *error = WSAGetLastError(); goto done; }
    ok = answer == 'A';
    *error = ok ? 0 : WSAEINVAL;
 done:
    closesocket(client);
    return ok;
}

static int credential(const wchar_t *target, DWORD *error, int *exact) {
    PCREDENTIALW value = NULL;
    BOOL found = CredReadW(target, CRED_TYPE_GENERIC, 0, &value);
    *error = found ? ERROR_SUCCESS : GetLastError();
    *exact = found && value->CredentialBlobSize == sizeof(fixture_blob) &&
        !memcmp(value->CredentialBlob, fixture_blob, sizeof(fixture_blob));
    if (value) CredFree(value);
    return found != FALSE;
}

#ifndef VIS_LOCAL_HOST
#include <sddl.h>
#include <stdlib.h>
/* The Server 2022 token-information flag is not the effective LPAC boundary. */
static int effective_lpac(HANDLE token) {
    HANDLE impersonation = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    GENERIC_MAPPING mapping = {0};
    PRIVILEGE_SET initial = {0}, *privileges = &initial;
    DWORD size = sizeof(initial), granted = 0;
    BOOL allowed = FALSE, checked;
    int ok = 0;
    if (!DuplicateToken(token, SecurityImpersonation, &impersonation) ||
        !ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)",
            SDDL_REVISION_1, &descriptor, NULL)) goto done;
    checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
        privileges, &size, &granted, &allowed);
    if (!checked && GetLastError() == ERROR_INSUFFICIENT_BUFFER) {
        if (size > 65536) goto done;
        privileges = malloc(size);
        if (!privileges) goto done;
        checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
            privileges, &size, &granted, &allowed);
    }
    ok = checked && allowed && granted == 0x2;
 done:
    if (privileges != &initial) free(privileges);
    if (descriptor) LocalFree(descriptor);
    if (impersonation) CloseHandle(impersonation);
    return ok;
}

static int token_valid(void) {
    HANDLE token = NULL;
    union { TOKEN_MANDATORY_LABEL alignment; BYTE bytes[4096]; } buffer;
    DWORD value = 0, size = 0;
    int ok = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token)) return 0;
    if (!GetTokenInformation(token, TokenIsAppContainer, &value, sizeof(value), &size) || value != 1 ||
        !effective_lpac(token) ||
        !GetTokenInformation(token, TokenIntegrityLevel, buffer.bytes, sizeof(buffer.bytes), &size)) goto done;
    {
        PSID sid = ((TOKEN_MANDATORY_LABEL *)buffer.bytes)->Label.Sid;
        if (!IsValidSid(sid) || !*GetSidSubAuthorityCount(sid) ||
            *GetSidSubAuthority(sid, (DWORD)*GetSidSubAuthorityCount(sid) - 1) != SECURITY_MANDATORY_LOW_RID) goto done;
    }
    if (!GetTokenInformation(token, TokenAppContainerSid, buffer.bytes, sizeof(buffer.bytes), &size) ||
        !IsValidSid(((TOKEN_APPCONTAINER_INFORMATION *)buffer.bytes)->TokenAppContainer)) goto done;
    ok = 1;
 done:
    CloseHandle(token);
    return ok;
}

int main(int argc, char **argv) {
    WSADATA data;
    wchar_t target[128];
    DWORD error;
    int exact, found;
    if (argc != 6 || !token_valid()) return 2;
    puts("LOCAL_TOKEN_LPAC_LOW=1");
    if (WSAStartup(MAKEWORD(2, 2), &data)) return 3;
    for (int i = 1; i <= 4; i++) {
        int socket_error = 0, connected = exchange(argv[i], &socket_error);
        printf("LOCAL_SOCKET index=%d connected=%d error=%d\n", i - 1, connected, socket_error);
    }
    WSACleanup();
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, argv[5], -1, target, 128)) return 4;
    found = credential(target, &error, &exact);
    printf("LOCAL_CREDENTIAL found=%d exact=%d error=%lu\n", found, exact, error);
    return 0;
}
#endif
