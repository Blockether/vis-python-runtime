/* Test-only capability prerequisite, not a production network-policy backend.
 * Include the unchanged runtime to reuse its exact private profile/tree/UI setup.
 * Put this executable beside the built visjail-ui.exe. Run elevated with:
 *   windows-network-capability-probe.exe <absolute-guest.exe> <fresh-empty-directory>
 * Only loopback fixtures run. Package-scoped dynamic WFP guards exist before any
 * child resumes; every child job is reaped before normal removal. Host-crash
 * handle-close ordering is NOT a proven fail-closed production design.
 */
#define VIS_CAP_HOST
#include "windows_network_capability_guest.c"
#include <initguid.h>
#include <fwpmu.h>
#include "visjail_windows.c"

static int cap_failures;
static int cap_check(int condition, const char *label) {
    printf("%s %s error=%lu\n", condition ? "PASS" : "FAIL", label, condition ? 0UL : GetLastError());
    fflush(stdout);
    if (!condition) ++cap_failures;
    return condition;
}

typedef struct {
    SOCKET sockets[4];
    unsigned short ports[2];
    HANDLE stop;
    volatile LONG accepted[4], received[4], replied[4], send_error[4];
} CapServer;

static DWORD WINAPI cap_serve(void *argument) {
    CapServer *server = argument;
    while (WaitForSingleObject(server->stop, 0) == WAIT_TIMEOUT) {
        fd_set readable;
        struct timeval timeout = {0, 100000};
        FD_ZERO(&readable);
        for (int i = 0; i < 4; ++i) FD_SET(server->sockets[i], &readable);
        if (select(0, &readable, NULL, NULL, &timeout) <= 0) continue;
        for (int i = 0; i < 4; ++i) if (FD_ISSET(server->sockets[i], &readable)) {
            char request = 0;
            if (i < 2) {
                SOCKET client = accept(server->sockets[i], NULL, NULL);
                DWORD io_timeout = 500;
                if (client == INVALID_SOCKET) continue;
                InterlockedIncrement(&server->accepted[i]);
                if (!setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (const char *)&io_timeout, sizeof(io_timeout)) &&
                    !setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, (const char *)&io_timeout, sizeof(io_timeout)) &&
                    recv(client, &request, 1, 0) == 1 && request == 'Q') {
                    InterlockedIncrement(&server->received[i]);
                    if (send(client, "A", 1, 0) == 1) InterlockedIncrement(&server->replied[i]);
                    else InterlockedExchange(&server->send_error[i], WSAGetLastError());
                }
                closesocket(client);
            } else {
                SOCKADDR_IN peer = {0};
                int size = sizeof(peer);
                if (recvfrom(server->sockets[i], &request, 1, 0, (SOCKADDR *)&peer, &size) == 1 && request == 'Q') {
                    InterlockedIncrement(&server->received[i]);
                    if (sendto(server->sockets[i], "A", 1, 0, (SOCKADDR *)&peer, size) == 1)
                        InterlockedIncrement(&server->replied[i]);
                    else InterlockedExchange(&server->send_error[i], WSAGetLastError());
                }
            }
        }
    }
    return 0;
}

static int cap_server_start(CapServer *server) {
    server->stop = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!server->stop) return 0;
    for (int i = 0; i < 4; ++i) {
        SOCKADDR_IN address = {0};
        int size = sizeof(address);
        BOOL exclusive = TRUE;
        server->sockets[i] = socket(AF_INET, i < 2 ? SOCK_STREAM : SOCK_DGRAM, i < 2 ? IPPROTO_TCP : IPPROTO_UDP);
        if (server->sockets[i] == INVALID_SOCKET) return 0;
        address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (i >= 2) address.sin_port = htons(server->ports[i - 2]);
        if (setsockopt(server->sockets[i], SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (const char *)&exclusive, sizeof(exclusive)) ||
            bind(server->sockets[i], (SOCKADDR *)&address, sizeof(address)) ||
            getsockname(server->sockets[i], (SOCKADDR *)&address, &size) ||
            (i < 2 && listen(server->sockets[i], 2))) return 0;
        if (i < 2) server->ports[i] = ntohs(address.sin_port);
    }
    return 1;
}

static int cap_controls(const CapServer *server) {
    const char *stage;
    for (int i = 0; i < 4; ++i) {
        int error = cap_exchange(server->ports[i % 2], i / 2, &stage);
        printf("HOST_CONTROL index=%d error=%d stage=%s\n", i, error, stage);
        if (error) return 0;
    }
    for (int i = 0; i < 2; ++i) if (cap_listener(i, &stage)) return 0;
    return 1;
}

