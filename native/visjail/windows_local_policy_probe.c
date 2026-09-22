/* Test-only local-channel substrate evidence; no grants, hooks or WFP changes.
 * Usage: windows-local-policy-probe.exe <absolute-visjail.dll> <absolute-guest.exe> <short-empty-root>
 * Root must be ASCII and shorter than 64 bytes. Context workspaces remain after cleanup.
 * Only this invocation's random synthetic credential and socket files are removed. */
#define VIS_LOCAL_HOST
#include "windows_local_policy_guest.c"
#include <bcrypt.h>
#include <stdlib.h>
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
static DWORD __stdcall serve(void *argument) {
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

int main(int argc, char **argv) {
    HMODULE module = NULL;
    struct Runtime runtime = {0};
    struct Server servers[4];
    HANDLE threads[4] = {0}, process = NULL;
    int contexts[2] = {0}, child[4] = {0}, bound[4] = {0}, initialized = 0, credential_written = 0;
    char roots[2][128], names[4][128], executable[256], work[256], profile[64], blob[2048];
    char target[128], random_name[33], error_text[4096] = {0}, output[512];
    wchar_t library[32768], credential_target[128];
    BYTE random[16];
    WSADATA data;
    CREDENTIALW created = {0};
    DWORD credential_error = 0;
    int exact = 0, exit_code = 0, used = 0;
    int token_lines = 0, socket_lines = 0, credential_lines = 0;
    ZeroMemory(servers, sizeof(servers)); ZeroMemory(names, sizeof(names));
    for (int i = 0; i < 4; i++) servers[i].listener = INVALID_SOCKET;
    if (argc != 4 || strlen(argv[3]) >= 64 || strlen(argv[3]) < 3 || argv[3][1] != ':') return 2;
    for (const unsigned char *at = (const unsigned char *)argv[3]; *at; at++) if (*at >= 128) return 2;
    if (!check(BCryptGenRandom(NULL, random, sizeof(random), BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0, "random fixture identity")) goto done;
    for (int i = 0; i < 16; i++) sprintf_s(random_name + i * 2, sizeof(random_name) - (size_t)i * 2, "%02x", random[i]);
    sprintf_s(target, sizeof(target), "VisLocalPolicy-%s", random_name);
    if (!check(MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, target, -1, credential_target, 128) &&
        MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, argv[1], -1, library, 32768), "convert fixture identifiers")) goto done;
    module = LoadLibraryExW(library, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!check(module && load_runtime(module, &runtime), "load exact runtime ABI")) goto done;
    for (int i = 0; i < 2; i++) {
        sprintf_s(roots[i], sizeof(roots[i]), "%s\\context%d", argv[3], i);
        if (!check(CreateDirectoryA(roots[i], NULL), "create fresh context directory")) goto done;
        contexts[i] = runtime.create(roots[i], error_text, sizeof(error_text));
        if (!check(contexts[i] > 0, "create actual WindowsJail context")) goto done;
    }
    if (!check(runtime.stage(contexts[0], argv[2], "guest.exe", error_text, sizeof(error_text)) == 0 &&
               runtime.seal(contexts[0], error_text, sizeof(error_text)) == 0, "stage and seal guest")) goto done;
    if (!check(WSAStartup(MAKEWORD(2, 2), &data) == 0, "initialize local sockets")) goto done;
    initialized = 1;
    sprintf_s(names[0], sizeof(names[0]), "%s\\work\\local.sock", roots[0]);
    sprintf_s(names[1], sizeof(names[1]), "%s\\work\\local.sock", roots[1]);
    sprintf_s(names[2], sizeof(names[2]), "%s\\outside-%s.sock", argv[3], random_name);
    sprintf_s(names[3], sizeof(names[3]), "@vis-local-%s", random_name);
    for (int i = 0; i < 4; i++) {
        SOCKADDR_UN address;
        int length = endpoint(names[i], &address), socket_error = 0;
        if (!check(length > 0, "endpoint fits AF_UNIX address")) goto done;
        servers[i].listener = socket(AF_UNIX, SOCK_STREAM, 0);
        servers[i].stop = CreateEventW(NULL, TRUE, FALSE, NULL);
        if (!check(servers[i].listener != INVALID_SOCKET && servers[i].stop &&
            !bind(servers[i].listener, (SOCKADDR *)&address, length), "bind local control endpoint")) goto done;
        bound[i] = 1;
        if (!check(!listen(servers[i].listener, 8), "listen on local control endpoint")) goto done;
        threads[i] = CreateThread(NULL, 0, serve, &servers[i], 0, NULL);
        if (!check(threads[i] != NULL && exchange(names[i], &socket_error), "trusted exact-byte socket control")) {
            printf("LOCAL_CONTROL index=%d error=%d\n", i, socket_error);
            /* Keep this failure, but measure independent pathname/credential axes.
             * Abstract connect also fails on the trusted host in microsoft/WSL#4240. */
            if (i != 3) goto done;
        }
    }
    /* Fail on a collision rather than replace any existing credential. */
    if (!check(!credential(credential_target, &credential_error, &exact) && credential_error == ERROR_NOT_FOUND,
               "synthetic credential target does not exist")) goto done;
    created.Type = CRED_TYPE_GENERIC; created.TargetName = credential_target;
    created.CredentialBlob = (LPBYTE)fixture_blob; created.CredentialBlobSize = sizeof(fixture_blob);
    created.Persist = CRED_PERSIST_SESSION; created.UserName = L"Vis synthetic fixture";
    if (!check(CredWriteW(&created, 0), "write only synthetic session credential")) goto done;
    credential_written = 1;
    if (!check(credential(credential_target, &credential_error, &exact) && exact, "trusted exact-blob credential control")) goto done;
    sprintf_s(executable, sizeof(executable), "%s\\app\\guest.exe", roots[0]);
    sprintf_s(work, sizeof(work), "%s\\work", roots[0]);
    sprintf_s(profile, sizeof(profile), "windows:%d", contexts[0]);
    {
        const char *arguments[6] = {executable, names[0], names[1], names[2], names[3], target};
        for (int i = 0; i < 6; i++) {
            size_t length = strlen(arguments[i]) + 1;
            if (!check(length <= sizeof(blob) - (size_t)used, "bounded guest argument blob")) goto done;
            memcpy(blob + used, arguments[i], length); used += (int)length;
        }
    }
    if (!check(runtime.spawn(blob, used, "", 1, work, profile, VISJAIL_CONFINED | VISJAIL_MERGE_STDERR,
        0, 0, 0, 0, child, error_text, sizeof(error_text)) == 0, "spawn unchanged LPAC local-channel guest")) goto done;
    process = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD)runtime.pid(child[0]));
    if (!check(process != NULL, "retain real guest process handle")) goto done;
    /* Exactly six bounded lines; unexpected output is an error, not a silent pass. */
    for (int i = 0; i < 6; i++) {
        if (!check(line(&runtime, child[2], output, sizeof(output)), "read bounded guest measurement")) goto done;
        if (!strcmp(output, "LOCAL_TOKEN_LPAC_LOW=1")) token_lines++;
        else if (!strncmp(output, "LOCAL_SOCKET index=", 19)) socket_lines++;
        else if (!strncmp(output, "LOCAL_CREDENTIAL found=", 23)) credential_lines++;
        else { check(0, "unexpected guest output"); goto done; }
        puts(output);
    }
    check(token_lines == 1 && socket_lines == 4 && credential_lines == 1, "complete local-channel measurements");
    if (!check(WaitForSingleObject(process, 3000) == WAIT_OBJECT_0, "guest exits within bound")) goto done;
    check(runtime.wait(child[0], 1, &exit_code) == 1 && exit_code == 0, "guest succeeds without weakening token");
    for (int i = 0; i < 4; i++) {
        int socket_error = 0;
        check(exchange(names[i], &socket_error), "trusted endpoint remains live after guest attempt");
    }
    check(credential(credential_target, &credential_error, &exact) && exact, "synthetic credential unchanged after guest attempt");
 done:
    if (credential_written) {
        check(CredDeleteW(credential_target, CRED_TYPE_GENERIC, 0), "remove only own synthetic credential");
        check(!credential(credential_target, &credential_error, &exact) && credential_error == ERROR_NOT_FOUND,
              "synthetic credential absent after cleanup");
    }
    if (child[0]) for (int i = 1; i < 4; i++) if (child[i] > 0 && (i == 1 || child[i] != child[i - 1])) (void)runtime.close(child[i]);
    for (int i = 0; i < 2; i++) if (contexts[i] > 0) check(runtime.destroy(contexts[i]) == 0, "destroy context and reap descendants");
    if (process) { check(WaitForSingleObject(process, 3000) == WAIT_OBJECT_0, "guest no longer alive"); CloseHandle(process); }
    for (int i = 0; i < 4; i++) if (servers[i].stop) SetEvent(servers[i].stop);
    for (int i = 0; i < 4; i++) {
        if (threads[i]) {
            /* Keep the stack-backed server alive if the finite socket timeout unexpectedly fails. */
            if (!check(WaitForSingleObject(threads[i], 4000) == WAIT_OBJECT_0, "server exits within bound")) ExitProcess(1);
            CloseHandle(threads[i]);
        }
        if (servers[i].listener != INVALID_SOCKET) closesocket(servers[i].listener);
        if (servers[i].stop) CloseHandle(servers[i].stop);
        if (i < 3 && bound[i]) check(DeleteFileA(names[i]), "remove own socket file");
    }
    if (module) FreeLibrary(module);
    if (initialized) WSACleanup();
    printf("LOCAL_SUBSTRATE_FAILURES=%d; policy parity not tested\n", failures);
    return failures ? 1 : 0;
}
