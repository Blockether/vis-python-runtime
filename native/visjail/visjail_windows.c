/* Windows private-workspace jail. All handles stay native; public IDs never repeat. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define COBJMACROS
#include <windows.h>
#include <userenv.h>
#include <aclapi.h>
#include <sddl.h>
#include <bcrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <limits.h>
#include "visjail.h"

#ifndef PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY
#define PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY 0x0002000f
#endif
#ifndef PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT
#define PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT 1
#endif

/* The host serializes lifecycle operations. Blocking stream operations retain a
 * reference independently, so close can cancel IO without recycling a HANDLE. */
static SRWLOCK lock = SRWLOCK_INIT;
static int next_id = 1;
typedef struct Pin { HANDLE handle; struct Pin *next; } Pin;
typedef struct Context {
    int id, sealed, poisoned;
    wchar_t *path;
    wchar_t profile[80];
    PSID sid;
    HANDLE job;
    Pin *pins;
    struct Context *next;
} Context;
typedef struct Stream {
    int id, context, refs, closed;
    HANDLE read, write, cancel;
    SRWLOCK reader;
    OVERLAPPED peek;
    unsigned char byte;
    int pending, cached, eof;
    struct Stream *next;
} Stream;
typedef struct Process {
    int id, context, reaped, waited;
    HANDLE process, job, closer;
    HPCON console;
    DWORD pid, code;
    struct Process *next;
} Process;
static Context *contexts;
static Stream *streams;
static Process *processes;

static int failure(char *error, int capacity, const char *operation) {
    DWORD code = GetLastError();
    if (!code) code = ERROR_INVALID_PARAMETER;
    if (error && capacity > 0)
        _snprintf_s(error, (size_t)capacity, _TRUNCATE, "%s (Windows error %lu)", operation, code);
    return -(int)(code <= INT_MAX ? code : ERROR_GEN_FAILURE);
}

static int allocate_id(void) {
    if (next_id == INT_MAX) { SetLastError(ERROR_TOO_MANY_OPEN_FILES); return 0; }
    return next_id++;
}

static wchar_t *wide(const char *text) {
    int count;
    wchar_t *answer;
    if (!text) { SetLastError(ERROR_INVALID_PARAMETER); return NULL; }
    count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, NULL, 0);
    if (!count) return NULL;
    answer = calloc((size_t)count, sizeof(wchar_t));
    if (!answer) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, answer, count)) {
        free(answer); return NULL;
    }
    return answer;
}

static wchar_t *join(const wchar_t *parent, const wchar_t *name) {
    size_t a = wcslen(parent), b = wcslen(name);
    wchar_t *answer = calloc(a + b + 2, sizeof(wchar_t));
    if (!answer) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    memcpy(answer, parent, a * sizeof(wchar_t));
    answer[a] = L'\\';
    memcpy(answer + a + 1, name, (b + 1) * sizeof(wchar_t));
    return answer;
}

/* Reject Win32 aliases before opening; do not normalize a forbidden spelling. */
static int component(const wchar_t *name, size_t length) {
    wchar_t base[16];
    size_t i, n = 0;
    if (!length || name[length - 1] == L'.' || name[length - 1] == L' ') return 0;
    for (i = 0; i < length; i++) {
        wchar_t c = name[i];
        if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) return 0;
        if (c == L'.') break;
        if (n < 15) base[n++] = c;
    }
    base[n] = 0;
    if (!_wcsicmp(base, L"CON") || !_wcsicmp(base, L"PRN") ||
        !_wcsicmp(base, L"AUX") || !_wcsicmp(base, L"NUL") ||
        !_wcsicmp(base, L"CONIN$") || !_wcsicmp(base, L"CONOUT$")) return 0;
    if (n == 4 && (!_wcsnicmp(base, L"COM", 3) || !_wcsnicmp(base, L"LPT", 3)) &&
        ((base[3] >= L'0' && base[3] <= L'9') || base[3] == 0xb9 || base[3] == 0xb2 || base[3] == 0xb3)) return 0;
    for (; i < length; i++)
        if (name[i] < 32 || wcschr(L"<>:\"/\\|?*", name[i])) return 0;
    return 1;
}

static int relative_path(const wchar_t *path) {
    const wchar_t *at = path, *end;
    if (!path || !*path) return 0;
    do {
        end = wcschr(at, L'\\');
        if (!component(at, end ? (size_t)(end - at) : wcslen(at))) return 0;
        if (!end) return 1;
        at = end + 1;
    } while (*at);
    return 0;
}

static int local_path(wchar_t *path) {
    wchar_t drive[4];
    size_t i;
    if (!path || wcslen(path) < 4) return 0;
    for (i = 0; path[i]; i++) if (path[i] == L'/') path[i] = L'\\';
    if (!((path[0] >= L'A' && path[0] <= L'Z') || (path[0] >= L'a' && path[0] <= L'z')) ||
        path[1] != L':' || path[2] != L'\\' || !relative_path(path + 3)) return 0;
    memcpy(drive, path, 3 * sizeof(wchar_t)); drive[3] = 0;
    return GetDriveTypeW(drive) == DRIVE_FIXED;
}

static void unpin(Pin *pins) {
    while (pins) { Pin *next = pins->next; CloseHandle(pins->handle); free(pins); pins = next; }
}

static int pin_handle(Pin **pins, HANDLE handle) {
    Pin *entry = calloc(1, sizeof(*entry));
    if (!entry) { CloseHandle(handle); SetLastError(ERROR_NOT_ENOUGH_MEMORY); return 0; }
    entry->handle = handle; entry->next = *pins; *pins = entry; return 1;
}

static HANDLE regular(const wchar_t *path, DWORD access, DWORD share, int directory) {
    BY_HANDLE_FILE_INFORMATION info;
    HANDLE handle = CreateFileW(path, access, share, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (handle == INVALID_HANDLE_VALUE) return handle;
    if (!GetFileInformationByHandle(handle, &info) ||
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) ||
        (!!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != (directory > 0)) ||
        (!directory && info.nNumberOfLinks != 1) || GetFileType(handle) != FILE_TYPE_DISK) {
        CloseHandle(handle); SetLastError(ERROR_ACCESS_DENIED); return INVALID_HANDLE_VALUE;
    }
    return handle;
}

