/* Experimental one-object WinFsp/BindFlt composition gate. Not production policy.
 * Build with MSVC /W4 /std:c11 /MT, WinFsp v2.1 headers/import library.
 * WinFsp fsctl.h emits C4324 for its ABI-aligned request buffer under /W4;
 * /WX therefore fails in that upstream header. Do not suppress or repack it.
 * advapi32.lib and ole32.lib. Run elevated on disposable Windows Server 2022:
 * probe.exe ABS_VISJAIL_DLL ABS_GUEST_EXE EXISTING_TEST_PARENT UNUSED_DRIVE:
 * Secure Boot and host object security are never changed. Exit 0 is this bounded
 * gate only, not arbitrary Windows filesystem-policy parity.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <objbase.h>
#include <winternl.h>
/* The user-mode SDK winternl.h declares NTSTATUS but not its pointer alias,
 * which WinFsp 2.1 directory-buffer declarations require. */
typedef NTSTATUS *PNTSTATUS;
#include <winfsp/winfsp.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>
#include "visjail.h"

#define CAP 1024
#define DEADLINE 60000
#define SECURITY_BITS (OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION | LABEL_SECURITY_INFORMATION)

typedef struct {
    wchar_t dll[MAX_PATH], guest[MAX_PATH], base[MAX_PATH];
    wchar_t original[MAX_PATH], outside[MAX_PATH], alias[CAP], image[MAX_PATH];
    unsigned char sid[SECURITY_MAX_SID_SIZE];
    volatile LONG phase;
    char error[CAP];
} Shared;

typedef struct {
    HANDLE files[3];
    PSECURITY_DESCRIPTOR security[3];
    SRWLOCK lock;
} Broker;

typedef struct { int index; UINT32 access; } PolicyOpenFile;

static int problem(const char *what) {
    fprintf(stderr, "FAIL %s win32=%lu\n", what, GetLastError());
    return 0;
}

static int join(wchar_t *out, size_t count, const wchar_t *base, const wchar_t *leaf) {
    return swprintf_s(out, count, L"%s\\%s", base, leaf) > 0;
}

static int utf8(const wchar_t *in, char *out, int size) {
    return WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, in, -1,
        out, size, NULL, NULL) > 0;
}

static int entry(const wchar_t *name) {
    if (!wcscmp(name, L"\\")) return 0;
    if (!_wcsicmp(name, L"\\sentinel.txt")) return 1;
    if (!_wcsicmp(name, L"\\noexec.exe")) return 2;
    return -1;
}

static NTSTATUS security_copy(Broker *b, int index, PSECURITY_DESCRIPTOR sd, SIZE_T *size) {
    DWORD needed = GetSecurityDescriptorLength(b->security[index]);
    if (size) {
        SIZE_T available = *size;
        *size = needed;
        if (sd) {
            if (available < needed) return STATUS_BUFFER_OVERFLOW;
            memcpy(sd, b->security[index], needed);
        }
    }
    return STATUS_SUCCESS;
}

static NTSTATUS information(Broker *b, int index, FSP_FSCTL_FILE_INFO *info) {
    BY_HANDLE_FILE_INFORMATION data;
    LARGE_INTEGER size;
    memset(info, 0, sizeof(*info));
    info->IndexNumber = (UINT64)index + 1;
    if (!index) {
        info->FileAttributes = FILE_ATTRIBUTE_DIRECTORY;
        return STATUS_SUCCESS;
    }
    if (!GetFileInformationByHandle(b->files[index], &data) ||
        !GetFileSizeEx(b->files[index], &size)) return FspNtStatusFromWin32(GetLastError());
    info->FileAttributes = FILE_ATTRIBUTE_NORMAL;
    info->FileSize = (UINT64)size.QuadPart;
    info->AllocationSize = (info->FileSize + 511) & ~(UINT64)511;
    info->CreationTime = ((UINT64)data.ftCreationTime.dwHighDateTime << 32) | data.ftCreationTime.dwLowDateTime;
    info->LastAccessTime = ((UINT64)data.ftLastAccessTime.dwHighDateTime << 32) | data.ftLastAccessTime.dwLowDateTime;
    info->LastWriteTime = ((UINT64)data.ftLastWriteTime.dwHighDateTime << 32) | data.ftLastWriteTime.dwLowDateTime;
    info->ChangeTime = info->LastWriteTime;
    return STATUS_SUCCESS;
}

static NTSTATUS get_security_name(FSP_FILE_SYSTEM *fs, PWSTR name, PUINT32 attributes,
        PSECURITY_DESCRIPTOR sd, SIZE_T *size) {
    int index = entry(name);
    if (index < 0) return STATUS_OBJECT_NAME_NOT_FOUND;
    if (attributes) *attributes = index ? FILE_ATTRIBUTE_NORMAL : FILE_ATTRIBUTE_DIRECTORY;
    return security_copy(fs->UserContext, index, sd, size);
}

