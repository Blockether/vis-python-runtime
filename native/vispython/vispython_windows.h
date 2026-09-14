/* Windows implementation details for the single vispython translation unit.
 * The C ABI and interpreter policy are shared with POSIX; only OS primitives live here.
 * Windows x64 uses the system CRT and kernel32, not a bundled pthread implementation.
 */
#ifndef VISPYTHON_WINDOWS_H
#define VISPYTHON_WINDOWS_H

#include <windows.h>
#include <direct.h>
#include <io.h>
#include <process.h>
#include <wchar.h>

#ifndef PATH_MAX
#define PATH_MAX 32768
#endif
#define VIS_PY_EXPORT __declspec(dllexport)
#define _Thread_local __declspec(thread)
#define strdup _strdup

typedef SRWLOCK pthread_mutex_t;
typedef CONDITION_VARIABLE pthread_cond_t;
typedef INIT_ONCE pthread_once_t;
typedef DWORD pthread_key_t;
typedef unsigned pthread_t;
#define PTHREAD_MUTEX_INITIALIZER SRWLOCK_INIT
#define PTHREAD_COND_INITIALIZER CONDITION_VARIABLE_INIT
#define PTHREAD_ONCE_INIT INIT_ONCE_STATIC_INIT
#define CLOCK_REALTIME 0
#define CLOCK_MONOTONIC 1

static int clock_gettime(int clock, struct timespec *value)
{
    if (clock == CLOCK_MONOTONIC) {
        ULONGLONG ticks = GetTickCount64();
        value->tv_sec = (time_t)(ticks / 1000);
        value->tv_nsec = (long)(ticks % 1000) * 1000000;
    } else {
        FILETIME now;
        ULARGE_INTEGER ticks;
        GetSystemTimePreciseAsFileTime(&now);
        ticks.LowPart = now.dwLowDateTime;
        ticks.HighPart = now.dwHighDateTime;
        ticks.QuadPart -= 116444736000000000ULL;
        value->tv_sec = (time_t)(ticks.QuadPart / 10000000);
        value->tv_nsec = (long)(ticks.QuadPart % 10000000) * 100;
    }
    return 0;
}

static void pthread_mutex_lock(pthread_mutex_t *lock) { AcquireSRWLockExclusive(lock); }
static void pthread_mutex_unlock(pthread_mutex_t *lock) { ReleaseSRWLockExclusive(lock); }
static void pthread_cond_signal(pthread_cond_t *cond) { WakeConditionVariable(cond); }
static void pthread_cond_broadcast(pthread_cond_t *cond) { WakeAllConditionVariable(cond); }
static int pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *lock)
{
    return SleepConditionVariableSRW(cond, lock, INFINITE, 0) ? 0 : EINVAL;
}

static int pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *lock,
                                  const struct timespec *deadline)
{
    struct timespec now;
    long long ms;
    clock_gettime(CLOCK_REALTIME, &now);
    ms = ((long long)deadline->tv_sec - now.tv_sec) * 1000
         + (deadline->tv_nsec - now.tv_nsec + 999999) / 1000000;
    if (ms < 0) ms = 0;
    if (ms >= INFINITE) ms = INFINITE - 1;
    if (SleepConditionVariableSRW(cond, lock, (DWORD)ms, 0)) return 0;
    return GetLastError() == ERROR_TIMEOUT ? ETIMEDOUT : EINVAL;
}

static BOOL CALLBACK vis_py_win_once(PINIT_ONCE once, PVOID parameter, PVOID *context)
{
    void (**function)(void) = parameter;
    (void)once;
    (void)context;
    (*function)();
    return TRUE;
}

static void pthread_once(pthread_once_t *once, void (*function)(void))
{
    if (!InitOnceExecuteOnce(once, vis_py_win_once, &function, NULL)) abort();
}

