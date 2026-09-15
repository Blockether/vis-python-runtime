/* Test-only adversarial guest for WindowsJail. Never shipped in runtime archives. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define _CRT_SECURE_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <winternl.h>
#include <tlhelp32.h>
#include <wct.h>
#include <aclapi.h>
#include <sddl.h>
#include <userenv.h>
#include <objbase.h>
#include <stdint.h>
#include <intrin.h>
#include <winioctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <io.h>
#include <fcntl.h>

#pragma intrinsic(_ReturnAddress)

static int failures;

static void check(int ok, const char *name) {
    if (!ok) {
        fprintf(stderr, "FAIL %s (win32=%lu)\n", name, GetLastError());
        failures++;
    }
}

static void report_path(const char *name, const wchar_t *value) {
    printf("%s=", name);
    while (*value) printf("%04X", (unsigned int)*value++);
    printf("\n");
}

static void denied(const wchar_t *path, DWORD access, const char *name) {
    HANDLE file = CreateFileW(path, access, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file == INVALID_HANDLE_VALUE && GetLastError() == ERROR_ACCESS_DENIED, name);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
}

static void staged_denied(const wchar_t *path, DWORD access, const char *name) {
    HANDLE file = CreateFileW(path, access, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD error = GetLastError();
    check(file == INVALID_HANDLE_VALUE && (error == ERROR_ACCESS_DENIED || error == ERROR_SHARING_VIOLATION), name);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    else printf("STAGED_DENIAL=%lu %s\n", error, name);
}

static void system_root_check(void) {
    wchar_t directory[MAX_PATH + 1], value[MAX_PATH + 1];
    UINT length = GetSystemWindowsDirectoryW(directory, MAX_PATH + 1);
    DWORD value_length = GetEnvironmentVariableW(L"SystemRoot", value, MAX_PATH + 1);
    check(length > 0 && length <= MAX_PATH && value_length == length && _wcsicmp(value, directory) == 0,
          "SystemRoot is the OS-derived Windows directory");
}

/* Check effective LPAC access to both package groups, not just a token-information flag. */
static void lpac_check(HANDLE token) {
    HANDLE impersonation = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    GENERIC_MAPPING mapping = {0};
    PRIVILEGE_SET initial = {0}, *privileges = &initial;
    DWORD size = sizeof(initial), granted = 0;
    BOOL allowed = FALSE, checked;
    check(DuplicateToken(token, SecurityImpersonation, &impersonation), "duplicate LPAC token for access check");
    if (!impersonation) return;
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(
              L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)",
              SDDL_REVISION_1, &descriptor, NULL), "LPAC package access descriptor");
    if (descriptor) {
        checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
            privileges, &size, &granted, &allowed);
        if (!checked && GetLastError() == ERROR_INSUFFICIENT_BUFFER) {
            privileges = malloc(size);
            check(privileges != NULL, "LPAC access-check privilege buffer");
            if (privileges) checked = AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping,
                privileges, &size, &granted, &allowed);
        }
        check(checked, "kernel LPAC access check");
        if (checked) {
            printf("LPAC_ACCESS=%lu\n", granted);
            check(allowed && granted == 0x2, "LPAC allows restricted packages, not all application packages");
        }
        LocalFree(descriptor);
    }
    if (privileges != &initial) free(privileges);
    CloseHandle(impersonation);
}

static void token_check(void) {
    HANDLE token = NULL;
    DWORD size = 0, value = 0;
    unsigned char data[4096];
    BOOL in_job = FALSE;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token), "open token");
    if (!token) return;
    check(GetTokenInformation(token, TokenIsAppContainer, &value, sizeof(value), &size)
          && value == 1, "AppContainer token");
    lpac_check(token);
    if (GetTokenInformation(token, TokenIntegrityLevel, data, sizeof(data), &size)) {
        PSID integrity = ((TOKEN_MANDATORY_LABEL *)data)->Label.Sid;
        DWORD rid = *GetSidSubAuthority(integrity, (DWORD)*GetSidSubAuthorityCount(integrity) - 1);
        check(rid == SECURITY_MANDATORY_LOW_RID, "low mandatory integrity");
    } else check(0, "query mandatory integrity");
    {
        PSID *groups = NULL, *capabilities = NULL;
        DWORD group_count = 0, capability_count = 0;
        BOOL derived = DeriveCapabilitySidsFromName(L"registryRead", &groups, &group_count,
                                                   &capabilities, &capability_count);
        BOOL expected = derived && capability_count == 1 && capabilities && capabilities[0]
                        && IsValidSid(capabilities[0]);
        check(expected, "derive exact registryRead capability");
        if (expected) {
            BOOL queried = GetTokenInformation(token, TokenCapabilities, data, sizeof(data), &size);
            TOKEN_GROUPS *actual = (TOKEN_GROUPS *)data;
            BOOL exact = queried && actual->GroupCount == 1
                         && actual->Groups[0].Attributes == SE_GROUP_ENABLED
                         && EqualSid(actual->Groups[0].Sid, capabilities[0]);
            if (queried && !exact) printf("CAPABILITY_COUNT=%lu ATTRIBUTES=%08lx\n", actual->GroupCount,
                                         actual->GroupCount ? actual->Groups[0].Attributes : 0UL);
            check(exact, "only enabled registryRead capability, no network or ambient capabilities");
        }
        if (groups) for (DWORD i = 0; i < group_count; i++) LocalFree(groups[i]);
        if (capabilities) for (DWORD i = 0; i < capability_count; i++) LocalFree(capabilities[i]);
        LocalFree(groups);
        LocalFree(capabilities);
    }
    if (GetTokenInformation(token, TokenAppContainerSid, data, sizeof(data), &size)) {
        LPWSTR sid = NULL;
        if (ConvertSidToStringSidW(((TOKEN_APPCONTAINER_INFORMATION *)data)->TokenAppContainer, &sid)) {
            printf("SID=%ls\n", sid);
            LocalFree(sid);
        } else check(0, "format AppContainer SID");
    } else check(0, "query AppContainer SID");
    check(IsProcessInJob(GetCurrentProcess(), NULL, &in_job) && in_job, "assigned job");
    CloseHandle(token);
    system_root_check();
}

static void security_check(int argc, wchar_t **argv) {
    wchar_t path[32768], extended[32768], work[32768];
    HANDLE file;
    DWORD written = 0;
    check(argc == 8, "security arguments");
    if (argc != 8) return;
    token_check();
    denied(argv[2], GENERIC_READ, "private host read denied");
    denied(argv[2], GENERIC_WRITE, "private host write denied");
    denied(argv[2], WRITE_DAC, "host-owner WRITE_DAC denied");
    denied(argv[2], WRITE_OWNER, "host-owner WRITE_OWNER denied");
    denied(argv[6], GENERIC_READ, "host-created junction cannot grant host read");
    denied(argv[6], GENERIC_WRITE, "host-created junction cannot grant host write");
    denied(argv[6], WRITE_DAC, "host-created junction cannot grant host ACL access");
    check(SetNamedSecurityInfoW(argv[2], SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                               NULL, NULL, NULL, NULL) == ERROR_ACCESS_DENIED,
          "cannot replace host DACL with NULL DACL");
    file = CreateFileW(argv[3], GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file != INVALID_HANDLE_VALUE, "staged input is readable");
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    staged_denied(argv[3], GENERIC_WRITE, "staged input mutation denied");
    staged_denied(argv[3], DELETE, "staged input deletion denied");
    staged_denied(argv[3], WRITE_DAC, "staged input ACL mutation denied");
    denied(argv[4], GENERIC_READ, "sibling context read denied");
    denied(argv[4], GENERIC_WRITE, "sibling context write denied");
    swprintf_s(extended, 32768, L"\\\\?\\%ls", argv[2]);
    denied(extended, GENERIC_READ, "extended path cannot escape");
    swprintf_s(path, 32768, L"%ls:probe", argv[2]);
    file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file == INVALID_HANDLE_VALUE && GetLastError() == ERROR_ACCESS_DENIED, "host alternate stream creation denied");
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    check(GetCurrentDirectoryW(32768, work) > 0, "read private cwd");
    swprintf_s(path, 32768, L"%ls\\work-file.txt", work);
    file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file != INVALID_HANDLE_VALUE, "create private work file");
    if (file != INVALID_HANDLE_VALUE) {
        check(WriteFile(file, "work", 4, &written, NULL) && written == 4, "write private work file");
        CloseHandle(file);
        check(DeleteFileW(path), "delete private work file");
    }
    {
        DWORD length = GetEnvironmentVariableW(L"TEMP", extended, 32768);
        check(length > 0 && length < 32768, "private temporary directory is set");
        if (length > 0 && length < 32768) {
            DWORD create_error;
            int expected = _wcsicmp(extended, argv[7]) == 0;
            check(expected, "TEMP matches the private temporary directory");
            swprintf_s(path, 32768, L"%ls\\temporary-file.txt", extended);
            file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
            create_error = GetLastError();
            if (file == INVALID_HANDLE_VALUE || !expected) {
                const wchar_t *keys[] = {L"TEMP", L"TMP", L"LOCALAPPDATA"};
                const char *labels[] = {"TEMP", "TMP", "LOCALAPPDATA"};
                /* CI 34869727586: distinguish effective environment changes from missing paths. */
                report_path("TEMP_EXPECTED", argv[7]);
                report_path("TEMP_CREATE_PATH", path);
                for (int i = 0; i < 3; i++) {
                    DWORD attributes;
                    SetLastError(ERROR_SUCCESS);
                    length = GetEnvironmentVariableW(keys[i], extended, 32768);
                    printf("%s_LENGTH=%lu ERROR=%lu\n", labels[i], length, GetLastError());
                    if (!length || length >= 32768) continue;
                    report_path(labels[i], extended);
                    SetLastError(ERROR_SUCCESS);
                    attributes = GetFileAttributesW(extended);
                    printf("%s_ATTRIBUTES=%lu ERROR=%lu\n", labels[i], attributes, GetLastError());
                }
            }
            SetLastError(create_error);
            check(file != INVALID_HANDLE_VALUE, "create low-integrity temporary file");
            if (file != INVALID_HANDLE_VALUE) {
                check(WriteFile(file, "tmp", 3, &written, NULL) && written == 3, "write private temporary file");
                CloseHandle(file);
                check(DeleteFileW(path), "delete private temporary file");
            }
        }
    }
    swprintf_s(path, 32768, L"%ls\\escape-link", work);
    if (CreateHardLinkW(path, argv[2], NULL)) {
        denied(path, GENERIC_READ, "hardlink cannot grant host read");
        denied(path, GENERIC_WRITE, "hardlink cannot grant host write");
        DeleteFileW(path);
    }
    swprintf_s(path, 32768, L"%ls\\symbolic-escape", work);
    if (CreateSymbolicLinkW(path, argv[2], SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE)) {
        denied(path, GENERIC_READ, "symlink cannot grant host read");
        denied(path, GENERIC_WRITE, "symlink cannot grant host write");
        DeleteFileW(path);
    }
    swprintf_s(path, 32768, L"%ls\\new-file", argv[5]);
    file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file == INVALID_HANDLE_VALUE, "staged application directory immutable");
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    denied(L"\\\\.\\PhysicalDrive0", GENERIC_READ | GENERIC_WRITE, "raw disk denied");
}

static void network_one(int family, int type, unsigned short port, int public_address) {
    SOCKET socket_handle = socket(family, type, type == SOCK_STREAM ? IPPROTO_TCP : IPPROTO_UDP);
    SOCKADDR_STORAGE address;
    int length, result, error;
    u_long nonblocking = 1;
    if (socket_handle == INVALID_SOCKET) {
        /* LPAC can deny socket creation before connect/send can be attempted. */
        error = WSAGetLastError();
        if (error != WSAEACCES) {
            fprintf(stderr, "FAIL socket creation family=%d type=%d public=%d error=%d\n",
                    family, type, public_address, error);
            failures++;
        }
        return;
    }
    ZeroMemory(&address, sizeof(address));
    if (family == AF_INET) {
        SOCKADDR_IN *ipv4 = (SOCKADDR_IN *)&address;
        ipv4->sin_family = AF_INET;
        ipv4->sin_port = htons(port);
        InetPtonW(AF_INET, public_address ? L"203.0.113.1" : L"127.0.0.1", &ipv4->sin_addr);
        length = sizeof(*ipv4);
    } else {
        SOCKADDR_IN6 *ipv6 = (SOCKADDR_IN6 *)&address;
        ipv6->sin6_family = AF_INET6;
        ipv6->sin6_port = htons(port);
        InetPtonW(AF_INET6, public_address ? L"2001:db8::1" : L"::1", &ipv6->sin6_addr);
        length = sizeof(*ipv6);
    }
    check(ioctlsocket(socket_handle, FIONBIO, &nonblocking) == 0, "bounded network probe");
    if (type == SOCK_STREAM) result = connect(socket_handle, (SOCKADDR *)&address, length);
    else result = sendto(socket_handle, "x", 1, 0, (SOCKADDR *)&address, length);
    error = WSAGetLastError();
    if (result != SOCKET_ERROR || error != WSAEACCES) {
        fprintf(stderr, "FAIL network family=%d type=%d public=%d result=%d error=%d\n",
                family, type, public_address, result, error);
        failures++;
    }
    closesocket(socket_handle);
}

