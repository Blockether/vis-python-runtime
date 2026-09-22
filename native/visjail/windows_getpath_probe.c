/* Test-only CPython getpath/share-mode regression diagnostic.
 * cl /nologo /std:c11 /W4 /WX /O2 /MT windows_getpath_probe.c /link advapi32.lib
 * Usage: windows_getpath_probe.exe <absolute-visjail.dll> <absolute-python-home> <new-root>
 * Creates only new fixture files. Private context directories are retained for inspection.
 * Nonzero means a getpath operation or its positive/security control failed. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "visjail.h"

#define PATH_CAP 32768
static int failures;
static int check(int ok, const char *label) {
    printf("%s %s error=%lu\n", ok ? "PASS" : "FAIL", label, ok ? 0UL : GetLastError());
    fflush(stdout);
    if (!ok) failures++;
    return ok;
}

/* Exactly the exclusive, zero-desired-access open used by CPython getpath.c.
 * Compare volume translation and normalized/opened names independently. */
static int metadata(const wchar_t *path, DWORD share, DWORD flags, const char *label) {
    wchar_t full[PATH_CAP], final[PATH_CAP];
    DWORD count, error;
    HANDLE file;
    count = GetFullPathNameW(path, PATH_CAP, full, NULL);
    error = count && count < PATH_CAP ? ERROR_SUCCESS : GetLastError();
    printf("GETPATH %s full=%d error=%lu\n", label, count && count < PATH_CAP, error);
    if (!count || count >= PATH_CAP) return 0;
    file = CreateFileW(full, 0, share, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    error = file == INVALID_HANDLE_VALUE ? GetLastError() : ERROR_SUCCESS;
    printf("GETPATH %s open=%d error=%lu share=%lu\n", label, file != INVALID_HANDLE_VALUE, error, share);
    if (file == INVALID_HANDLE_VALUE) { SetLastError(error); return 0; }
    count = GetFinalPathNameByHandleW(file, final, PATH_CAP, flags);
    error = count && count < PATH_CAP ? ERROR_SUCCESS : GetLastError();
    printf("GETPATH %s final=%d error=%lu flags=%lu\n", label, count && count < PATH_CAP, error, flags);
    CloseHandle(file);
    SetLastError(error);
    return count && count < PATH_CAP;
}

/* A measured fallback candidate only: resolve the normalized handle name, then
 * prove any DOS mapping by reopening the same file identity. No token or ACL changes. */
static void mapping_probe(const wchar_t *path, const char *label) {
    wchar_t full[PATH_CAP], nt[PATH_CAP], devices[PATH_CAP], mapped[PATH_CAP];
    wchar_t drive[3] = {0};
    HANDLE file = INVALID_HANDLE_VALUE, reopened = INVALID_HANDLE_VALUE;
    BY_HANDLE_FILE_INFORMATION original = {0}, actual = {0};
    DWORD count, error;
    size_t device_length;
    int matched = 0, exact = 0;
    count = GetFullPathNameW(path, PATH_CAP, full, NULL);
    if (!count || count >= PATH_CAP || full[1] != L':') {
        printf("GETPATH_MAPPING %s stage=drive error=%lu\n", label, GetLastError()); return;
    }
    file = CreateFileW(full, 0, 0, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        printf("GETPATH_MAPPING %s stage=open error=%lu\n", label, GetLastError()); return;
    }
    count = GetFinalPathNameByHandleW(file, nt, PATH_CAP, VOLUME_NAME_NT);
    if (!count || count >= PATH_CAP || !GetFileInformationByHandle(file, &original)) {
        printf("GETPATH_MAPPING %s stage=normalized-nt error=%lu\n", label, GetLastError()); goto done;
    }
    drive[0] = full[0]; drive[1] = L':';
    count = QueryDosDeviceW(drive, devices, PATH_CAP);
    error = count ? ERROR_SUCCESS : GetLastError();
    printf("GETPATH_MAPPING %s query=%d error=%lu\n", label, count != 0, error);
    if (count) {
        device_length = wcslen(devices);
        matched = device_length && _wcsnicmp(nt, devices, device_length) == 0 &&
            (nt[device_length] == L'\\' || nt[device_length] == 0);
        if (matched && _snwprintf_s(mapped, PATH_CAP, _TRUNCATE, L"\\\\?\\%ls%ls", drive, nt + device_length) >= 0) {
            reopened = CreateFileW(mapped, 0, 0, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
            if (reopened != INVALID_HANDLE_VALUE && GetFileInformationByHandle(reopened, &actual)) {
                exact = original.dwVolumeSerialNumber == actual.dwVolumeSerialNumber &&
                    original.nFileIndexHigh == actual.nFileIndexHigh && original.nFileIndexLow == actual.nFileIndexLow;
            }
        }
        printf("GETPATH_MAPPING %s dos_matched=%d dos_reopened=%d dos_identity=%d error=%lu\n",
            label, matched, reopened != INVALID_HANDLE_VALUE, exact, exact ? 0UL : GetLastError());
        if (reopened != INVALID_HANDLE_VALUE) { CloseHandle(reopened); reopened = INVALID_HANDLE_VALUE; }
    }
    exact = 0;
    if (_snwprintf_s(mapped, PATH_CAP, _TRUNCATE, L"\\\\?\\GLOBALROOT%ls", nt) >= 0) {
        reopened = CreateFileW(mapped, 0, 0, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
        if (reopened != INVALID_HANDLE_VALUE && GetFileInformationByHandle(reopened, &actual)) {
            exact = original.dwVolumeSerialNumber == actual.dwVolumeSerialNumber &&
                original.nFileIndexHigh == actual.nFileIndexHigh && original.nFileIndexLow == actual.nFileIndexLow;
        }
        printf("GETPATH_MAPPING %s globalroot_reopened=%d globalroot_identity=%d error=%lu\n",
            label, reopened != INVALID_HANDLE_VALUE, exact, exact ? 0UL : GetLastError());
    }
 done:
    if (reopened != INVALID_HANDLE_VALUE) CloseHandle(reopened);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
}

static int sharing_controls(const wchar_t *path) {
    HANDLE file = INVALID_HANDLE_VALUE, pin = INVALID_HANDLE_VALUE, mutation = INVALID_HANDLE_VALUE;
    DWORD bytes = 0, error;
    const DWORD access[2] = {GENERIC_READ | WRITE_DAC, FILE_READ_ATTRIBUTES | WRITE_DAC};
    const char *names[2] = {"generic-read-pin", "attributes-pin"};
    int created = 0, before = failures;
    file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (!check(file != INVALID_HANDLE_VALUE, "create new metadata fixture")) goto done;
    created = 1;
    check(WriteFile(file, "getpath", 7, &bytes, NULL) && bytes == 7, "write metadata fixture");
    CloseHandle(file); file = INVALID_HANDLE_VALUE;
    check(metadata(path, 0, VOLUME_NAME_DOS, "unpinned"), "unpinned exclusive metadata control");
    for (int i = 0; i < 2; i++) {
        int ok;
        pin = CreateFileW(path, access[i], FILE_SHARE_READ, NULL, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
        if (!check(pin != INVALID_HANDLE_VALUE, names[i])) goto done;
        ok = metadata(path, 0, VOLUME_NAME_DOS, names[i]);
        error = GetLastError();
        check(ok, "zero-access metadata succeeds with pin");
        check(metadata(path, FILE_SHARE_READ, VOLUME_NAME_DOS, names[i]), "shared metadata control");
        mutation = CreateFileW(path, FILE_WRITE_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            NULL, OPEN_EXISTING, 0, NULL);
        error = GetLastError();
        check(mutation == INVALID_HANDLE_VALUE && error == ERROR_SHARING_VIOLATION, "pin still excludes data writes");
        if (mutation != INVALID_HANDLE_VALUE) CloseHandle(mutation);
        mutation = CreateFileW(path, DELETE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            NULL, OPEN_EXISTING, 0, NULL);
        error = GetLastError();
        check(mutation == INVALID_HANDLE_VALUE && error == ERROR_SHARING_VIOLATION, "pin still excludes delete access");
        if (mutation != INVALID_HANDLE_VALUE) CloseHandle(mutation);
        CloseHandle(pin); pin = INVALID_HANDLE_VALUE;
    }
 done:
    if (pin != INVALID_HANDLE_VALUE) CloseHandle(pin);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    if (created) check(DeleteFileW(path), "delete only new metadata fixture");
    return failures == before;
}

static int guest(const char *python) {
    wchar_t executable[PATH_CAP], target[PATH_CAP];
    HANDLE token = NULL;
    DWORD app_container = 0, size = 0, count;
    union { TOKEN_MANDATORY_LABEL align; BYTE bytes[4096]; } label;
    if (!check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "query actual guest token")) return 1;
    check(GetTokenInformation(token, TokenIsAppContainer, &app_container, sizeof(app_container), &size) &&
        app_container == 1, "guest is AppContainer");
    if (check(GetTokenInformation(token, TokenIntegrityLevel, label.bytes, sizeof(label.bytes), &size), "guest integrity label")) {
        PSID sid = ((TOKEN_MANDATORY_LABEL *)label.bytes)->Label.Sid;
        check(IsValidSid(sid) && *GetSidSubAuthorityCount(sid) &&
            *GetSidSubAuthority(sid, (DWORD)*GetSidSubAuthorityCount(sid) - 1) == SECURITY_MANDATORY_LOW_RID,
            "guest remains LOW integrity");
    }
    CloseHandle(token);
    count = GetModuleFileNameW(NULL, executable, PATH_CAP);
    if (!check(count && count < PATH_CAP && MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
        python, -1, target, PATH_CAP), "resolve guest executable and staged Python")) return 1;
    {
        const DWORD volumes[4] = {VOLUME_NAME_DOS, VOLUME_NAME_NT, VOLUME_NAME_GUID, VOLUME_NAME_NONE};
        const char *names[4] = {"dos", "nt", "guid", "none"};
        const wchar_t *paths[2] = {executable, target};
        const char *path_names[2] = {"self", "python"};
        for (int p = 0; p < 2; p++) {
            mapping_probe(paths[p], path_names[p]);
            for (int v = 0; v < 4; v++) {
                for (int opened = 0; opened < 2; opened++) {
                    char name[96];
                    int result;
                    (void)snprintf(name, sizeof(name), "guest-%s-%s-%s", path_names[p], names[v],
                        opened ? "opened" : "normalized");
                    result = metadata(paths[p], 0, volumes[v] | (opened ? FILE_NAME_OPENED : 0), name);
                    /* Alternative flags are measurements, not proof of a supported replacement. */
                    if (v == 0 && !opened) check(result, name);
                }
            }
        }
    }
    printf("GETPATH_GUEST_FAILURES=%d\n", failures);
    return failures ? 1 : 0;
}

struct Runtime {
    int (*create)(const char *, char *, int);
    int (*stage)(int, const char *, const char *, char *, int);
    int (*seal)(int, char *, int);
    int (*destroy)(int);
    int (*spawn)(const char *, int, const char *, int, const char *, const char *, int, int, int, int, int, int *, char *, int);
    int (*read)(int, void *, int);
    int (*close)(int);
    int (*poll)(int, int);
    int (*wait)(int, int, int *);
    int (*kill)(int, int);
};

static int load_runtime(HMODULE module, struct Runtime *runtime) {
#define LOAD(field, symbol) do { \
    FARPROC address = GetProcAddress(module, symbol); \
    if (!address || sizeof(address) != sizeof(runtime->field)) return 0; \
    memcpy(&runtime->field, &address, sizeof(address)); \
} while (0)
    LOAD(create, "visjail_windows_create"); LOAD(stage, "visjail_windows_stage");
    LOAD(seal, "visjail_windows_seal"); LOAD(destroy, "visjail_windows_destroy");
    LOAD(spawn, "visjail_spawn"); LOAD(read, "visjail_read"); LOAD(close, "visjail_close");
    LOAD(poll, "visjail_poll"); LOAD(wait, "visjail_wait"); LOAD(kill, "visjail_kill");
#undef LOAD
    return 1;
}

/* Output is bounded but never discarded: an overflow or unread stream is a failure.
 * Process teardown always precedes releasing the containing context. */
static int run(struct Runtime *runtime, const char **arguments, int count,
    const char *work, const char *profile, const char *marker) {
    char blob[8192], error[4096] = {0}, output[16384];
    int used = 0, child[4] = {0}, length = 0, exited = 0, complete = 0, code = -1, before = failures;
    ULONGLONG deadline;
    for (int i = 0; i < count; i++) {
        size_t size = strlen(arguments[i]) + 1;
        if (!check(size <= sizeof(blob) - (size_t)used, "bounded child arguments")) return 0;
        memcpy(blob + used, arguments[i], size); used += (int)size;
    }
    if (!check(runtime->spawn(blob, used, "", 1, work, profile, VISJAIL_CONFINED | VISJAIL_MERGE_STDERR,
        0, 0, 0, 0, child, error, sizeof(error)) == 0, "spawn actual WindowsJail child")) {
        printf("GETPATH_SPAWN_ERROR %s\n", error);
        return 0;
    }
    if (child[1] > 0) { (void)runtime->close(child[1]); child[1] = 0; }
    deadline = GetTickCount64() + 15000;
    while (GetTickCount64() < deadline && length + 1 < (int)sizeof(output)) {
        int ready = runtime->poll(child[2], 100);
        if (ready < 0) break;
        if (ready) {
            int got = runtime->read(child[2], output + length, (int)sizeof(output) - length - 1);
            if (got <= 0) { complete = got == 0; break; }
            length += got;
        }
    }
    output[length] = 0;
    printf("GETPATH_CHILD_OUTPUT_BEGIN\n%s\nGETPATH_CHILD_OUTPUT_END\n", output);
    check(complete && length + 1 < (int)sizeof(output) && GetTickCount64() < deadline, "bounded complete child output");
    while (GetTickCount64() < deadline) {
        int state = runtime->wait(child[0], 1, &code);
        if (state) { exited = state == 1; break; }
        Sleep(10);
    }
    check(exited && code == 0, "child exits successfully");
    check(strstr(output, marker) != NULL, "child completion marker");
    check(strstr(output, "Failed to find real location") == NULL, "CPython getpath warning absent");
    if (!exited) {
        check(runtime->kill(child[0], 9) == 0, "terminate incomplete child");
        deadline = GetTickCount64() + 3000;
        while (GetTickCount64() < deadline) {
            int state = runtime->wait(child[0], 1, &code);
            if (state) { exited = state == 1; break; }
            Sleep(10);
        }
        check(exited, "reap terminated child within bound");
    }
    for (int i = 1; i < 4; i++) if (child[i] > 0 && (i == 1 || child[i] != child[i - 1])) (void)runtime->close(child[i]);
    return failures == before;
}

int main(int argc, char **argv) {
    struct Runtime runtime = {0};
    HMODULE module = NULL;
    HANDLE pin = INVALID_HANDLE_VALUE;
    wchar_t library[PATH_CAP], self[PATH_CAP], fixture[PATH_CAP];
    char source[4096], context[1024], python[1200], probe[1200], work[1200], profile[64], error[4096] = {0};
    int id = 0;
    DWORD count;
    if (argc == 3 && !strcmp(argv[1], "--guest")) return guest(argv[2]);
    if (argc != 4 || strlen(argv[3]) > 900) return 2;
    for (int i = 1; i < 4; i++) if (strlen(argv[i]) < 3 || argv[i][1] != ':' ||
        (argv[i][2] != '\\' && argv[i][2] != '/')) return 2;
    for (const unsigned char *at = (const unsigned char *)argv[3]; *at; at++) if (*at >= 128) return 2;
    if (!check(CreateDirectoryA(argv[3], NULL), "create fresh diagnostic root")) return 1;
    sprintf_s(context, sizeof(context), "%s\\metadata.bin", argv[3]);
    if (!check(MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, context, -1, fixture, PATH_CAP), "fixture path")) goto done;
    (void)sharing_controls(fixture);
    sprintf_s(context, sizeof(context), "%s\\context", argv[3]);
    if (!check(CreateDirectoryA(context, NULL), "create fresh context root")) goto done;
    count = GetModuleFileNameW(NULL, self, PATH_CAP);
    if (!check(count && count < PATH_CAP && WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
        self, -1, source, sizeof(source), NULL, NULL) && MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
        argv[1], -1, library, PATH_CAP), "resolve exact binaries")) goto done;
    pin = CreateFileW(library, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (!check(pin != INVALID_HANDLE_VALUE, "pin exact runtime DLL")) goto done;
    module = LoadLibraryExW(library, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!check(module && load_runtime(module, &runtime), "load actual runtime ABI")) goto done;
    id = runtime.create(context, error, sizeof(error));
    if (!check(id > 0, "create real WindowsJail")) goto done;
    if (!check(runtime.stage(id, source, "getpath.exe", error, sizeof(error)) == 0 &&
        runtime.stage(id, argv[2], "python", error, sizeof(error)) == 0 &&
        runtime.seal(id, error, sizeof(error)) == 0, "stage and seal probe and stock Python")) goto done;
    sprintf_s(python, sizeof(python), "%s\\app\\python\\python.exe", context);
    sprintf_s(probe, sizeof(probe), "%s\\app\\getpath.exe", context);
    sprintf_s(work, sizeof(work), "%s\\work", context);
    sprintf_s(profile, sizeof(profile), "windows:%d", id);
    {
        const char *arguments[3] = {probe, "--guest", python};
        (void)run(&runtime, arguments, 3, work, profile, "GETPATH_GUEST_FAILURES=0");
    }
    {
        const char *arguments[5] = {python, "-I", "-S", "-c",
            "import encodings, sys, _ctypes; print('GETPATH_PYTHON_OK')"};
        (void)run(&runtime, arguments, 5, work, profile, "GETPATH_PYTHON_OK");
    }
 done:
    if (error[0]) printf("GETPATH_RUNTIME_DIAGNOSTIC %s\n", error);
    if (id > 0) check(runtime.destroy(id) == 0, "destroy context and reap every descendant");
    if (module) FreeLibrary(module);
    if (pin != INVALID_HANDLE_VALUE) CloseHandle(pin);
    printf("GETPATH_PROBE_FAILURES=%d\n", failures);
    return failures ? 1 : 0;
}
