/* Trusted, short-lived desktop creator. It accepts only inherited IPC handles and a profile. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <errno.h>
#include "windows_ui.h"

static HANDLE inherited_handle(const wchar_t *text) {
    wchar_t *end;
    unsigned long long value;
    DWORD flags;
    if (!text || *text < L'0' || *text > L'9') return NULL;
    errno = 0;
    value = wcstoull(text, &end, 10);
    if (errno || *end || !value || value > UINTPTR_MAX ||
        !GetHandleInformation((HANDLE)(uintptr_t)value, &flags) || !(flags & HANDLE_FLAG_INHERIT)) return NULL;
    return (HANDLE)(uintptr_t)value;
}

int wmain(int argc, wchar_t **argv) {
    HANDLE mapping = NULL, ready = NULL, ack = NULL;
    VisjailUiTransfer *shared = NULL, request;
    VisjailUiSecurity security = {0};
    HWINSTA station = NULL;
    HDESK desktop = NULL;
    PSID sid = NULL;
    wchar_t station_name[256];
    SECURITY_ATTRIBUTES attributes = {sizeof(attributes), NULL, FALSE};
    DWORD error = ERROR_INVALID_PARAMETER;
    int result = 1;
    if (argc != 4 || !(mapping = inherited_handle(argv[1])) || !(ready = inherited_handle(argv[2])) ||
        !(ack = inherited_handle(argv[3])) || mapping == ready || mapping == ack || ready == ack) goto done;
    if (!SetHandleInformation(mapping, HANDLE_FLAG_INHERIT, 0) ||
        !SetHandleInformation(ready, HANDLE_FLAG_INHERIT, 0) ||
        !SetHandleInformation(ack, HANDLE_FLAG_INHERIT, 0)) goto done;
    shared = MapViewOfFile(mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, sizeof(*shared));
    if (!shared) goto done;
    memcpy(&request, shared, sizeof(request));
    if (request.version != VISJAIL_UI_VERSION || request.size != sizeof(request) ||
        request.error != ERROR_IO_PENDING || request.station || request.desktop || !ui_profile_valid(request.profile)) goto reply;
    if (FAILED(DeriveAppContainerSidFromAppContainerName(request.profile, &sid)) || !sid) {
        error = ERROR_INVALID_SID; goto reply;
    }
    if (!ui_object(GetProcessWindowStation(), L"WindowStation", station_name, sizeof(station_name)) ||
        !(station = OpenWindowStationW(station_name, FALSE, WINSTA_READATTRIBUTES)) ||
        !ui_security_init(&security, sid)) { error = GetLastError(); goto reply; }
    attributes.lpSecurityDescriptor = &security.descriptor;
    desktop = CreateDesktopW(request.profile, NULL, NULL, 0, VISJAIL_UI_DESKTOP_RIGHTS, &attributes);
    if (!desktop || !ui_desktop_valid(desktop, request.profile, &security)) { error = GetLastError(); goto reply; }
    memcpy(shared->station_name, station_name, (wcslen(station_name) + 1) * sizeof(wchar_t));
    shared->station = (uintptr_t)station;
    shared->desktop = (uintptr_t)desktop;
    error = ERROR_SUCCESS;
 reply:
    shared->error = error;
    /* The host pulls handles while these originals remain alive, then acknowledges. */
    if (SetEvent(ready) && WaitForSingleObject(ack, VISJAIL_UI_TIMEOUT) == WAIT_OBJECT_0 && !error) result = 0;
 done:
    if (desktop && !CloseDesktop(desktop)) result = 1;
    if (station && !CloseWindowStation(station)) result = 1;
    ui_security_free(&security);
    if (sid) FreeSid(sid);
    if (shared) UnmapViewOfFile(shared);
    if (ack && ack != mapping && ack != ready) CloseHandle(ack);
    if (ready && ready != mapping) CloseHandle(ready);
    if (mapping) CloseHandle(mapping);
    return result;
}
