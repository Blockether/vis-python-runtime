/* Test-only restricting-SID/raw-NT falsification using the actual WindowsJail DLL.
 * Usage: windows-restricted-policy-probe.exe <absolute-dll> <absolute-guest> <empty-root>
 * Root is an existing fresh ASCII directory shorter than 64 bytes. No existing ACL
 * or integrity label is changed. Exit 0 = gate not falsified (NOT policy support),
 * 1 = candidate falsified, 2 = prerequisite/test/cleanup failure. */
#define VIS_RESTRICTED_HOST
#include "windows_restricted_policy_guest.c"
#include "visjail.h"
#include <bcrypt.h>

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
    LOAD(spawn, "visjail_spawn"); LOAD(read, "visjail_read"); LOAD(close, "visjail_close");
    LOAD(poll, "visjail_poll"); LOAD(wait, "visjail_wait"); LOAD(pid, "visjail_windows_pid");
#undef LOAD
    return 1;
}

static int check(int ok, const char *name) {
    printf("%s %s error=%lu\n", ok ? "PASS" : "FAIL", name, ok ? 0UL : GetLastError());
    fflush(stdout);
    return ok;
}

static LPWSTR descriptor_text(HANDLE file) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    LPWSTR text = NULL;
    SECURITY_INFORMATION information = OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION;
    if (GetSecurityInfo(file, SE_FILE_OBJECT, information, NULL, NULL, NULL, NULL, &descriptor) == ERROR_SUCCESS) {
        SECURITY_DESCRIPTOR_CONTROL control = 0;
        DWORD revision = 0, bytes = GetSecurityDescriptorLength(descriptor);
        if (IsValidSecurityDescriptor(descriptor) &&
            GetSecurityDescriptorControl(descriptor, &control, &revision) &&
            (control & SE_SELF_RELATIVE) && bytes >= sizeof(SECURITY_DESCRIPTOR_RELATIVE) && bytes <= 4096) {
            static const wchar_t digits[] = L"0123456789abcdef";
            text = LocalAlloc(LMEM_FIXED, ((SIZE_T)bytes * 2 + 1) * sizeof(wchar_t));
            if (text) {
                for (DWORD i = 0; i < bytes; i++) {
                    BYTE value = ((BYTE *)descriptor)[i];
                    text[i * 2] = digits[value >> 4]; text[i * 2 + 1] = digits[value & 15];
                }
                PSECURITY_DESCRIPTOR decoded;
                text[bytes * 2] = 0;
                decoded = decode_descriptor(text);
                if (!decoded || memcmp(decoded, descriptor, bytes)) {
                    LocalFree(text); text = NULL;
                }
                if (decoded) LocalFree(decoded);
            }
        }
    }
    if (descriptor) LocalFree(descriptor);
    return text;
}

static int append_argument(char *blob, int *used, int capacity, const wchar_t *text) {
    int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1, blob + *used, capacity - *used, NULL, NULL);
    if (!count) return 0;
    *used += count;
    return 1;
}