static void network_check(int argc, wchar_t **argv) {
    WSADATA data;
    int public_address;
    check(argc == 6, "network arguments");
    if (argc != 6) return;
    check(WSAStartup(MAKEWORD(2, 2), &data) == 0, "Winsock startup");
    for (public_address = 0; public_address < 2; public_address++) {
        network_one(AF_INET, SOCK_STREAM, (unsigned short)_wtoi(argv[2]), public_address);
        network_one(AF_INET, SOCK_DGRAM, (unsigned short)_wtoi(argv[3]), public_address);
        network_one(AF_INET6, SOCK_STREAM, (unsigned short)_wtoi(argv[4]), public_address);
        network_one(AF_INET6, SOCK_DGRAM, (unsigned short)_wtoi(argv[5]), public_address);
    }
    WSACleanup();
}

typedef LONG (__stdcall *last_ntstatus_fn)(void);
typedef void (__stdcall *set_last_error_fn)(LONG);

typedef LONG (__stdcall *nt_create_fn)(
    PHANDLE, PHANDLE, ACCESS_MASK, ACCESS_MASK, const void *, const void *,
    ULONG, ULONG, PVOID, PVOID, PVOID);
typedef LONG (__stdcall *nt_info_fn)(HANDLE, int, PVOID, ULONG);
typedef LONG (__stdcall *nt_query_fn)(HANDLE, int, PVOID, ULONG, PULONG);
typedef LONG (__stdcall *nt_memory_fn)(HANDLE, PVOID, PVOID, SIZE_T, PSIZE_T);
typedef LONG (__stdcall *nt_open_token_fn)(HANDLE, ACCESS_MASK, PHANDLE);
typedef LONG (__stdcall *nt_open_token_ex_fn)(HANDLE, ACCESS_MASK, ULONG, PHANDLE);
typedef LONG (__stdcall *nt_open_key_fn)(PHANDLE, ACCESS_MASK, PVOID);
typedef LONG (__stdcall *nt_query_value_fn)(HANDLE, const void *, int, PVOID, ULONG, PULONG);
typedef LONG (__stdcall *nt_duplicate_fn)(HANDLE, HANDLE, HANDLE, PHANDLE, ACCESS_MASK, ULONG, ULONG);
typedef LONG (__stdcall *nt_terminate_fn)(HANDLE, LONG);
typedef ULONG (__stdcall *nt_error_fn)(LONG);
typedef LONG (__stdcall *nt_resume_fn)(HANDLE, PULONG);
typedef LONG (__stdcall *csr_call_fn)(PVOID, PVOID, ULONG, ULONG);

static nt_create_fn original_nt_create;
static nt_info_fn original_process_info, original_thread_info;
static nt_query_fn original_process_query, original_thread_query, original_token_query;
static nt_memory_fn original_read_memory, original_write_memory;
static nt_open_token_fn original_open_token;
static nt_open_token_ex_fn original_open_token_ex;
static nt_open_key_fn original_open_key, original_open_key_alias;
static nt_query_value_fn original_query_value, original_query_value_alias;
static nt_duplicate_fn original_duplicate;
static nt_terminate_fn original_terminate;
static nt_error_fn original_error_conversion, original_error_conversion_no_teb;
static set_last_error_fn original_set_nt_status, original_set_win32_error;
static nt_resume_fn original_resume;
static csr_call_fn original_csr_call;
static volatile LONG native_calls;
static BOOL native_started;
static struct {
    const wchar_t *name;
    uintptr_t base;
    DWORD size, timestamp;
} native_modules[] = {
    {L"KernelBase.dll", 0, 0, 0},
    {L"kernel32.dll", 0, 0, 0}
};

static struct native_step {
    const char *operation;
    const wchar_t *caller_module;
    LONG status, message_status, input_status;
    DWORD caller_rva;
    BOOL no_result;
    ULONG state, process_flags, thread_flags, info_class, length;
    ULONG api_number, message_api, message_data, message_total, access_mask, options;
    SIZE_T memory_size;
    BOOL message_valid;
} native_steps[128];

static struct native_step *record_native_step(const char *operation, LONG status) {
    ULONG index;
    /* This controlled launch traces native creation onward, not earlier setup. */
    if (!native_started) return NULL;
    index = (ULONG)InterlockedIncrement(&native_calls) - 1;
    if (index >= 128) return NULL;
    native_steps[index].operation = operation;
    native_steps[index].status = status;
    return &native_steps[index];
}

static void record_native_caller(struct native_step *step, const void *caller) {
    uintptr_t address = (uintptr_t)caller;
    if (!step) return;
    step->caller_module = L"unresolved";
    for (SIZE_T i = 0; i < sizeof(native_modules) / sizeof(native_modules[0]); i++) {
        if (address >= native_modules[i].base && address - native_modules[i].base < native_modules[i].size) {
            step->caller_module = native_modules[i].name;
            step->caller_rva = (DWORD)(address - native_modules[i].base);
            break;
        }
    }
}

static void record_error_step(const char *operation, LONG result, LONG input, BOOL no_result, const void *caller) {
    struct native_step *step = record_native_step(operation, result);
    if (!step) return;
    step->input_status = input;
    step->no_result = no_result;
    record_native_caller(step, caller);
}

/* Observe actual calls; never replace their arguments, output buffers or results. */
static LONG __stdcall observe_nt_create(
    PHANDLE process, PHANDLE thread, ACCESS_MASK process_access, ACCESS_MASK thread_access,
    const void *process_attributes, const void *thread_attributes, ULONG process_flags,
    ULONG thread_flags, PVOID parameters, PVOID create_info, PVOID attributes) {
    LONG status = original_nt_create(process, thread, process_access, thread_access,
                                     process_attributes, thread_attributes, process_flags,
                                     thread_flags, parameters, create_info, attributes);
    struct native_step *step;
    native_started = TRUE;
    step = record_native_step("NtCreateUserProcess", status);
    if (step) {
        SIZE_T size = 0;
        step->state = MAXDWORD;
        step->process_flags = process_flags;
        step->thread_flags = thread_flags;
        /* PS_CREATE_INFO begins with SIZE_T Size and a 32-bit PS_CREATE_STATE. */
        if (create_info) memcpy(&size, create_info, sizeof(size));
        if (size >= sizeof(size) + sizeof(ULONG))
            memcpy(&step->state, (const BYTE *)create_info + sizeof(size), sizeof(ULONG));
    }
    return status;
}