/* Pin every ancestor: an attacker cannot substitute a junction between checks. */
static int ancestors(wchar_t *path, Pin **pins) {
    size_t i;
    for (i = 3; path[i]; i++) if (path[i] == L'\\') {
        HANDLE handle;
        path[i] = 0;
        handle = regular(path, FILE_READ_ATTRIBUTES, FILE_SHARE_READ, 1);
        path[i] = L'\\';
        if (handle == INVALID_HANDLE_VALUE || !pin_handle(pins, handle)) return 0;
    }
    return 1;
}

static Context *context_get(int id) {
    Context *c;
    for (c = contexts; c; c = c->next) if (c->id == id) return c;
    SetLastError(ERROR_INVALID_HANDLE); return NULL;
}

/* Explicit protected ACLs never import source permissions. Owner Rights removes
 * implicit owner WRITE_DAC for guest-created descendants. The host user's token
 * still has its explicit grant, intersected with package rights in the guest. */
static int permissions(HANDLE handle, PSID package, DWORD rights, int directory) {
    HANDLE token = NULL;
    TOKEN_USER *user = NULL;
    DWORD size = 0, result = ERROR_NOT_ENOUGH_MEMORY;
    PSID owner_rights = NULL;
    EXPLICIT_ACCESSW entries[3];
    PACL acl = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return 0;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    user = malloc(size);
    if (!user || !GetTokenInformation(token, TokenUser, user, size, &size) ||
        !ConvertStringSidToSidW(L"S-1-3-4", &owner_rights)) goto done;
    ZeroMemory(entries, sizeof(entries));
    entries[0].grfAccessPermissions = FILE_ALL_ACCESS;
    entries[0].grfAccessMode = SET_ACCESS;
    entries[0].grfInheritance = directory ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : NO_INHERITANCE;
    entries[0].Trustee.TrusteeForm = TRUSTEE_IS_SID;
    entries[0].Trustee.ptstrName = (LPWSTR)user->User.Sid;
    entries[1] = entries[0];
    entries[1].grfAccessPermissions = rights;
    entries[1].Trustee.ptstrName = (LPWSTR)package;
    entries[2] = entries[0];
    entries[2].grfAccessPermissions = READ_CONTROL;
    entries[2].Trustee.ptstrName = (LPWSTR)owner_rights;
    result = SetEntriesInAclW(3, entries, NULL, &acl);
    if (result == ERROR_SUCCESS)
        result = SetSecurityInfo(handle, SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            NULL, NULL, acl, NULL);
done:
    if (acl) LocalFree(acl);
    if (owner_rights) LocalFree(owner_rights);
    free(user); CloseHandle(token); SetLastError(result);
    return result == ERROR_SUCCESS;
}

/* MIC checks precede DACLs: a low-integrity guest needs a low writable directory.
 * LABEL_SECURITY_INFORMATION needs WRITE_OWNER, not SeSecurityPrivilege. */
static int low_integrity(HANDLE handle) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PACL sacl = NULL;
    BOOL present = FALSE, defaulted = FALSE;
    DWORD result;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"S:(ML;OICI;NW;;;LW)", SDDL_REVISION_1, &descriptor, NULL)) return 0;
    if (!GetSecurityDescriptorSacl(descriptor, &present, &sacl, &defaulted))
        result = GetLastError();
    else if (!present || !sacl)
        result = ERROR_INVALID_SECURITY_DESCR;
    else
        result = SetSecurityInfo(handle, SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION,
            NULL, NULL, NULL, sacl);
    LocalFree(descriptor); SetLastError(result);
    return result == ERROR_SUCCESS;
}

static HANDLE new_job(void) {
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (!job) return NULL;
    ZeroMemory(&limits, sizeof(limits));
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        CloseHandle(job); return NULL;
    }
    return job;
}

