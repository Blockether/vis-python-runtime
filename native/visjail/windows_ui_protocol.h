/* Private fixed-size protocol for the Windows UI helper and its launcher. */
#ifndef VISJAIL_WINDOWS_UI_PROTOCOL_H
#define VISJAIL_WINDOWS_UI_PROTOCOL_H
#include <windows.h>
#include <stdint.h>

#define VISJAIL_UI_VERSION 1u
#define VISJAIL_UI_TIMEOUT 10000u
#define VISJAIL_UI_PROFILE_CHARS 41u

typedef struct {
    DWORD version, size, error;
    wchar_t profile[VISJAIL_UI_PROFILE_CHARS];
    wchar_t station_name[256];
    uintptr_t station, desktop;
} VisjailUiTransfer;

#endif
