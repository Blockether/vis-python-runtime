/* Test-only prerequisite, not a full network policy backend.
 * Run as an administrator with a fresh empty fixture directory:
 *   windows-network-policy-probe.exe <visjail.dll> <guest.exe> <empty-directory>
 * All endpoints are loopback; all temporary WFP grants match one package SID,
 * IPv4 loopback address, TCP protocol and exact remote port. No exemptions or
 * host firewall defaults are changed. Dynamic session close removes the grants.
 */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <winsock2.h>
#include <windows.h>
#include <initguid.h>
#include <fwpmu.h>
#include <userenv.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include "visjail.h"

static int failures;
static int check(int ok, const char *name) {
    printf("%s %s error=%lu\n", ok ? "PASS" : "FAIL", name, ok ? 0UL : GetLastError());
    fflush(stdout);
    if (!ok) failures++;
    return ok;
}

struct Runtime {
    int (*create)(const char *, char *, int);
    int (*stage)(int, const char *, const char *, char *, int);
    int (*seal)(int, char *, int);
    int (*destroy)(int);
    int (*spawn)(const char *, int, const char *, int, const char *, const char *, int, int, int, int, int, int *, char *, int);
    int (*read)(int, void *, int);
    int (*write)(int, const void *, int);
    int (*close)(int);
    int (*poll)(int, int);
    int (*wait)(int, int, int *);
    int (*pid)(int);
};

static int load_runtime(HMODULE module, struct Runtime *runtime) {
#define LOAD(field, symbol) do { \
    FARPROC address = GetProcAddress(module, symbol); \
    if (!address || sizeof(address) != sizeof(runtime->field)) return 0; \
    memcpy(&runtime->field, &address, sizeof(address)); \
} while (0)
    LOAD(create, "visjail_windows_create"); LOAD(stage, "visjail_windows_stage");
    LOAD(seal, "visjail_windows_seal"); LOAD(destroy, "visjail_windows_destroy");
    LOAD(spawn, "visjail_spawn"); LOAD(read, "visjail_read");
    LOAD(write, "visjail_write"); LOAD(close, "visjail_close");
    LOAD(poll, "visjail_poll"); LOAD(wait, "visjail_wait"); LOAD(pid, "visjail_windows_pid");
#undef LOAD
    return 1;
}

static int line(struct Runtime *runtime, int descriptor, char *text, int capacity) {
    ULONGLONG deadline = GetTickCount64() + 7000;
    int used = 0;
    while (GetTickCount64() < deadline && used + 1 < capacity) {
        char byte;
        int ready = runtime->poll(descriptor, 100);
        if (ready < 0) return 0;
        if (!ready) continue;
        if (runtime->read(descriptor, &byte, 1) != 1) return 0;
        if (byte == '\n') { text[used] = 0; return 1; }
        if (byte != '\r') text[used++] = byte;
    }
    return 0;
}

struct Server { SOCKET listener; HANDLE stop; };
static DWORD WINAPI serve(void *argument) {
    struct Server *server = argument;
    while (WaitForSingleObject(server->stop, 0) == WAIT_TIMEOUT) {
        fd_set readable;
        struct timeval wait = {0, 100000};
        SOCKET client;
        DWORD timeout = 1000;
        char byte = 0;
        FD_ZERO(&readable); FD_SET(server->listener, &readable);
        if (select(0, &readable, NULL, NULL, &wait) <= 0) continue;
        client = accept(server->listener, NULL, NULL);
        if (client == INVALID_SOCKET) continue;
        if (!setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout)) &&
            !setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout)) &&
            recv(client, &byte, 1, 0) == 1 && byte == 'Q') (void)send(client, "A", 1, 0);
        closesocket(client);
    }
    return 0;
}