static int empty_directory(const wchar_t *path) {
    WIN32_FIND_DATAW data;
    wchar_t *pattern = join(path, L"*");
    HANDLE search;
    int empty = 1;
    if (!pattern) return 0;
    search = FindFirstFileW(pattern, &data); free(pattern);
    if (search == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND;
    do {
        if (wcscmp(data.cFileName, L".") && wcscmp(data.cFileName, L"..")) { empty = 0; break; }
    } while (FindNextFileW(search, &data));
    if (empty && GetLastError() != ERROR_NO_MORE_FILES) empty = 0;
    FindClose(search); return empty;
}

static int private_directory(Context *c, const wchar_t *path, DWORD rights) {
    HANDLE handle;
    DWORD access = READ_CONTROL | WRITE_DAC | FILE_READ_ATTRIBUTES;
    if (rights & FILE_ADD_FILE) access |= WRITE_OWNER;
    if (!CreateDirectoryW(path, NULL)) return 0;
    handle = regular(path, access, FILE_SHARE_READ, 1);
    if (handle == INVALID_HANDLE_VALUE) return 0;
    if (!permissions(handle, c->sid, rights, 1) ||
            ((rights & FILE_ADD_FILE) && !low_integrity(handle))) {
        CloseHandle(handle); return 0;
    }
    return pin_handle(&c->pins, handle);
}

static int delete_profile(const wchar_t *name, char *error, int error_cap) {
    HRESULT hr = DeleteAppContainerProfile(name);
    char operation[160];
    /* A failed deletion can be partial; the documented recovery is another call. */
    if (FAILED(hr)) hr = DeleteAppContainerProfile(name);
    if (SUCCEEDED(hr)) return 0;
    _snprintf_s(operation, sizeof(operation), _TRUNCATE,
        "Remove Windows profile %ls (HRESULT 0x%08lx)", name, (unsigned long)hr);
    SetLastError((DWORD)(HRESULT_FACILITY(hr) == FACILITY_WIN32 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE));
    return failure(error, error_cap, operation);
}

int visjail_windows_create(const char *directory, char *error, int error_cap) {
    Context *c = calloc(1, sizeof(*c));
    wchar_t name[80], *app = NULL, *work = NULL, *tmp = NULL;
    unsigned char random[16];
    HANDLE handle = INVALID_HANDLE_VALUE;
    HRESULT hr;
    char profile_operation[160];
    const char *operation = "Create private Windows jail";
    int result;
    AcquireSRWLockExclusive(&lock);
    if (!c) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto fail; }
    c->path = wide(directory);
    if (!local_path(c->path)) { SetLastError(ERROR_INVALID_NAME); goto fail; }
    if (!ancestors(c->path, &c->pins)) goto fail;
    handle = regular(c->path, READ_CONTROL | WRITE_DAC | FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ, 1);
    if (handle == INVALID_HANDLE_VALUE) goto fail;
    if (!empty_directory(c->path)) { SetLastError(ERROR_DIR_NOT_EMPTY); goto fail; }
    if (BCryptGenRandom(NULL, random, sizeof(random), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
        SetLastError(ERROR_GEN_FAILURE); goto fail;
    }
    _snwprintf_s(name, 80, _TRUNCATE,
        L"visjail.%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
        random[0], random[1], random[2], random[3], random[4], random[5], random[6], random[7],
        random[8], random[9], random[10], random[11], random[12], random[13], random[14], random[15]);
    hr = CreateAppContainerProfile(name, L"Vis private process", L"Private workspace for confined processes",
        NULL, 0, &c->sid);
    if (FAILED(hr)) {
        _snprintf_s(profile_operation, sizeof(profile_operation), _TRUNCATE,
            "Create Windows profile %ls (HRESULT 0x%08lx)", name, (unsigned long)hr);
        operation = profile_operation;
        SetLastError((DWORD)(HRESULT_FACILITY(hr) == FACILITY_WIN32 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE));
        goto fail;
    }
    memcpy(c->profile, name, (wcslen(name) + 1) * sizeof(wchar_t));
    if (!permissions(handle, c->sid, FILE_GENERIC_READ | FILE_GENERIC_EXECUTE, 1)) goto fail;
    if (!pin_handle(&c->pins, handle)) { handle = INVALID_HANDLE_VALUE; goto fail; }
    handle = INVALID_HANDLE_VALUE;
    app = join(c->path, L"app"); work = join(c->path, L"work"); tmp = join(c->path, L"tmp");
    if (!app || !work || !tmp || !private_directory(c, app, 0) ||
        !private_directory(c, work, FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | FILE_DELETE_CHILD) ||
        !private_directory(c, tmp, FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | FILE_DELETE_CHILD)) goto fail;
    c->job = new_job();
    if (!c->job || !(c->id = allocate_id())) goto fail;
    c->next = contexts; contexts = c; result = c->id;
    goto done;
fail:
    result = failure(error, error_cap, operation);
    if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
    if (c) {
        unpin(c->pins); if (c->job) CloseHandle(c->job);
        if (c->profile[0]) {
            int cleanup = delete_profile(c->profile, error, error_cap);
            if (cleanup) result = cleanup;
        }
        if (c->sid) FreeSid(c->sid); free(c->path); free(c);
    }
done:
    free(app); free(work); free(tmp);
    ReleaseSRWLockExclusive(&lock); return result;
}

/* Source objects stay pinned for the entire traversal. A regular source cannot
 * be written, renamed, replaced or retargeted while its content is copied. */
static int copy_tree(Context *c, const wchar_t *source, const wchar_t *destination, unsigned depth) {
    HANDLE input = INVALID_HANDLE_VALUE, output = INVALID_HANDLE_VALUE, search = INVALID_HANDLE_VALUE;
    BY_HANDLE_FILE_INFORMATION info;
    WIN32_FIND_DATAW data;
    wchar_t *pattern = NULL;
    int ok = 0;
    DWORD got, written, saved;
    unsigned char *buffer = NULL;
    if (depth > 128) { SetLastError(ERROR_DIRECTORY); return 0; }
    input = CreateFileW(source, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (input == INVALID_HANDLE_VALUE) goto done;
    if (!GetFileInformationByHandle(input, &info) || GetFileType(input) != FILE_TYPE_DISK ||
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) ||
        (!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && info.nNumberOfLinks != 1)) {
        SetLastError(ERROR_ACCESS_DENIED); goto done;
    }
    if (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
        if (!private_directory(c, destination, 0)) goto done;
        pattern = join(source, L"*"); if (!pattern) goto done;
        search = FindFirstFileW(pattern, &data);
        if (search == INVALID_HANDLE_VALUE) { ok = GetLastError() == ERROR_FILE_NOT_FOUND; goto done; }
        do {
            wchar_t *src, *dst;
            int copied;
            if (!wcscmp(data.cFileName, L".") || !wcscmp(data.cFileName, L"..")) continue;
            if (!component(data.cFileName, wcslen(data.cFileName))) { SetLastError(ERROR_INVALID_NAME); goto done; }
            src = join(source, data.cFileName); dst = join(destination, data.cFileName);
            copied = src && dst && copy_tree(c, src, dst, depth + 1);
            free(src); free(dst); if (!copied) goto done;
        } while (FindNextFileW(search, &data));
        ok = GetLastError() == ERROR_NO_MORE_FILES;
    } else {
        buffer = malloc(65536);
        if (!buffer) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto done; }
        output = CreateFileW(destination, GENERIC_WRITE | WRITE_DAC, 0, NULL, CREATE_NEW,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
        if (output == INVALID_HANDLE_VALUE || !permissions(output, c->sid, 0, 0)) goto done;
        for (;;) {
            if (!ReadFile(input, buffer, 65536, &got, NULL)) goto done;
            if (!got) break;
            if (!WriteFile(output, buffer, got, &written, NULL) || written != got) goto done;
        }
        ok = FlushFileBuffers(output) != 0;
    }
done:
    saved = GetLastError();
    if (search != INVALID_HANDLE_VALUE) FindClose(search);
    if (input != INVALID_HANDLE_VALUE) CloseHandle(input);
    if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
    free(buffer); free(pattern); SetLastError(saved); return ok;
}

int visjail_windows_stage(int id, const char *source, const char *relative_destination,
    char *error, int error_cap) {
    Context *c;
    wchar_t *src = wide(source), *rel = wide(relative_destination), *app = NULL, *dst = NULL;
    Pin *pins = NULL;
    int result = 0;
    AcquireSRWLockExclusive(&lock);
    c = context_get(id);
    if (!c || c->sealed || c->poisoned || !local_path(src) || !rel) {
        SetLastError(ERROR_INVALID_PARAMETER); goto fail;
    }
    for (size_t i = 0; rel[i]; i++) if (rel[i] == L'/') rel[i] = L'\\';
    if (!relative_path(rel)) { SetLastError(ERROR_INVALID_NAME); goto fail; }
    if (!ancestors(src, &pins)) goto fail;
    app = join(c->path, L"app"); if (app) dst = join(app, rel);
    if (!dst) goto fail;
    for (size_t i = wcslen(app) + 1; dst[i]; i++) if (dst[i] == L'\\') {
        HANDLE parent;
        dst[i] = 0;
        if (GetFileAttributesW(dst) == INVALID_FILE_ATTRIBUTES) {
            if (!private_directory(c, dst, 0)) { dst[i] = L'\\'; c->poisoned = 1; goto fail; }
        }
        parent = regular(dst, FILE_READ_ATTRIBUTES, FILE_SHARE_READ, 1);
        dst[i] = L'\\';
        if (parent == INVALID_HANDLE_VALUE || !pin_handle(&pins, parent)) { c->poisoned = 1; goto fail; }
    }
    if (!copy_tree(c, src, dst, 0)) { c->poisoned = 1; goto fail; }
    goto done;
fail:
    result = failure(error, error_cap, "Stage private application");
done:
    unpin(pins); free(src); free(rel); free(app); free(dst);
    ReleaseSRWLockExclusive(&lock); return result;
}

static int seal_tree(Context *c, const wchar_t *path, unsigned depth) {
    HANDLE handle, search;
    BY_HANDLE_FILE_INFORMATION info;
    WIN32_FIND_DATAW data;
    wchar_t *pattern;
    int directory, ok;
    if (depth > 128) { SetLastError(ERROR_DIRECTORY); return 0; }
    handle = CreateFileW(path, GENERIC_READ | WRITE_DAC, FILE_SHARE_READ, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (handle == INVALID_HANDLE_VALUE) return 0;
    if (!GetFileInformationByHandle(handle, &info) ||
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) || GetFileType(handle) != FILE_TYPE_DISK ||
        (!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && info.nNumberOfLinks != 1)) {
        CloseHandle(handle); SetLastError(ERROR_ACCESS_DENIED); return 0;
    }
    directory = !!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY);
    if (!permissions(handle, c->sid, FILE_GENERIC_READ | FILE_GENERIC_EXECUTE, directory)) {
        CloseHandle(handle); return 0;
    }
    if (!pin_handle(&c->pins, handle)) return 0;
    if (!directory) return 1;
    pattern = join(path, L"*"); if (!pattern) return 0;
    search = FindFirstFileW(pattern, &data); free(pattern);
    if (search == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND;
    ok = 1;
    do {
        wchar_t *child;
        if (!wcscmp(data.cFileName, L".") || !wcscmp(data.cFileName, L"..")) continue;
        child = join(path, data.cFileName);
        ok = component(data.cFileName, wcslen(data.cFileName)) && child && seal_tree(c, child, depth + 1);
        free(child); if (!ok) break;
    } while (FindNextFileW(search, &data));
    if (ok) ok = GetLastError() == ERROR_NO_MORE_FILES;
    FindClose(search); return ok;
}

int visjail_windows_seal(int id, char *error, int error_cap) {
    Context *c;
    wchar_t *app = NULL;
    int result = 0;
    AcquireSRWLockExclusive(&lock);
    c = context_get(id);
    if (!c || c->poisoned) { SetLastError(ERROR_INVALID_STATE); goto fail; }
    if (c->sealed) goto done;
    app = join(c->path, L"app");
    if (!app || !seal_tree(c, app, 0)) { c->poisoned = 1; goto fail; }
    c->sealed = 1; goto done;
fail:
    result = failure(error, error_cap, "Seal private application");
done:
    free(app); ReleaseSRWLockExclusive(&lock); return result;
}

static Stream *stream_get(int id) {
    Stream *s;
    AcquireSRWLockExclusive(&lock);
    for (s = streams; s; s = s->next) if (s->id == id && !s->closed) { s->refs++; break; }
    ReleaseSRWLockExclusive(&lock); return s;
}

static void stream_dispose(Stream *s) {
    if (s->pending) {
        DWORD ignored;
        CancelIoEx(s->read, &s->peek);
        GetOverlappedResult(s->read, &s->peek, &ignored, TRUE);
    }
    if (s->read) CloseHandle(s->read);
    if (s->write && s->write != s->read) CloseHandle(s->write);
    CloseHandle(s->peek.hEvent); CloseHandle(s->cancel); free(s);
}

static void stream_release(Stream *s) {
    int dispose;
    AcquireSRWLockExclusive(&lock); dispose = --s->refs == 0;
    ReleaseSRWLockExclusive(&lock);
    if (dispose) stream_dispose(s);
}

static Stream *stream_new(int context, HANDLE read, HANDLE write) {
    Stream *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->cancel = CreateEventW(NULL, TRUE, FALSE, NULL);
    s->peek.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    InitializeSRWLock(&s->reader);
    s->id = allocate_id();
    if (!s->cancel || !s->peek.hEvent || !s->id) {
        if (s->cancel) CloseHandle(s->cancel);
        if (s->peek.hEvent) CloseHandle(s->peek.hEvent);
        free(s); return NULL;
    }
    s->read = read; s->write = write; s->refs = 1; s->context = context;
    s->next = streams; streams = s; return s;
}

/* Caller owns the exclusive table lock. Outstanding IO references retain handles. */
static void stream_close_locked(Stream **slot) {
    Stream *s = *slot;
    *slot = s->next; s->closed = 1; SetEvent(s->cancel);
    if (s->read) CancelIoEx(s->read, NULL);
    if (s->write && s->write != s->read) CancelIoEx(s->write, NULL);
    if (--s->refs == 0) stream_dispose(s);
}

int visjail_close(int id) {
    Stream **slot;
    int result = -ERROR_INVALID_HANDLE;
    AcquireSRWLockExclusive(&lock);
    for (slot = &streams; *slot; slot = &(*slot)->next) if ((*slot)->id == id) {
        stream_close_locked(slot); result = 0; break;
    }
    ReleaseSRWLockExclusive(&lock); return result;
}

static int stream_io(int id, void *buffer, int length, int writing) {
    Stream *s;
    HANDLE handle, waits[2];
    OVERLAPPED overlapped;
    DWORD count = 0, code = ERROR_SUCCESS;
    BOOL ok;
    if (length < 0 || (!buffer && length)) return -ERROR_INVALID_PARAMETER;
    s = stream_get(id); if (!s) return -ERROR_INVALID_HANDLE;
    handle = writing ? s->write : s->read;
    if (!handle) { stream_release(s); return -ERROR_INVALID_HANDLE; }
    ZeroMemory(&overlapped, sizeof(overlapped));
    overlapped.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!overlapped.hEvent) { code = GetLastError(); goto done; }
    if (WaitForSingleObject(s->cancel, 0) == WAIT_OBJECT_0) { code = ERROR_OPERATION_ABORTED; goto done; }
    ok = writing ? WriteFile(handle, buffer, (DWORD)length, &count, &overlapped)
                 : ReadFile(handle, buffer, (DWORD)length, &count, &overlapped);
    if (!ok) {
        code = GetLastError();
        if (code == ERROR_IO_PENDING) {
            waits[0] = s->cancel; waits[1] = overlapped.hEvent;
            if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0 + 1)
                CancelIoEx(handle, &overlapped);
            if (GetOverlappedResult(handle, &overlapped, &count, TRUE)) code = ERROR_SUCCESS;
            else code = GetLastError();
        }
    }