static void pthread_key_create(pthread_key_t *key, void (*destructor)(void *))
{
    /* On x64 Windows the callback and C calling conventions are identical. */
    *key = FlsAlloc((PFLS_CALLBACK_FUNCTION)destructor);
    if (*key == FLS_OUT_OF_INDEXES) abort();
}

static void *pthread_getspecific(pthread_key_t key) { return FlsGetValue(key); }
static void pthread_setspecific(pthread_key_t key, void *value)
{
    if (!FlsSetValue(key, value)) abort();
}

struct vis_py_win_thread_start {
    void *(*function)(void *);
    void *argument;
};

static unsigned __stdcall vis_py_win_thread_main(void *argument)
{
    struct vis_py_win_thread_start start = *(struct vis_py_win_thread_start *)argument;
    free(argument);
    start.function(start.argument);
    return 0;
}

static int pthread_create(pthread_t *thread, const void *attributes,
                           void *(*function)(void *), void *argument)
{
    uintptr_t handle;
    struct vis_py_win_thread_start *start = malloc(sizeof *start);
    (void)attributes;
    if (start == NULL) return ENOMEM;
    start->function = function;
    start->argument = argument;
    handle = _beginthreadex(NULL, 0, vis_py_win_thread_main, start, 0, thread);
    if (handle == 0) {
        free(start);
        return EAGAIN;
    }
    /* The bounded worker pool lives for the process; no join handle is retained. */
    CloseHandle((HANDLE)handle);
    return 0;
}

static wchar_t *vis_py_win_wide(const char *text)
{
    int size;
    wchar_t *wide;
    if (text == NULL || strlen(text) >= PATH_MAX) { errno = ENAMETOOLONG; return NULL; }
    size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, NULL, 0);
    if (size == 0) { errno = EILSEQ; return NULL; }
    wide = malloc((size_t)size * sizeof *wide);
    if (wide == NULL) { errno = ENOMEM; return NULL; }
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, wide, size)) {
        free(wide);
        errno = EILSEQ;
        return NULL;
    }
    return wide;
}

static FILE *vis_py_win_fopen(const char *path, const char *mode)
{
    wchar_t *wide = vis_py_win_wide(path);
    wchar_t *wide_mode = vis_py_win_wide(mode);
    FILE *file = wide != NULL && wide_mode != NULL ? _wfopen(wide, wide_mode) : NULL;
    free(wide);
    free(wide_mode);
    return file;
}

static int vis_py_win_stat(const char *path, struct _stat64 *info)
{
    wchar_t *wide = vis_py_win_wide(path);
    int result = wide == NULL ? -1 : _wstat64(wide, info);
    free(wide);
    return result;
}

static int vis_py_win_chmod(const char *path, int mode)
{
    wchar_t *wide = vis_py_win_wide(path);
    int result = wide == NULL ? -1 : _wchmod(wide, mode & (_S_IREAD | _S_IWRITE));
    free(wide);
    return result;
}

static int vis_py_win_unlink(const char *path)
{
    wchar_t *wide = vis_py_win_wide(path);
    int result = wide == NULL ? -1 : _wunlink(wide);
    free(wide);
    return result;
}

static int vis_py_win_rmdir(const char *path)
{
    wchar_t *wide = vis_py_win_wide(path);
    int result = wide == NULL ? -1 : _wrmdir(wide);
    free(wide);
    return result;
}

static int vis_py_win_mkdir(const char *path, int mode)
{
    wchar_t *wide = vis_py_win_wide(path);
    int result = wide == NULL ? -1 : _wmkdir(wide);
    (void)mode;
    free(wide);
    return result;
}