static DWORD cap_filter(HANDLE engine, const GUID *sublayer, const GUID *layer,
                        PSID package, unsigned short port, UINT8 protocol) {
    FWPM_FILTER0 filter = {0};
    FWPM_FILTER_CONDITION0 conditions[4] = {0};
    filter.displayData.name = port ? L"Vis capability prerequisite exact loopback permit" : L"Vis capability prerequisite package block";
    filter.layerKey = *layer; filter.subLayerKey = *sublayer;
    filter.weight.type = FWP_UINT8; filter.weight.uint8 = port ? 15 : 1;
    filter.action.type = port ? FWP_ACTION_PERMIT : FWP_ACTION_BLOCK;
    if (port) filter.flags = FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT;
    conditions[0].fieldKey = FWPM_CONDITION_ALE_PACKAGE_ID;
    conditions[0].matchType = FWP_MATCH_EQUAL;
    conditions[0].conditionValue.type = FWP_SID; conditions[0].conditionValue.sid = package;
    filter.numFilterConditions = 1; filter.filterCondition = conditions;
    if (port) {
        conditions[1].fieldKey = FWPM_CONDITION_IP_REMOTE_ADDRESS;
        conditions[1].matchType = FWP_MATCH_EQUAL;
        conditions[1].conditionValue.type = FWP_UINT32; conditions[1].conditionValue.uint32 = INADDR_LOOPBACK;
        conditions[2].fieldKey = FWPM_CONDITION_IP_REMOTE_PORT;
        conditions[2].matchType = FWP_MATCH_EQUAL;
        conditions[2].conditionValue.type = FWP_UINT16; conditions[2].conditionValue.uint16 = port;
        conditions[3].fieldKey = FWPM_CONDITION_IP_PROTOCOL;
        conditions[3].matchType = FWP_MATCH_EQUAL;
        conditions[3].conditionValue.type = FWP_UINT8; conditions[3].conditionValue.uint8 = protocol;
        filter.numFilterConditions = 4;
    }
    return FwpmFilterAdd0(engine, &filter, NULL, NULL);
}

/* Both ALE layers express the peer as REMOTE: the client receive permit must
 * match the server port, not its own ephemeral port. These filters do not grant
 * an OS loopback exemption. A timeout alone cannot locate a drop or prove denial. */
static int cap_guards(HANDLE *engine, PSID package, unsigned short port) {
    FWPM_SESSION0 policy_session = {0};
    FWPM_SUBLAYER0 sublayer = {0};
    DWORD error;
    policy_session.flags = FWPM_SESSION_FLAG_DYNAMIC;
    error = FwpmEngineOpen0(NULL, RPC_C_AUTHN_WINNT, NULL, &policy_session, engine);
    if (error) { SetLastError(error); return 0; }
    error = UuidCreate(&sublayer.subLayerKey);
    if (error != RPC_S_OK && error != RPC_S_UUID_LOCAL_ONLY) { SetLastError(error); return 0; }
    sublayer.displayData.name = L"Vis bounded network capability prerequisite";
    sublayer.weight = 0xffff;
    error = FwpmTransactionBegin0(*engine, 0);
    if (error) { SetLastError(error); return 0; }
    error = FwpmSubLayerAdd0(*engine, &sublayer, NULL);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_CONNECT_V4, package, 0, 0);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_CONNECT_V6, package, 0, 0);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, package, 0, 0);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6, package, 0, 0);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_CONNECT_V4, package, port, IPPROTO_TCP);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_CONNECT_V4, package, port, IPPROTO_UDP);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, package, port, IPPROTO_TCP);
    if (!error) error = cap_filter(*engine, &sublayer.subLayerKey, &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, package, port, IPPROTO_UDP);
    if (error) (void)FwpmTransactionAbort0(*engine);
    else error = FwpmTransactionCommit0(*engine);
    SetLastError(error); return error == ERROR_SUCCESS;
}