static NTSTATUS open_file(FSP_FILE_SYSTEM *fs, PWSTR name, UINT32 options,
        UINT32 access, PVOID *context, FSP_FSCTL_FILE_INFO *info) {
    int index = entry(name);
    PolicyOpenFile *file;
    NTSTATUS status;
    if (index < 0) return STATUS_OBJECT_NAME_NOT_FOUND;
    if ((options & FILE_DIRECTORY_FILE) && index) return STATUS_NOT_A_DIRECTORY;
    if ((options & FILE_NON_DIRECTORY_FILE) && !index) return STATUS_FILE_IS_A_DIRECTORY;
    /* Defense in depth; the FSD must also enforce our returned security descriptor. */
    if (index && (access & FILE_EXECUTE)) return STATUS_ACCESS_DENIED;
    if (index == 2 && (access & (FILE_WRITE_DATA | FILE_APPEND_DATA))) return STATUS_ACCESS_DENIED;
    file = calloc(1, sizeof(*file));
    if (!file) return STATUS_INSUFFICIENT_RESOURCES;
    file->index = index;
    file->access = access;
    status = information(fs->UserContext, index, info);
    if (!NT_SUCCESS(status)) { free(file); return status; }
    *context = file;
    return STATUS_SUCCESS;
}

static void close_file(FSP_FILE_SYSTEM *fs, PVOID context) {
    (void)fs;
    free(context);
}

static NTSTATUS get_info(FSP_FILE_SYSTEM *fs, PVOID context, FSP_FSCTL_FILE_INFO *info) {
    return information(fs->UserContext, ((PolicyOpenFile *)context)->index, info);
}

static NTSTATUS get_security(FSP_FILE_SYSTEM *fs, PVOID context,
        PSECURITY_DESCRIPTOR sd, SIZE_T *size) {
    return security_copy(fs->UserContext, ((PolicyOpenFile *)context)->index, sd, size);
}

static NTSTATUS read_file(FSP_FILE_SYSTEM *fs, PVOID context, PVOID buffer,
        UINT64 offset, ULONG length, PULONG transferred) {
    Broker *b = fs->UserContext;
    PolicyOpenFile *file = context;
    LARGE_INTEGER position;
    DWORD error = ERROR_SUCCESS;
    *transferred = 0;
    if (!file->index || !(file->access & FILE_READ_DATA)) return STATUS_ACCESS_DENIED;
    if (offset > 2 * 1024 * 1024 || length > 2 * 1024 * 1024) return STATUS_INVALID_PARAMETER;
    position.QuadPart = (LONGLONG)offset;
    AcquireSRWLockExclusive(&b->lock);
    if (!SetFilePointerEx(b->files[file->index], position, NULL, FILE_BEGIN) ||
        !ReadFile(b->files[file->index], buffer, length, transferred, NULL)) error = GetLastError();
    ReleaseSRWLockExclusive(&b->lock);
    if (error) return FspNtStatusFromWin32(error);
    return *transferred ? STATUS_SUCCESS : STATUS_END_OF_FILE;
}

static NTSTATUS write_file(FSP_FILE_SYSTEM *fs, PVOID context, PVOID buffer,
        UINT64 offset, ULONG length, BOOLEAN append, BOOLEAN constrained,
        PULONG transferred, FSP_FSCTL_FILE_INFO *info) {
    Broker *b = fs->UserContext;
    PolicyOpenFile *file = context;
    LARGE_INTEGER position;
    DWORD error = ERROR_SUCCESS;
    *transferred = 0;
    if (file->index != 1 || !(file->access & FILE_WRITE_DATA)) return STATUS_ACCESS_DENIED;
    if (append) return STATUS_INVALID_PARAMETER;
    /* Cached paging writes may cover a whole page; never extend the host object. */
    if (constrained) {
        if (offset >= 8) return information(b, 1, info);
        if (length > 8 - offset) length = (ULONG)(8 - offset);
    } else if (offset > 8 || length > 8 - offset) return STATUS_INVALID_PARAMETER;
    position.QuadPart = (LONGLONG)offset;
    AcquireSRWLockExclusive(&b->lock);
    if (!SetFilePointerEx(b->files[1], position, NULL, FILE_BEGIN) ||
        !WriteFile(b->files[1], buffer, length, transferred, NULL) ||
        !FlushFileBuffers(b->files[1])) error = GetLastError();
    ReleaseSRWLockExclusive(&b->lock);
    return error ? FspNtStatusFromWin32(error) : information(b, 1, info);
}

static NTSTATUS flush_file(FSP_FILE_SYSTEM *fs, PVOID context, FSP_FSCTL_FILE_INFO *info) {
    Broker *b = fs->UserContext;
    PolicyOpenFile *file = context;
    if (!file) return STATUS_SUCCESS;
    if (file->index == 1 && !FlushFileBuffers(b->files[1])) return FspNtStatusFromWin32(GetLastError());
    return information(b, file->index, info);
}

static NTSTATUS volume_info(FSP_FILE_SYSTEM *fs, FSP_FSCTL_VOLUME_INFO *info) {
    (void)fs;
    memset(info, 0, sizeof(*info));
    info->TotalSize = 4 * 1024 * 1024;
    info->FreeSize = 1024 * 1024;
    return STATUS_SUCCESS;
}