static LONG __stdcall observe_process_info(HANDLE process, int info_class, PVOID info, ULONG length) {
    LONG status = original_process_info(process, info_class, info, length);
    struct native_step *step = record_native_step("NtSetInformationProcess", status);
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_thread_info(HANDLE thread, int info_class, PVOID info, ULONG length) {
    LONG status = original_thread_info(thread, info_class, info, length);
    struct native_step *step = record_native_step("NtSetInformationThread", status);
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_process_query(HANDLE process, int info_class, PVOID info, ULONG length, PULONG returned) {
    LONG status = original_process_query(process, info_class, info, length, returned);
    struct native_step *step = record_native_step("NtQueryInformationProcess", status);
    record_native_caller(step, _ReturnAddress());
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_thread_query(HANDLE thread, int info_class, PVOID info, ULONG length, PULONG returned) {
    LONG status = original_thread_query(thread, info_class, info, length, returned);
    struct native_step *step = record_native_step("NtQueryInformationThread", status);
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_token_query(HANDLE token, int info_class, PVOID info, ULONG length, PULONG returned) {
    LONG status = original_token_query(token, info_class, info, length, returned);
    struct native_step *step = record_native_step("NtQueryInformationToken", status);
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_read_memory(HANDLE process, PVOID address, PVOID buffer, SIZE_T size, PSIZE_T returned) {
    LONG status = original_read_memory(process, address, buffer, size, returned);
    struct native_step *step = record_native_step("NtReadVirtualMemory", status);
    if (step) step->memory_size = size;
    return status;
}

static LONG __stdcall observe_write_memory(HANDLE process, PVOID address, PVOID buffer, SIZE_T size, PSIZE_T returned) {
    LONG status = original_write_memory(process, address, buffer, size, returned);
    struct native_step *step = record_native_step("NtWriteVirtualMemory", status);
    if (step) step->memory_size = size;
    return status;
}

static LONG __stdcall observe_open_token(HANDLE process, ACCESS_MASK access, PHANDLE token) {
    LONG status = original_open_token(process, access, token);
    struct native_step *step = record_native_step("NtOpenProcessToken", status);
    if (step) step->access_mask = access;
    return status;
}

static LONG __stdcall observe_open_token_ex(HANDLE process, ACCESS_MASK access, ULONG attributes, PHANDLE token) {
    LONG status = original_open_token_ex(process, access, attributes, token);
    struct native_step *step = record_native_step("NtOpenProcessTokenEx", status);
    if (step) step->access_mask = access;
    return status;
}

static LONG __stdcall observe_open_key(PHANDLE key, ACCESS_MASK access, PVOID attributes) {
    LONG status = original_open_key(key, access, attributes);
    struct native_step *step = record_native_step("NtOpenKey", status);
    record_native_caller(step, _ReturnAddress());
    if (step) step->access_mask = access;
    return status;
}

static LONG __stdcall observe_open_key_alias(PHANDLE key, ACCESS_MASK access, PVOID attributes) {
    LONG status = original_open_key_alias(key, access, attributes);
    struct native_step *step = record_native_step("ZwOpenKey", status);
    record_native_caller(step, _ReturnAddress());
    if (step) step->access_mask = access;
    return status;
}

static LONG __stdcall observe_query_value(HANDLE key, const void *name, int info_class,
                                         PVOID info, ULONG length, PULONG returned) {
    LONG status = original_query_value(key, name, info_class, info, length, returned);
    struct native_step *step = record_native_step("NtQueryValueKey", status);
    record_native_caller(step, _ReturnAddress());
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_query_value_alias(HANDLE key, const void *name, int info_class,
                                               PVOID info, ULONG length, PULONG returned) {
    LONG status = original_query_value_alias(key, name, info_class, info, length, returned);
    struct native_step *step = record_native_step("ZwQueryValueKey", status);
    record_native_caller(step, _ReturnAddress());
    if (step) { step->info_class = (ULONG)info_class; step->length = length; }
    return status;
}

static LONG __stdcall observe_duplicate(HANDLE source_process, HANDLE source, HANDLE target_process,
                                        PHANDLE target, ACCESS_MASK access, ULONG attributes, ULONG options) {
    LONG status = original_duplicate(source_process, source, target_process, target, access, attributes, options);
    struct native_step *step = record_native_step("NtDuplicateObject", status);
    if (step) { step->access_mask = access; step->options = options; }
    return status;
}

static LONG __stdcall observe_terminate(HANDLE process, LONG exit_status) {
    LONG status = original_terminate(process, exit_status);
    record_error_step("NtTerminateProcess", status, exit_status, FALSE, _ReturnAddress());
    return status;
}

static ULONG __stdcall observe_error_conversion(LONG status) {
    ULONG error = original_error_conversion(status);
    record_error_step("RtlNtStatusToDosError", (LONG)error, status, FALSE, _ReturnAddress());
    return error;
}

static ULONG __stdcall observe_error_conversion_no_teb(LONG status) {
    ULONG error = original_error_conversion_no_teb(status);
    record_error_step("RtlNtStatusToDosErrorNoTeb", (LONG)error, status, FALSE, _ReturnAddress());
    return error;
}

static void __stdcall observe_set_nt_status(LONG status) {
    original_set_nt_status(status);
    record_error_step("RtlSetLastWin32ErrorAndNtStatusFromNtStatus", 0, status, TRUE, _ReturnAddress());
}

static void __stdcall observe_set_win32_error(LONG error) {
    original_set_win32_error(error);
    record_error_step("RtlSetLastWin32Error", 0, error, TRUE, _ReturnAddress());
}

static LONG __stdcall observe_resume(HANDLE thread, PULONG previous_count) {
    LONG status = original_resume(thread, previous_count);
    record_native_step("NtResumeThread", status);
    return status;
}

static LONG __stdcall observe_csr_call(PVOID message, PVOID capture, ULONG api_number, ULONG length) {
    LONG status = original_csr_call(message, capture, api_number, length);
    struct native_step *step = record_native_step("CsrClientCallServer", status);
    if (step) {
        USHORT lengths[2] = {0};
        step->api_number = api_number;
        step->length = length;
        /* The x64 CSR prefix follows a 40-byte PORT_MESSAGE and capture pointer.
         * Validate the returned lengths and API before interpreting its status. */
        if (message) memcpy(lengths, message, sizeof(lengths));
        step->message_data = lengths[0];
        step->message_total = lengths[1];
        if (lengths[1] >= 56 && (ULONG)lengths[0] + 40 == lengths[1]) {
            memcpy(&step->message_api, (const BYTE *)message + 48, sizeof(ULONG));
            if (step->message_api == api_number) {
                memcpy(&step->message_status, (const BYTE *)message + 52, sizeof(LONG));
                step->message_valid = TRUE;
            }
        }
    }
    return status;
}

/* Search only the loaded OS module's bounded x64 import-address table. */
static ULONGLONG *native_import(SIZE_T module_index, const char *name) {
    BYTE *module = (BYTE *)GetModuleHandleW(native_modules[module_index].name);
    FARPROC address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), name);
    IMAGE_DOS_HEADER *dos;
    IMAGE_NT_HEADERS64 *headers;
    IMAGE_DATA_DIRECTORY table;
    ULONGLONG expected, *slots;
    if (!module || !address) return NULL;
    dos = (IMAGE_DOS_HEADER *)module;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE || dos->e_lfanew <= 0 || dos->e_lfanew >= 4096) return NULL;
    headers = (IMAGE_NT_HEADERS64 *)(module + dos->e_lfanew);
    if (headers->Signature != IMAGE_NT_SIGNATURE || headers->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC ||
        headers->OptionalHeader.NumberOfRvaAndSizes <= IMAGE_DIRECTORY_ENTRY_IAT) return NULL;
    native_modules[module_index].base = (uintptr_t)module;
    native_modules[module_index].size = headers->OptionalHeader.SizeOfImage;
    native_modules[module_index].timestamp = headers->FileHeader.TimeDateStamp;
    table = headers->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IAT];
    if (!table.VirtualAddress || table.VirtualAddress % sizeof(ULONGLONG) ||
        table.VirtualAddress >= headers->OptionalHeader.SizeOfImage ||
        table.Size > headers->OptionalHeader.SizeOfImage - table.VirtualAddress ||
        table.Size % sizeof(ULONGLONG)) return NULL;
    memcpy(&expected, &address, sizeof(expected));
    slots = (ULONGLONG *)(module + table.VirtualAddress);
    for (SIZE_T i = 0; i < table.Size / sizeof(ULONGLONG); i++)
        if (slots[i] == expected) return &slots[i];
    return NULL;
}

/* This changes only the diagnostic guest's private import-page mapping. */
static BOOL replace_nt_import(ULONGLONG *slot, ULONGLONG value) {
    DWORD protection, unused;
    if (!VirtualProtect(slot, sizeof(*slot), PAGE_READWRITE, &protection)) return FALSE;
    *slot = value;
    check(VirtualProtect(slot, sizeof(*slot), protection, &unused), "restore diagnostic import page protection");
    return TRUE;
}

static void child_launch_diagnostics(const wchar_t *executable, last_ntstatus_fn last_status,
                                     set_last_error_fn reset_status) {
    static const char *const variants[] = {
        "ordinary-traced", "current-token-inherited-desktop", "current-token-empty-desktop"
    };
    wchar_t command[32768], desktop[] = L"";
    HANDLE token = NULL;
    nt_create_fn create_observer = observe_nt_create;
    nt_info_fn process_observer = observe_process_info, thread_observer = observe_thread_info;
    nt_query_fn process_query_observer = observe_process_query, thread_query_observer = observe_thread_query;
    nt_query_fn token_query_observer = observe_token_query;
    nt_memory_fn read_observer = observe_read_memory, write_observer = observe_write_memory;
    nt_open_token_fn open_token_observer = observe_open_token;
    nt_open_token_ex_fn open_token_ex_observer = observe_open_token_ex;
    nt_open_key_fn open_key_observer = observe_open_key, open_key_alias_observer = observe_open_key_alias;
    nt_query_value_fn query_value_observer = observe_query_value, query_value_alias_observer = observe_query_value_alias;
    nt_duplicate_fn duplicate_observer = observe_duplicate;
    nt_terminate_fn terminate_observer = observe_terminate;
    nt_error_fn error_observer = observe_error_conversion, no_teb_observer = observe_error_conversion_no_teb;
    set_last_error_fn set_nt_observer = observe_set_nt_status, set_win32_observer = observe_set_win32_error;
    nt_resume_fn resume_observer = observe_resume;
    csr_call_fn csr_observer = observe_csr_call;
    struct native_import_slot {
        ULONGLONG *address, saved;
        BOOL installed;
    };
    struct {
        const char *name;
        void *original;
        const void *observer;
        struct native_import_slot slots[2];
    } imports[] = {
        {"NtCreateUserProcess", &original_nt_create, &create_observer, {{0}}},
        {"NtSetInformationProcess", &original_process_info, &process_observer, {{0}}},
        {"NtSetInformationThread", &original_thread_info, &thread_observer, {{0}}},
        {"NtQueryInformationProcess", &original_process_query, &process_query_observer, {{0}}},
        {"NtQueryInformationThread", &original_thread_query, &thread_query_observer, {{0}}},
        {"NtQueryInformationToken", &original_token_query, &token_query_observer, {{0}}},
        {"NtReadVirtualMemory", &original_read_memory, &read_observer, {{0}}},
        {"NtWriteVirtualMemory", &original_write_memory, &write_observer, {{0}}},
        {"NtOpenProcessToken", &original_open_token, &open_token_observer, {{0}}},
        {"NtOpenProcessTokenEx", &original_open_token_ex, &open_token_ex_observer, {{0}}},
        /* Nt/Zw aliases can occupy distinct import slots; observe both. */
        {"NtOpenKey", &original_open_key, &open_key_observer, {{0}}},
        {"ZwOpenKey", &original_open_key_alias, &open_key_alias_observer, {{0}}},
        {"NtQueryValueKey", &original_query_value, &query_value_observer, {{0}}},
        {"ZwQueryValueKey", &original_query_value_alias, &query_value_alias_observer, {{0}}},
        {"NtDuplicateObject", &original_duplicate, &duplicate_observer, {{0}}},
        {"NtTerminateProcess", &original_terminate, &terminate_observer, {{0}}},
        {"RtlNtStatusToDosError", &original_error_conversion, &error_observer, {{0}}},
        {"RtlNtStatusToDosErrorNoTeb", &original_error_conversion_no_teb, &no_teb_observer, {{0}}},
        {"RtlSetLastWin32ErrorAndNtStatusFromNtStatus", &original_set_nt_status, &set_nt_observer, {{0}}},
        {"RtlSetLastWin32Error", &original_set_win32_error, &set_win32_observer, {{0}}},
        {"NtResumeThread", &original_resume, &resume_observer, {{0}}},
        {"CsrClientCallServer", &original_csr_call, &csr_observer, {{0}}}
    };
    C_ASSERT(sizeof(nt_create_fn) == 8 && sizeof(nt_info_fn) == 8 && sizeof(nt_query_fn) == 8 &&
             sizeof(nt_memory_fn) == 8 && sizeof(nt_open_token_fn) == 8 && sizeof(nt_open_token_ex_fn) == 8 &&
             sizeof(nt_duplicate_fn) == 8 && sizeof(nt_resume_fn) == 8 && sizeof(csr_call_fn) == 8 &&
             sizeof(nt_terminate_fn) == 8 && sizeof(nt_error_fn) == 8 && sizeof(set_last_error_fn) == 8 &&
             sizeof(nt_open_key_fn) == 8 && sizeof(nt_query_value_fn) == 8);
    for (SIZE_T i = 0; i < sizeof(imports) / sizeof(imports[0]); i++) {
        for (SIZE_T j = 0; j < sizeof(native_modules) / sizeof(native_modules[0]); j++) {
            struct native_import_slot *slot = &imports[i].slots[j];
            ULONGLONG replacement;
            DWORD trace_error = ERROR_SUCCESS;
            slot->address = native_import(j, imports[i].name);
            if (slot->address) {
                slot->saved = *slot->address;
                memcpy(imports[i].original, &slot->saved, sizeof(ULONGLONG));
                memcpy(&replacement, imports[i].observer, sizeof(replacement));
                SetLastError(ERROR_SUCCESS);
                slot->installed = replace_nt_import(slot->address, replacement);
                if (!slot->installed) trace_error = GetLastError();
            }
            printf("DESCENDANT_IMPORT=%s MODULE=%ls FOUND=%d INSTALLED=%d ERROR=%lu\n",
                   imports[i].name, native_modules[j].name, slot->address != NULL, slot->installed, trace_error);
        }
    }
    for (SIZE_T i = 0; i < sizeof(native_modules) / sizeof(native_modules[0]); i++)
        printf("DESCENDANT_MODULE=%ls IMAGE_SIZE=%lu TIMESTAMP=%08lX\n",
               native_modules[i].name, native_modules[i].size, native_modules[i].timestamp);
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE | TOKEN_ASSIGN_PRIMARY, &token))
        printf("DESCENDANT_TOKEN_ASSIGN_ACCESS=0 ERROR=%lu\n", GetLastError());
    for (int i = 0; i < 3; i++) {
        STARTUPINFOW startup = {0};
        PROCESS_INFORMATION process = {0};
        DWORD error = ERROR_INVALID_PARAMETER, code = STILL_ACTIVE, waited = WAIT_FAILED;
        LONG nt_status = 0;
        BOOL configured = i == 0 || token != NULL;
        BOOL started = FALSE;
        startup.cb = sizeof(startup);
        startup.lpDesktop = i == 2 ? desktop : NULL;
        native_started = FALSE;
        native_calls = 0;
        ZeroMemory(native_steps, sizeof(native_steps));
        if (configured) {
            swprintf_s(command, 32768, L"\"%ls\" token", executable);
            if (reset_status) reset_status(0);
            SetLastError(ERROR_SUCCESS);
            if (i != 0) started = CreateProcessAsUserW(token, executable, command, NULL, NULL, TRUE,
                                                       0, NULL, NULL, &startup, &process);
            else started = CreateProcessW(executable, command, NULL, NULL, TRUE,
                                          0, NULL, NULL, &startup, &process);
            error = GetLastError();
            if (!started && last_status) nt_status = last_status();
        }
        native_started = FALSE;
        printf("DESCENDANT_VARIANT=%s CONFIGURED=%d STARTED=%d ERROR=%lu LAST_NTSTATUS=%08lX CREATE_AND_POST_CALLS=%ld\n",
               variants[i], configured, started, error, (unsigned long)nt_status, native_calls);
        for (LONG j = 0; j < native_calls && j < 128; j++) {
            const struct native_step *step = &native_steps[j];
            printf("DESCENDANT_NATIVE_STEP=%ld API=%s RESULT_KIND=%s RESULT=%08lX INPUT=%08lX CALLER=%ls+%08lX "
                   "STATE=%lu PROCESS_FLAGS=%08lX THREAD_FLAGS=%08lX "
                   "CLASS=%lu LENGTH=%lu ACCESS=%08lX OPTIONS=%08lX MEMORY_SIZE=%zu CSR_API=%08lX CSR_LAYOUT=%d CSR_MESSAGE_API=%08lX "
                   "CSR_MESSAGE_STATUS=%08lX CSR_LENGTHS=%lu/%lu\n",
                   j, step->operation, step->no_result ? "void" : "return", (unsigned long)step->status,
                   (unsigned long)step->input_status, step->caller_module ? step->caller_module : L"-", step->caller_rva,
                   step->state,
                   step->process_flags, step->thread_flags, step->info_class, step->length,
                   step->access_mask, step->options, step->memory_size,
                   step->api_number, step->message_valid, step->message_api,
                   (unsigned long)step->message_status, step->message_data, step->message_total);
        }
        if (started) {
            waited = WaitForSingleObject(process.hProcess, 10000);
            if (waited != WAIT_OBJECT_0) {
                TerminateProcess(process.hProcess, 99);
                WaitForSingleObject(process.hProcess, 1000);
            }
            GetExitCodeProcess(process.hProcess, &code);
            CloseHandle(process.hThread); CloseHandle(process.hProcess);
        }
        printf("DESCENDANT_VARIANT=%s WAIT=%lu EXIT=%lu\n", variants[i], waited, code);
    }
    for (SIZE_T i = sizeof(imports) / sizeof(imports[0]); i > 0; i--) {
        for (SIZE_T j = sizeof(native_modules) / sizeof(native_modules[0]); j > 0; j--) {
            struct native_import_slot *slot = &imports[i - 1].slots[j - 1];
            if (slot->installed) check(replace_nt_import(slot->address, slot->saved), "restore diagnostic native import");
        }
    }
    if (token) CloseHandle(token);
}