done:
    if (overlapped.hEvent) CloseHandle(overlapped.hEvent);
    stream_release(s);
    if (!writing && (code == ERROR_BROKEN_PIPE || code == ERROR_PIPE_NOT_CONNECTED)) return 0;
    return code == ERROR_SUCCESS ? (int)count : -(int)code;
}

/* One persistent overlapped byte makes poll event-driven, not a PeekNamedPipe
 * sleep loop. The reader lock serializes lookahead and bulk reads. Closing the
 * logical stream cancels pending lookahead before its native storage is freed. */
static int read_ready(Stream *s, DWORD timeout) {
    DWORD count = 0, code, waited;
    HANDLE waits[2] = {s->cancel, s->peek.hEvent};
    if (!s->read) return -ERROR_INVALID_HANDLE;
    if (WaitForSingleObject(s->cancel, 0) == WAIT_OBJECT_0) return -ERROR_OPERATION_ABORTED;
    if (s->cached || s->eof) return 1;
    if (!s->pending) {
        ResetEvent(s->peek.hEvent);
        if (ReadFile(s->read, &s->byte, 1, &count, &s->peek)) {
            s->cached = count != 0; s->eof = count == 0; return 1;
        }
        code = GetLastError();
        if (code == ERROR_BROKEN_PIPE || code == ERROR_PIPE_NOT_CONNECTED) { s->eof = 1; return 1; }
        if (code != ERROR_IO_PENDING) return -(int)code;
        s->pending = 1;
    }
    waited = WaitForMultipleObjects(2, waits, FALSE, timeout);
    if (waited == WAIT_TIMEOUT) return 0;
    if (waited != WAIT_OBJECT_0 + 1) return -ERROR_OPERATION_ABORTED;
    s->pending = 0;
    if (!GetOverlappedResult(s->read, &s->peek, &count, FALSE)) {
        code = GetLastError();
        if (code == ERROR_BROKEN_PIPE || code == ERROR_PIPE_NOT_CONNECTED) { s->eof = 1; return 1; }
        return -(int)code;
    }
    s->cached = count != 0; s->eof = count == 0; return 1;
}