static FSP_FILE_SYSTEM_INTERFACE policy_interface = {
    .GetVolumeInfo = volume_info,
    .GetSecurityByName = get_security_name,
    .Open = open_file,
    .Close = close_file,
    .Read = read_file,
    .Write = write_file,
    .Flush = flush_file,
    .GetFileInfo = get_info,
    .GetSecurity = get_security
};

typedef struct {
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
    int (*kill)(int, int);
} Api;

static int load_api(HMODULE module, Api *api) {
#define LOAD(member, symbol) do { \
    FARPROC proc = GetProcAddress(module, symbol); \
    if (!proc) return problem(symbol); \
    _Static_assert(sizeof(proc) == sizeof(api->member), "function pointer size"); \
    memcpy(&api->member, &proc, sizeof(proc)); \
} while (0)
    LOAD(create, "visjail_windows_create");
    LOAD(stage, "visjail_windows_stage");
    LOAD(seal, "visjail_windows_seal");
    LOAD(destroy, "visjail_windows_destroy");
    LOAD(spawn, "visjail_spawn");
    LOAD(read, "visjail_read");
    LOAD(write, "visjail_write");
    LOAD(close, "visjail_close");
    LOAD(poll, "visjail_poll");
    LOAD(wait, "visjail_wait");
    LOAD(kill, "visjail_kill");
#undef LOAD
    return 1;
}

static int package_sid(const wchar_t *work, unsigned char *destination) {
    PSECURITY_DESCRIPTOR sd = NULL;
    PACL acl = NULL;
    DWORD error = GetNamedSecurityInfoW((PWSTR)work, SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION, NULL, NULL, &acl, NULL, &sd);
    DWORD i;
    int found = 0;
    if (error) { SetLastError(error); return problem("context package ACL"); }
    for (i = 0; acl && i < acl->AceCount; i++) {
        ACCESS_ALLOWED_ACE *ace = NULL;
        PSID sid;
        SID_IDENTIFIER_AUTHORITY authority = SECURITY_APP_PACKAGE_AUTHORITY;
        if (!GetAce(acl, i, (LPVOID *)&ace) || ace->Header.AceType != ACCESS_ALLOWED_ACE_TYPE) continue;
        sid = &ace->SidStart;
        if (IsValidSid(sid) && !memcmp(GetSidIdentifierAuthority(sid), &authority, sizeof(authority)) &&
            *GetSidSubAuthorityCount(sid) == 8 && *GetSidSubAuthority(sid, 0) == 2) {
            found = CopySid(SECURITY_MAX_SID_SIZE, destination, sid) != 0;
            break;
        }
    }
    LocalFree(sd);
    return found;
}

static int synchronize(Shared *s, HANDLE ready, HANDLE proceed, LONG phase) {
    InterlockedExchange(&s->phase, phase);
    return SetEvent(ready) && WaitForSingleObject(proceed, DEADLINE) == WAIT_OBJECT_0;
}

static int guest_run(Api *api, int id, const wchar_t *context, Shared *s,
        HANDLE ready, HANDLE proceed, int sibling) {
    wchar_t executable[MAX_PATH], work[MAX_PATH];
    const wchar_t *args[6];
    char blob[8192], directory[CAP], profile[64], output[8192];
    int used = 0, result[4] = {-1, -1, -1, -1}, exit_code = -1, ok = 0;
    size_t output_used = 0;
    ULONGLONG deadline = GetTickCount64() + DEADLINE;
    int i, resumed = sibling;
    if (!join(executable, MAX_PATH, context, L"app\\guest.exe") ||
        !join(work, MAX_PATH, context, L"work") || !utf8(work, directory, CAP)) return 0;
    args[0] = executable;
    args[1] = sibling ? L"sibling" : L"allow";
    args[2] = s->original;
    args[3] = s->outside;
    args[4] = s->alias;
    args[5] = s->image;
    for (i = 0; i < 6; i++) {
        int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, args[i], -1,
            blob + used, (int)sizeof(blob) - used, NULL, NULL);
        if (!count) return 0;
        used += count;
    }
    sprintf_s(profile, sizeof(profile), "windows:%d", id);
    if (api->spawn(blob, used, "", 1, directory, profile,
        VISJAIL_CONFINED | VISJAIL_MERGE_STDERR, 0, 0, 0, 0,
        result, s->error, CAP) < 0) return 0;
    output[0] = 0;
    while (GetTickCount64() < deadline) {
        int available = api->poll(result[2], 100);
        if (available > 0) {
            char chunk[512];
            int count = api->read(result[2], chunk, (int)sizeof(chunk));
            if (count > 0) {
                fwrite(chunk, 1, (size_t)count, stdout);
                fflush(stdout);
                if (output_used + (size_t)count >= sizeof(output)) break;
                memcpy(output + output_used, chunk, (size_t)count);
                output_used += (size_t)count;
                output[output_used] = 0;
            }
        }
        if (!resumed && strstr(output, "GUEST_WROTE")) {
            if (!synchronize(s, ready, proceed, 2) || api->write(result[1], "c", 1) != 1) break;
            resumed = 1;
        }
        if (api->wait(result[0], 1, &exit_code) > 0) {
            ok = exit_code == 0 && resumed;
            result[0] = -1;
            break;
        }
    }
    if (result[0] >= 0) {
        api->kill(result[0], 9);
        api->wait(result[0], 0, &exit_code);
    }
    for (i = 1; i < 4; i++) if (result[i] >= 0) api->close(result[i]);
    return ok;
}