static void child_check(const wchar_t *mode, DWORD flags, int expect_failure) {
    wchar_t executable[32768], command[32768];
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    BOOL spawned;
    DWORD code = 1;
    last_ntstatus_fn last_status = NULL;
    set_last_error_fn reset_status = NULL;
    FARPROC address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetLastNtStatus");
    FARPROC reset_address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlSetLastWin32ErrorAndNtStatusFromNtStatus");
    memcpy(&last_status, &address, sizeof(last_status));
    memcpy(&reset_status, &reset_address, sizeof(reset_status));
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    check(GetModuleFileNameW(NULL, executable, 32768) > 0, "find guest executable");
    swprintf_s(command, 32768, L"\"%ls\" %ls", executable, mode);
    if (reset_status) reset_status(0);
    SetLastError(ERROR_SUCCESS);
    spawned = CreateProcessW(executable, command, NULL, NULL, TRUE, flags, NULL, NULL, &startup, &process);
    if (expect_failure) {
        check(!spawned, "job breakaway denied");
        if (spawned) TerminateProcess(process.hProcess, 99);
    } else {
        if (!spawned) {
            DWORD original_error = GetLastError();
            LONG nt_status = last_status ? last_status() : 0;
            printf("DESCENDANT_ERROR=%lu NTSTATUS_AVAILABLE=%d NTSTATUS_RESET=%d LAST_NTSTATUS=%08lX\n",
                   original_error, last_status != NULL, reset_status != NULL, (unsigned long)nt_status);
            child_launch_diagnostics(executable, last_status, reset_status);
            SetLastError(original_error);
        }
        check(spawned, "confined descendant starts");
        if (spawned && wcscmp(mode, L"sleep") == 0) {
            printf("CHILD=%lu\n", process.dwProcessId);
            fflush(stdout);
            Sleep(INFINITE);
        }
        if (spawned) {
            check(WaitForSingleObject(process.hProcess, 10000) == WAIT_OBJECT_0, "descendant exits");
            GetExitCodeProcess(process.hProcess, &code);
            check(code == 0, "descendant retains confinement");
        }
    }
    if (spawned) {
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
    }
}

static void stream_check(void) {
    char block[8192];
    size_t total = 0, count;
    int i;
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    _setmode(_fileno(stderr), _O_BINARY);
    while ((count = fread(block, 1, sizeof(block), stdin)) != 0) total += count;
    check(total == 262144, "stdin backpressure and EOF");
    for (i = 0; i < 32; i++) {
        memset(block, 'o', sizeof(block));
        check(fwrite(block, 1, sizeof(block), stdout) == sizeof(block), "stdout backpressure");
        memset(block, 'e', sizeof(block));
        check(fwrite(block, 1, sizeof(block), stderr) == sizeof(block), "stderr backpressure");
    }
}

static void protected_file(const wchar_t *path) {
    HANDLE token = NULL;
    unsigned char data[4096];
    DWORD size;
    LPWSTR sid = NULL;
    wchar_t sddl[512];
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PACL acl = NULL;
    BOOL present, defaulted;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "fixture token");
    if (!token) return;
    check(GetTokenInformation(token, TokenUser, data, sizeof(data), &size), "fixture user SID");
    check(ConvertSidToStringSidW(((TOKEN_USER *)data)->User.Sid, &sid), "fixture SID text");
    if (sid) {
        swprintf_s(sddl, 512, L"D:P(A;;FA;;;%ls)", sid);
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL)) {
            check(GetSecurityDescriptorDacl(descriptor, &present, &acl, &defaulted) && present,
                  "explicit protected fixture DACL");
            check(SetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT,
                                        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                                        NULL, NULL, acl, NULL) == ERROR_SUCCESS, "protect host fixture");
            LocalFree(descriptor);
        } else check(0, "fixture descriptor");
        LocalFree(sid);
    }
    CloseHandle(token);
}

/* Absolute native names bypass predefined Win32 handles and HKCU redirection. */
static void registry_native(HKEY root, const wchar_t *path, ACCESS_MASK access, LONG expected, const char *label) {
    wchar_t absolute[560];
    UNICODE_STRING name;
    OBJECT_ATTRIBUTES attributes;
    HANDLE key = NULL;
    nt_open_key_fn open_key = NULL;
    LONG (__stdcall *close_key)(HANDLE) = NULL;
    HMODULE module = GetModuleHandleW(L"ntdll.dll");
    FARPROC address = module ? GetProcAddress(module, "NtOpenKey") : NULL;
    FARPROC close_address = module ? GetProcAddress(module, "NtClose") : NULL;
    LONG status;
    int length = _snwprintf_s(absolute, 560, _TRUNCATE, L"\\Registry\\%ls\\%ls",
                             root == HKEY_LOCAL_MACHINE ? L"Machine" : L"User", path);
    check(address && close_address, "native registry interfaces available");
    check(length >= 0, "bounded absolute native registry path");
    if (!address || !close_address || length < 0) return;
    memcpy(&open_key, &address, sizeof(open_key));
    memcpy(&close_key, &close_address, sizeof(close_key));
    name.Length = (USHORT)((size_t)length * sizeof(wchar_t));
    name.MaximumLength = (USHORT)sizeof(absolute);
    name.Buffer = absolute;
    ZeroMemory(&attributes, sizeof(attributes));
    attributes.Length = sizeof(attributes);
    attributes.ObjectName = &name;
    attributes.Attributes = OBJ_CASE_INSENSITIVE;
    status = open_key(&key, access, &attributes);
    if (status != expected) printf("NATIVE_REGISTRY_STATUS=%08lx ACCESS=%08lx\n", (unsigned long)status, access);
    check(status == expected, label);
    check(status != 0 || key != NULL, "native registry open returns a handle on success");
    if (key) check(close_key(key) == 0, "close native registry handle");
}

/* Host handles retain ownership until the Java driver finishes, including failures. */
typedef struct RegistryFixture {
    HKEY root, key;
    wchar_t path[512];
    DWORD expected;
    int owned;
} RegistryFixture;

static void registry_unchanged(HKEY key, DWORD expected) {
    DWORD value = 0, type = 0, size = sizeof(value), subkeys = 0, values = 0;
    check(RegQueryValueExW(key, L"sentinel", NULL, &type, (BYTE *)&value, &size) == ERROR_SUCCESS
          && type == REG_DWORD && size == sizeof(value) && value == expected,
          "private registry fixture value unchanged");
    check(RegQueryInfoKeyW(key, NULL, NULL, NULL, &subkeys, NULL, NULL, &values,
                          NULL, NULL, NULL, NULL) == ERROR_SUCCESS && subkeys == 0 && values == 1,
          "private registry fixture contains only its original value");
}

static void registry_host(int machine) {
    RegistryFixture fixtures[3] = {0};
    HANDLE token = NULL;
    BYTE data[4096];
    DWORD size = 0, appcontainer = 1;
    GUID guid;
    wchar_t unique[40], sddl[512];
    LPWSTR sid = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, FALSE};
    int count = machine ? 3 : 2;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "registry fixture host token");
    if (!token) goto done;
    check(GetTokenInformation(token, TokenIsAppContainer, &appcontainer, sizeof(appcontainer), &size)
          && appcontainer == 0, "registry fixture setup is host-only");
    if (failures) goto done;
    check(GetTokenInformation(token, TokenUser, data, sizeof(data), &size), "registry fixture host user");
    if (failures) goto done;
    check(ConvertSidToStringSidW(((TOKEN_USER *)data)->User.Sid, &sid), "registry fixture user identity");
    check(SUCCEEDED(CoCreateGuid(&guid)) && StringFromGUID2(&guid, unique, 40) > 0,
          "unique registry fixture name");
    if (failures) goto done;
    swprintf_s(sddl, 512, L"D:P(A;;KA;;;%ls)(A;;KA;;;SY)", sid);
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL),
          "private registry fixture descriptor");
    if (failures) goto done;
    attributes.lpSecurityDescriptor = descriptor;
    for (int i = 0; i < count; i++) {
        DWORD disposition = 0;
        LSTATUS status;
        HKEY parent = NULL;
        wchar_t parent_path[256], leaf[80];
        RegistryFixture *fixture = &fixtures[i];
        fixture->root = i == 2 ? HKEY_LOCAL_MACHINE : HKEY_USERS;
        if (i == 2) wcscpy_s(parent_path, 256, L"Software");
        else swprintf_s(parent_path, 256, L"%ls\\Software", sid);
        swprintf_s(leaf, 80, L"visjail.registry.%ls-%d", unique, i);
        swprintf_s(fixture->path, 512, L"%ls\\%ls", parent_path, leaf);
        check(RegOpenKeyExW(fixture->root, parent_path, 0, KEY_CREATE_SUB_KEY | KEY_WOW64_64KEY,
                            &parent) == ERROR_SUCCESS, "open existing registry fixture parent");
        if (!parent) goto done;
        status = RegCreateKeyExW(parent, leaf, 0, NULL, REG_OPTION_VOLATILE,
                                KEY_ALL_ACCESS | KEY_WOW64_64KEY, i == 0 ? NULL : &attributes,
                                &fixture->key, &disposition);
        RegCloseKey(parent);
        fixture->owned = status == ERROR_SUCCESS && disposition == REG_CREATED_NEW_KEY;
        check(fixture->owned, "create a new registry fixture, never reuse a collision");
        if (!fixture->owned) goto done;
        fixture->expected = 0x51a17u + (DWORD)i;
        check(RegSetValueExW(fixture->key, L"sentinel", 0, REG_DWORD,
                            (const BYTE *)&fixture->expected, sizeof(fixture->expected)) == ERROR_SUCCESS,
              "initialize synthetic registry fixture");
        if (failures) goto done;
        registry_native(fixture->root, fixture->path, KEY_READ, 0, "host opens the exact native registry fixture");
        if (failures) goto done;
    }
    /* The guest uses these exact HKU paths, not its redirected HKCU view. */
    for (int i = 0; i < count; i++) printf("REGISTRY_PATH=%ls\n", fixtures[i].path);
    printf("REGISTRY_READY\n");
    fflush(stdout);
    {
        HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
        ULONGLONG deadline = GetTickCount64() + 60000;
        DWORD available = 0, read = 0;
        char signal = 0;
        BOOL signaled = FALSE;
        while (GetTickCount64() < deadline) {
            if (!PeekNamedPipe(input, NULL, 0, NULL, &available, NULL)) break;
            if (available) {
                signaled = ReadFile(input, &signal, 1, &read, NULL) && read == 1 && signal == 'q';
                break;
            }
            Sleep(10);
        }
        check(signaled, "registry fixture driver completed within watchdog");
    }
done:
    for (int i = 0; i < count; i++) {
        RegistryFixture *fixture = &fixtures[i];
        if (fixture->owned) {
            HKEY reopened = NULL;
            registry_unchanged(fixture->key, fixture->expected);
            check(RegOpenKeyExW(fixture->root, fixture->path, 0, KEY_READ | KEY_WOW64_64KEY,
                                &reopened) == ERROR_SUCCESS,
                  "host reopens the exact existing key targeted by the guest");
            if (reopened) { registry_unchanged(reopened, fixture->expected); RegCloseKey(reopened); }
            check(RegDeleteKeyExW(fixture->root, fixture->path, KEY_WOW64_64KEY, 0) == ERROR_SUCCESS,
                  "remove only the registry leaf created by this test");
        }
        if (fixture->key) RegCloseKey(fixture->key);
        if (fixture->owned) {
            HKEY absent = NULL;
            check(RegOpenKeyExW(fixture->root, fixture->path, 0, KEY_READ | KEY_WOW64_64KEY,
                                &absent) == ERROR_FILE_NOT_FOUND, "owned registry fixture deletion completed");
            if (absent) RegCloseKey(absent);
        }
    }
    LocalFree(descriptor);
    LocalFree(sid);
    if (token) CloseHandle(token);
}

static void registry_denied(HKEY root, const wchar_t *path, REGSAM access, const char *label) {
    HKEY key = NULL;
    LSTATUS status = RegOpenKeyExW(root, path, 0, access | KEY_WOW64_64KEY, &key);
    if (status != ERROR_ACCESS_DENIED) printf("REGISTRY_OPEN_STATUS=%ld\n", status);
    check(status == ERROR_ACCESS_DENIED, label);
    if (key) RegCloseKey(key);
    registry_native(root, path, access, (LONG)0xc0000022UL, label);
}