/* Map documented Win32 failures to the errno contract used by the shared filesystem API. */
static void vis_py_win_set_errno(DWORD error)
{
    switch (error) {
    case ERROR_FILE_NOT_FOUND:
    case ERROR_PATH_NOT_FOUND: errno = ENOENT; break;
    case ERROR_ACCESS_DENIED:
    case ERROR_SHARING_VIOLATION:
    case ERROR_LOCK_VIOLATION: errno = EACCES; break;
    case ERROR_ALREADY_EXISTS:
    case ERROR_FILE_EXISTS: errno = EEXIST; break;
    case ERROR_NOT_SAME_DEVICE: errno = EXDEV; break;
    case ERROR_DIRECTORY: errno = ENOTDIR; break;
    case ERROR_DIR_NOT_EMPTY: errno = ENOTEMPTY; break;
    case ERROR_DISK_FULL: errno = ENOSPC; break;
    case ERROR_NOT_ENOUGH_MEMORY:
    case ERROR_OUTOFMEMORY: errno = ENOMEM; break;
    case ERROR_INVALID_NAME:
    case ERROR_INVALID_PARAMETER: errno = EINVAL; break;
    default: errno = EIO; break;
    }
}

static int vis_py_win_rename(const char *from, const char *to)
{
    wchar_t *source = vis_py_win_wide(from);
    wchar_t *target = vis_py_win_wide(to);
    int result = -1;
    if (source != NULL && target != NULL) {
        if (MoveFileExW(source, target, MOVEFILE_REPLACE_EXISTING)) result = 0;
        else vis_py_win_set_errno(GetLastError());
    }
    free(source);
    free(target);
    return result;
}

/* Local directory enumeration returns UTF-8, matching CPython's Windows filesystem codec. */
struct dirent { char d_name[PATH_MAX]; };
typedef struct {
    HANDLE handle;
    WIN32_FIND_DATAW data;
    struct dirent entry;
    int first;
} DIR;

static DIR *opendir(const char *path)
{
    wchar_t *wide = vis_py_win_wide(path);
    wchar_t *pattern;
    DIR *dir;
    size_t size;
    if (wide == NULL) return NULL;
    size = wcslen(wide);
    pattern = malloc((size + 3) * sizeof *pattern);
    dir = calloc(1, sizeof *dir);
    if (pattern == NULL || dir == NULL) {
        free(wide); free(pattern); free(dir); errno = ENOMEM; return NULL;
    }
    memcpy(pattern, wide, size * sizeof *wide);
    memcpy(pattern + size, L"\\*", 3 * sizeof *wide);
    free(wide);
    dir->handle = FindFirstFileW(pattern, &dir->data);
    if (dir->handle == INVALID_HANDLE_VALUE) {
        vis_py_win_set_errno(GetLastError()); free(pattern); free(dir); return NULL;
    }
    free(pattern);
    dir->first = 1;
    return dir;
}

static struct dirent *readdir(DIR *dir)
{
    if (!dir->first && !FindNextFileW(dir->handle, &dir->data)) return NULL;
    dir->first = 0;
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, dir->data.cFileName, -1,
                            dir->entry.d_name, sizeof dir->entry.d_name, NULL, NULL)) return NULL;
    return &dir->entry;
}

static void closedir(DIR *dir) { FindClose(dir->handle); free(dir); }

/* Only ordinary local DOS paths are accepted by confinement. Extended/device/UNC
 * names, streams and DOS device aliases have semantics that are not directory roots.
 * Resolve the deepest existing ancestor through a HANDLE, not lexical casing: this
 * follows junctions and symlinks and preserves case-sensitive Windows directories.
 */
static int vis_py_win_component_safe(const wchar_t *part, size_t length)
{
    size_t stem = 0;
    if (length == 0 || part[length - 1] == L'.' || part[length - 1] == L' ') return 0;
    while (stem < length && part[stem] != L'.') stem++;
    if ((stem == 3 && (_wcsnicmp(part, L"CON", 3) == 0 ||
                      _wcsnicmp(part, L"PRN", 3) == 0 ||
                      _wcsnicmp(part, L"AUX", 3) == 0 ||
                      _wcsnicmp(part, L"NUL", 3) == 0)) ||
        (stem == 6 && _wcsnicmp(part, L"CONIN$", 6) == 0) ||
        (stem == 7 && _wcsnicmp(part, L"CONOUT$", 7) == 0)) return 0;
    if (stem == 4 && (_wcsnicmp(part, L"COM", 3) == 0 || _wcsnicmp(part, L"LPT", 3) == 0) &&
        ((part[3] >= L'1' && part[3] <= L'9') || part[3] == 0xb9 ||
         part[3] == 0xb2 || part[3] == 0xb3)) return 0;
    return 1;
}