static int fixture(HANDLE mapping, HANDLE ready, HANDLE proceed) {
    Shared *s = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(*s));
    HMODULE module = NULL;
    Api api = {0};
    wchar_t contexts[2][MAX_PATH], work[MAX_PATH];
    char directory[CAP], guest[CAP];
    int ids[2] = {0, 0}, i, ok = 0;
    if (!s) return 1;
    module = LoadLibraryExW(s->dll, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!module || !load_api(module, &api) || !utf8(s->guest, guest, CAP)) goto done;
    for (i = 0; i < 2; i++) {
        if (!join(contexts[i], MAX_PATH, s->base, i ? L"context2" : L"context1") ||
            !CreateDirectoryW(contexts[i], NULL) || !utf8(contexts[i], directory, CAP)) goto done;
        ids[i] = api.create(directory, s->error, CAP);
        if (ids[i] <= 0 || api.stage(ids[i], guest, "guest.exe", s->error, CAP) < 0 ||
            api.seal(ids[i], s->error, CAP) < 0) goto done;
    }
    if (!join(work, MAX_PATH, contexts[0], L"work") || !package_sid(work, s->sid) ||
        !synchronize(s, ready, proceed, 1)) goto done;
    if (!guest_run(&api, ids[0], contexts[0], s, ready, proceed, 0) ||
        !guest_run(&api, ids[1], contexts[1], s, ready, proceed, 1)) goto done;
    ok = 1;
done:
    if (!ok) fprintf(stderr, "FAIL trusted fixture win32=%lu detail=%s\n", GetLastError(), s->error);
    for (i = 0; i < 2; i++) if (ids[i] > 0 && api.destroy(ids[i]) < 0) ok = 0;
    InterlockedExchange(&s->phase, ok ? 3 : -1);
    SetEvent(ready);
    if (module) FreeLibrary(module);
    UnmapViewOfFile(s);
    CloseHandle(proceed);
    CloseHandle(ready);
    CloseHandle(mapping);
    return ok ? 0 : 1;
}

static wchar_t *snapshot(HANDLE file, int require_medium) {
    PSECURITY_DESCRIPTOR sd = NULL;
    PACL sacl = NULL;
    LPWSTR text = NULL;
    DWORD error = GetSecurityInfo(file, SE_FILE_OBJECT, SECURITY_BITS,
        NULL, NULL, NULL, &sacl, &sd);
    DWORD i;
    if (error) { SetLastError(error); problem("host security snapshot"); return NULL; }
    for (i = 0; require_medium && sacl && i < sacl->AceCount; i++) {
        SYSTEM_MANDATORY_LABEL_ACE *ace = NULL;
        if (!GetAce(sacl, i, (LPVOID *)&ace)) goto done;
        if (ace->Header.AceType == SYSTEM_MANDATORY_LABEL_ACE_TYPE) {
            PSID sid = &ace->SidStart;
            if (!IsValidSid(sid) || *GetSidSubAuthority(sid,
                (DWORD)*GetSidSubAuthorityCount(sid) - 1) != SECURITY_MANDATORY_MEDIUM_RID) {
                SetLastError(ERROR_INVALID_SECURITY_DESCR);
                problem("host object must be MEDIUM (implicit MEDIUM accepted)");
                goto done;
            }
        }
    }
    if (!ConvertSecurityDescriptorToStringSecurityDescriptorW(sd, SDDL_REVISION_1,
        SECURITY_BITS, &text, NULL)) problem("host security SDDL");
done:
    LocalFree(sd);
    return text;
}

static int make_security(Broker *b, PSID package) {
    HANDLE token = NULL;
    union { TOKEN_USER align; unsigned char bytes[4096]; } user;
    DWORD size;
    LPWSTR owner = NULL, sid = NULL;
    wchar_t sddl[2048];
    int i, ok = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) ||
        !GetTokenInformation(token, TokenUser, user.bytes, sizeof(user.bytes), &size) ||
        !ConvertSidToStringSidW(((TOKEN_USER *)user.bytes)->User.Sid, &owner) ||
        !ConvertSidToStringSidW(package, &sid)) goto done;
    for (i = 0; i < 3; i++) {
        DWORD rights = i == 0 ? FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE :
            i == 1 ? FILE_GENERIC_READ | FILE_GENERIC_WRITE : FILE_GENERIC_READ;
        if (swprintf_s(sddl, 2048,
            L"O:%sG:%sD:P(A;;FA;;;%s)(A;;RC;;;OW)(A;;0x%lx;;;%s)S:(ML;;NW;;;LW)",
            owner, owner, owner, rights, sid) < 0 ||
            !ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1,
                &b->security[i], NULL)) goto done;
    }
    ok = 1;