static void registry_check(int argc, wchar_t **argv) {
    const wchar_t *sxs = L"Software\\Microsoft\\Windows\\CurrentVersion\\SideBySide";
    HKEY key = NULL;
    LSTATUS open_status;
    check(argc == 4 || argc == 5, "registry guest arguments");
    if (argc != 4 && argc != 5) return;
    token_check();
    registry_native(HKEY_LOCAL_MACHINE, sxs, KEY_READ, 0, "native SxS initialization key read");
    open_status = RegOpenKeyExW(HKEY_LOCAL_MACHINE, sxs, 0, KEY_READ | KEY_WOW64_64KEY, &key);
    if (open_status != ERROR_SUCCESS) printf("SXS_OPEN_STATUS=%ld\n", open_status);
    check(open_status == ERROR_SUCCESS, "registryRead allows actual SxS initialization key read");
    if (key) {
        DWORD size = 0;
        LSTATUS status = RegQueryValueExW(key, L"PreferExternalManifest", NULL, NULL, NULL, &size);
        if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) printf("SXS_QUERY_STATUS=%ld\n", status);
        check(status == ERROR_SUCCESS || status == ERROR_FILE_NOT_FOUND,
              "SxS setting query succeeds or the optional value is absent");
        RegCloseKey(key);
    }
    registry_denied(HKEY_LOCAL_MACHINE, sxs, KEY_SET_VALUE, "system registry value write denied");
    registry_denied(HKEY_LOCAL_MACHINE, sxs, KEY_CREATE_SUB_KEY, "system registry subkey creation denied");
    for (int i = 2; i < argc; i++) {
        HKEY root = i == 4 ? HKEY_LOCAL_MACHINE : HKEY_USERS;
        printf("REGISTRY_FIXTURE=%d\n", i - 2);
        registry_denied(root, argv[i], KEY_READ, "private host registry read denied, not missing");
        registry_denied(root, argv[i], KEY_QUERY_VALUE, "private host registry value query denied");
        registry_denied(root, argv[i], KEY_ENUMERATE_SUB_KEYS, "private host registry enumeration denied");
        registry_denied(root, argv[i], KEY_SET_VALUE, "private host registry value write denied");
        registry_denied(root, argv[i], KEY_CREATE_SUB_KEY, "private host registry subkey creation denied");
        registry_denied(root, argv[i], WRITE_DAC, "private host registry DACL replacement denied");
        registry_denied(root, argv[i], WRITE_OWNER, "private host registry ownership change denied");
        registry_denied(root, argv[i], DELETE, "private host registry deletion denied");
    }
}

static void junction(const wchar_t *path, const wchar_t *target) {
    struct {
        DWORD tag;
        WORD length, reserved;
        WORD substitute_offset, substitute_length, print_offset, print_length;
        wchar_t names[16376];
    } point;
    wchar_t substitute[8192];
    HANDLE directory;
    DWORD returned;
    size_t count;
    ZeroMemory(&point, sizeof(point));
    swprintf_s(substitute, 8192, L"\\??\\%ls", target);
    count = wcslen(substitute);
    check(count < 8190, "bounded junction fixture target");
    if (count >= 8190) return;
    point.tag = IO_REPARSE_TAG_MOUNT_POINT;
    point.substitute_length = (WORD)(count * sizeof(wchar_t));
    point.print_offset = (WORD)((count + 1) * sizeof(wchar_t));
    point.length = (WORD)(8 + point.print_offset + sizeof(wchar_t));
    memcpy(point.names, substitute, (count + 1) * sizeof(wchar_t));
    directory = CreateFileW(path, GENERIC_WRITE, 0, NULL, OPEN_EXISTING,
                            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    check(directory != INVALID_HANDLE_VALUE, "open junction fixture");
    if (directory != INVALID_HANDLE_VALUE) {
        check(DeviceIoControl(directory, FSCTL_SET_REPARSE_POINT, &point, 8u + point.length,
                              NULL, 0, &returned, NULL), "create actual junction fixture");
        CloseHandle(directory);
    }
}

static wchar_t *command_line(int argc, wchar_t **argv) {
    size_t capacity = 1;
    wchar_t *command, *out;
    int index;
    for (index = 0; index < argc; index++) capacity += 2 * wcslen(argv[index]) + 4;
    command = (wchar_t *)calloc(capacity, sizeof(wchar_t));
    if (!command) return NULL;
    out = command;
    for (index = 0; index < argc; index++) {
        const wchar_t *at = argv[index];
        if (index) *out++ = L' ';
        *out++ = L'"';
        while (*at) {
            size_t slashes = 0;
            while (*at == L'\\') { slashes++; at++; }
            if (*at == L'"' || !*at) slashes *= 2;
            while (slashes--) *out++ = L'\\';
            if (*at == L'"') *out++ = L'\\';
            if (*at) *out++ = *at++;
        }
        *out++ = L'"';
    }
    *out = 0;
    return command;
}

/* Only new objects of the test host get these defaults; existing host ACLs stay untouched. */
static void standard_defaults(HANDLE token) {
    BYTE data[4096];
    DWORD size;
    LPWSTR sid = NULL;
    wchar_t sddl[512];
    PSECURITY_DESCRIPTOR descriptor = NULL;
    TOKEN_DEFAULT_DACL defaults = {0};
    BOOL present = FALSE, defaulted = FALSE;
    check(GetTokenInformation(token, TokenUser, data, sizeof(data), &size), "standard default ACL user");
    if (failures) return;
    check(ConvertSidToStringSidW(((TOKEN_USER *)data)->User.Sid, &sid), "standard default ACL identity");
    if (!sid) return;
    check(swprintf_s(sddl, 512, L"D:(A;;GA;;;%ls)(A;;GA;;;SY)", sid) > 0, "standard default ACL text");
    if (failures) goto done;
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL),
          "standard default security descriptor");
    if (!descriptor) goto done;
    check(GetSecurityDescriptorDacl(descriptor, &present, &defaults.DefaultDacl, &defaulted) &&
          present && defaults.DefaultDacl != NULL, "standard explicit default ACL");
    if (!failures)
        check(SetTokenInformation(token, TokenDefaultDacl, &defaults, sizeof(defaults)),
              "standard host owns newly created objects");
done:
    if (descriptor) LocalFree(descriptor);
    LocalFree(sid);
}

static HANDLE standard_token(void) {
    HANDLE original = NULL, restricted = NULL;
    PSID administrators = NULL, medium = NULL;
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    SID_AND_ATTRIBUTES disabled;
    TOKEN_MANDATORY_LABEL label;
    /* CreateRestrictedToken preserves handle access; lowering integrity needs TOKEN_ADJUST_DEFAULT. */
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY |
                           TOKEN_ASSIGN_PRIMARY | TOKEN_ADJUST_DEFAULT, &original), "open parent token");
    if (!original) return NULL;
    check(AllocateAndInitializeSid(&nt, 2, SECURITY_BUILTIN_DOMAIN_RID, DOMAIN_ALIAS_RID_ADMINS,
                                   0, 0, 0, 0, 0, 0, &administrators), "administrator SID");
    disabled.Sid = administrators;
    disabled.Attributes = 0;
    if (administrators) {
        check(CreateRestrictedToken(original, DISABLE_MAX_PRIVILEGE, 1, &disabled, 0, NULL, 0, NULL,
                                    &restricted), "create standard-equivalent token");
        FreeSid(administrators);
    }
    if (restricted && ConvertStringSidToSidW(L"S-1-16-8192", &medium)) {
        label.Label.Sid = medium;
        label.Label.Attributes = SE_GROUP_INTEGRITY;
        check(SetTokenInformation(restricted, TokenIntegrityLevel, &label,
                                  (DWORD)sizeof(label) + GetLengthSid(medium)), "medium integrity parent");
        LocalFree(medium);
    } else check(0, "medium integrity SID");
    if (restricted && !failures) standard_defaults(restricted);
    CloseHandle(original);
    return restricted;
}

static void standard_check(void) {
    HANDLE token = NULL, impersonation = NULL, reader = NULL, writer = NULL;
    BYTE data[4096], user_data[4096];
    DWORD size, i, count = 0;
    PSID administrators = NULL, user;
    BOOL member = TRUE, created, written;
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    LUID notify;
    char value = 0;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token), "standard host token");
    if (!token) return;
    check(GetTokenInformation(token, TokenUser, user_data, sizeof(user_data), &size), "standard host user");
    if (failures) goto done;
    user = ((TOKEN_USER *)user_data)->User.Sid;
    check(AllocateAndInitializeSid(&nt, 2, SECURITY_BUILTIN_DOMAIN_RID, DOMAIN_ALIAS_RID_ADMINS,
                                   0, 0, 0, 0, 0, 0, &administrators), "query admin SID");
    check(DuplicateToken(token, SecurityIdentification, &impersonation), "standard impersonation token");
    if (administrators && impersonation) {
        check(CheckTokenMembership(impersonation, administrators, &member) && !member,
              "parent has no enabled administrator membership");
    }
    if (GetTokenInformation(token, TokenIntegrityLevel, data, sizeof(data), &size)) {
        PSID sid = ((TOKEN_MANDATORY_LABEL *)data)->Label.Sid;
        DWORD integrity = *GetSidSubAuthority(sid, (DWORD)*GetSidSubAuthorityCount(sid) - 1);
        check(integrity <= SECURITY_MANDATORY_MEDIUM_RID, "parent is not elevated integrity");
    } else check(0, "standard integrity query");
    check(LookupPrivilegeValueW(NULL, L"SeChangeNotifyPrivilege", &notify), "standard traversal privilege identity");
    if (failures) goto done;
    if (GetTokenInformation(token, TokenPrivileges, data, sizeof(data), &size)) {
        TOKEN_PRIVILEGES *privileges = (TOKEN_PRIVILEGES *)data;
        for (i = 0; i < privileges->PrivilegeCount; i++) {
            LUID_AND_ATTRIBUTES *entry = &privileges->Privileges[i];
            check(!(entry->Attributes & SE_PRIVILEGE_ENABLED) ||
                  (entry->Luid.LowPart == notify.LowPart && entry->Luid.HighPart == notify.HighPart),
                  "parent has no enabled privilege except traversal");
        }
    } else check(0, "standard privilege query");
    if (GetTokenInformation(token, TokenOwner, data, sizeof(data), &size)) {
        check(EqualSid(((TOKEN_OWNER *)data)->Owner, user), "standard default owner is the user");
    } else check(0, "standard owner query");
    if (GetTokenInformation(token, TokenDefaultDacl, data, sizeof(data), &size)) {
        PACL acl = ((TOKEN_DEFAULT_DACL *)data)->DefaultDacl;
        check(acl && IsValidAcl(acl), "standard default DACL is explicit and valid");
        if (acl && IsValidAcl(acl)) {
            DWORD user_aces = 0, system_aces = 0;
            check(acl->AceCount == 2, "standard default DACL contains only user and system");
            for (i = 0; i < acl->AceCount; i++) {
                void *raw = NULL;
                if (!GetAce(acl, i, &raw)) { check(0, "standard default ACE query"); break; }
                if (((ACE_HEADER *)raw)->AceType == ACCESS_ALLOWED_ACE_TYPE) {
                    ACCESS_ALLOWED_ACE *ace = (ACCESS_ALLOWED_ACE *)raw;
                    PSID sid = &ace->SidStart;
                    BOOL is_user = EqualSid(sid, user), is_system = IsWellKnownSid(sid, WinLocalSystemSid);
                    check((is_user || is_system) && ace->Mask == GENERIC_ALL && ace->Header.AceFlags == 0,
                          "standard default ACE grants only the user or system");
                    user_aces += is_user ? 1 : 0;
                    system_aces += is_system ? 1 : 0;
                } else check(0, "standard default ACL contains only allow entries");
            }
            check(user_aces == 1 && system_aces == 1, "standard default DACL grants user and system self-access");
        }
    } else check(0, "standard default DACL query");
    created = CreatePipe(&reader, &writer, NULL, 0);
    check(created, "standard host creates a default-security pipe");
    if (created) {
        written = WriteFile(writer, "p", 1, &count, NULL);
        check(written && count == 1, "standard host writes its pipe");
        if (written && count == 1)
            check(ReadFile(reader, &value, 1, &count, NULL) && count == 1 && value == 'p',
                  "standard host default pipe roundtrip");
    }
done:
    if (reader) CloseHandle(reader);
    if (writer) CloseHandle(writer);
    if (administrators) FreeSid(administrators);
    if (impersonation) CloseHandle(impersonation);
    CloseHandle(token);
}

