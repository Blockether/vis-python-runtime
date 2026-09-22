/* Test-only raw-file boundary falsification. Not a policy backend or descendant proof.
 * The host includes only the common exact-object read helpers below. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <winternl.h>
#include <sddl.h>
#include <aclapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <string.h>
#include <limits.h>

struct Identity { DWORD volume, high, low; };
struct Reading { int opened, exact; DWORD error; LONG status; };
typedef NTSTATUS (NTAPI *NativeOpen)(PHANDLE, ACCESS_MASK, POBJECT_ATTRIBUTES,
    PIO_STATUS_BLOCK, PLARGE_INTEGER, ULONG, ULONG, ULONG, ULONG, PVOID, ULONG);
static const char sentinel[3][9] = {"normal01", "null0001", "empty001"};

/* Exact self-relative bytes avoid machine-relative SDDL aliases inside LPAC.
 * Validate every relative range before asking the Windows descriptor validator. */
static PSECURITY_DESCRIPTOR decode_descriptor(const wchar_t *text) {
    size_t length = wcsnlen_s(text, 8193), bytes;
    BYTE *value;
    SECURITY_DESCRIPTOR_RELATIVE *relative;
    DWORD offsets[4];
    if (length < sizeof(SECURITY_DESCRIPTOR_RELATIVE) * 2 || length > 8192 || length % 2) return NULL;
    bytes = length / 2;
    value = LocalAlloc(LMEM_FIXED, bytes);
    if (!value) return NULL;
    for (size_t i = 0; i < bytes; i++) {
        unsigned int pair = 0;
        for (size_t j = 0; j < 2; j++) {
            wchar_t c = text[i * 2 + j];
            if (c < L'0' || (c > L'9' && c < L'a') || c > L'f') goto invalid;
            pair = pair * 16 + (unsigned int)(c <= L'9' ? c - L'0' : c - L'a' + 10);
        }
        value[i] = (BYTE)pair;
    }
    relative = (SECURITY_DESCRIPTOR_RELATIVE *)value;
    if (!(relative->Control & SE_SELF_RELATIVE)) goto invalid;
    offsets[0] = relative->Owner; offsets[1] = relative->Group;
    offsets[2] = relative->Sacl; offsets[3] = relative->Dacl;
    for (int i = 0; i < 4; i++) {
        size_t offset = offsets[i], needed;
        if (!offset) continue;
        if (offset < sizeof(*relative) || offset % sizeof(DWORD) || offset > bytes || bytes - offset < 8) goto invalid;
        needed = i < 2 ? 8 + (size_t)value[offset + 1] * sizeof(DWORD) :
            (size_t)value[offset + 2] | ((size_t)value[offset + 3] << 8);
        if (needed < 8 || needed > bytes - offset) goto invalid;
    }
    if (!IsValidSecurityDescriptor(value) || GetSecurityDescriptorLength(value) != bytes) goto invalid;
    return value;
 invalid:
    LocalFree(value);
    return NULL;
}

static int identity(HANDLE file, struct Identity *value) {
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(file, &info)) return 0;
    value->volume = info.dwVolumeSerialNumber;
    value->high = info.nFileIndexHigh; value->low = info.nFileIndexLow;
    return 1;
}

static int same_identity(const struct Identity *left, const struct Identity *right) {
    return left->volume == right->volume && left->high == right->high && left->low == right->low;
}

static HANDLE open_nt(const wchar_t *path, int directory, LONG *status) {
    NativeOpen function = NULL;
    FARPROC address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateFile");
    UNICODE_STRING name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io;
    HANDLE file = INVALID_HANDLE_VALUE;
    size_t length = wcslen(path) * sizeof(wchar_t);
    if (!address || sizeof(address) != sizeof(function) || length > USHRT_MAX - sizeof(wchar_t)) {
        *status = (LONG)0xC000000DL; return INVALID_HANDLE_VALUE;
    }
    memcpy(&function, &address, sizeof(function));
    name.Buffer = (PWSTR)path; name.Length = (USHORT)length;
    name.MaximumLength = (USHORT)(length + sizeof(wchar_t));
    ZeroMemory(&attributes, sizeof(attributes)); attributes.Length = sizeof(attributes);
    attributes.ObjectName = &name; attributes.Attributes = 0x40; /* OBJ_CASE_INSENSITIVE */
    *status = function(&file, directory ? FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE : FILE_GENERIC_READ,
        &attributes, &io, NULL, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        1, 0x20UL | (directory ? 0x1UL : 0x40UL), NULL, 0);
    return *status >= 0 ? file : INVALID_HANDLE_VALUE;
}