done:
    if (sid) LocalFree(sid);
    if (owner) LocalFree(owner);
    if (token) CloseHandle(token);
    return ok;
}

static int start_fs(Broker *b, const wchar_t *mount, PSID sid, FSP_FILE_SYSTEM **fs) {
    FSP_FSCTL_VOLUME_PARAMS params = {0};
    NTSTATUS status;
    if (!make_security(b, sid)) return problem("synthetic private LOW security");
    params.SectorSize = 512;
    params.SectorsPerAllocationUnit = 1;
    params.MaxComponentLength = 255;
    params.VolumeCreationTime = 1;
    params.VolumeSerialNumber = 0x56535047;
    params.FileInfoTimeout = 0;
    params.CasePreservedNames = 1;
    params.UnicodeOnDisk = 1;
    params.PersistentAcls = 1;
    params.PostCleanupWhenModifiedOnly = 1;
    params.FlushAndPurgeOnCleanup = 1;
    params.UmFileContextIsUserContext2 = 1;
    wcscpy_s(params.FileSystemName, sizeof(params.FileSystemName) / sizeof(WCHAR), L"VisPolicyGate");
    status = FspFileSystemCreate(L"" FSP_FSCTL_DISK_DEVICE_NAME, &params, &policy_interface, fs);
    if (NT_SUCCESS(status)) {
        (*fs)->UserContext = b;
        status = FspFileSystemSetMountPoint(*fs, (PWSTR)mount);
    }
    if (NT_SUCCESS(status)) status = FspFileSystemStartDispatcher(*fs, 1);
    printf("WinFsp start NTSTATUS=0x%08lx\n", (unsigned long)status);
    return NT_SUCCESS(status);
}

static int host_io(Broker *b, int index, const char *expected, const char *replacement) {
    char data[8];
    DWORD count;
    LARGE_INTEGER zero;
    int ok;
    zero.QuadPart = 0;
    AcquireSRWLockExclusive(&b->lock);
    ok = SetFilePointerEx(b->files[index], zero, NULL, FILE_BEGIN) &&
        ReadFile(b->files[index], data, 8, &count, NULL) && count == 8 && !memcmp(data, expected, 8);
    if (ok && replacement) ok = SetFilePointerEx(b->files[index], zero, NULL, FILE_BEGIN) &&
        WriteFile(b->files[index], replacement, 8, &count, NULL) && count == 8 && FlushFileBuffers(b->files[index]);
    ReleaseSRWLockExclusive(&b->lock);
    printf("%s host live backing %s\n", ok ? "PASS" : "FAIL", expected);
    return ok;
}

static int prepare_file(const wchar_t *path, const char *data, HANDLE *file) {
    DWORD count;
    *file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE | READ_CONTROL,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    return *file != INVALID_HANDLE_VALUE && WriteFile(*file, data, 8, &count, NULL) &&
        count == 8 && FlushFileBuffers(*file);
}

static int start_fixture(HANDLE job, HANDLE mapping, HANDLE ready, HANDLE proceed,
        PROCESS_INFORMATION *process) {
    wchar_t executable[MAX_PATH], command[CAP];
    STARTUPINFOEXW startup = {0};
    SIZE_T bytes = 0;
    HANDLE inherited[3] = {mapping, ready, proceed};
    int ok = 0;
    DWORD length = GetModuleFileNameW(NULL, executable, MAX_PATH);
    if (!length || length >= MAX_PATH) return 0;
    if (swprintf_s(command, CAP, L"\"%s\" --fixture %llu %llu %llu", executable,
        (unsigned long long)(uintptr_t)mapping, (unsigned long long)(uintptr_t)ready,
        (unsigned long long)(uintptr_t)proceed) < 0) return 0;
    startup.StartupInfo.cb = sizeof(startup);
    InitializeProcThreadAttributeList(NULL, 1, 0, &bytes);
    startup.lpAttributeList = malloc(bytes);
    if (!startup.lpAttributeList) return 0;
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &bytes)) {
        free(startup.lpAttributeList);
        return 0;
    }
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        inherited, sizeof(inherited), NULL, NULL) ||
        !CreateProcessW(executable, command, NULL, NULL, TRUE,
            CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT, NULL, NULL,
            &startup.StartupInfo, process)) goto done;
    if (!AssignProcessToJobObject(job, process->hProcess) || ResumeThread(process->hThread) == (DWORD)-1) {
        TerminateProcess(process->hProcess, 99);
        WaitForSingleObject(process->hProcess, 5000);
        goto done;
    }
    ok = 1;
done:
    DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    return ok;
}

static int wait_phase(Shared *s, HANDLE ready, HANDLE process, LONG expected) {
    HANDLE handles[2] = {ready, process};
    DWORD waited = WaitForMultipleObjects(2, handles, FALSE, DEADLINE);
    if (waited != WAIT_OBJECT_0 || s->phase != expected) {
        fprintf(stderr, "FAIL fixture phase expected=%ld actual=%ld wait=%lu detail=%s\n",
            expected, s->phase, waited, s->error);
        return 0;
    }
    return 1;
}