int visjail_read(int id, void *buffer, int length) {
    Stream *s;
    int result;
    DWORD available = 0;
    if (length < 0 || (!buffer && length)) return -ERROR_INVALID_PARAMETER;
    if (!length) return 0;
    s = stream_get(id); if (!s) return -ERROR_INVALID_HANDLE;
    AcquireSRWLockExclusive(&s->reader);
    result = read_ready(s, INFINITE);
    if (result > 0) {
        result = 0;
        if (s->cached) {
            ((unsigned char *)buffer)[0] = s->byte; s->cached = 0; result = 1;
            if (length > 1 && PeekNamedPipe(s->read, NULL, 0, NULL, &available, NULL) && available) {
                int extra, requested = available < (DWORD)(length - 1) ? (int)available : length - 1;
                extra = stream_io(id, (unsigned char *)buffer + 1, requested, 0);
                if (extra > 0) result += extra;
            }
        }
    }
    ReleaseSRWLockExclusive(&s->reader); stream_release(s); return result;
}

int visjail_write(int id, const void *buffer, int length) {
    return stream_io(id, (void *)buffer, length, 1);
}

int visjail_poll(int id, int timeout_ms) {
    Stream *s = stream_get(id);
    int result;
    if (!s) return -ERROR_INVALID_HANDLE;
    AcquireSRWLockExclusive(&s->reader);
    result = read_ready(s, timeout_ms < 0 ? INFINITE : (DWORD)timeout_ms);
    ReleaseSRWLockExclusive(&s->reader); stream_release(s); return result;
}

/* Only server handles are overlapped. Child ends are synchronous and inheritable.
 * The unpredictable name and first-instance flag prevent pipe substitution. */
static int pipe_pair(HANDLE *host, HANDLE *child, int host_reads) {
    unsigned char random[16];
    wchar_t name[100];
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, TRUE};
    HANDLE server, client;
    if (BCryptGenRandom(NULL, random, sizeof(random), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) return 0;
    _snwprintf_s(name, 100, _TRUNCATE,
        L"\\\\.\\pipe\\visjail.%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
        random[0], random[1], random[2], random[3], random[4], random[5], random[6], random[7],
        random[8], random[9], random[10], random[11], random[12], random[13], random[14], random[15]);
    server = CreateNamedPipeW(name, (host_reads ? PIPE_ACCESS_INBOUND : PIPE_ACCESS_OUTBOUND) |
        FILE_FLAG_OVERLAPPED | FILE_FLAG_FIRST_PIPE_INSTANCE,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
        1, 65536, 65536, 0, NULL);
    if (server == INVALID_HANDLE_VALUE) return 0;
    client = CreateFileW(name, host_reads ? GENERIC_WRITE : GENERIC_READ, 0, &attributes, OPEN_EXISTING, 0, NULL);
    if (client == INVALID_HANDLE_VALUE) { CloseHandle(server); return 0; }
    *host = server; *child = client; return 1;
}

static Process *process_get(int id) {
    Process *p;
    for (p = processes; p; p = p->next) if (p->id == id) return p;
    return NULL;
}

int visjail_windows_pid(int id) {
    Process *p;
    int result;
    AcquireSRWLockShared(&lock); p = process_get(id);
    result = p ? (int)p->pid : 0;
    ReleaseSRWLockShared(&lock); return result;
}

/* Job termination completes only after every descendant has left the job. */
static int finish_job(HANDLE job) {
    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting;
    if (!TerminateJobObject(job, 1)) return 0;
    for (;;) {
        if (!QueryInformationJobObject(job, JobObjectBasicAccountingInformation,
            &accounting, sizeof(accounting), NULL)) return 0;
        if (!accounting.ActiveProcesses) return 1;
        Sleep(1);
    }
}