static struct Reading read_object(const wchar_t *ntpath, int raw, int directory,
    const struct Identity *expected, int index) {
    struct Reading result = {0};
    struct Identity actual;
    HANDLE file;
    wchar_t global[2048];
    if (raw) file = open_nt(ntpath, directory, &result.status);
    else {
        if (swprintf_s(global, 2048, L"\\\\?\\GLOBALROOT%ls", ntpath) < 0) {
            result.error = ERROR_BUFFER_OVERFLOW; return result;
        }
        file = CreateFileW(global, directory ? FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE : FILE_GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
            directory ? FILE_FLAG_BACKUP_SEMANTICS : FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (file == INVALID_HANDLE_VALUE) {
        if (raw) {
            typedef ULONG (WINAPI *DosError)(NTSTATUS);
            DosError convert = NULL;
            FARPROC address = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlNtStatusToDosError");
            if (address && sizeof(address) == sizeof(convert)) memcpy(&convert, &address, sizeof(convert));
            result.error = convert ? convert(result.status) : ERROR_INVALID_FUNCTION;
        } else result.error = GetLastError();
        return result;
    }
    result.opened = 1;
    result.exact = identity(file, &actual) && same_identity(expected, &actual);
    if (!directory) {
        char bytes[9] = {0};
        DWORD count = 0;
        result.exact = result.exact && ReadFile(file, bytes, sizeof(bytes), &count, NULL) &&
            count == 8 && !memcmp(bytes, sentinel[index], 8);
    }
    result.error = result.exact ? ERROR_SUCCESS : ERROR_INVALID_DATA;
    CloseHandle(file);
    return result;
}

static int access_result(PSECURITY_DESCRIPTOR descriptor, HANDLE token, DWORD *error, DWORD *granted) {
    GENERIC_MAPPING mapping = {FILE_GENERIC_READ, FILE_GENERIC_WRITE, FILE_GENERIC_EXECUTE, FILE_ALL_ACCESS};
    union { PRIVILEGE_SET alignment; BYTE bytes[8192]; } privileges;
    DWORD size = sizeof(privileges);
    BOOL allowed = FALSE;
    *granted = 0;
    if (!AccessCheck(descriptor, token, FILE_GENERIC_READ, &mapping,
        (PPRIVILEGE_SET)privileges.bytes, &size, granted, &allowed)) {
        *error = GetLastError(); return -1;
    }
    *error = allowed ? ERROR_SUCCESS : ERROR_ACCESS_DENIED;
    return allowed ? 1 : 0;
}

#ifndef VIS_RESTRICTED_HOST
static void *token_info(HANDLE token, TOKEN_INFORMATION_CLASS kind) {
    DWORD size = 0;
    void *value;
    if (GetTokenInformation(token, kind, NULL, 0, &size) || GetLastError() != ERROR_INSUFFICIENT_BUFFER ||
        !size || size > 65536) return NULL;
    value = malloc(size);
    if (!value) return NULL;
    if (!GetTokenInformation(token, kind, value, size, &size)) { free(value); return NULL; }
    return value;
}

static int groups_equal(const TOKEN_GROUPS *left, const TOKEN_GROUPS *right) {
    if (!left || !right || left->GroupCount != right->GroupCount) return 0;
    for (DWORD i = 0; i < left->GroupCount; i++) {
        if (!EqualSid(left->Groups[i].Sid, right->Groups[i].Sid) ||
            left->Groups[i].Attributes != right->Groups[i].Attributes) return 0;
    }
    return 1;
}

static int registry_capability(const TOKEN_GROUPS *capabilities) {
    PSID *groups = NULL, *sids = NULL;
    DWORD group_count = 0, sid_count = 0;
    int ok = DeriveCapabilitySidsFromName(L"registryRead", &groups, &group_count, &sids, &sid_count) &&
        capabilities && capabilities->GroupCount == 1 && sid_count == 1 &&
        capabilities->Groups[0].Attributes == SE_GROUP_ENABLED && EqualSid(capabilities->Groups[0].Sid, sids[0]);
    if (groups) { for (DWORD i = 0; i < group_count; i++) LocalFree(groups[i]); LocalFree(groups); }
    if (sids) { for (DWORD i = 0; i < sid_count; i++) LocalFree(sids[i]); LocalFree(sids); }
    return ok;
}

static int effective_lpac(HANDLE token, const wchar_t *restrict_sid) {
    wchar_t text[512];
    PSECURITY_DESCRIPTOR descriptor = NULL;
    GENERIC_MAPPING mapping = {0};
    union { PRIVILEGE_SET alignment; BYTE bytes[8192]; } privileges;
    DWORD size = sizeof(privileges), granted = 0;
    BOOL allowed = FALSE;
    int ok;
    if (swprintf_s(text, 512,
        L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)(A;;0x3;;;%ls)",
        restrict_sid) < 0 || !ConvertStringSecurityDescriptorToSecurityDescriptorW(text,
            SDDL_REVISION_1, &descriptor, NULL)) return 0;
    ok = AccessCheck(descriptor, token, MAXIMUM_ALLOWED, &mapping, (PPRIVILEGE_SET)privileges.bytes,
        &size, &granted, &allowed) && allowed && granted == 0x2;
    LocalFree(descriptor);
    return ok;
}

static int token_properties(HANDLE token, HANDLE baseline, PSID restrict_sid,
    const wchar_t *restrict_text, int phase) {
    DWORD app = 0, size = 0, integrity = 0;
    TOKEN_MANDATORY_LABEL *label = token_info(token, TokenIntegrityLevel);
    TOKEN_APPCONTAINER_INFORMATION *package = token_info(token, TokenAppContainerSid);
    TOKEN_APPCONTAINER_INFORMATION *original = token_info(baseline, TokenAppContainerSid);
    TOKEN_GROUPS *caps = token_info(token, TokenCapabilities), *before_caps = token_info(baseline, TokenCapabilities);
    TOKEN_GROUPS *restricted = token_info(token, TokenRestrictedSids);
    int unique = restricted && restricted->GroupCount == 1 && EqualSid(restricted->Groups[0].Sid, restrict_sid);
    int lpac = effective_lpac(token, restrict_text);
    int ok;
    if (label && IsValidSid(label->Label.Sid) && *GetSidSubAuthorityCount(label->Label.Sid))
        integrity = *GetSidSubAuthority(label->Label.Sid, (DWORD)*GetSidSubAuthorityCount(label->Label.Sid) - 1);
    ok = GetTokenInformation(token, TokenIsAppContainer, &app, sizeof(app), &size) && app == 1 &&
        integrity == SECURITY_MANDATORY_LOW_RID && lpac && package && original &&
        package->TokenAppContainer && original->TokenAppContainer &&
        EqualSid(package->TokenAppContainer, original->TokenAppContainer) &&
        groups_equal(caps, before_caps) && registry_capability(caps) && restricted && (!phase || unique);
    printf("TOKEN phase=%d app=%lu integrity=%lu lpac=%d caps=%lu restricted=%lu unique=%d valid=%d\n",
        phase, app, integrity, lpac, caps ? caps->GroupCount : MAXDWORD,
        restricted ? restricted->GroupCount : MAXDWORD, unique, ok);
    if (restricted) for (DWORD i = 0; i < restricted->GroupCount; i++) {
        LPWSTR text = NULL;
        if (!ConvertSidToStringSidW(restricted->Groups[i].Sid, &text)) ok = 0;
        else {
            printf("RESTRICTING_SID phase=%d index=%lu sid=%ls attributes=%lu\n", phase, i, text,
                restricted->Groups[i].Attributes);
            LocalFree(text);
        }
    }
    free(label); free(package); free(original); free(caps); free(before_caps); free(restricted);
    return ok;
}

static int parse_identity(const wchar_t *text, struct Identity *value) {
    wchar_t extra;
    return swscanf_s(text, L"%lu:%lu:%lu%c", &value->volume, &value->high, &value->low,
        &extra, (unsigned)1) == 3;
}

int wmain(int argc, wchar_t **argv) {
    HANDLE process = NULL, baseline = NULL, restricted = NULL, thread = NULL;
    PSID unique = NULL;
    SID_AND_ATTRIBUTES added;
    PSECURITY_DESCRIPTOR descriptors[3] = {0};
    struct Identity files[3], directory;
    wchar_t parent[2048], *last;
    int result = 2, impersonating = 0, falsified = 0;
    if (argc != 12) {
        printf("SETUP_FAILED stage=argc actual=%d expected=12 error=%lu\n", argc, (DWORD)ERROR_BAD_ARGUMENTS);
        goto done;
    }
    if (!ConvertStringSidToSidW(argv[1], &unique)) {
        printf("SETUP_FAILED stage=restricting_sid error=%lu\n", GetLastError()); goto done;
    }
    if (!parse_identity(argv[11], &directory)) {
        printf("SETUP_FAILED stage=directory_identity error=%lu\n", (DWORD)ERROR_INVALID_DATA); goto done;
    }
    {
        errno_t copied = wcscpy_s(parent, 2048, argv[2]);
        if (copied) {
            printf("SETUP_FAILED stage=parent_copy crt_error=%d\n", (int)copied); goto done;
        }
    }
    last = wcsrchr(parent, L'\\');
    if (!last || last == parent) {
        printf("SETUP_FAILED stage=parent_separator error=%lu\n", (DWORD)ERROR_INVALID_NAME); goto done;
    }
    *last = 0;
    for (int i = 0; i < 3; i++) {
        descriptors[i] = decode_descriptor(argv[3 + i * 3]);
        if (!descriptors[i]) {
            printf("SETUP_FAILED stage=file_descriptor_bytes index=%d error=%lu\n", i, (DWORD)ERROR_INVALID_SECURITY_DESCR); goto done;
        }
        if (!parse_identity(argv[4 + i * 3], &files[i])) {
            printf("SETUP_FAILED stage=file_identity index=%d error=%lu\n", i, (DWORD)ERROR_INVALID_DATA); goto done;
        }
    }
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &process)) {
        printf("SETUP_FAILED stage=open_process_token error=%lu\n", GetLastError()); goto done;
    }
    if (!DuplicateTokenEx(process, TOKEN_QUERY | TOKEN_DUPLICATE | TOKEN_IMPERSONATE,
        NULL, SecurityImpersonation, TokenImpersonation, &baseline)) {
        printf("SETUP_FAILED stage=duplicate_token error=%lu\n", GetLastError()); goto done;
    }
    if (!token_properties(baseline, baseline, unique, argv[1], 0)) goto done;
    for (int phase = 0; phase < 2; phase++) {
        HANDLE token = baseline;
        if (phase) {
            added.Sid = unique; added.Attributes = 0;
            if (!CreateRestrictedToken(baseline, 0, 0, NULL, 0, NULL, 1, &added, &restricted)) {
                printf("RESTRICTION_NOT_ESTABLISHED error=%lu\n", GetLastError()); goto done;
            }
            if (!token_properties(restricted, baseline, unique, argv[1], 1)) goto done;
            token = restricted;
        }
        if (!SetThreadToken(NULL, token)) {
            printf("IMPERSONATION_FAILED phase=%d error=%lu\n", phase, GetLastError()); goto done;
        }
        impersonating = 1;
        if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY | TOKEN_DUPLICATE, TRUE, &thread) ||
            !token_properties(thread, baseline, unique, argv[1], phase)) goto done;
        for (int raw = 0; raw < 2; raw++) {
            struct Reading ancestor = read_object(parent, raw, 1, &directory, 0);
            printf("ANCESTOR phase=%d raw=%d opened=%d exact=%d error=%lu status=%08lx\n",
                phase, raw, ancestor.opened, ancestor.exact, ancestor.error, (ULONG)ancestor.status);
            if (!ancestor.exact) goto done;
        }
        for (int i = 0; i < 3; i++) {
            DWORD error = 0, granted = 0;
            int allowed = access_result(descriptors[i], thread, &error, &granted);
            printf("ACCESS phase=%d file=%d allowed=%d granted=%lu error=%lu\n", phase, i, allowed, granted, error);
            if (allowed < 0) goto done;
            for (int raw = 0; raw < 2; raw++) {
                struct Reading read = read_object(argv[2 + i * 3], raw, 0, &files[i], i);
                printf("READ phase=%d file=%d raw=%d opened=%d exact=%d error=%lu status=%08lx\n",
                    phase, i, raw, read.opened, read.exact, read.error, (ULONG)read.status);
                if ((read.opened && !read.exact) || (!read.opened && read.error != ERROR_ACCESS_DENIED) ||
                    (!phase && i == 0 && !read.exact) || (i == 2 && read.opened)) goto done;
                if (phase && i != 2 && read.exact) falsified = 1;
            }
        }
        CloseHandle(thread); thread = NULL;
        if (!RevertToSelf()) goto done;
        impersonating = 0;
    }
    result = falsified ? 1 : 0;
 done:
    if (impersonating && !RevertToSelf()) {
        puts("REVERT_FAILED; terminating test process"); ExitProcess(2);
    }
    if (thread) CloseHandle(thread);
    if (restricted) CloseHandle(restricted);
    if (baseline) CloseHandle(baseline);
    if (process) CloseHandle(process);
    if (unique) LocalFree(unique);
    for (int i = 0; i < 3; i++) if (descriptors[i]) LocalFree(descriptors[i]);
    printf("BOUNDARY_RESULT=%s; impersonation only, no policy parity claim\n",
        result == 1 ? "FALSIFIED" : result == 0 ? "NOT_FALSIFIED" : "INCONCLUSIVE");
    fflush(stdout);
    return result;
}
#endif