typedef HRESULT (WINAPI *SetupFilter)(HANDLE, ULONG, LPCWSTR, LPCWSTR, LPCWSTR *, ULONG);

/* Trusted controls isolate BindFlt setup from LPAC and the WinFsp callbacks.
 * Each mapping has its own fresh silo; none is installed in the host namespace. */
static HRESULT setup_binding(SetupFilter setup, HANDLE job, const char *phase,
        const wchar_t *source, const wchar_t *target) {
    HRESULT result;
    SetLastError(ERROR_SUCCESS);
    result = setup(job, 0x00000004, source, target, NULL, 0);
    printf("BIND phase=%s job=%p flags=0x00000004 source=%ls target=%ls exceptions=NULL count=0 HRESULT=0x%08lx last-error=%lu\n",
        phase, (void *)job, source, target, (unsigned long)result, GetLastError());
    fflush(stdout);
    return result;
}

static int binding_reader(const wchar_t *path, const wchar_t *expected) {
    HANDLE file;
    char data[8], wanted[16];
    DWORD count = 0;
    int ok;
    if (!utf8(expected, wanted, sizeof(wanted)) || strlen(wanted) != 8) return 2;
    file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return 3;
    ok = ReadFile(file, data, sizeof(data), &count, NULL) && count == sizeof(data) &&
        !memcmp(data, wanted, sizeof(data));
    CloseHandle(file);
    return ok ? 0 : 4;
}

static int binding_control(SetupFilter setup, const char *label, const wchar_t *source,
        const wchar_t *target, const wchar_t *expected, int before_process) {
    HANDLE silo = NULL;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    wchar_t executable[MAX_PATH], command[CAP], path[MAX_PATH];
    DWORD length, code = 99;
    int ok = 0;
    HRESULT result;
    silo = CreateJobObjectW(NULL, NULL);
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!silo || !SetInformationJobObject(silo, JobObjectExtendedLimitInformation, &limits, sizeof(limits)) ||
        !SetInformationJobObject(silo, (JOBOBJECTINFOCLASS)35, NULL, 0)) {
        problem("control fresh silo"); goto done;
    }
    if (before_process) {
        result = setup_binding(setup, silo, label, source, target);
        if (FAILED(result)) goto done;
    }
    length = GetModuleFileNameW(NULL, executable, MAX_PATH);
    if (!length || length >= MAX_PATH || !join(path, MAX_PATH, source, L"sentinel.txt") ||
        swprintf_s(command, CAP, L"\"%s\" --binding-reader \"%s\" %s", executable, path, expected) < 0) goto done;
    startup.cb = sizeof(startup);
    if (!CreateProcessW(executable, command, NULL, NULL, FALSE, CREATE_SUSPENDED,
            NULL, NULL, &startup, &process) || !AssignProcessToJobObject(silo, process.hProcess)) {
        problem("control suspended child assignment"); goto done;
    }
    if (!before_process) {
        result = setup_binding(setup, silo, label, source, target);
        if (FAILED(result)) goto done;
    }
    if (ResumeThread(process.hThread) == (DWORD)-1 ||
        WaitForSingleObject(process.hProcess, DEADLINE) != WAIT_OBJECT_0 ||
        !GetExitCodeProcess(process.hProcess, &code)) goto done;
    ok = code == 0;
done:
    if (process.hProcess) {
        TerminateProcess(process.hProcess, 99);
        WaitForSingleObject(process.hProcess, 5000);
        CloseHandle(process.hProcess);
    }
    if (process.hThread) CloseHandle(process.hThread);
    if (silo) CloseHandle(silo);
    printf("CONTROL %s %s trusted-original-path-read exit=%lu (not LPAC policy verdict)\n",
        label, ok ? "PASS" : "FAIL", code);
    return ok;
}

static int ntfs_controls(SetupFilter setup, const wchar_t *base) {
    wchar_t source[MAX_PATH], target[MAX_PATH], path[MAX_PATH];
    HANDLE files[2] = {INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE};
    int prepared = 0, pre = 0, post = 0, host = 0;
    if (!join(source, MAX_PATH, base, L"control-source") || !CreateDirectoryW(source, NULL) ||
        !join(target, MAX_PATH, base, L"control-target") || !CreateDirectoryW(target, NULL) ||
        !join(path, MAX_PATH, source, L"sentinel.txt") || !prepare_file(path, "source!!", &files[0]) ||
        !join(path, MAX_PATH, target, L"sentinel.txt") || !prepare_file(path, "target!!", &files[1])) goto done;
    prepared = 1;
    pre = binding_control(setup, "NTFS-empty-silo", source, target, L"target!!", 1);
    post = binding_control(setup, "NTFS-assigned-suspended-child", source, target, L"target!!", 0);
    join(path, MAX_PATH, source, L"sentinel.txt");
    host = binding_reader(path, L"source!!") == 0;
    printf("CONTROL host-namespace-unmapped %s\n", host ? "PASS" : "FAIL");
done:
    if (files[0] != INVALID_HANDLE_VALUE) CloseHandle(files[0]);
    if (files[1] != INVALID_HANDLE_VALUE) CloseHandle(files[1]);
    if (!prepared) problem("NTFS control fixtures");
    return prepared && pre && post && host;
}