int visjail_kill(int id, int signal_number) {
    Process *p;
    int result;
    if (signal_number != 9 && signal_number != 15) return -ERROR_INVALID_PARAMETER;
    AcquireSRWLockShared(&lock); p = process_get(id);
    result = p ? (TerminateJobObject(p->job, 1) ? 0 : -(int)GetLastError()) : -ERROR_INVALID_HANDLE;
    ReleaseSRWLockShared(&lock); return result;
}

static DWORD close_console(void *console) {
    ClosePseudoConsole((HPCON)console); return 0;
}

int visjail_wait(int id, int nohang, int *exit_code) {
    Process *p;
    HANDLE process = NULL;
    DWORD status, code;
    int result = -ERROR_INVALID_HANDLE;
    if (!exit_code) return -ERROR_INVALID_PARAMETER;
    AcquireSRWLockExclusive(&lock); p = process_get(id);
    if (p && p->reaped) {
        *exit_code = (int)p->code; p->waited = 1;
        ReleaseSRWLockExclusive(&lock); return 1;
    }
    if (p && p->process) DuplicateHandle(GetCurrentProcess(), p->process, GetCurrentProcess(), &process,
        SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, 0);
    ReleaseSRWLockExclusive(&lock);
    if (!process) return result;
    status = WaitForSingleObject(process, nohang ? 0 : INFINITE);
    if (status == WAIT_TIMEOUT) { CloseHandle(process); return 0; }
    if (status != WAIT_OBJECT_0 || !GetExitCodeProcess(process, &code)) {
        result = -(int)GetLastError(); CloseHandle(process); return result;
    }
    CloseHandle(process);
    /* Context destroy may already have consumed the original process record. */
    *exit_code = (int)code; result = 1;
    AcquireSRWLockExclusive(&lock); p = process_get(id);
    if (p && !p->reaped) {
        if (!finish_job(p->job)) result = -(int)GetLastError();
        else {
            p->reaped = 1; p->code = code;
            CloseHandle(p->process); p->process = NULL;
            CloseHandle(p->job); p->job = NULL;
            if (p->console) {
                /* Closing ConPTY flushes while the independent reader drains. */
                p->closer = CreateThread(NULL, 0, close_console, p->console, 0, NULL);
                if (p->closer) p->console = NULL;
                else result = -(int)GetLastError();
            }
        }
    }
    if (p) p->waited = 1;
    ReleaseSRWLockExclusive(&lock); return result;
}

int visjail_windows_destroy(int id) {
    Context **slot, *c;
    Process *p;
    Stream **sp;
    int result = -ERROR_INVALID_HANDLE;
    AcquireSRWLockExclusive(&lock);
    for (slot = &contexts; *slot && (*slot)->id != id; slot = &(*slot)->next) {}
    c = *slot;
    if (!c) { ReleaseSRWLockExclusive(&lock); return result; }
    if (c->job && !finish_job(c->job)) {
        result = -(int)GetLastError(); ReleaseSRWLockExclusive(&lock); return result;
    }
    *slot = c->next;
    for (sp = &streams; *sp;) {
        if ((*sp)->context == id) stream_close_locked(sp); else sp = &(*sp)->next;
    }
    for (;;) {
        HPCON console;
        HANDLE closer;
        for (p = processes; p && p->context != id; p = p->next) {}
        if (!p) break;
        /* Preserve a handle-free exit record even when Java's reaper has not
         * entered wait yet. A later wait consumes it; IDs are never recycled. */
        if (!p->reaped) {
            if (!GetExitCodeProcess(p->process, &p->code)) p->code = 1;
            p->reaped = 1;
        }
        p->context = 0;
        console = p->console; closer = p->closer;
        p->console = NULL; p->closer = NULL;
        if (p->process) { CloseHandle(p->process); p->process = NULL; }
        if (p->job) { CloseHandle(p->job); p->job = NULL; }
        ReleaseSRWLockExclusive(&lock);
        /* Never retain the table lock while canceled IO releases its handles. */
        if (console) ClosePseudoConsole(console);
        if (closer) { WaitForSingleObject(closer, INFINITE); CloseHandle(closer); }
        AcquireSRWLockExclusive(&lock);
    }
    ReleaseSRWLockExclusive(&lock);
    unpin(c->pins); c->pins = NULL;
    if (c->job) { CloseHandle(c->job); c->job = NULL; }
    result = delete_profile(c->profile, NULL, 0);
    if (result) {
        /* Retain only cleanup state so close can retry without permitting launches. */
        c->poisoned = 1;
        AcquireSRWLockExclusive(&lock); c->next = contexts; contexts = c;
        ReleaseSRWLockExclusive(&lock); return result;
    }
    FreeSid(c->sid); free(c->path); free(c);
    return 0;
}

static wchar_t **decode_blob(const char *blob, int length, int *count) {
    wchar_t **items;
    int offset = 0, n = 0;
    if (!blob || length < 1 || blob[length - 1]) { SetLastError(ERROR_INVALID_PARAMETER); return NULL; }
    items = calloc((size_t)length + 1, sizeof(*items));
    if (!items) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    while (offset < length) {
        const char *end = memchr(blob + offset, 0, (size_t)(length - offset));
        if (!end || !(items[n] = wide(blob + offset))) {
            while (n) free(items[--n]); free(items); return NULL;
        }
        n++; offset = (int)(end - blob) + 1;
    }
    *count = n; return items;
}

static void free_blob(wchar_t **items, int count) {
    if (items) { while (count) free(items[--count]); free(items); }
}

/* Microsoft CRT argv quoting: quote every argument, double backslashes before
 * quotes and the closing quote. Empty and Unicode arguments are preserved. */
static wchar_t *command_line(wchar_t **args, int count) {
    size_t capacity = 1, at = 0;
    wchar_t *line;
    int i;
    for (i = 0; i < count; i++) capacity += 2 * wcslen(args[i]) + 3;
    if (capacity > 32767) { SetLastError(ERROR_BAD_LENGTH); return NULL; }
    line = calloc(capacity, sizeof(wchar_t));
    if (!line) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    for (i = 0; i < count; i++) {
        const wchar_t *p = args[i];
        if (i) line[at++] = L' ';
        line[at++] = L'"';
        for (;;) {
            size_t slashes = 0, j;
            while (*p == L'\\') { slashes++; p++; }
            for (j = 0; j < slashes * ((*p == L'"' || !*p) ? 2 : 1); j++) line[at++] = L'\\';
            if (!*p) break;
            if (*p == L'"') line[at++] = L'\\';
            line[at++] = *p++;
        }
        line[at++] = L'"';
    }
    return line;
}