static void host_launch(int argc, wchar_t **argv, int standard) {
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, TRUE};
    HANDLE secret = INVALID_HANDLE_VALUE, token = NULL;
    wchar_t value[32], *command;
    BOOL started;
    DWORD code = 1;
    int first = standard ? 2 : 3;
    check(argc > first, "host launcher arguments");
    if (argc <= first) return;
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    if (standard) token = standard_token();
    else {
        secret = CreateFileW(argv[2], GENERIC_READ, FILE_SHARE_READ, &attributes, OPEN_EXISTING, 0, NULL);
        check(secret != INVALID_HANDLE_VALUE, "inheritable secret fixture handle");
        swprintf_s(value, 32, L"%llu", (unsigned long long)(uintptr_t)secret);
        check(SetEnvironmentVariableW(L"VIS_JAIL_LEAK_HANDLE", value), "pass test handle identity");
    }
    check(SetEnvironmentVariableW(L"VIS_JAIL_SECRET", L"not-for-guest"), "host-only environment fixture");
    command = command_line(argc - first, argv + first);
    check(command != NULL, "host command allocation");
    if (!command || failures) { free(command); if (token) CloseHandle(token); return; }
    if (standard) started = CreateProcessAsUserW(token, argv[first], command, NULL, NULL, TRUE, 0,
                                                NULL, NULL, &startup, &process);
    else started = CreateProcessW(argv[first], command, NULL, NULL, TRUE, 0, NULL, NULL, &startup, &process);
    check(started, "launch test host");
    if (started) {
        if (WaitForSingleObject(process.hProcess, 60000) != WAIT_OBJECT_0) {
            TerminateProcess(process.hProcess, 99);
            check(0, "test host watchdog");
        }
        GetExitCodeProcess(process.hProcess, &code);
        check(code == 0, "test host succeeded");
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
    }
    free(command);
    if (secret != INVALID_HANDLE_VALUE) CloseHandle(secret);
    if (token) CloseHandle(token);
}

static void handle_check(int argc, wchar_t **argv, int host) {
    HANDLE handle, process = NULL;
    char secret[64] = {0};
    DWORD count = 0, type = FILE_TYPE_UNKNOWN;
    check(argc == (host ? 4 : 3), "handle arguments");
    if (argc != (host ? 4 : 3)) return;
    handle = (HANDLE)(uintptr_t)_wcstoui64(argv[host ? 3 : 2], NULL, 10);
    if (host) {
        HANDLE copy = NULL;
        process = OpenProcess(PROCESS_DUP_HANDLE, FALSE, (DWORD)_wcstoui64(argv[2], NULL, 10));
        check(process != NULL && DuplicateHandle(process, handle, GetCurrentProcess(), &copy,
                                                 0, FALSE, DUPLICATE_SAME_ACCESS), "host really inherited secret handle");
        handle = copy;
    }
    __try {
        if (handle) type = GetFileType(handle);
    } __except (!host && GetExceptionCode() == EXCEPTION_INVALID_HANDLE ?
                EXCEPTION_EXECUTE_HANDLER : EXCEPTION_CONTINUE_SEARCH) {
        /* Strict handle checking can raise instead of returning an invalid type. */
        printf("HANDLE_REFERENCE_DENIED=STATUS_INVALID_HANDLE\n");
    }
    if (type == FILE_TYPE_DISK) {
        ReadFile(handle, secret, sizeof(secret) - 1, &count, NULL);
        if (host) SetFilePointer(handle, 0, NULL, FILE_BEGIN);
    }
    check(host ? strcmp(secret, "inheritable-secret") == 0 : strcmp(secret, "inheritable-secret") != 0,
          host ? "host inherited secret is readable" : "ambient secret handle not inherited by guest");
    if (host && handle) CloseHandle(handle);
    if (process) CloseHandle(process);
}

/* Query only the token's exact profile; cleanup never enumerates other profiles. */
static void profile_check(const wchar_t *sid_text, int remove_profile) {
    HANDLE token = NULL;
    DWORD value = 1, size = 0;
    PWSTR path = NULL;
    PSID sid = NULL, derived = NULL;
    const wchar_t *name;
    HRESULT hr;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "profile fixture host token");
    if (!token) return;
    check(GetTokenInformation(token, TokenIsAppContainer, &value, sizeof(value), &size)
          && value == 0, "profile fixture is host-only");
    CloseHandle(token);
    if (failures) return;
    hr = GetAppContainerFolderPath(sid_text, &path);
    check(SUCCEEDED(hr) && path != NULL, "query exact AppContainer profile path");
    if (!path) return;
    printf("PROFILE_PATH=");
    for (const wchar_t *at = path; *at; at++) printf("%04X", (unsigned int)*at);
    printf("\n");
    if (remove_profile) {
        wchar_t *leaf = wcsrchr(path, L'\\');
        /* GetAppContainerFolderPath returns the profile's local app-data folder. */
        check(leaf && _wcsicmp(leaf + 1, L"AC") == 0, "profile lookup returns local app data");
        if (leaf) *leaf = L'\0';
        name = wcsrchr(path, L'\\');
        name = name ? name + 1 : path;
        check(wcslen(name) == 40 && wcsncmp(name, L"visjail.", 8) == 0,
              "cleanup names one generated Vis profile");
        if (!failures) {
            for (size_t i = 8; i < 40; i++)
                check(wcschr(L"0123456789abcdef", name[i]) != NULL, "generated profile identifier");
        }
        if (!failures) {
            check(ConvertStringSidToSidW(sid_text, &sid), "parse recorded profile SID");
            hr = DeriveAppContainerSidFromAppContainerName(name, &derived);
            check(SUCCEEDED(hr) && derived != NULL, "derive recorded profile identity");
            if (sid && derived) check(EqualSid(sid, derived), "cleanup profile matches recorded token SID");
        }
        if (!failures) check(SUCCEEDED(DeleteAppContainerProfile(name)), "delete exact crash fixture profile");
    }
    if (derived) FreeSid(derived);
    if (sid) LocalFree(sid);
    CoTaskMemFree(path);
}

/* Failure-only host diagnostics. Never inspect guest data or print object names. */
static int wait_chains(DWORD host_pid) {
    DWORD pids[16], tids[512], owners[512];
    size_t process_count = 1, thread_count = 0;
    HANDLE token = NULL, snapshot = INVALID_HANDLE_VALUE;
    TOKEN_PRIVILEGES privilege = {0};
    PROCESSENTRY32W process = {0};
    THREADENTRY32 thread = {0};
    HWCT session = NULL;
    int result = 1;
    if (!host_pid) return 2;
    pids[0] = host_pid;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_ADJUST_PRIVILEGES, &token) ||
        !LookupPrivilegeValueW(NULL, L"SeDebugPrivilege", &privilege.Privileges[0].Luid)) {
        fprintf(stderr, "ConPTY WCT privilege lookup error=%lu\n", GetLastError());
        goto done;
    }
    privilege.PrivilegeCount = 1;
    privilege.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED;
    SetLastError(ERROR_SUCCESS);
    if (!AdjustTokenPrivileges(token, FALSE, &privilege, 0, NULL, NULL) ||
        GetLastError() != ERROR_SUCCESS) {
        fprintf(stderr, "ConPTY WCT privilege unavailable error=%lu\n", GetLastError());
        goto done;
    }
    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    process.dwSize = sizeof(process);
    if (snapshot == INVALID_HANDLE_VALUE || !Process32FirstW(snapshot, &process)) {
        fprintf(stderr, "ConPTY WCT process snapshot error=%lu\n", GetLastError());
        goto done;
    }
    do {
        if (process.th32ParentProcessID == host_pid && _wcsicmp(process.szExeFile, L"conhost.exe") == 0) {
            if (process_count == sizeof(pids) / sizeof(pids[0])) {
                fprintf(stderr, "ConPTY WCT process limit reached\n");
                goto done;
            }
            pids[process_count++] = process.th32ProcessID;
        }
    } while (Process32NextW(snapshot, &process));
    CloseHandle(snapshot);
    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    thread.dwSize = sizeof(thread);
    if (snapshot == INVALID_HANDLE_VALUE || !Thread32First(snapshot, &thread)) {
        fprintf(stderr, "ConPTY WCT thread snapshot error=%lu\n", GetLastError());
        goto done;
    }
    do {
        for (size_t i = 0; i < process_count; i++) {
            if (thread.th32OwnerProcessID != pids[i]) continue;
            if (thread_count == sizeof(tids) / sizeof(tids[0])) {
                fprintf(stderr, "ConPTY WCT thread limit reached\n");
                goto done;
            }
            owners[thread_count] = pids[i];
            tids[thread_count++] = thread.th32ThreadID;
            break;
        }
    } while (Thread32Next(snapshot, &thread));
    CloseHandle(snapshot);
    snapshot = INVALID_HANDLE_VALUE;
    fprintf(stderr, "ConPTY WCT processes=%zu threads=%zu\n", process_count, thread_count);
    for (size_t i = 0; i < process_count; i++) {
        HANDLE queried = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pids[i]);
        FILETIME created, exited, kernel, user;
        if (queried && GetProcessTimes(queried, &created, &exited, &kernel, &user)) {
            unsigned long long kernel_time = ((unsigned long long)kernel.dwHighDateTime << 32) | kernel.dwLowDateTime;
            unsigned long long user_time = ((unsigned long long)user.dwHighDateTime << 32) | user.dwLowDateTime;
            fprintf(stderr, "ConPTY WCT pid=%lu role=%s kernel100ns=%llu user100ns=%llu\n",
                    pids[i], i == 0 ? "host" : "conhost", kernel_time, user_time);
        } else {
            fprintf(stderr, "ConPTY WCT pid=%lu process query error=%lu\n", pids[i], GetLastError());
        }
        if (queried) CloseHandle(queried);
    }
    session = OpenThreadWaitChainSession(0, NULL);
    if (!session) {
        fprintf(stderr, "ConPTY WCT session error=%lu\n", GetLastError());
        goto done;
    }
    result = 0;
    /* Do not recursively follow chains into unrelated processes. */
    for (size_t i = thread_count; i > 0; i--) {
        WAITCHAIN_NODE_INFO nodes[WCT_MAX_NODE_COUNT];
        DWORD count = WCT_MAX_NODE_COUNT;
        BOOL cycle = FALSE;
        fprintf(stderr, "ConPTY WCT query pid=%lu tid=%lu\n", owners[i - 1], tids[i - 1]);
        if (!GetThreadWaitChain(session, 0, WCT_OUT_OF_PROC_CS_FLAG, tids[i - 1], &count, nodes, &cycle)) {
            fprintf(stderr, "ConPTY WCT error=%lu required=%lu\n", GetLastError(), count);
            result = 1;
            continue;
        }
        fprintf(stderr, "ConPTY WCT nodes=%lu cycle=%d\n", count, cycle);
        for (DWORD j = 0; j < count; j++) {
            fprintf(stderr, "ConPTY WCT node=%lu type=%u status=%u", j,
                    (unsigned)nodes[j].ObjectType, (unsigned)nodes[j].ObjectStatus);
            if (nodes[j].ObjectType == WctThreadType)
                fprintf(stderr, " pid=%lu tid=%lu wait=%lu switches=%lu",
                        nodes[j].ThreadObject.ProcessId, nodes[j].ThreadObject.ThreadId,
                        nodes[j].ThreadObject.WaitTime, nodes[j].ThreadObject.ContextSwitches);
            fputc('\n', stderr);
        }
    }
done:
    if (session) CloseThreadWaitChainSession(session);
    if (snapshot != INVALID_HANDLE_VALUE) CloseHandle(snapshot);
    if (token) CloseHandle(token);
    return result;
}

/* Query only: these probes never change an existing UI object's descriptor or flags. */
static int desktop_name(HANDLE object, wchar_t *name, DWORD bytes) {
    DWORD needed = 0;
    int ok = object && GetUserObjectInformationW(object, UOI_NAME, name, bytes, &needed);
    check(ok, "query Windows UI object identity");
    return ok;
}

static LPWSTR desktop_security(HANDLE object) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    LPWSTR text = NULL;
    SECURITY_INFORMATION information = OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION |
        DACL_SECURITY_INFORMATION | LABEL_SECURITY_INFORMATION;
    DWORD status = GetSecurityInfo(object, SE_WINDOW_OBJECT, information, NULL, NULL, NULL, NULL, &descriptor);
    check(status == ERROR_SUCCESS, "read host UI descriptor without changing it");
    if (status == ERROR_SUCCESS) {
        check(ConvertSecurityDescriptorToStringSecurityDescriptorW(descriptor, SDDL_REVISION_1,
              information, &text, NULL), "format host UI descriptor");
    }
    if (descriptor) LocalFree(descriptor);
    return text;
}

static void desktop_descriptor(HANDLE object, const char *key) {
    LPWSTR text = desktop_security(object);
    if (text) { printf("%s=%ls\n", key, text); LocalFree(text); }
}

static void desktop_state(void) {
    HWINSTA station = GetProcessWindowStation();
    HDESK desktop = GetThreadDesktop(GetCurrentThreadId());
    wchar_t name[256];
    if (desktop_name(station, name, sizeof(name))) printf("STATION=%ls\n", name);
    if (desktop_name(desktop, name, sizeof(name))) printf("DESKTOP=%ls\n", name);
    desktop_descriptor(station, "STATION_SECURITY");
    desktop_descriptor(desktop, "DESKTOP_SECURITY");
}