static int host_control(unsigned short port) {
    SOCKADDR_IN address = {0};
    SOCKET socket_handle = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    DWORD timeout = 1500;
    u_long nonblocking = 1;
    char reply = 0;
    int ok = 0, socket_error = 0, size = sizeof(socket_error);
    if (socket_handle == INVALID_SOCKET) return 0;
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(port);
    if (ioctlsocket(socket_handle, FIONBIO, &nonblocking)) goto done;
    if (connect(socket_handle, (SOCKADDR *)&address, sizeof(address))) {
        fd_set writable, exceptional;
        struct timeval wait = {2, 0};
        if (WSAGetLastError() != WSAEWOULDBLOCK) goto done;
        FD_ZERO(&writable); FD_SET(socket_handle, &writable);
        FD_ZERO(&exceptional); FD_SET(socket_handle, &exceptional);
        if (select(0, NULL, &writable, &exceptional, &wait) <= 0 ||
            getsockopt(socket_handle, SOL_SOCKET, SO_ERROR, (char *)&socket_error, &size) || socket_error) goto done;
    }
    nonblocking = 0;
    if (!ioctlsocket(socket_handle, FIONBIO, &nonblocking) &&
        !setsockopt(socket_handle, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout)) &&
        !setsockopt(socket_handle, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout)) &&
        send(socket_handle, "Q", 1, 0) == 1 && recv(socket_handle, &reply, 1, 0) == 1 && reply == 'A') ok = 1;
 done:
    closesocket(socket_handle);
    return ok;
}

/* Match the suite's effective access matrix, not an optional token-information flag. */
static int effective_lpac(HANDLE token) {
    HANDLE impersonation = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    GENERIC_MAPPING mapping = {0};
    PRIVILEGE_SET initial = {0}, *privileges = &initial;
    DWORD size = sizeof(initial), granted = 0, saved;
    BOOL allowed = FALSE, checked;
    int ok = 0;
    if (!check(DuplicateToken(token, SecurityImpersonation, &impersonation),
               "duplicate token for effective LPAC check")) goto done;
    if (!check(ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)",
            SDDL_REVISION_1, &descriptor, NULL), "effective LPAC descriptor")) goto done;
    checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
                          privileges, &size, &granted, &allowed);
    if (!checked && GetLastError() == ERROR_INSUFFICIENT_BUFFER) {
        if (size > 65536) { SetLastError(ERROR_INSUFFICIENT_BUFFER); goto done; }
        privileges = malloc(size);
        if (!privileges) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto done; }
        checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
                              privileges, &size, &granted, &allowed);
    }
    if (!check(checked, "kernel effective LPAC AccessCheck")) goto done;
    printf("LPAC_ACCESS=%lu allowed=%d\n", granted, allowed);
    if (!allowed || granted != 0x2) { SetLastError(ERROR_ACCESS_DENIED); goto done; }
    ok = 1;
 done:
    saved = GetLastError();
    if (privileges != &initial) free(privileges);
    if (descriptor) LocalFree(descriptor);
    if (impersonation) CloseHandle(impersonation);
    SetLastError(saved);
    return ok;
}

static int inspect_token(HANDLE process, PSID *package) {
    HANDLE token = NULL;
    union { TOKEN_GROUPS alignment; unsigned char bytes[4096]; } data;
    PSID *groups = NULL, *capabilities = NULL;
    DWORD size = 0, value = 0, group_count = 0, capability_count = 0;
    int ok = 0;
    if (!check(OpenProcessToken(process, TOKEN_QUERY | TOKEN_DUPLICATE, &token), "open child token")) return 0;
    if (!check(GetTokenInformation(token, TokenIsAppContainer, &value, sizeof(value), &size),
               "query TokenIsAppContainer")) goto done;
    if (value != 1) { SetLastError(ERROR_ACCESS_DENIED); goto done; }
    /* Diagnostic only: effective access below is the required LPAC proof. */
    SetLastError(ERROR_SUCCESS);
    if (!GetTokenInformation(token, TokenIsLessPrivilegedAppContainer, &value, sizeof(value), &size))
        printf("TOKEN_INFORMATION class=TokenIsLessPrivilegedAppContainer error=%lu\n", GetLastError());
    else printf("TOKEN_INFORMATION class=TokenIsLessPrivilegedAppContainer value=%lu\n", value);
    if (!effective_lpac(token)) goto done;
    if (!check(GetTokenInformation(token, TokenIntegrityLevel, data.bytes, sizeof(data.bytes), &size),
               "query TokenIntegrityLevel")) goto done;
    {
        PSID integrity = ((TOKEN_MANDATORY_LABEL *)data.bytes)->Label.Sid;
        if (!IsValidSid(integrity) || *GetSidSubAuthority(integrity, (DWORD)*GetSidSubAuthorityCount(integrity) - 1) != SECURITY_MANDATORY_LOW_RID) goto done;
    }
    if (!DeriveCapabilitySidsFromName(L"registryRead", &groups, &group_count, &capabilities, &capability_count) ||
        capability_count != 1 || !GetTokenInformation(token, TokenCapabilities, data.bytes, sizeof(data.bytes), &size)) goto done;
    {
        TOKEN_GROUPS *actual = (TOKEN_GROUPS *)data.bytes;
        if (actual->GroupCount != 1 || actual->Groups[0].Attributes != SE_GROUP_ENABLED ||
            !EqualSid(actual->Groups[0].Sid, capabilities[0])) goto done;
    }
    if (!GetTokenInformation(token, TokenAppContainerSid, data.bytes, sizeof(data.bytes), &size)) goto done;
    {
        PSID sid = ((TOKEN_APPCONTAINER_INFORMATION *)data.bytes)->TokenAppContainer;
        if (!IsValidSid(sid)) goto done;
        size = GetLengthSid(sid);
        *package = malloc(size);
        if (!*package || !CopySid(size, *package, sid)) goto done;
    }
    ok = 1;
 done:
    if (groups) { for (DWORD i = 0; i < group_count; i++) LocalFree(groups[i]); LocalFree(groups); }
    if (capabilities) { for (DWORD i = 0; i < capability_count; i++) LocalFree(capabilities[i]); LocalFree(capabilities); }
    CloseHandle(token);
    return ok;
}