int wmain(int argc, wchar_t **argv) {
    Broker broker = {0};
    FSP_FILE_SYSTEM *fs = NULL;
    Shared *s = NULL;
    HANDLE mapping = NULL, ready = NULL, proceed = NULL, job = NULL;
    SECURITY_ATTRIBUTES inherit = {sizeof(inherit), NULL, TRUE};
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    PROCESS_INFORMATION child = {0};
    HMODULE bind_module = NULL;
    SetupFilter setup = NULL;
    FARPROC symbol;
    wchar_t base[MAX_PATH], backing[MAX_PATH], guid_text[64], drive[3], device[CAP], mount[4];
    wchar_t *before[3] = {NULL, NULL, NULL};
    GUID guid;
    HRESULT hr;
    DWORD code = 1;
    int i, ok = 0;
    if (argc == 4 && !wcscmp(argv[1], L"--binding-reader"))
        return binding_reader(argv[2], argv[3]);
    if (argc == 5 && !wcscmp(argv[1], L"--fixture"))
        return fixture((HANDLE)(uintptr_t)_wcstoui64(argv[2], NULL, 10),
            (HANDLE)(uintptr_t)_wcstoui64(argv[3], NULL, 10),
            (HANDLE)(uintptr_t)_wcstoui64(argv[4], NULL, 10));
    if (argc != 5) {
        fputs("usage: probe ABS_VISJAIL_DLL ABS_GUEST_EXE EXISTING_TEST_PARENT UNUSED_DRIVE:\n", stderr);
        return 2;
    }
    for (i = 0; i < 3; i++) broker.files[i] = INVALID_HANDLE_VALUE;
    InitializeSRWLock(&broker.lock);
    if (wcslen(argv[4]) != 2 || argv[4][1] != L':' || argv[4][0] < L'D' || argv[4][0] > L'Z') {
        problem("unused uppercase drive D:..Z: required"); goto done;
    }
    wcscpy_s(mount, 4, argv[4]);
    if (GetLogicalDrives() & (1UL << (mount[0] - L'A'))) {
        problem("mount drive already exists"); goto done;
    }
    if (QueryDosDeviceW(mount, device, CAP) || GetLastError() != ERROR_FILE_NOT_FOUND) {
        problem("mount DOS device must not exist"); goto done;
    }
    for (i = 1; i <= 3; i++) {
        if (wcslen(argv[i]) >= MAX_PATH || wcslen(argv[i]) < 3 ||
            argv[i][1] != L':' || argv[i][2] != L'\\') {
            problem("absolute bounded local drive paths required"); goto done;
        }
    }
    if (FAILED(CoCreateGuid(&guid)) || !StringFromGUID2(&guid, guid_text, 64) ||
        !join(base, MAX_PATH, argv[3], guid_text) || !CreateDirectoryW(base, NULL) ||
        !join(backing, MAX_PATH, base, L"backing") || !CreateDirectoryW(backing, NULL)) {
        problem("fresh test directories"); goto done;
    }
    wprintf(L"Test artifacts: %s\n", base);
    mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, &inherit, PAGE_READWRITE, 0, sizeof(Shared), NULL);
    ready = CreateEventW(&inherit, FALSE, FALSE, NULL);
    proceed = CreateEventW(&inherit, FALSE, FALSE, NULL);
    if (!mapping || !ready || !proceed) { problem("fixture synchronization"); goto done; }
    s = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(*s));
    if (!s) goto done;
    memset(s, 0, sizeof(*s));
    wcscpy_s(s->dll, MAX_PATH, argv[1]);
    wcscpy_s(s->guest, MAX_PATH, argv[2]);
    wcscpy_s(s->base, MAX_PATH, base);
    if (!join(s->original, MAX_PATH, backing, L"sentinel.txt") ||
        !join(s->image, MAX_PATH, backing, L"noexec.exe") ||
        !join(s->outside, MAX_PATH, base, L"outside.txt") ||
        !prepare_file(s->original, "host-one", &broker.files[1]) ||
        !prepare_file(s->outside, "outside!", &broker.files[0]) ||
        !CopyFileW(s->guest, s->image, TRUE)) { problem("host fixture files"); goto done; }
    broker.files[2] = CreateFileW(s->image, GENERIC_READ | READ_CONTROL,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (broker.files[2] == INVALID_HANDLE_VALUE) goto done;
    {
        LARGE_INTEGER image_size;
        if (!GetFileSizeEx(broker.files[2], &image_size) || image_size.QuadPart < 2 ||
            image_size.QuadPart > 2 * 1024 * 1024) { problem("bounded image size"); goto done; }
    }
    for (i = 0; i < 3; i++) {
        before[i] = snapshot(broker.files[i], 1);
        if (!before[i]) goto done;
    }
    drive[0] = s->original[0]; drive[1] = L':'; drive[2] = 0;
    if (!QueryDosDeviceW(drive, device, CAP) ||
        swprintf_s(s->alias, CAP, L"\\\\?\\GLOBALROOT%s%s", device, s->original + 2) < 0) goto done;
    {
        HANDLE positive = CreateFileW(s->alias, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        BY_HANDLE_FILE_INFORMATION original_info, alias_info;
        int same = positive != INVALID_HANDLE_VALUE &&
            GetFileInformationByHandle(positive, &alias_info) &&
            GetFileInformationByHandle(broker.files[1], &original_info) &&
            original_info.dwVolumeSerialNumber == alias_info.dwVolumeSerialNumber &&
            original_info.nFileIndexHigh == alias_info.nFileIndexHigh &&
            original_info.nFileIndexLow == alias_info.nFileIndexLow;
        if (positive != INVALID_HANDLE_VALUE) CloseHandle(positive);
        if (!same) { problem("raw alias host positive control identity"); goto done; }
    }
    bind_module = LoadLibraryExW(L"bindfltapi.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!bind_module) { problem("bindfltapi.dll"); goto done; }
    symbol = GetProcAddress(bind_module, "BfSetupFilter");
    if (!symbol) { problem("BfSetupFilter export"); goto done; }
    _Static_assert(sizeof(setup) == sizeof(symbol), "BindFlt function pointer size");
    memcpy(&setup, &symbol, sizeof(setup));
    printf("NTFS_DIAGNOSTIC_CONTROLS=%s\n", ntfs_controls(setup, base) ? "PASS" : "FAIL");
    job = CreateJobObjectW(NULL, NULL);
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job || !SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        problem("kill-on-close job"); goto done;
    }
    /* Microsoft hcsshim's JobObjectCreateSilo information class; empty job only. */
    if (!SetInformationJobObject(job, (JOBOBJECTINFOCLASS)35, NULL, 0)) {
        problem("JobObjectCreateSilo"); goto done;
    }
    if (!start_fixture(job, mapping, ready, proceed, &child)) { problem("suspended silo fixture"); goto done; }
    if (!wait_phase(s, ready, child.hProcess, 1) || !IsValidSid(s->sid) ||
        !start_fs(&broker, mount, s->sid, &fs)) goto done;
    {
        wchar_t target[4] = {mount[0], L':', L'\\', 0};
        (void)binding_control(setup, "WinFsp-empty-silo", backing, target, L"host-one", 1);
        /* BINDFLT_FLAG_USE_CURRENT_SILO_MAPPING, not READ_ONLY or MERGED. */
        hr = setup_binding(setup, job, "WinFsp-running-real-context-fixture", backing, target);
        if (FAILED(hr)) goto done;
    }
    if (!SetEvent(proceed) || !wait_phase(s, ready, child.hProcess, 2) ||
        !host_io(&broker, 1, "guestone", "host-two") || !SetEvent(proceed) ||
        !wait_phase(s, ready, child.hProcess, 3) ||
        WaitForSingleObject(child.hProcess, DEADLINE) != WAIT_OBJECT_0 ||
        !GetExitCodeProcess(child.hProcess, &code) || code != 0) goto done;
    ok = host_io(&broker, 1, "host-two", NULL) && host_io(&broker, 0, "outside!", NULL);
    for (i = 0; i < 3; i++) {
        wchar_t *after = snapshot(broker.files[i], 1);
        int same = after && !wcscmp(before[i], after);
        printf("%s exact host owner/group/DACL/MIC unchanged object=%d\n", same ? "PASS" : "FAIL", i);
        if (!same) ok = 0;
        if (after) LocalFree(after);
    }
done:
    /* No descendant may outlive the synthetic view or its trusted host handles. */
    if (job) TerminateJobObject(job, 99);
    if (child.hProcess) {
        if (WaitForSingleObject(child.hProcess, 5000) != WAIT_OBJECT_0) ok = 0;
        CloseHandle(child.hProcess);
    }
    if (child.hThread) CloseHandle(child.hThread);
    if (job) CloseHandle(job);
    if (fs) { FspFileSystemStopDispatcher(fs); FspFileSystemDelete(fs); }
    for (i = 0; i < 3; i++) {
        if (before[i]) LocalFree(before[i]);
        if (broker.security[i]) LocalFree(broker.security[i]);
        if (broker.files[i] != INVALID_HANDLE_VALUE) CloseHandle(broker.files[i]);
    }
    if (bind_module) FreeLibrary(bind_module);
    if (s) UnmapViewOfFile(s);
    if (mapping) CloseHandle(mapping);
    if (ready) CloseHandle(ready);
    if (proceed) CloseHandle(proceed);
    puts(ok ? "BOUNDED_POLICY_COMPOSITION_PASS" : "BOUNDED_POLICY_COMPOSITION_FAIL");
    return ok ? 0 : 1;
}
