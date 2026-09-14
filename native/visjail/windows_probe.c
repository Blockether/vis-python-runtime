/* Test-only adversarial guest for WindowsJail. Never shipped in runtime archives. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define _CRT_SECURE_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <userenv.h>
#include <objbase.h>
#include <stdint.h>
#include <winioctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <io.h>
#include <fcntl.h>

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

/* CI 34881927919: guest object grants must cover both sides of the token check. */
static void default_dacl_check(HANDLE token) {
    union TokenData {
        TOKEN_USER user;
        TOKEN_APPCONTAINER_INFORMATION package;
        TOKEN_DEFAULT_DACL defaults;
        BYTE data[4096];
    } user, package, defaults;
    DWORD size;
    int has_user = 0, has_package = 0, has_system = 0;
    PACL acl;
    if (!GetTokenInformation(token, TokenUser, &user, sizeof(user), &size) ||
        !GetTokenInformation(token, TokenAppContainerSid, &package, sizeof(package), &size) ||
        !GetTokenInformation(token, TokenDefaultDacl, &defaults, sizeof(defaults), &size)) {
        check(0, "read guest object default permissions"); return;
    }
    check(package.package.TokenAppContainer != NULL, "guest object package identity");
    if (!package.package.TokenAppContainer) return;
    acl = defaults.defaults.DefaultDacl;
    check(acl != NULL && IsValidAcl(acl), "explicit valid guest object DACL");
    if (!acl || !IsValidAcl(acl)) return;
    check(acl->AceCount >= 2 && acl->AceCount <= 3, "only user, package and system default grants");
    for (DWORD index = 0; index < acl->AceCount; index++) {
        ACCESS_ALLOWED_ACE *ace = NULL;
        int own_user, own_package, system;
        check(GetAce(acl, index, (void **)&ace), "read guest object ACE");
        if (!ace) continue;
        check(ace->Header.AceType == ACCESS_ALLOWED_ACE_TYPE && ace->Header.AceFlags == 0 &&
              ace->Mask == GENERIC_ALL, "explicit full guest object grant");
        if (ace->Header.AceType != ACCESS_ALLOWED_ACE_TYPE) continue;
        own_user = EqualSid(&ace->SidStart, user.user.User.Sid);
        own_package = EqualSid(&ace->SidStart, package.package.TokenAppContainer);
        system = IsWellKnownSid(&ace->SidStart, WinLocalSystemSid);
        check(own_user || own_package || system, "no ambient group object grants");
        has_user |= own_user; has_package |= own_package; has_system |= system;
    }
    check(has_user && has_package && has_system, "user and private package both have object access");
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
    default_dacl_check(token);
    if (GetTokenInformation(token, TokenIntegrityLevel, data, sizeof(data), &size)) {
        PSID integrity = ((TOKEN_MANDATORY_LABEL *)data)->Label.Sid;
        DWORD rid = *GetSidSubAuthority(integrity, (DWORD)*GetSidSubAuthorityCount(integrity) - 1);
        check(rid == SECURITY_MANDATORY_LOW_RID, "low mandatory integrity");
    } else check(0, "query mandatory integrity");
    check(GetTokenInformation(token, TokenCapabilities, data, sizeof(data), &size)
          && ((TOKEN_GROUPS *)data)->GroupCount == 0, "no network or ambient capabilities");
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
    check(socket_handle != INVALID_SOCKET, "network probe socket available");
    if (socket_handle == INVALID_SOCKET) return;
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
    unsigned short port;
    check(argc == 3, "network arguments");
    if (argc != 3) return;
    port = (unsigned short)_wtoi(argv[2]);
    check(WSAStartup(MAKEWORD(2, 2), &data) == 0, "Winsock startup");
    for (public_address = 0; public_address < 2; public_address++) {
        network_one(AF_INET, SOCK_STREAM, port, public_address);
        network_one(AF_INET, SOCK_DGRAM, port, public_address);
        network_one(AF_INET6, SOCK_STREAM, port, public_address);
        network_one(AF_INET6, SOCK_DGRAM, port, public_address);
    }
    WSACleanup();
}

static void child_check(const wchar_t *mode, DWORD flags, int expect_failure) {
    wchar_t executable[32768], command[32768];
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    BOOL spawned;
    DWORD code = 1;
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    check(GetModuleFileNameW(NULL, executable, 32768) > 0, "find guest executable");
    swprintf_s(command, 32768, L"\"%ls\" %ls", executable, mode);
    spawned = CreateProcessW(executable, command, NULL, NULL, TRUE, flags, NULL, NULL, &startup, &process);
    if (expect_failure) {
        check(!spawned, "job breakaway denied");
        if (spawned) TerminateProcess(process.hProcess, 99);
    } else {
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

static HANDLE standard_token(void) {
    HANDLE original = NULL, restricted = NULL;
    PSID administrators = NULL, medium = NULL;
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    SID_AND_ATTRIBUTES disabled;
    TOKEN_MANDATORY_LABEL label;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY,
                           &original), "open parent token");
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
    CloseHandle(original);
    return restricted;
}

static void standard_check(void) {
    HANDLE token = NULL, impersonation = NULL;
    BYTE data[4096];
    DWORD size;
    PSID administrators = NULL;
    BOOL member = TRUE;
    SID_IDENTIFIER_AUTHORITY nt = SECURITY_NT_AUTHORITY;
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token), "standard host token");
    if (!token) return;
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
    DWORD count = 0;
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
    if (handle && GetFileType(handle) == FILE_TYPE_DISK) {
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

int wmain(int argc, wchar_t **argv) {
    int i;
    if (argc < 2) return 2;
    if (wcscmp(argv[1], L"protect") == 0 && argc == 3) protected_file(argv[2]);
    else if (wcscmp(argv[1], L"junction") == 0 && argc == 4) junction(argv[2], argv[3]);
    else if (wcscmp(argv[1], L"leak-host") == 0) host_launch(argc, argv, 0);
    else if (wcscmp(argv[1], L"standard-host") == 0) host_launch(argc, argv, 1);
    else if (wcscmp(argv[1], L"standard-token") == 0) standard_check();
    else if (wcscmp(argv[1], L"host-handle") == 0) handle_check(argc, argv, 1);
    else if (wcscmp(argv[1], L"secret-handle") == 0) handle_check(argc, argv, 0);
    else if (wcscmp(argv[1], L"profile-path") == 0 && argc == 3) profile_check(argv[2], 0);
    else if (wcscmp(argv[1], L"profile-delete") == 0 && argc == 3) profile_check(argv[2], 1);
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
        int index;
        memset(block, 'x', sizeof(block));
        for (index = 0; index < 128; index++) {
            if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), block, sizeof(block), &count, NULL)) return 1;
            if (count != sizeof(block)) return 1;
        }
        Sleep(INFINITE);
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
        check(GetConsoleScreenBufferInfo(GetStdHandle(STD_OUTPUT_HANDLE), &info), "ConPTY console");
        check(info.dwSize.X == 97 && info.dwSize.Y == 31, "ConPTY dimensions");
        check(GetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), &mode), "ConPTY input console");
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
