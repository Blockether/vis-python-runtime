/* Bounded Windows policy feasibility guest. Not a product policy test suite. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

static int failed;

static int check(int value, const char *name) {
    printf("%s %s error=%lu\n", value ? "PASS" : "FAIL", name,
        value ? 0UL : GetLastError());
    fflush(stdout);
    if (!value) failed = 1;
    return value;
}

static int token_check(void) {
    HANDLE token = NULL, duplicate = NULL;
    PSECURITY_DESCRIPTOR sd = NULL;
    GENERIC_MAPPING mapping = {0};
    DWORD size = 0, container = 0, granted = 0;
    union { TOKEN_MANDATORY_LABEL align; unsigned char bytes[4096]; } data;
    PRIVILEGE_SET initial = {0}, *privileges = &initial;
    BOOL allowed = FALSE, ok = FALSE;
    if (!check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE,
            &token), "open token")) return 0;
    check(GetTokenInformation(token, TokenIsAppContainer, &container,
        sizeof(container), &size) && container == 1, "AppContainer");
    if (check(GetTokenInformation(token, TokenIntegrityLevel, data.bytes,
            sizeof(data.bytes), &size), "integrity query")) {
        PSID sid = ((TOKEN_MANDATORY_LABEL *)data.bytes)->Label.Sid;
        check(IsValidSid(sid) && *GetSidSubAuthority(sid,
            (DWORD)*GetSidSubAuthorityCount(sid) - 1) == SECURITY_MANDATORY_LOW_RID,
            "exact LOW integrity");
    }
    if (DuplicateToken(token, SecurityImpersonation, &duplicate) &&
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)",
            SDDL_REVISION_1, &sd, NULL)) {
        size = sizeof(initial);
        ok = AccessCheck(sd, duplicate, MAXIMUM_ALLOWED, &mapping,
            privileges, &size, &granted, &allowed);
        if (!ok && GetLastError() == ERROR_INSUFFICIENT_BUFFER) {
            privileges = malloc(size);
            if (privileges) ok = AccessCheck(sd, duplicate, MAXIMUM_ALLOWED,
                &mapping, privileges, &size, &granted, &allowed);
        }
        check(ok && allowed && granted == 2, "effective LPAC restricted package access");
    } else check(0, "LPAC access-check setup");
    if (privileges != &initial) free(privileges);
    if (sd) LocalFree(sd);
    if (duplicate) CloseHandle(duplicate);
    CloseHandle(token);
    return !failed;
}

static int denied(const wchar_t *path, DWORD access, const char *name) {
    HANDLE file = CreateFileW(path, access, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD error = GetLastError();
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    SetLastError(error);
    return check(file == INVALID_HANDLE_VALUE && error == ERROR_ACCESS_DENIED, name);
}

static int read_value(const wchar_t *path, const char *expected) {
    char bytes[8];
    DWORD count = 0;
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    int ok = file != INVALID_HANDLE_VALUE && ReadFile(file, bytes, sizeof(bytes),
        &count, NULL) && count == sizeof(bytes) && !memcmp(bytes, expected, sizeof(bytes));
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    return check(ok, expected);
}

int wmain(int argc, wchar_t **argv) {
    HANDLE file;
    DWORD count = 0;
    char command = 0, magic[2];
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION child = {0};
    BOOL started;
    DWORD error;
    if (argc != 6 || (wcscmp(argv[1], L"allow") && wcscmp(argv[1], L"sibling"))) {
        fputs("usage: guest allow|sibling original outside raw-alias noexec-image\n", stderr);
        return 2;
    }
    if (!token_check()) return 1;
    if (!wcscmp(argv[1], L"sibling")) {
        denied(argv[2], GENERIC_READ, "sibling package cannot read synthetic view");
        denied(argv[2], GENERIC_WRITE, "sibling package cannot write synthetic view");
        return failed;
    }
    if (!read_value(argv[2], "host-one")) return 1;
    file = CreateFileW(argv[2], GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (!check(file != INVALID_HANDLE_VALUE, "original absolute path write open")) return 1;
    check(WriteFile(file, "guestone", 8, &count, NULL) && count == 8 &&
        FlushFileBuffers(file), "write existing host object through view");
    CloseHandle(file);
    if (failed) return 1;
    puts("GUEST_WROTE");
    fflush(stdout);
    if (!check(ReadFile(GetStdHandle(STD_INPUT_HANDLE), &command, 1, &count, NULL) &&
            count == 1 && command == 'c', "host synchronization")) return 1;
    read_value(argv[2], "host-two");
    denied(argv[3], GENERIC_READ, "outside host sentinel read denied");
    denied(argv[3], GENERIC_WRITE, "outside host sentinel write denied");
    denied(argv[4], GENERIC_READ, "direct volume alias read denied");
    denied(argv[4], GENERIC_WRITE, "direct volume alias write denied");
    file = CreateFileW(argv[5], GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    check(file != INVALID_HANDLE_VALUE && ReadFile(file, magic, 2, &count, NULL) &&
        count == 2 && magic[0] == 'M' && magic[1] == 'Z', "denied-exec image readable");
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    startup.cb = sizeof(startup);
    started = CreateProcessW(argv[5], NULL, NULL, NULL, FALSE, CREATE_SUSPENDED,
        NULL, NULL, &startup, &child);
    error = GetLastError();
    if (started) {
        TerminateProcess(child.hProcess, 99);
        WaitForSingleObject(child.hProcess, 5000);
        CloseHandle(child.hThread);
        CloseHandle(child.hProcess);
    }
    SetLastError(error);
    check(!started && error == ERROR_ACCESS_DENIED, "readable image cannot execute");
    puts(failed ? "POLICY_GUEST_FAIL" : "POLICY_GUEST_PASS");
    return failed;
}