static int cap_token(HANDLE process, PSID package, const SID_AND_ATTRIBUTES *expected, DWORD count) {
    HANDLE token = NULL, impersonation = NULL;
    union { TOKEN_GROUPS alignment; BYTE bytes[4096]; } information;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    union { PRIVILEGE_SET alignment; BYTE bytes[4096]; } privileges;
    GENERIC_MAPPING mapping = {0};
    DWORD size = 0, value = 0, granted = 0, privilege_size = sizeof(privileges);
    BOOL allowed = FALSE;
    int ok = 0;
    if (!OpenProcessToken(process, TOKEN_QUERY | TOKEN_DUPLICATE, &token) ||
        !GetTokenInformation(token, TokenIsAppContainer, &value, sizeof(value), &size) || value != 1 ||
        !GetTokenInformation(token, TokenIntegrityLevel, information.bytes, sizeof(information.bytes), &size)) goto done;
    {
        PSID integrity = ((TOKEN_MANDATORY_LABEL *)information.bytes)->Label.Sid;
        if (!IsValidSid(integrity) || !*GetSidSubAuthorityCount(integrity) ||
            *GetSidSubAuthority(integrity, (DWORD)*GetSidSubAuthorityCount(integrity) - 1) != SECURITY_MANDATORY_LOW_RID) goto done;
    }
    if (!GetTokenInformation(token, TokenAppContainerSid, information.bytes, sizeof(information.bytes), &size) ||
        !EqualSid(package, ((TOKEN_APPCONTAINER_INFORMATION *)information.bytes)->TokenAppContainer) ||
        !GetTokenInformation(token, TokenCapabilities, information.bytes, sizeof(information.bytes), &size)) goto done;
    {
        TOKEN_GROUPS *actual = (TOKEN_GROUPS *)information.bytes;
        if (actual->GroupCount != count) goto done;
        for (DWORD i = 0; i < count; ++i) {
            DWORD matched = 0;
            for (DWORD j = 0; j < count; ++j)
                if (actual->Groups[j].Attributes == SE_GROUP_ENABLED && EqualSid(expected[i].Sid, actual->Groups[j].Sid)) ++matched;
            if (matched != 1) goto done;
        }
    }
    if (!DuplicateToken(token, SecurityImpersonation, &impersonation) ||
        !ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"O:SYG:SYD:(A;;0x3;;;WD)(A;;0x1;;;S-1-15-2-1)(A;;0x2;;;S-1-15-2-2)",
            SDDL_REVISION_1, &descriptor, NULL) ||
        !AccessCheck(descriptor, impersonation, MAXIMUM_ALLOWED, &mapping, (PPRIVILEGE_SET)privileges.bytes,
            &privilege_size, &granted, &allowed) || !allowed || granted != 2) goto done;
    printf("CAP_TOKEN appcontainer=1 low=1 package=exact registryRead=1 capability_count=%lu effective_lpac=2\n", count);
    ok = 1;
 done:
    if (descriptor) LocalFree(descriptor);
    if (impersonation) CloseHandle(impersonation);
    if (token) CloseHandle(token);
    return ok;
}

static int cap_protocol(char *output, unsigned mode, int results[6]) {
    char *cursor = output;
    unsigned parsed_mode = 0;
    int consumed = 0;
    if (sscanf_s(cursor, "CAP_BEGIN %u%n", &parsed_mode, &consumed) != 1 || parsed_mode != mode) return 0;
    cursor += consumed;
    if (*cursor == '\r') ++cursor;
    if (*cursor++ != '\n') return 0;
    for (int i = 0; i < 6; ++i) {
        int index = -1, error = -1;
        char stage[32];
        consumed = 0;
        if (sscanf_s(cursor, "CAP_RESULT %d %d %31s%n", &index, &error, stage, (unsigned)sizeof(stage), &consumed) != 3 ||
            index != i || error < 0 || (!error && strcmp(stage, "complete"))) return 0;
        if (error && strcmp(stage, "socket") && strcmp(stage, "nonblocking") && strcmp(stage, "connect") &&
            strcmp(stage, "connect-select") && strcmp(stage, "connect-status") && strcmp(stage, "io-setup") &&
            strcmp(stage, "send") && strcmp(stage, "recv") && strcmp(stage, "reply-bytes") &&
            strcmp(stage, "bind") && strcmp(stage, "listen")) return 0;
        results[i] = error; cursor += consumed;
        if (*cursor == '\r') ++cursor;
        if (*cursor++ != '\n') return 0;
    }
    consumed = 0;
    if (sscanf_s(cursor, "CAP_END %u%n", &parsed_mode, &consumed) != 1 || parsed_mode != mode) return 0;
    cursor += consumed;
    if (*cursor == '\r') ++cursor;
    return *cursor++ == '\n' && !*cursor;
}