static int compare_env(const void *left, const void *right) {
    return _wcsicmp(*(const wchar_t *const *)left, *(const wchar_t *const *)right);
}

static wchar_t *environment_block(wchar_t **items, int count) {
    size_t size = 2, offset = 0;
    wchar_t *block;
    int i;
    if (count == 1 && !*items[0]) count = 0;
    for (i = 0; i < count; i++) {
        wchar_t *equal = wcschr(items[i], L'=');
        if (!equal || equal == items[i]) { SetLastError(ERROR_INVALID_PARAMETER); return NULL; }
        for (int j = 0; j < i; j++) {
            size_t key_length = (size_t)(equal - items[i]);
            wchar_t *prior_equal = wcschr(items[j], L'=');
            if (prior_equal && (size_t)(prior_equal - items[j]) == key_length &&
                !_wcsnicmp(items[i], items[j], key_length)) {
                SetLastError(ERROR_INVALID_PARAMETER); return NULL;
            }
        }
        size += wcslen(items[i]) + 1;
    }
    qsort(items, (size_t)count, sizeof(*items), compare_env);
    block = calloc(size, sizeof(wchar_t));
    if (!block) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    for (i = 0; i < count; i++) {
        size_t length = wcslen(items[i]) + 1;
        memcpy(block + offset, items[i], length * sizeof(wchar_t)); offset += length;
    }
    return block;
}

static int inside(const wchar_t *path, const wchar_t *directory) {
    size_t length = wcslen(directory);
    return wcslen(path) > length && !_wcsnicmp(path, directory, length) && path[length] == L'\\';
}

int visjail_spawn(const char *argv_blob, int argv_len, const char *env_blob, int env_len,
    const char *cwd, const char *profile, int flags, int rows, int cols,
    int proxy_port, int inbound_port, int result[VISJAIL_RESULT_COUNT], char *error, int error_cap) {
    Context *c = NULL;
    Process *p = NULL;
    Stream *input = NULL, *output = NULL, *errstream = NULL;
    wchar_t **args = NULL, **env = NULL, *line = NULL, *block = NULL, *directory = NULL;
    wchar_t *app = NULL, *work = NULL, *tmp = NULL;
    wchar_t system_directory[MAX_PATH + 1], system_root[MAX_PATH + 1];
    Pin *launch_pins = NULL;
    int argc = 0, envc = 0, context_id = 0, status = 0, pty = !!(flags & VISJAIL_PTY);
    char *end;
    const char *operation = "Prepare private Windows process";
    long parsed;
    HANDLE host_in = NULL, host_out = NULL, host_err = NULL;
    HANDLE child_in = NULL, child_out = NULL, child_err = NULL;
    HANDLE inherit[3];
    SIZE_T attribute_size = 0;
    STARTUPINFOEXW startup;
    PROCESS_INFORMATION info;
    SECURITY_CAPABILITIES capabilities;
    DWORD policy = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT;
    HPCON console = NULL;
    HRESULT hr;
    ZeroMemory(&startup, sizeof(startup)); ZeroMemory(&info, sizeof(info));
    if (result) { result[0] = 0; result[1] = result[2] = result[3] = -1; }
    if (!result || !(flags & VISJAIL_CONFINED) || (flags & ~7) || proxy_port || inbound_port ||
        !profile || strncmp(profile, "windows:", 8) || !profile[8]) {
        SetLastError(ERROR_INVALID_PARAMETER); return failure(error, error_cap, "Unsupported Windows jail request");
    }
    parsed = strtol(profile + 8, &end, 10);
    if (*end || parsed <= 0 || parsed > INT_MAX || profile[8] < '1' || profile[8] > '9') {
        SetLastError(ERROR_INVALID_PARAMETER); return failure(error, error_cap, "Invalid Windows context");
    }
    context_id = (int)parsed;
    args = decode_blob(argv_blob, argv_len, &argc); env = decode_blob(env_blob, env_len, &envc);
    if (!args || !argc || !env || !local_path(args[0])) goto fail_unlocked;
    line = command_line(args, argc); block = environment_block(env, envc); directory = wide(cwd);
    if (!line || !block || !local_path(directory)) goto fail_unlocked;
    if (envc == 1 && !*env[0]) { free(env[0]); envc = 0; }
    AcquireSRWLockExclusive(&lock);
    c = context_get(context_id);
    if (!c || !c->sealed || c->poisoned) { SetLastError(ERROR_INVALID_STATE); goto fail; }
    app = join(c->path, L"app"); work = join(c->path, L"work"); tmp = join(c->path, L"tmp");
    if (!app || !work || !tmp) goto fail;
    {
        const wchar_t *keys[3] = {L"TEMP=", L"TMP=", L"SystemRoot="};
        const wchar_t *values[3] = {tmp, tmp, system_root};
        wchar_t **expanded;
        UINT windows_length;
        operation = "Read Windows directory for process environment";
        windows_length = GetSystemWindowsDirectoryW(system_root, MAX_PATH + 1);
        if (!windows_length) goto fail;
        if (windows_length > MAX_PATH) { SetLastError(ERROR_INSUFFICIENT_BUFFER); goto fail; }
        operation = "Prepare private Windows environment";
        expanded = realloc(env, ((size_t)envc + 3) * sizeof(*env));
        if (!expanded) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto fail; }
        env = expanded;
        for (int k = 0; k < 3; k++) {
            size_t key_length = wcslen(keys[k]), value_length = wcslen(values[k]);
            int at;
            wchar_t *value = calloc(key_length + value_length + 1, sizeof(wchar_t));
            if (!value) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto fail; }
            memcpy(value, keys[k], key_length * sizeof(wchar_t));
            memcpy(value + key_length, values[k], (value_length + 1) * sizeof(wchar_t));
            for (at = 0; at < envc; at++) if (!_wcsnicmp(env[at], keys[k], key_length)) break;
            if (at < envc) free(env[at]); else envc++;
            env[at] = value;
        }
        free(block); block = environment_block(env, envc);
        if (!block) goto fail;
    }
    operation = "Pin Windows launch paths";
    {
        UINT system_length = GetSystemDirectoryW(system_directory, MAX_PATH + 1);
        HANDLE directory_handle;
        if (!system_length || system_length > MAX_PATH ||
            (!inside(args[0], app) && !inside(args[0], system_directory)) ||
            (_wcsicmp(directory, work) && _wcsicmp(directory, tmp) && _wcsicmp(directory, app) &&
             !inside(directory, work) && !inside(directory, tmp) && !inside(directory, app))) {
            SetLastError(ERROR_ACCESS_DENIED); goto fail;
        }
        if (!ancestors(directory, &launch_pins) || !ancestors(args[0], &launch_pins)) goto fail;
        directory_handle = regular(directory, FILE_READ_ATTRIBUTES, FILE_SHARE_READ, 1);
        if (directory_handle == INVALID_HANDLE_VALUE || !pin_handle(&launch_pins, directory_handle)) goto fail;
        /* Windows servicing hardlinks System32 files into WinSxS. The OS-derived
         * system baseline permits those links; staged application files do not. */
        directory_handle = regular(args[0], FILE_READ_ATTRIBUTES, FILE_SHARE_READ,
            inside(args[0], app) ? 0 : -1);
        if (directory_handle == INVALID_HANDLE_VALUE || !pin_handle(&launch_pins, directory_handle)) goto fail;
    }
    /* Reclaim completed records/closer thread handles before another launch. */
    for (Process **slot = &processes; *slot;) {
        Process *old = *slot;
        if (old->reaped && old->waited && !old->console && (!old->closer || WaitForSingleObject(old->closer, 0) == WAIT_OBJECT_0)) {
            *slot = old->next; if (old->closer) CloseHandle(old->closer); free(old);
        } else slot = &old->next;
    }
    if (pty && (rows <= 0 || cols <= 0 || rows > SHRT_MAX || cols > SHRT_MAX)) {
        SetLastError(ERROR_INVALID_PARAMETER); goto fail;
    }
    operation = "Create private Windows pipes";
    if (!pipe_pair(&host_in, &child_in, 0) || !pipe_pair(&host_out, &child_out, 1)) goto fail;
    if (!pty && !(flags & VISJAIL_MERGE_STDERR) && !pipe_pair(&host_err, &child_err, 1)) goto fail;
    if (pty) {
        COORD size = {(SHORT)cols, (SHORT)rows};
        hr = CreatePseudoConsole(size, child_in, child_out, 0, &console);
        if (FAILED(hr)) { SetLastError((DWORD)hr); goto fail; }
    }
    operation = "Configure Windows process security";
    startup.StartupInfo.cb = sizeof(startup);
    InitializeProcThreadAttributeList(NULL, 3, 0, &attribute_size);
    startup.lpAttributeList = malloc(attribute_size);
    if (!startup.lpAttributeList) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto fail; }
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 3, 0, &attribute_size)) {
        free(startup.lpAttributeList); startup.lpAttributeList = NULL; goto fail;
    }
    ZeroMemory(&capabilities, sizeof(capabilities)); capabilities.AppContainerSid = c->sid;
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
            &capabilities, sizeof(capabilities), NULL, NULL) ||
        !UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY,
            &policy, sizeof(policy), NULL, NULL)) goto fail;
    if (pty) {
        if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
            console, sizeof(console), NULL, NULL)) goto fail;
    } else {
        inherit[0] = child_in; inherit[1] = child_out; inherit[2] = child_err ? child_err : child_out;
        startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        startup.StartupInfo.hStdInput = inherit[0]; startup.StartupInfo.hStdOutput = inherit[1];
        startup.StartupInfo.hStdError = inherit[2];
        if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
            inherit, (child_err ? 3 : 2) * sizeof(HANDLE), NULL, NULL)) goto fail;
    }
    operation = "Create Windows process job";
    p = calloc(1, sizeof(*p));
    if (!p) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto fail; }
    p->job = new_job();
    if (!p->job || !(p->id = allocate_id())) goto fail;
    operation = "Create confined Windows process";
    if (!CreateProcessW(args[0], line, NULL, NULL, !pty,
        CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT |
            (pty ? 0 : CREATE_NO_WINDOW), block, directory, &startup.StartupInfo, &info)) goto fail;
    operation = "Assign Windows process jobs";
    if (!AssignProcessToJobObject(c->job, info.hProcess) || !AssignProcessToJobObject(p->job, info.hProcess)) goto fail;
    operation = "Connect Windows process streams";
    input = stream_new(c->id, pty ? host_out : NULL, host_in);
    if (!input) goto fail;
    host_in = NULL; if (pty) host_out = NULL;
    if (!pty) {
        output = stream_new(c->id, host_out, NULL); if (!output) goto fail; host_out = NULL;
        if (host_err) { errstream = stream_new(c->id, host_err, NULL); if (!errstream) goto fail; host_err = NULL; }
    }
    operation = "Start confined Windows process";
    if (ResumeThread(info.hThread) == (DWORD)-1) goto fail;
    p->process = info.hProcess; p->pid = info.dwProcessId; p->context = c->id; p->console = console;
    p->next = processes; processes = p;
    result[0] = p->id; result[1] = input->id; result[2] = pty ? input->id : output->id;
    result[3] = pty ? input->id : errstream ? errstream->id : -1;
    info.hProcess = NULL; console = NULL; p = NULL;
    goto done;