static int vis_py_win_canonical(const char *path, char *out, size_t cap)
{
    wchar_t *input = vis_py_win_wide(path);
    wchar_t *absolute = NULL, *work = NULL, *resolved = NULL;
    wchar_t drive[4];
    HANDLE handle = INVALID_HANDLE_VALUE;
    DWORD length, error;
    size_t total, kept, i, part;
    int result = 0;
    if (input == NULL || input[0] == L'\0') goto done;
    if ((input[0] == L'\\' || input[0] == L'/') &&
        (input[1] == L'\\' || input[1] == L'/')) goto done;
    absolute = malloc(PATH_MAX * sizeof *absolute);
    work = malloc(PATH_MAX * sizeof *work);
    resolved = malloc(PATH_MAX * sizeof *resolved);
    if (absolute == NULL || work == NULL || resolved == NULL) goto done;
    length = GetFullPathNameW(input, PATH_MAX, absolute, NULL);
    if (length < 3 || length >= PATH_MAX || absolute[1] != L':' || absolute[2] != L'\\') goto done;
    drive[0] = absolute[0]; drive[1] = L':'; drive[2] = L'\\'; drive[3] = L'\0';
    if (GetDriveTypeW(drive) == DRIVE_REMOTE) goto done;
    total = wcslen(absolute);
    while (total > 3 && absolute[total - 1] == L'\\') absolute[--total] = L'\0';
    part = 3;
    for (i = 3; i <= total; i++) {
        wchar_t c = absolute[i];
        if (c == L'\0' || c == L'\\') {
            if (i > 3 && !vis_py_win_component_safe(absolute + part, i - part)) goto done;
            part = i + 1;
        } else if (c < 32 || wcschr(L":*?\"<>|", c) != NULL) goto done;
    }
    memcpy(work, absolute, (total + 1) * sizeof *work);
    kept = total;
    for (;;) {
        handle = CreateFileW(work, FILE_READ_ATTRIBUTES,
                             FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                             NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
        if (handle != INVALID_HANDLE_VALUE) break;
        error = GetLastError();
        /* Access errors and broken reparse points must not turn into missing files. */
        if ((error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) ||
            GetFileAttributesW(work) != INVALID_FILE_ATTRIBUTES || kept <= 3) goto done;
        while (kept > 3 && work[kept - 1] != L'\\') kept--;
        if (kept > 3) kept--;
        work[kept] = L'\0';
    }
    length = GetFinalPathNameByHandleW(handle, resolved, PATH_MAX,
                                        FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (length < 7 || length >= PATH_MAX || wcsncmp(resolved, L"\\\\?\\", 4) != 0 ||
        resolved[5] != L':' || resolved[6] != L'\\') goto done;
    /* Remove the DOS namespace prefix only AFTER the kernel resolves the path. */
    memmove(resolved, resolved + 4, (length - 3) * sizeof *resolved);
    length -= 4;
    while (length > 3 && resolved[length - 1] == L'\\') resolved[--length] = L'\0';
    if (kept < total) {
        size_t tail = kept;
        if (absolute[tail] == L'\\') tail++;
        if (length + 1 + total - tail >= PATH_MAX) goto done;
        if (resolved[length - 1] != L'\\') resolved[length++] = L'\\';
        memcpy(resolved + length, absolute + tail, (total - tail + 1) * sizeof *resolved);
    }
    for (i = 0; resolved[i] != L'\0'; i++) if (resolved[i] == L'\\') resolved[i] = L'/';
    if (cap <= INT_MAX && WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, resolved, -1,
                                              out, (int)cap, NULL, NULL) != 0) result = 1;
done:
    if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
    free(input); free(absolute); free(work); free(resolved);
    return result;
}

/* IOCP needs an overlapped read handle, not the CRT descriptor os.pipe returns.
 * This creates only a connected, non-inheritable local pipe, never a caller-chosen
 * path or network endpoint. No Python audit capability is temporarily widened. */
static PyObject *vis_py_win_wakeup_pipe(PyObject *self, PyObject *args)
{
    static unsigned long long serial = 0;
    wchar_t name[128];
    LARGE_INTEGER tick = {0};
    HANDLE reader = INVALID_HANDLE_VALUE, writer = INVALID_HANDLE_VALUE;
    OVERLAPPED connect = {0};
    DWORD mode = PIPE_NOWAIT, transferred;
    PyObject *result = NULL;
    int writer_fd;
    (void)self; (void)args;
    QueryPerformanceCounter(&tick);
    swprintf(name, sizeof name / sizeof *name, L"\\\\.\\pipe\\vis-python-%lu-%llu-%lld",
             GetCurrentProcessId(), ++serial, tick.QuadPart);
    reader = CreateNamedPipeW(name, PIPE_ACCESS_INBOUND | FILE_FLAG_OVERLAPPED |
                             FILE_FLAG_FIRST_PIPE_INSTANCE,
                             PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_REJECT_REMOTE_CLIENTS,
                             1, 0, 65536, 0, NULL);
    if (reader == INVALID_HANDLE_VALUE) goto windows_error;
    writer = CreateFileW(name, GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    if (writer == INVALID_HANDLE_VALUE) goto windows_error;
    connect.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (connect.hEvent == NULL) goto windows_error;
    if (!ConnectNamedPipe(reader, &connect)) {
        DWORD error = GetLastError();
        if (error == ERROR_IO_PENDING) {
            if (!GetOverlappedResult(reader, &connect, &transferred, TRUE)) goto windows_error;
        } else if (error != ERROR_PIPE_CONNECTED) {
            SetLastError(error);
            goto windows_error;
        }
    }
    if (!SetNamedPipeHandleState(writer, &mode, NULL, NULL)) goto windows_error;
    writer_fd = _open_osfhandle((intptr_t)writer, _O_WRONLY | _O_BINARY | _O_NOINHERIT);
    if (writer_fd < 0) {
        PyErr_SetFromErrno(PyExc_OSError);
        goto done;
    }
    writer = INVALID_HANDLE_VALUE; /* The CRT descriptor owns it now. */
    result = Py_BuildValue("Ki", (unsigned long long)(uintptr_t)reader, writer_fd);
    if (result == NULL) _close(writer_fd);
    else reader = INVALID_HANDLE_VALUE; /* The returned handle belongs to the loop. */
    goto done;
windows_error:
    PyErr_SetFromWindowsErr(0);
done:
    if (connect.hEvent != NULL) CloseHandle(connect.hEvent);
    if (reader != INVALID_HANDLE_VALUE) CloseHandle(reader);
    if (writer != INVALID_HANDLE_VALUE) CloseHandle(writer);
    return result;
}

/* The ABI is UTF-8 even when the Windows user's ANSI code page is not. */
static PyStatus vis_py_win_config_string(PyConfig *config, wchar_t **field, const char *text)
{
    wchar_t *wide = vis_py_win_wide(text);
    PyStatus status;
    if (wide == NULL) return PyStatus_Error("invalid UTF-8 runtime path");
    status = PyConfig_SetString(config, field, wide);
    free(wide);
    return status;
}

#define PyConfig_SetBytesString vis_py_win_config_string
#define fopen vis_py_win_fopen
#define chmod vis_py_win_chmod
#define unlink vis_py_win_unlink
#define rmdir vis_py_win_rmdir
#define mkdir vis_py_win_mkdir
#define rename vis_py_win_rename
#define vis_py_file_info struct _stat64
#define vis_py_stat_path vis_py_win_stat

#endif