static int cap_launch(Context *context, const wchar_t *program, const CapServer *server, unsigned mode, int results[6]) {
    SID_AND_ATTRIBUTES capabilities[4] = {0};
    BYTE network_sids[3][SECURITY_MAX_SID_SIZE];
    const WELL_KNOWN_SID_TYPE types[3] = {WinCapabilityInternetClientSid, WinCapabilityInternetClientServerSid,
        WinCapabilityPrivateNetworkClientServerSid};
    SECURITY_CAPABILITIES security = {0};
    STARTUPINFOEXW startup = {0};
    PROCESS_INFORMATION process = {0};
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, TRUE};
    HANDLE read_pipe = NULL, write_pipe = NULL, input = INVALID_HANDLE_VALUE, inherit[4] = {0};
    HANDLE job = NULL;
    SIZE_T attribute_size = 0;
    DWORD policy = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT, count = 1, code = 0;
    UINT windows_length;
    wchar_t command[4096], windows[MAX_PATH + 1], *environment = NULL, *tmp = NULL, *work = NULL;
    wchar_t *environment_items[4] = {0};
    const wchar_t *stage = L"derive capability SIDs";
    char output[8192] = {0};
    size_t used = 0;
    int ok = 0, initialized = 0;
    ULONGLONG deadline;
    capabilities[0].Sid = context->registry_read; capabilities[0].Attributes = SE_GROUP_ENABLED;
    for (unsigned i = 0; i < 3; ++i) if (mode == 5 || mode == i + 2) {
        DWORD size = SECURITY_MAX_SID_SIZE;
        if (!CreateWellKnownSid(types[i], NULL, network_sids[i], &size)) goto done;
        capabilities[count].Sid = network_sids[i]; capabilities[count++].Attributes = SE_GROUP_ENABLED;
    }
    security.AppContainerSid = context->sid; security.Capabilities = capabilities; security.CapabilityCount = count;
    stage = L"create output pipe";
    if (!CreatePipe(&read_pipe, &write_pipe, &attributes, 0) || !SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0)) goto done;
    stage = L"open input and duplicate private UI handles";
    input = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &attributes, OPEN_EXISTING, 0, NULL);
    if (input == INVALID_HANDLE_VALUE ||
        !DuplicateHandle(GetCurrentProcess(), context->station, GetCurrentProcess(), &inherit[0], 0, TRUE, DUPLICATE_SAME_ACCESS) ||
        !DuplicateHandle(GetCurrentProcess(), context->desktop, GetCurrentProcess(), &inherit[1], 0, TRUE, DUPLICATE_SAME_ACCESS)) goto done;
    inherit[2] = input; inherit[3] = write_pipe;
    startup.StartupInfo.cb = sizeof(startup); startup.StartupInfo.lpDesktop = L"";
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = input; startup.StartupInfo.hStdOutput = write_pipe; startup.StartupInfo.hStdError = write_pipe;
    stage = L"initialize attribute list";
    InitializeProcThreadAttributeList(NULL, 3, 0, &attribute_size);
    startup.lpAttributeList = malloc(attribute_size);
    if (!startup.lpAttributeList || !InitializeProcThreadAttributeList(startup.lpAttributeList, 3, 0, &attribute_size)) goto done;
    initialized = 1;
    stage = L"set capability attribute";
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &security, sizeof(security), NULL, NULL)) goto done;
    stage = L"set LPAC opt-out attribute";
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY, &policy, sizeof(policy), NULL, NULL)) goto done;
    stage = L"set handle-list attribute";
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit, sizeof(inherit), NULL, NULL)) goto done;
    stage = L"build command and private environment";
    if (_snwprintf_s(command, 4096, _TRUNCATE, L"\"%ls\" %u %u %u", program, mode,
            (unsigned)server->ports[0], (unsigned)server->ports[1]) < 0) goto done;
    windows_length = GetSystemWindowsDirectoryW(windows, MAX_PATH + 1);
    if (!windows_length || windows_length > MAX_PATH) goto done;
    tmp = join(context->path, L"tmp"); work = join(context->path, L"work");
    if (!tmp || !work) goto done;
    {
        const wchar_t *keys[4] = {L"TEMP=", L"TMP=", L"SystemRoot=", L"LOCALAPPDATA="};
        const wchar_t *values[4] = {tmp, tmp, windows, tmp};
        for (int i = 0; i < 4; ++i) {
            size_t a = wcslen(keys[i]), b = wcslen(values[i]);
            environment_items[i] = calloc(a + b + 1, sizeof(wchar_t));
            if (!environment_items[i]) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); goto done; }
            memcpy(environment_items[i], keys[i], a * sizeof(wchar_t));
            memcpy(environment_items[i] + a, values[i], (b + 1) * sizeof(wchar_t));
        }
    }
    environment = environment_block(environment_items, 4);
    if (!environment) goto done;
    stage = L"create child job";
    job = new_job();
    if (!job) goto done;
    stage = L"CreateProcessW suspended";
    if (!CreateProcessW(program, command, NULL, NULL, TRUE,
            CREATE_SUSPENDED | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
            environment, work, &startup.StartupInfo, &process)) goto done;
    stage = L"assign context job";
    if (!AssignProcessToJobObject(context->job, process.hProcess)) goto done;
    stage = L"assign child job";
    if (!AssignProcessToJobObject(job, process.hProcess)) goto done;
    stage = L"verify suspended child token";
    if (!cap_token(process.hProcess, context->sid, capabilities, count)) goto done;
    CloseHandle(write_pipe); write_pipe = NULL;
    stage = L"resume child";
    if (ResumeThread(process.hThread) == (DWORD)-1) goto done;
    stage = L"read bounded child protocol";
    deadline = GetTickCount64() + 16000;
    while (GetTickCount64() < deadline) {
        DWORD available = 0, got = 0;
        if (!PeekNamedPipe(read_pipe, NULL, 0, NULL, &available, NULL)) {
            if (GetLastError() == ERROR_BROKEN_PIPE && WaitForSingleObject(process.hProcess, 0) == WAIT_OBJECT_0) break;
            goto done;
        }
        if (available) {
            DWORD capacity = (DWORD)(sizeof(output) - 1 - used);
            if (!capacity || !ReadFile(read_pipe, output + used, available < capacity ? available : capacity, &got, NULL) || !got) goto done;
            used += got; output[used] = 0;
        } else if (WaitForSingleObject(process.hProcess, 20) == WAIT_OBJECT_0) {
            if (!PeekNamedPipe(read_pipe, NULL, 0, NULL, &available, NULL)) {
                if (GetLastError() == ERROR_BROKEN_PIPE) break;
                goto done;
            }
            if (!available) break;
        }
    }
    stage = L"child exit status";
    if (WaitForSingleObject(process.hProcess, 0) != WAIT_OBJECT_0 || !GetExitCodeProcess(process.hProcess, &code) || code) goto done;
    printf("CAP_MODE %u\n%s", mode, output); fflush(stdout);
    stage = L"validate complete child protocol";
    ok = cap_protocol(output, mode, results);
 done:
    if (!ok) {
        printf("CAP_LAUNCH_FAILURE mode=%u stage=%ls error=%lu exit=%lu output=%s\n", mode, stage, GetLastError(), code, output);
        fflush(stdout);
    }
    if (process.hProcess) {
        if (job) (void)TerminateJobObject(job, 1);
        if (WaitForSingleObject(process.hProcess, 0) != WAIT_OBJECT_0) (void)TerminateProcess(process.hProcess, 1);
        if (!cap_check(WaitForSingleObject(process.hProcess, 5000) == WAIT_OBJECT_0, "capability child reaped")) ok = 0;
    }
    if (job) {
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting = {0};
        int queried = 0;
        ULONGLONG finish = GetTickCount64() + 5000;
        do {
            queried = QueryInformationJobObject(job, JobObjectBasicAccountingInformation, &accounting, sizeof(accounting), NULL) != FALSE;
            if (!queried) { ok = 0; break; }
            if (!accounting.ActiveProcesses) break;
            Sleep(10);
        } while (GetTickCount64() < finish);
        if (!cap_check(queried && accounting.ActiveProcesses == 0, "capability descendants reaped")) ok = 0;
        CloseHandle(job);
    }
    if (process.hThread) CloseHandle(process.hThread);
    if (process.hProcess) CloseHandle(process.hProcess);
    if (initialized) DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    free(environment); free(tmp); free(work);
    for (int i = 0; i < 4; ++i) free(environment_items[i]);
    if (inherit[1]) CloseDesktop((HDESK)inherit[1]);
    if (inherit[0]) CloseWindowStation((HWINSTA)inherit[0]);
    if (input != INVALID_HANDLE_VALUE) CloseHandle(input);
    if (write_pipe) CloseHandle(write_pipe);
    if (read_pipe) CloseHandle(read_pipe);
    return ok;
}