static void desktop_denied(const wchar_t *name, DWORD rights, const char *message) {
    HDESK opened;
    DWORD error;
    SetLastError(ERROR_SUCCESS);
    opened = OpenDesktopW(name, 0, FALSE, DESKTOP_READOBJECTS | DESKTOP_WRITEOBJECTS | rights);
    error = GetLastError();
    if (opened || error != ERROR_ACCESS_DENIED)
        fprintf(stderr, "DESKTOP_ACCESS name=%ls opened=%d rights=0x%lx error=%lu\n",
                name, opened != NULL, DESKTOP_READOBJECTS | DESKTOP_WRITEOBJECTS | rights, error);
    check(!opened && error == ERROR_ACCESS_DENIED, message);
    if (opened) check(CloseDesktop(opened), "close unexpected desktop access");
}

/* Failure diagnostics inspect only this process's bounded handle table. */
static void desktop_handles(void) {
    typedef NTSTATUS (__stdcall *QueryProcess)(HANDLE, PROCESSINFOCLASS, PVOID, ULONG, PULONG);
    typedef struct {
        HANDLE handle;
        ULONG_PTR handles, pointers;
        ACCESS_MASK access;
        ULONG type, attributes, reserved;
    } Entry;
    typedef struct { ULONG_PTR count, reserved; Entry entries[1]; } Snapshot;
    union { FARPROC address; QueryProcess query; } api;
    const ULONG capacity = 1024 * 1024;
    DWORD saved = GetLastError(), shown = 0;
    ULONG returned = 0;
    Snapshot *snapshot = calloc(1, capacity);
    NTSTATUS status;
    api.address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess");
    fprintf(stderr, "UI_SELECTED station=%p desktop=%p\n", GetProcessWindowStation(),
            GetThreadDesktop(GetCurrentThreadId()));
    if (!snapshot || !api.address) goto done;
    /* ProcessHandleInformation is a snapshot of our own handles, never other processes. */
    status = api.query(GetCurrentProcess(), (PROCESSINFOCLASS)51, snapshot, capacity, &returned);
    fprintf(stderr, "UI_SNAPSHOT status=0x%08lx bytes=%lu\n", (ULONG)status, returned);
    if (status < 0 || snapshot->count > 1 + (capacity - sizeof(*snapshot)) / sizeof(Entry)) goto done;
    for (ULONG_PTR i = 0; i < snapshot->count; i++) {
        wchar_t type[64], name[256];
        USEROBJECTFLAGS flags = {0};
        DWORD needed = 0, inherited = 0;
        HANDLE handle = snapshot->entries[i].handle;
        if (!GetUserObjectInformationW(handle, UOI_TYPE, type, sizeof(type), &needed) ||
            (wcscmp(type, L"WindowStation") && wcscmp(type, L"Desktop"))) continue;
        if (++shown > 16) { fprintf(stderr, "UI_HANDLES truncated\n"); break; }
        name[0] = L'\0';
        GetUserObjectInformationW(handle, UOI_NAME, name, sizeof(name), &needed);
        GetUserObjectInformationW(handle, UOI_FLAGS, &flags, sizeof(flags), &needed);
        GetHandleInformation(handle, &inherited);
        fprintf(stderr, "UI_HANDLE handle=%p type=%ls name=%ls access=0x%lx attributes=0x%lx inherit=%lu user_inherit=%d\n",
                handle, type, name, snapshot->entries[i].access, snapshot->entries[i].attributes,
                inherited, flags.fInherit);
    }
 done:
    free(snapshot);
    SetLastError(saved);
}

static void desktop_isolation(int argc, wchar_t **argv) {
    const DWORD forbidden_desktop[] = {WRITE_DAC, WRITE_OWNER, DELETE, DESKTOP_HOOKCONTROL,
        DESKTOP_JOURNALRECORD, DESKTOP_JOURNALPLAYBACK, DESKTOP_SWITCHDESKTOP};
    const DWORD forbidden_station[] = {WRITE_DAC, WRITE_OWNER, DELETE, WINSTA_ACCESSCLIPBOARD,
        WINSTA_ACCESSGLOBALATOMS, WINSTA_CREATEDESKTOP, WINSTA_ENUMDESKTOPS,
        WINSTA_EXITWINDOWS, WINSTA_READSCREEN, WINSTA_WRITEATTRIBUTES};
    wchar_t station_name[256], name[256];
    HANDLE token = NULL;
    BYTE data[4096];
    DWORD needed = 0;
    PSID derived = NULL;
    HDESK opened = NULL;
    check(argc == 2 || argc == 4, "desktop isolation arguments");
    if (failures) return;
    token_check();
    if (!desktop_name(GetProcessWindowStation(), station_name, sizeof(station_name)) ||
        !desktop_name(GetThreadDesktop(GetCurrentThreadId()), name, sizeof(name))) return;
    printf("STATION=%ls\nDESKTOP=%ls\n", station_name, name);
    check(wcslen(name) == 40 && !wcsncmp(name, L"visjail.", 8), "guest uses a private Vis desktop");
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "desktop guest token");
    if (token) {
        check(GetTokenInformation(token, TokenAppContainerSid, data, sizeof(data), &needed), "desktop package identity");
        check(SUCCEEDED(DeriveAppContainerSidFromAppContainerName(name, &derived)) && derived,
              "derive private desktop package identity");
        if (!failures && derived) check(EqualSid(derived, ((TOKEN_APPCONTAINER_INFORMATION *)data)->TokenAppContainer),
                                       "desktop name belongs to this exact package SID");
        CloseHandle(token);
    }
    if (derived) FreeSid(derived);
    opened = OpenDesktopW(name, 0, FALSE,
        READ_CONTROL | DESKTOP_READOBJECTS | DESKTOP_WRITEOBJECTS | DESKTOP_CREATEWINDOW);
    if (!opened) desktop_handles();
    check(opened != NULL, "guest can reopen its own desktop with only required rights");
    if (opened) check(CloseDesktop(opened), "close guest desktop access control");
    for (size_t i = 0; i < sizeof(forbidden_desktop) / sizeof(forbidden_desktop[0]); i++)
        desktop_denied(name, forbidden_desktop[i], "guest cannot acquire excessive private-desktop rights");
    for (size_t i = 0; i < sizeof(forbidden_station) / sizeof(forbidden_station[0]); i++) {
        HWINSTA station = OpenWindowStationW(station_name, FALSE, forbidden_station[i]);
        DWORD error = GetLastError();
        check(!station && error == ERROR_ACCESS_DENIED, "guest cannot acquire host window-station rights");
        if (station) check(CloseWindowStation(station), "close unexpected station access");
    }
    if (argc == 4) {
        check(wcscmp(name, argv[2]) && wcscmp(name, argv[3]), "host and sibling names are distinct controls");
        desktop_denied(argv[2], 0, "private host desktop access denied");
        desktop_denied(argv[3], 0, "sibling desktop access denied");
    }
}

static void desktop_lifetime(const wchar_t *first, const wchar_t *second, int present) {
    const wchar_t *names[] = {first, second};
    /* A service host need not have WINSTA_ENUMDESKTOPS. Check only our own objects,
     * with a successful-open control before cleanup so access denial cannot pass. */
    for (size_t i = 0; i < (second ? 2u : 1u); i++) {
        HDESK desktop = OpenDesktopW(names[i], 0, FALSE, READ_CONTROL);
        DWORD error = GetLastError();
        if (present) check(desktop != NULL, "host can open its live private desktop");
        else check(!desktop && error == ERROR_FILE_NOT_FOUND,
                   "normal and failed launches leave no private desktop handles alive");
        if (desktop) check(CloseDesktop(desktop), "close host desktop lifetime control");
    }
}

/* A private same-station object is a denial control; the OS Default desktop need not be private. */
static void desktop_fixture(void) {
    HANDLE token = NULL;
    HDESK desktop = NULL, opened = NULL;
    BYTE user[4096];
    DWORD size = 0, count = 0;
    char command = 0;
    GUID id;
    wchar_t guid[40], name[80], sddl[512];
    LPWSTR sid = NULL, before = NULL, after = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, FALSE};
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "private desktop fixture host token");
    if (!token) goto done;
    check(GetTokenInformation(token, TokenUser, user, sizeof(user), &size), "private desktop fixture host identity");
    if (failures) goto done;
    check(ConvertSidToStringSidW(((TOKEN_USER *)user)->User.Sid, &sid), "private desktop fixture identity string");
    if (!sid) goto done;
    swprintf_s(sddl, 512, L"D:P(A;;GA;;;%ls)(A;;RC;;;OW)S:(ML;;NW;;;LW)", sid);
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL),
          "private desktop fixture descriptor");
    if (!descriptor) goto done;
    attributes.lpSecurityDescriptor = descriptor;
    check(SUCCEEDED(CoCreateGuid(&id)) && StringFromGUID2(&id, guid, 40), "unique private desktop fixture name");
    if (failures) goto done;
    swprintf_s(name, 80, L"visjail-control-%ls", guid);
    desktop = CreateDesktopW(name, NULL, NULL, 0, GENERIC_ALL, &attributes);
    check(desktop != NULL, "create host-only same-station desktop fixture");
    if (!desktop) goto done;
    opened = OpenDesktopW(name, 0, FALSE, DESKTOP_READOBJECTS | DESKTOP_WRITEOBJECTS);
    check(opened != NULL, "host opens private desktop with the rights the guest must be denied");
    if (opened) { check(CloseDesktop(opened), "close private desktop positive control"); opened = NULL; }
    before = desktop_security(desktop);
    if (failures || !before) goto done;
    printf("DESKTOP_PATH=%ls\nDESKTOP_READY\n", name);
    fflush(stdout);
    check(ReadFile(GetStdHandle(STD_INPUT_HANDLE), &command, 1, &count, NULL) && count == 1 && command == 'q',
          "private desktop fixture shutdown");
    after = desktop_security(desktop);
    check(after && !wcscmp(before, after), "private host desktop descriptor unchanged");
 done:
    if (opened) CloseDesktop(opened);
    if (desktop) check(CloseDesktop(desktop), "close private host desktop fixture");
    if (before) LocalFree(before);
    if (after) LocalFree(after);
    if (descriptor) LocalFree(descriptor);
    if (sid) LocalFree(sid);
    if (token) CloseHandle(token);
}

static void desktop_access(const wchar_t *name, int shared) {
    const DWORD forbidden[] = {WRITE_DAC, WRITE_OWNER, DELETE, DESKTOP_CREATEWINDOW, DESKTOP_CREATEMENU,
        DESKTOP_HOOKCONTROL, DESKTOP_JOURNALRECORD, DESKTOP_JOURNALPLAYBACK, DESKTOP_ENUMERATE, DESKTOP_SWITCHDESKTOP};
    HDESK opened;
    token_check();
    if (shared) {
        opened = OpenDesktopW(name, 0, FALSE, DESKTOP_READOBJECTS | DESKTOP_WRITEOBJECTS);
        check(opened != NULL, "guest can use the exact shared-desktop grant");
        if (opened) check(CloseDesktop(opened), "close shared desktop positive control");
    } else desktop_denied(name, 0, "private service Default desktop access denied");
    for (size_t i = 0; i < sizeof(forbidden) / sizeof(forbidden[0]); i++)
        desktop_denied(name, forbidden[i], "host desktop grants no stronger rights");
}

/* Reproduce service-desktop startup without changing any existing host object's ACL.
 * The LPAC fixture is a positive control on NEW disposable objects, not a jail policy. */