fail:
    status = failure(error, error_cap, operation);
    if (info.hProcess) { TerminateProcess(info.hProcess, 1); WaitForSingleObject(info.hProcess, INFINITE); }
    if (p && p->job) finish_job(p->job);
    for (Stream **slot = &streams; *slot;) {
        if (*slot == input || *slot == output || *slot == errstream) stream_close_locked(slot);
        else slot = &(*slot)->next;
    }
done:
    if (info.hThread) CloseHandle(info.hThread);
    if (info.hProcess) CloseHandle(info.hProcess);
    if (p) { if (p->job) CloseHandle(p->job); free(p); }
    if (host_in) CloseHandle(host_in);
    if (host_out) CloseHandle(host_out);
    if (host_err) CloseHandle(host_err);
    if (child_in) CloseHandle(child_in);
    if (child_out) CloseHandle(child_out);
    if (child_err) CloseHandle(child_err);
    /* No child was resumed when console remains owned here. Close output ends
     * before flushing this failed setup, outside the global handle-table lock. */
    if (startup.lpAttributeList) { DeleteProcThreadAttributeList(startup.lpAttributeList); free(startup.lpAttributeList); }
    ReleaseSRWLockExclusive(&lock);
    if (console) ClosePseudoConsole(console);
    unpin(launch_pins);
    goto cleanup;
fail_unlocked:
    status = failure(error, error_cap, "Validate Windows process arguments");
cleanup:
    free_blob(args, argc); free_blob(env, envc); free(line); free(block); free(directory);
    free(app); free(work); free(tmp); return status;
}