int wmain(int argc, wchar_t **argv) {
    CapServer server = {{INVALID_SOCKET, INVALID_SOCKET, INVALID_SOCKET, INVALID_SOCKET}, {0}, NULL, {0}, {0}, {0}, {0}};
    WSADATA data;
    HANDLE server_thread = NULL, engine = NULL;
    Context *context = NULL;
    char directory[4096], source[4096], error[1024] = {0};
    wchar_t *program = NULL;
    int context_id = 0, winsock_ready = 0, complete = 0, useful = 0;
    if (argc != 3) { fputs("usage: probe <absolute-guest.exe> <fresh-empty-directory>\n", stderr); return 2; }
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[1], -1, source, sizeof(source), NULL, NULL) ||
        !WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[2], -1, directory, sizeof(directory), NULL, NULL)) return 2;
    if (!cap_check(WSAStartup(MAKEWORD(2, 2), &data) == 0, "Winsock startup")) goto done;
    winsock_ready = 1;
    if (!cap_check(cap_server_start(&server), "four loopback control endpoints")) goto done;
    server_thread = CreateThread(NULL, 0, cap_serve, &server, 0, NULL);
    if (!cap_check(server_thread != NULL, "loopback responder started") || !cap_check(cap_controls(&server), "trusted controls before")) goto done;
    context_id = visjail_windows_create(directory, error, sizeof(error));
    if (!cap_check(context_id > 0, "unchanged runtime private context")) { printf("CONTEXT_ERROR %s\n", error); goto done; }
    context = context_get(context_id);
    if (!cap_check(visjail_windows_stage(context_id, source, "cap-guest.exe", error, sizeof(error)) == 0 &&
        visjail_windows_seal(context_id, error, sizeof(error)) == 0, "unchanged runtime guest stage and seal")) goto done;
    program = join(context->path, L"app\\cap-guest.exe");
    if (!program || !cap_check(cap_guards(&engine, context->sid, server.ports[0]), "package guards committed before capable children")) goto done;
    for (unsigned mode = 1; mode <= 5; ++mode) {
        LONG accepted[4], received[4], replied[4];
        int results[6] = {-1, -1, -1, -1, -1, -1};
        for (int i = 0; i < 4; ++i) {
            accepted[i] = InterlockedCompareExchange(&server.accepted[i], 0, 0);
            received[i] = InterlockedCompareExchange(&server.received[i], 0, 0);
            replied[i] = InterlockedCompareExchange(&server.replied[i], 0, 0);
            InterlockedExchange(&server.send_error[i], 0);
        }
        /* Setup/token/protocol/reap failure remains fatal. Measurement failures
         * are retained in cap_failures but must not hide later capability modes. */
        if (!cap_check(cap_launch(context, program, &server, mode, results), "complete token and stage protocol")) goto done;
        for (int i = 0; i < 4; ++i) {
            printf("CAP_SERVER mode=%u index=%d accepted=%ld received=%ld replied=%ld send_error=%ld\n",
                mode, i, InterlockedCompareExchange(&server.accepted[i], 0, 0) - accepted[i],
                InterlockedCompareExchange(&server.received[i], 0, 0) - received[i],
                InterlockedCompareExchange(&server.replied[i], 0, 0) - replied[i],
                InterlockedCompareExchange(&server.send_error[i], 0, 0));
        }
        fflush(stdout);
        (void)cap_check(results[1] == WSAEACCES && results[3] == WSAEACCES,
            "ungranted loopback endpoints return exact WSAEACCES");
        if (mode > 1 && !results[0] && !results[2]) useful = 1;
    }
    complete = 1;
 done:
    free(program);
    if (context_id) cap_check(visjail_windows_destroy(context_id) == 0, "runtime context and all descendants destroyed before guards");
    if (engine) cap_check(FwpmEngineClose0(engine) == ERROR_SUCCESS, "owned WFP dynamic guards removed");
    if (server_thread) {
        cap_check(cap_controls(&server), "trusted controls after");
        SetEvent(server.stop);
        cap_check(WaitForSingleObject(server_thread, 5000) == WAIT_OBJECT_0, "loopback responder stopped");
        CloseHandle(server_thread);
    }
    for (int i = 0; i < 4; ++i) if (server.sockets[i] != INVALID_SOCKET) closesocket(server.sockets[i]);
    if (server.stop) CloseHandle(server.stop);
    if (winsock_ready) WSACleanup();
    printf("CAP_MEASUREMENT complete=%d usable_tcp_udp=%d failures=%d\n", complete, useful, cap_failures);
    return complete && useful && !cap_failures ? 0 : 1;
}