int wmain(int argc, wchar_t **argv) {
    struct Runtime runtime = {0};
    HMODULE module = NULL;
    HANDLE token = NULL, impersonation = NULL, process = NULL, parent = INVALID_HANDLE_VALUE;
    HANDLE files[3] = {INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE};
    union { TOKEN_USER alignment; BYTE bytes[4096]; } token_buffer;
    DWORD size = 0, random[4], count;
    LPWSTR user = NULL, descriptions[3] = {0}, directory_description = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_ATTRIBUTES security = {sizeof(security), NULL, FALSE};
    struct Identity ids[3] = {0}, directory_id = {0};
    wchar_t restricted_sid[128], acl_text[1024], directory[256], context_path[256], file_paths[3][256];
    wchar_t ntpaths[3][2048], identity_text[4][128], executable[512];
    char root_utf8[256], source_utf8[2048], context_utf8[512], work[512], profile[64], blob[16000], error_text[4096] = {0};
    int context = 0, child[4] = {0}, used = 0, exit_code = 2, result = 2, directory_created = 0, output_ok = 1;
    if (argc != 4 || wcslen(argv[1]) < 3 || wcslen(argv[2]) < 3 || wcslen(argv[3]) >= 64 || wcslen(argv[3]) < 3 ||
        argv[1][1] != L':' || argv[2][1] != L':' || argv[3][1] != L':') return 2;
    for (const wchar_t *at = argv[3]; *at; at++) if (*at >= 128) return 2;
    if (!check(WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[3], -1, root_utf8, sizeof(root_utf8), NULL, NULL) &&
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[2], -1, source_utf8, sizeof(source_utf8), NULL, NULL), "convert bounded paths")) goto done;
    {
        WIN32_FIND_DATAW entry;
        wchar_t pattern[256];
        HANDLE find;
        int empty = 1;
        DWORD attributes = GetFileAttributesW(argv[3]);
        if (!check(attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) &&
            !(attributes & FILE_ATTRIBUTE_REPARSE_POINT), "fresh root is a real directory")) goto done;
        swprintf_s(pattern, 256, L"%ls\\*", argv[3]);
        find = FindFirstFileW(pattern, &entry);
        if (find != INVALID_HANDLE_VALUE) {
            do { if (wcscmp(entry.cFileName, L".") && wcscmp(entry.cFileName, L"..")) empty = 0; }
            while (FindNextFileW(find, &entry));
            FindClose(find);
        } else if (GetLastError() != ERROR_FILE_NOT_FOUND) empty = 0;
        if (!check(empty, "fixture root initially empty")) goto done;
    }
    if (!check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token) &&
        GetTokenInformation(token, TokenUser, token_buffer.bytes, sizeof(token_buffer), &size) &&
        ConvertSidToStringSidW(((TOKEN_USER *)token_buffer.bytes)->User.Sid, &user) &&
        DuplicateToken(token, SecurityImpersonation, &impersonation), "trusted token and owner")) goto done;
    if (!check(BCryptGenRandom(NULL, (PUCHAR)random, sizeof(random), BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0,
        "unique restricting SID")) goto done;
    swprintf_s(restricted_sid, 128, L"S-1-5-21-%lu-%lu-%lu-%lu", random[0], random[1], random[2], random[3] | 1024UL);
    swprintf_s(acl_text, 1024, L"D:P(A;;FA;;;%ls)(A;;FRFX;;;WD)(A;;FRFX;;;S-1-15-2-2)(A;;FRFX;;;%ls)", user, restricted_sid);
    if (!check(ConvertStringSecurityDescriptorToSecurityDescriptorW(acl_text, SDDL_REVISION_1, &descriptor, NULL), "new directory DACL")) goto done;
    security.lpSecurityDescriptor = descriptor;
    swprintf_s(directory, 256, L"%ls\\fixtures", argv[3]);
    if (!check(CreateDirectoryW(directory, &security), "create only own reachable fixture directory")) goto done;
    directory_created = 1;
    LocalFree(descriptor); descriptor = NULL;
    parent = CreateFileW(directory, READ_CONTROL | FILE_READ_ATTRIBUTES | FILE_TRAVERSE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (!check(parent != INVALID_HANDLE_VALUE && identity(parent, &directory_id) &&
        (directory_description = descriptor_text(parent)) != NULL, "pin directory identity and DACL")) goto done;
    swprintf_s(identity_text[3], 128, L"%lu:%lu:%lu", directory_id.volume, directory_id.high, directory_id.low);
    for (int i = 0; i < 3; i++) {
        SECURITY_DESCRIPTOR empty_or_null;
        ACL empty_acl;
        swprintf_s(file_paths[i], 256, L"%ls\\file%d.bin", directory, i);
        if (i == 0) {
            swprintf_s(acl_text, 1024, L"D:P(A;;FA;;;%ls)(A;;FR;;;WD)(A;;FR;;;S-1-15-2-2)", user);
            if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(acl_text, SDDL_REVISION_1, &descriptor, NULL)) goto done;
            security.lpSecurityDescriptor = descriptor;
        } else {
            if (!InitializeSecurityDescriptor(&empty_or_null, SECURITY_DESCRIPTOR_REVISION) ||
                !InitializeAcl(&empty_acl, sizeof(empty_acl), ACL_REVISION) ||
                !SetSecurityDescriptorDacl(&empty_or_null, TRUE, i == 1 ? NULL : &empty_acl, FALSE) ||
                !SetSecurityDescriptorControl(&empty_or_null, SE_DACL_PROTECTED, SE_DACL_PROTECTED)) goto done;
            security.lpSecurityDescriptor = &empty_or_null;
        }
        files[i] = CreateFileW(file_paths[i], GENERIC_READ | GENERIC_WRITE | DELETE | READ_CONTROL,
            FILE_SHARE_READ | FILE_SHARE_DELETE, &security, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
        if (descriptor) { LocalFree(descriptor); descriptor = NULL; }
        if (!check(files[i] != INVALID_HANDLE_VALUE && WriteFile(files[i], sentinel[i], 8, &count, NULL) && count == 8 &&
            FlushFileBuffers(files[i]) && identity(files[i], &ids[i]), "create and pin exact sentinel")) goto done;
        count = GetFinalPathNameByHandleW(files[i], ntpaths[i], 2048, FILE_NAME_NORMALIZED | VOLUME_NAME_NT);
        if (!check(count > 0 && count < 2048 && (descriptions[i] = descriptor_text(files[i])) != NULL,
            "resolve actual NT path and security descriptor")) goto done;
        swprintf_s(identity_text[i], 128, L"%lu:%lu:%lu", ids[i].volume, ids[i].high, ids[i].low);
        {
            PSECURITY_DESCRIPTOR control_sd = NULL;
            PACL actual_acl = NULL;
            BOOL present = FALSE, defaulted = FALSE;
            DWORD error = 0, granted = 0;
            int allowed, shape;
            control_sd = decode_descriptor(descriptions[i]);
            if (!control_sd) goto done;
            shape = GetSecurityDescriptorDacl(control_sd, &present, &actual_acl, &defaulted) && present &&
                (i == 1 ? actual_acl == NULL : actual_acl && actual_acl->AceCount == (i == 0 ? 3 : 0));
            allowed = access_result(control_sd, impersonation, &error, &granted);
            LocalFree(control_sd);
            if (!check(shape, "actual normal NULL and empty DACL shapes")) goto done;
            printf("HOST_ACCESS file=%d allowed=%d granted=%lu error=%lu\n", i, allowed, granted, error);
            if (!check(allowed == (i == 2 ? 0 : 1), "trusted AccessCheck control")) goto done;
        }
        for (int raw = 0; raw < 2; raw++) {
            struct Reading read = read_object(ntpaths[i], raw, 0, &ids[i], i);
            printf("HOST_READ file=%d raw=%d opened=%d exact=%d error=%lu status=%08lx\n",
                i, raw, read.opened, read.exact, read.error, (ULONG)read.status);
            if (!check(i == 2 ? !read.opened && read.error == ERROR_ACCESS_DENIED : read.exact,
                "trusted exact-object raw-path control")) goto done;
        }
    }
    module = LoadLibraryExW(argv[1], NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!check(module && load_runtime(module, &runtime), "load exact runtime ABI")) goto done;
    swprintf_s(context_path, 256, L"%ls\\context", argv[3]);
    if (!check(CreateDirectoryW(context_path, NULL) && WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
        context_path, -1, context_utf8, sizeof(context_utf8), NULL, NULL), "create fresh context")) goto done;
    context = runtime.create(context_utf8, error_text, sizeof(error_text));
    if (!check(context > 0 && runtime.stage(context, source_utf8, "guest.exe", error_text, sizeof(error_text)) == 0 &&
        runtime.seal(context, error_text, sizeof(error_text)) == 0, "stage and seal actual WindowsJail guest")) goto done;
    swprintf_s(executable, 512, L"%ls\\app\\guest.exe", context_path);
    if (!append_argument(blob, &used, sizeof(blob), executable) || !append_argument(blob, &used, sizeof(blob), restricted_sid)) goto done;
    for (int i = 0; i < 3; i++) if (!append_argument(blob, &used, sizeof(blob), ntpaths[i]) ||
        !append_argument(blob, &used, sizeof(blob), descriptions[i]) || !append_argument(blob, &used, sizeof(blob), identity_text[i])) goto done;
    if (!append_argument(blob, &used, sizeof(blob), identity_text[3])) goto done;
    sprintf_s(work, sizeof(work), "%s\\work", context_utf8);
    sprintf_s(profile, sizeof(profile), "windows:%d", context);
    if (!check(runtime.spawn(blob, used, "", 1, work, profile, VISJAIL_CONFINED | VISJAIL_MERGE_STDERR,
        0, 0, 0, 0, child, error_text, sizeof(error_text)) == 0, "spawn unchanged LPAC guest")) goto done;
    process = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD)runtime.pid(child[0]));
    if (!check(process != NULL, "retain guest process lifetime")) goto done;
    {
        ULONGLONG deadline = GetTickCount64() + 15000;
        char output[32769];
        int bytes = 0;
        while (GetTickCount64() < deadline) {
            char buffer[2048];
            int ready = runtime.poll(child[2], 100), length;
            if (ready < 0) break;
            if (ready) {
                length = runtime.read(child[2], buffer, sizeof(buffer));
                if (length <= 0) break;
                if (length > (int)sizeof(output) - 1 - bytes) { output_ok = 0; break; }
                memcpy(output + bytes, buffer, (size_t)length); bytes += length;
                if (fwrite(buffer, 1, (size_t)length, stdout) != (size_t)length) { output_ok = 0; break; }
                fflush(stdout);
            } else if (WaitForSingleObject(process, 0) == WAIT_OBJECT_0) break;
        }
        output[bytes] = 0;
        if (!check(output_ok && WaitForSingleObject(process, 2000) == WAIT_OBJECT_0 &&
            runtime.wait(child[0], 1, &exit_code) == 1 && exit_code >= 0 && exit_code <= 2,
            "bounded guest result and exit")) goto done;
        {
            int reads = 0, accesses = 0, ancestors = 0, tokens = 0, results = 0;
            char *at = output;
            const char *expected = exit_code == 1 ? "BOUNDARY_RESULT=FALSIFIED;" :
                exit_code == 0 ? "BOUNDARY_RESULT=NOT_FALSIFIED;" : "BOUNDARY_RESULT=INCONCLUSIVE;";
            while (*at) {
                char *next = strchr(at, '\n');
                if (!next) { output_ok = 0; break; }
                *next = 0;
                if (!strncmp(at, "READ ", 5)) reads++;
                else if (!strncmp(at, "ACCESS ", 7)) accesses++;
                else if (!strncmp(at, "ANCESTOR ", 9)) ancestors++;
                else if (!strncmp(at, "TOKEN ", 6)) tokens++;
                else if (!strncmp(at, expected, strlen(expected))) results++;
                else if (strncmp(at, "RESTRICTING_SID ", 16) && exit_code != 2) output_ok = 0;
                at = next + 1;
            }
            if (!check(output_ok && results == 1 && (exit_code == 2 ||
                (reads == 12 && accesses == 6 && ancestors == 4 && tokens == 4)),
                "complete measurement protocol agrees with guest exit")) goto done;
        }
        result = exit_code;
    }
 done:
    if (child[0]) for (int i = 1; i < 4; i++) if (child[i] > 0 && (i == 1 || child[i] != child[i - 1])) (void)runtime.close(child[i]);
    if (context > 0 && !check(runtime.destroy(context) == 0, "destroy context and reap descendants")) result = 2;
    if (process) { if (!check(WaitForSingleObject(process, 3000) == WAIT_OBJECT_0, "guest process absent")) result = 2; CloseHandle(process); }
    for (int i = 0; i < 3; i++) if (files[i] != INVALID_HANDLE_VALUE) {
        FILE_DISPOSITION_INFO disposition = {TRUE};
        LPWSTR after = descriptor_text(files[i]);
        struct Identity actual;
        LARGE_INTEGER zero = {0};
        char bytes[9] = {0};
        if (!check(descriptions[i] && after && !wcscmp(descriptions[i], after) && identity(files[i], &actual) &&
            same_identity(&ids[i], &actual) && SetFilePointerEx(files[i], zero, NULL, FILE_BEGIN) &&
            ReadFile(files[i], bytes, sizeof(bytes), &count, NULL) && count == 8 && !memcmp(bytes, sentinel[i], 8),
            "owned sentinel identity bytes and DACL unchanged")) result = 2;
        if (after) LocalFree(after);
        if (!check(SetFileInformationByHandle(files[i], FileDispositionInfo, &disposition, sizeof(disposition)), "remove only own sentinel")) result = 2;
        CloseHandle(files[i]);
        if (!check(GetFileAttributesW(file_paths[i]) == INVALID_FILE_ATTRIBUTES && GetLastError() == ERROR_FILE_NOT_FOUND,
            "owned sentinel absent")) result = 2;
    }
    if (parent != INVALID_HANDLE_VALUE) {
        LPWSTR after = descriptor_text(parent);
        if (!check(after && directory_description && !wcscmp(after, directory_description), "fixture directory DACL unchanged")) result = 2;
        if (after) LocalFree(after);
        CloseHandle(parent);
    }
    if (directory_created && !check(RemoveDirectoryW(directory), "remove only own fixture directory")) result = 2;
    if (descriptor) LocalFree(descriptor);
    if (user) LocalFree(user);
    if (directory_description) LocalFree(directory_description);
    for (int i = 0; i < 3; i++) if (descriptions[i]) LocalFree(descriptions[i]);
    if (impersonation) CloseHandle(impersonation);
    if (token) CloseHandle(token);
    if (module) FreeLibrary(module);
    printf("RESTRICTED_POLICY_PROBE_EXIT=%d; context workspace retained; no policy parity claim\n", result);
    return result;
}