static DWORD permit(HANDLE engine, const GUID *sublayer, PSID package, unsigned short port, int hard, UINT64 *id) {
    FWPM_FILTER0 filter = {0};
    FWPM_FILTER_CONDITION0 conditions[4] = {0};
    DWORD error;
    filter.displayData.name = L"Vis test-only exact package loopback permit";
    filter.layerKey = FWPM_LAYER_ALE_AUTH_CONNECT_V4;
    filter.subLayerKey = *sublayer;
    filter.weight.type = FWP_UINT8; filter.weight.uint8 = 15;
    filter.action.type = FWP_ACTION_PERMIT;
    if (hard) filter.flags = FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT;
    conditions[0].fieldKey = FWPM_CONDITION_ALE_PACKAGE_ID;
    conditions[0].matchType = FWP_MATCH_EQUAL;
    conditions[0].conditionValue.type = FWP_SID; conditions[0].conditionValue.sid = package;
    conditions[1].fieldKey = FWPM_CONDITION_IP_REMOTE_ADDRESS;
    conditions[1].matchType = FWP_MATCH_EQUAL;
    conditions[1].conditionValue.type = FWP_UINT32; conditions[1].conditionValue.uint32 = INADDR_LOOPBACK;
    conditions[2].fieldKey = FWPM_CONDITION_IP_REMOTE_PORT;
    conditions[2].matchType = FWP_MATCH_EQUAL;
    conditions[2].conditionValue.type = FWP_UINT16; conditions[2].conditionValue.uint16 = port;
    conditions[3].fieldKey = FWPM_CONDITION_IP_PROTOCOL;
    conditions[3].matchType = FWP_MATCH_EQUAL;
    conditions[3].conditionValue.type = FWP_UINT8; conditions[3].conditionValue.uint8 = IPPROTO_TCP;
    filter.numFilterConditions = 4; filter.filterCondition = conditions;
    error = FwpmTransactionBegin0(engine, 0);
    if (error) return error;
    error = FwpmFilterAdd0(engine, &filter, NULL, id);
    if (error) { (void)FwpmTransactionAbort0(engine); return error; }
    return FwpmTransactionCommit0(engine);
}

static int attempt(struct Runtime *runtime, int *child, char phase, int *socket_error) {
    char output[128], stage[32], actual_phase = 0, extra = 0;
    if (runtime->write(child[1], &phase, 1) != 1 || !line(runtime, child[2], output, sizeof(output))) return 0;
    printf("%s\n", output); fflush(stdout);
    if (sscanf_s(output, "RESULT %c %d %31s%c", &actual_phase, 1u, socket_error,
                 stage, (unsigned)sizeof(stage), &extra, 1u) != 3 || actual_phase != phase) return 0;
    if (!*socket_error) return !strcmp(stage, "complete");
    return !strcmp(stage, "socket") || !strcmp(stage, "nonblocking") ||
        !strcmp(stage, "connect") || !strcmp(stage, "connect-select") ||
        !strcmp(stage, "connect-status") || !strcmp(stage, "io-setup") ||
        !strcmp(stage, "send") || !strcmp(stage, "recv") || !strcmp(stage, "reply-bytes");
}