static void desktop_host(int argc, wchar_t **argv) {
    HANDLE token = NULL, job = NULL;
    HWINSTA original = NULL, station = NULL;
    HDESK desktop = NULL, original_desktop = NULL;
    BYTE user[4096];
    DWORD size = 0, code = 1;
    LPWSTR sid = NULL, station_security = NULL, desktop_security_before = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, FALSE};
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    wchar_t name[128], location[160], sddl[1024], *command = NULL;
    BOOL switched = FALSE, started = FALSE;
    check(argc > 3 && (!wcscmp(argv[2], L"private") || !wcscmp(argv[2], L"lpac") ||
          !wcscmp(argv[2], L"shared")), "desktop host arguments");
    if (failures) return;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "desktop fixture host token");
    if (!token) goto done;
    check(GetTokenInformation(token, TokenUser, user, sizeof(user), &size), "desktop fixture host identity");
    if (failures) goto done;
    check(ConvertSidToStringSidW(((TOKEN_USER *)user)->User.Sid, &sid), "desktop fixture identity string");
    if (!sid) goto done;
    swprintf_s(sddl, 1024, L"D:P(A;;GA;;;%ls)(A;;RC;;;OW)%lsS:(ML;;NW;;;LW)", sid,
        !wcscmp(argv[2], L"lpac") ? L"(A;;GA;;;S-1-15-2-2)" : L"");
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL),
          "private desktop fixture descriptor");
    if (!descriptor) goto done;
    attributes.lpSecurityDescriptor = descriptor;
    original = GetProcessWindowStation();
    check(original != NULL, "original host window station");
    if (!original) goto done;
    original_desktop = GetThreadDesktop(GetCurrentThreadId());
    check(original_desktop != NULL, "original host thread desktop");
    if (!original_desktop) goto done;
    station_security = desktop_security(original);
    desktop_security_before = desktop_security(original_desktop);
    if (!station_security || !desktop_security_before) goto done;
    swprintf_s(name, 128, L"visjail-test-%lu-%llu", GetCurrentProcessId(), GetTickCount64());
    station = CreateWindowStationW(name, CWF_CREATE_ONLY, WINSTA_ALL_ACCESS, &attributes);
    check(station != NULL, "create disposable test window station");
    if (!station) goto done;
    if (!wcscmp(argv[2], L"shared")) {
        /* Model an OS-accessible desktop on NEW objects only. The station stays private. */
        LocalFree(descriptor);
        descriptor = NULL;
        swprintf_s(sddl, 1024, L"D:P(A;;GA;;;%ls)(A;;RC;;;OW)(A;;0x81;;;S-1-15-2-2)S:(ML;;NW;;;LW)", sid);
        check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL),
              "shared desktop fixture descriptor");
        if (!descriptor) goto done;
        attributes.lpSecurityDescriptor = descriptor;
    }
    switched = SetProcessWindowStation(station);
    check(switched, "select disposable test window station");
    if (!switched) goto done;
    desktop = CreateDesktopW(L"Default", NULL, NULL, 0, GENERIC_ALL, &attributes);
    check(desktop != NULL, "create disposable test desktop");
    if (SetProcessWindowStation(original)) switched = FALSE;
    check(!switched, "restore original host window station");
    if (failures) goto done;
    swprintf_s(location, 160, L"%ls\\Default", name);
    startup.cb = sizeof(startup);
    startup.lpDesktop = location;
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    command = command_line(argc - 3, argv + 3);
    check(command != NULL, "desktop host command allocation");
    if (!command) goto done;
    job = CreateJobObjectW(NULL, NULL);
    check(job != NULL, "desktop host lifetime job");
    if (!job) goto done;
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    check(SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)),
          "desktop host kill-on-close");
    if (failures) goto done;
    started = CreateProcessW(argv[3], command, NULL, NULL, TRUE, CREATE_SUSPENDED,
                             NULL, NULL, &startup, &process);
    check(started, "launch host on disposable desktop");
    if (!started) goto done;
    check(AssignProcessToJobObject(job, process.hProcess), "own disposable desktop process tree");
    if (failures) goto done;
    check(ResumeThread(process.hThread) != (DWORD)-1, "start disposable desktop host");
    if (failures) goto done;
    check(WaitForSingleObject(process.hProcess, 120000) == WAIT_OBJECT_0, "desktop host watchdog");
    if (failures) goto done;
    check(GetExitCodeProcess(process.hProcess, &code), "desktop host exit status");
    printf("DESKTOP_HOST_EXIT=%lu\n", code);
    check(code == 0, "disposable desktop host succeeded");
 done:
    if (started && WaitForSingleObject(process.hProcess, 0) != WAIT_OBJECT_0) {
        check(TerminateProcess(process.hProcess, 99) || WaitForSingleObject(process.hProcess, 0) == WAIT_OBJECT_0,
              "terminate unfinished desktop host");
        if (job) check(TerminateJobObject(job, 99), "terminate disposable desktop process tree");
        check(WaitForSingleObject(process.hProcess, 5000) == WAIT_OBJECT_0, "desktop host cleanup watchdog");
    }
    if (job) CloseHandle(job);
    if (process.hThread) CloseHandle(process.hThread);
    if (process.hProcess) CloseHandle(process.hProcess);
    if (switched) check(SetProcessWindowStation(original), "restore test station during cleanup");
    if (desktop) check(CloseDesktop(desktop), "close disposable test desktop");
    if (station) check(CloseWindowStation(station), "close disposable test station");
    if (station_security && desktop_security_before) {
        LPWSTR station_after = desktop_security(original);
        LPWSTR desktop_after = desktop_security(original_desktop);
        check(GetProcessWindowStation() == original && GetThreadDesktop(GetCurrentThreadId()) == original_desktop,
              "fixture preserves original host station and thread desktop");
        check(station_after && !wcscmp(station_security, station_after), "original host station descriptor unchanged");
        check(desktop_after && !wcscmp(desktop_security_before, desktop_after), "original host desktop descriptor unchanged");
        if (station_after) LocalFree(station_after);
        if (desktop_after) LocalFree(desktop_after);
    }
    if (station_security) LocalFree(station_security);
    if (desktop_security_before) LocalFree(desktop_security_before);
    if (descriptor) LocalFree(descriptor);
    if (sid) LocalFree(sid);
    if (token) CloseHandle(token);
    free(command);
}

int wmain(int argc, wchar_t **argv) {
    int i;
    if (argc < 2) return 2;
    if (wcscmp(argv[1], L"wait-chains") == 0 && argc == 3) return wait_chains(wcstoul(argv[2], NULL, 10));
    else if (wcscmp(argv[1], L"protect") == 0 && argc == 3) protected_file(argv[2]);
    else if (wcscmp(argv[1], L"junction") == 0 && argc == 4) junction(argv[2], argv[3]);
    else if (wcscmp(argv[1], L"leak-host") == 0) host_launch(argc, argv, 0);
    else if (wcscmp(argv[1], L"standard-host") == 0) host_launch(argc, argv, 1);
    else if (wcscmp(argv[1], L"desktop-host") == 0) desktop_host(argc, argv);
    else if (wcscmp(argv[1], L"desktop-state") == 0 && argc == 2) desktop_state();
    else if (wcscmp(argv[1], L"desktop-handles") == 0 && argc == 2) desktop_handles();
    else if (wcscmp(argv[1], L"desktop-isolation") == 0) desktop_isolation(argc, argv);
    else if (wcscmp(argv[1], L"desktop-fixture") == 0 && argc == 2) desktop_fixture();
    else if (wcscmp(argv[1], L"desktop-shared") == 0 && argc == 3) desktop_access(argv[2], 1);
    else if (wcscmp(argv[1], L"desktop-private") == 0 && argc == 3) desktop_access(argv[2], 0);
    else if (wcscmp(argv[1], L"desktop-present") == 0 && (argc == 3 || argc == 4))
        desktop_lifetime(argv[2], argc == 4 ? argv[3] : NULL, 1);
    else if (wcscmp(argv[1], L"desktop-absent") == 0 && (argc == 3 || argc == 4))
        desktop_lifetime(argv[2], argc == 4 ? argv[3] : NULL, 0);
    else if (wcscmp(argv[1], L"standard-token") == 0) standard_check();
    else if (wcscmp(argv[1], L"host-handle") == 0) handle_check(argc, argv, 1);
    else if (wcscmp(argv[1], L"secret-handle") == 0) handle_check(argc, argv, 0);
    else if (wcscmp(argv[1], L"profile-path") == 0 && argc == 3) profile_check(argv[2], 0);
    else if (wcscmp(argv[1], L"profile-delete") == 0 && argc == 3) profile_check(argv[2], 1);
    else if (wcscmp(argv[1], L"registry-host") == 0 && argc == 3 &&
             (wcscmp(argv[2], L"machine") == 0 || wcscmp(argv[2], L"user") == 0))
        registry_host(wcscmp(argv[2], L"machine") == 0);
    else if (wcscmp(argv[1], L"registry") == 0) registry_check(argc, argv);
    else if (wcscmp(argv[1], L"token") == 0) token_check();
    else if (wcscmp(argv[1], L"security") == 0) security_check(argc, argv);
    else if (wcscmp(argv[1], L"denied-file") == 0 && argc == 3) {
        denied(argv[2], GENERIC_READ, "sibling profile read denied");
        denied(argv[2], GENERIC_WRITE, "sibling profile write denied");
    }
    else if (wcscmp(argv[1], L"network") == 0) network_check(argc, argv);
    else if (wcscmp(argv[1], L"descendant") == 0) {
        child_check(L"token", 0, 0);
        child_check(L"sleep", CREATE_BREAKAWAY_FROM_JOB | CREATE_SUSPENDED, 1);
    } else if (wcscmp(argv[1], L"tree") == 0) child_check(L"sleep", 0, 0);
    else if (wcscmp(argv[1], L"sleep") == 0) Sleep(INFINITE);
    else if (wcscmp(argv[1], L"pty-flood") == 0) {
        char block[8192];
        DWORD count;
        memset(block, 'x', sizeof(block));
        /* Keep producing until killed; finite console writes need not yield equal VT output. */
        for (;;) {
            if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), block, sizeof(block), &count, NULL)) return 1;
            if (count != sizeof(block)) return 1;
        }
    }
    else if (wcscmp(argv[1], L"streams") == 0) { stream_check(); return failures ? 1 : 0; }
    else if (wcscmp(argv[1], L"args") == 0) {
        for (i = 2; i < argc; i++) {
            const wchar_t *at = argv[i];
            printf("ARG=");
            while (*at) printf("%04X", (unsigned int)*at++);
            printf("\n");
        }
    } else if (wcscmp(argv[1], L"environment") == 0) {
        wchar_t value[32768];
        check(GetEnvironmentVariableW(L"VIS_JAIL_TEST", value, 32768) && wcscmp(value, L"expected") == 0,
              "complete explicit environment");
        check(!GetEnvironmentVariableW(L"VIS_JAIL_SECRET", value, 32768), "host environment not inherited");
        system_root_check();
        {
            LPWCH environment = GetEnvironmentStringsW();
            int count = 0;
            check(environment != NULL, "read complete child environment");
            if (environment) {
                for (const wchar_t *entry = environment; *entry; entry += wcslen(entry) + 1) {
                    check(!_wcsnicmp(entry, L"VIS_JAIL_TEST=", 14) || !_wcsnicmp(entry, L"TEMP=", 5) ||
                          !_wcsnicmp(entry, L"TMP=", 4) || !_wcsnicmp(entry, L"SystemRoot=", 11) ||
                          !_wcsnicmp(entry, L"LOCALAPPDATA=", 13),
                          "no unrelated environment entries");
                    count++;
                }
                check(count == 5, "explicit entry plus four reserved environment entries");
                FreeEnvironmentStringsW(environment);
            }
        }
        check(GetCurrentDirectoryW(32768, value) > 0 && argc == 5 && _wcsicmp(value, argv[2]) == 0,
              "private working directory");
        check(GetEnvironmentVariableW(L"TEMP", value, 32768) && _wcsicmp(value, argv[3]) == 0, "private TEMP");
        check(GetEnvironmentVariableW(L"TMP", value, 32768) && _wcsicmp(value, argv[3]) == 0, "private TMP");
        check(GetEnvironmentVariableW(L"LOCALAPPDATA", value, 32768) && _wcsicmp(value, argv[4]) == 0, "private LOCALAPPDATA");
        {
            wchar_t path[32768];
            HANDLE file;
            swprintf_s(path, 32768, L"%ls\\application-data.txt", value);
            file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
            check(file != INVALID_HANDLE_VALUE, "create private application data");
            if (file != INVALID_HANDLE_VALUE) {
                DWORD written;
                check(WriteFile(file, "data", 4, &written, NULL) && written == 4, "write private application data");
                CloseHandle(file);
                check(DeleteFileW(path), "delete private application data");
            }
        }
    } else if (wcscmp(argv[1], L"pty") == 0) {
        CONSOLE_SCREEN_BUFFER_INFO info = {0};
        DWORD mode, count = 0;
        char input[128];
        token_check();
        check(GetConsoleScreenBufferInfo(GetStdHandle(STD_OUTPUT_HANDLE), &info), "ConPTY console");
        check(info.dwSize.X == 97 && info.dwSize.Y == 31, "ConPTY dimensions");
        check(GetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), &mode), "ConPTY input console");
        check(GetConsoleMode(GetStdHandle(STD_ERROR_HANDLE), &mode), "ConPTY error console");
        printf("PTY=31x97\n");
        fflush(stdout);
        check(ReadFile(GetStdHandle(STD_INPUT_HANDLE), input, sizeof(input), &count, NULL) && count > 0,
              "ConPTY input roundtrip");
        check(count >= 4 && memcmp(input, "ping", 4) == 0, "ConPTY received ping");
    } else if (wcscmp(argv[1], L"handles") == 0 && argc == 3) {
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD)_wcstoui64(argv[2], NULL, 10));
        DWORD handles = 0;
        check(process != NULL && GetProcessHandleCount(process, &handles), "host handle count");
        if (process) CloseHandle(process);
        printf("HANDLES=%lu\n", handles);
    } else return 2;
    if (!failures) printf("PASS\n");
    return failures ? 1 : 0;
}