int wmain(int argc, wchar_t **argv) {
    HMODULE module = NULL;
    struct Runtime runtime = {0};
    struct Server server = {INVALID_SOCKET, NULL};
    HANDLE thread = NULL, process = NULL, engine = NULL;
    PSID package = NULL;
    WSADATA winsock;
    SOCKADDR_IN address = {0};
    FWPM_SESSION0 session = {0};
    FWPM_SUBLAYER0 sublayer = {0};
    int address_size = sizeof(address), context = 0, child[4] = {0}, initialized = 0;
    int socket_error = -1, soft_error = -1, hard_error = -1, exit_code = -1;
    UINT64 filter_id = 0;
    char directory[4096], source[4096], program[4096], cwd[4096], arguments[4200], profile[64], error[1024] = {0}, output[128];
    char environment[1200] = "SystemRoot=";
    wchar_t windows[MAX_PATH + 1];
    UINT windows_length;
    unsigned short port = 0;
    DWORD status;
    if (argc != 4) { fprintf(stderr, "usage: probe <absolute-visjail.dll> <absolute-guest.exe> <empty-directory>\n"); return 2; }
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[3], -1, directory, sizeof(directory), NULL, NULL) ||
        !WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[2], -1, source, sizeof(source), NULL, NULL)) return 2;
    windows_length = GetWindowsDirectoryW(windows, MAX_PATH + 1);
    if (!windows_length || windows_length > MAX_PATH ||
        !WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, windows, -1, environment + 11,
                            (int)sizeof(environment) - 11, NULL, NULL)) return 2;
    if (WSAStartup(MAKEWORD(2, 2), &winsock)) return 2;
    initialized = 1;
    server.stop = CreateEventW(NULL, TRUE, FALSE, NULL);
    server.listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (!check(server.stop && server.listener != INVALID_SOCKET &&
            !bind(server.listener, (SOCKADDR *)&address, sizeof(address)) &&
            !listen(server.listener, 4) && !getsockname(server.listener, (SOCKADDR *)&address, &address_size), "bounded loopback server")) goto done;
    port = ntohs(address.sin_port);
    thread = CreateThread(NULL, 0, serve, &server, 0, NULL);
    if (!check(thread != NULL && host_control(port), "host exact-byte positive control")) goto done;
    module = LoadLibraryExW(argv[1], NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!check(module && load_runtime(module, &runtime), "load actual jail ABI")) goto done;
    context = runtime.create(directory, error, sizeof(error));
    if (!check(context > 0, "create actual LPAC context")) { fprintf(stderr, "%s\n", error); goto done; }
    if (!check(runtime.stage(context, source, "network-guest.exe", error, sizeof(error)) == 0 &&
        runtime.seal(context, error, sizeof(error)) == 0, "stage and seal direct Winsock guest")) { fprintf(stderr, "%s\n", error); goto done; }
    if (!check(sprintf_s(program, sizeof(program), "%s\\app\\network-guest.exe", directory) >= 0 &&
        sprintf_s(cwd, sizeof(cwd), "%s\\work", directory) >= 0 &&
        sprintf_s(profile, sizeof(profile), "windows:%d", context) >= 0, "bounded fixture paths")) goto done;
    {
        size_t first = strlen(program) + 1;
        memcpy(arguments, program, first);
        if (!check(sprintf_s(arguments + first, sizeof(arguments) - first, "%hu", port) >= 0,
                   "bounded guest port argument")) goto done;
        if (!check(runtime.spawn(arguments, (int)(first + strlen(arguments + first) + 1), environment,
            (int)strlen(environment) + 1, cwd, profile, VISJAIL_CONFINED | VISJAIL_MERGE_STDERR, 0, 0, 0, 0,
            child, error, sizeof(error)) == 0, "spawn actual LPAC client")) { fprintf(stderr, "%s\n", error); goto done; }
    }
    process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, (DWORD)runtime.pid(child[0]));
    if (!check(process && inspect_token(process, &package), "actual LOW LPAC with only registryRead capability") ||
        !check(line(&runtime, child[2], output, sizeof(output)) && !strcmp(output, "READY"), "guest ready before grant")) goto done;
    if (!check(attempt(&runtime, child, '0', &socket_error) && socket_error == WSAEACCES, "baseline egress denied by kernel")) goto done;
    session.flags = FWPM_SESSION_FLAG_DYNAMIC;
    status = FwpmEngineOpen0(NULL, RPC_C_AUTHN_WINNT, NULL, &session, &engine);
    if (!check(status == ERROR_SUCCESS, "open dynamic WFP session")) { printf("WFP_ERROR=%lu\n", status); goto done; }
    if (!check(UuidCreate(&sublayer.subLayerKey) == RPC_S_OK, "unique test sublayer")) goto done;
    sublayer.displayData.name = L"Vis test-only package network prerequisite"; sublayer.weight = 0xffff;
    status = FwpmSubLayerAdd0(engine, &sublayer, NULL);
    if (!check(status == ERROR_SUCCESS, "add dynamic test sublayer")) { printf("WFP_ERROR=%lu\n", status); goto done; }
    status = permit(engine, &sublayer.subLayerKey, package, port, 0, &filter_id);
    if (!check(status == ERROR_SUCCESS, "add exact package soft permit")) { printf("WFP_ERROR=%lu\n", status); goto done; }
    if (!check(attempt(&runtime, child, '1', &soft_error), "soft permit attempt completed")) goto done;
    if (!check(FwpmFilterDeleteById0(engine, filter_id) == ERROR_SUCCESS, "remove soft permit")) goto done;
    filter_id = 0;
    status = permit(engine, &sublayer.subLayerKey, package, port, 1, &filter_id);
    if (!check(status == ERROR_SUCCESS, "add exact package hard permit")) { printf("WFP_ERROR=%lu\n", status); goto done; }
    if (!check(attempt(&runtime, child, '2', &hard_error), "hard permit attempt completed")) goto done;
    if (!check(FwpmFilterDeleteById0(engine, filter_id) == ERROR_SUCCESS, "revoke exact package permit")) goto done;
    filter_id = 0;
    if (!check(attempt(&runtime, child, '3', &socket_error) && socket_error == WSAEACCES, "revoked egress denied by kernel") ||
        !check(host_control(port), "host control remains reachable")) goto done;
    printf("WFP_PREREQUISITE soft_error=%d hard_error=%d\n", soft_error, hard_error);
    check(hard_error == 0, "exact loopback grant is usable by unchanged LPAC token");
    if (hard_error == WSAEACCES) puts("PREREQUISITE_BLOCKED: exact WFP permit does not admit current LPAC loopback connection");
 done:
    if (child[0] && runtime.write) (void)runtime.write(child[1], "X", 1);
    if (process) check(WaitForSingleObject(process, 3000) == WAIT_OBJECT_0, "guest exits within bound");
    if (child[0] && runtime.wait && process && WaitForSingleObject(process, 0) == WAIT_OBJECT_0)
        check(runtime.wait(child[0], 1, &exit_code) == 1 && exit_code == 0, "guest exit status");
    if (context > 0) check(runtime.destroy(context) == 0, "destroy context and reap descendants");
    if (child[0]) for (int i = 1; i < 4; i++) if (child[i] && (i == 1 || child[i] != child[i - 1])) (void)runtime.close(child[i]);
    if (engine) check(FwpmEngineClose0(engine) == ERROR_SUCCESS, "close dynamic WFP session and remove all grants");
    if (process) CloseHandle(process);
    free(package);
    if (server.stop) SetEvent(server.stop);
    if (thread) { check(WaitForSingleObject(thread, 3000) == WAIT_OBJECT_0, "server thread exits within bound"); CloseHandle(thread); }
    if (server.listener != INVALID_SOCKET) closesocket(server.listener);
    if (server.stop) CloseHandle(server.stop);
    if (module) FreeLibrary(module);
    if (initialized) WSACleanup();
    printf("NETWORK_PREREQUISITE_FAILURES=%d\n", failures);
    return failures ? 1 : 0;
}
