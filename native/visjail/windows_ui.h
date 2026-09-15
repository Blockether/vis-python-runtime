/* Private descriptor construction shared only by the UI helper and launcher. */
#ifndef VISJAIL_WINDOWS_UI_H
#define VISJAIL_WINDOWS_UI_H
#include "windows_ui_protocol.h"
#include <userenv.h>
#include <aclapi.h>
#include <sddl.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define VISJAIL_UI_DESKTOP_RIGHTS (READ_CONTROL | DESKTOP_READOBJECTS | DESKTOP_CREATEWINDOW | DESKTOP_WRITEOBJECTS)
#define VISJAIL_UI_HOST_RIGHTS (STANDARD_RIGHTS_REQUIRED | DESKTOP_READOBJECTS | DESKTOP_CREATEWINDOW | \
    DESKTOP_CREATEMENU | DESKTOP_HOOKCONTROL | DESKTOP_JOURNALRECORD | DESKTOP_JOURNALPLAYBACK | \
    DESKTOP_ENUMERATE | DESKTOP_WRITEOBJECTS | DESKTOP_SWITCHDESKTOP)

typedef struct {
    SECURITY_DESCRIPTOR descriptor;
    PACL acl, label;
    PSECURITY_DESCRIPTOR low;
} VisjailUiSecurity;

/* Explicit ACLs never import ambient grants. Owner Rights removes implicit WRITE_DAC. */
static PACL private_acl(PSID package, DWORD host_rights, DWORD rights, int directory) {
    HANDLE token = NULL;
    TOKEN_USER *user = NULL;
    DWORD size = 0, result = ERROR_NOT_ENOUGH_MEMORY;
    PSID owner_rights = NULL;
    EXPLICIT_ACCESSW entries[3];
    PACL acl = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    user = malloc(size);
    if (!user) goto done;
    if (!GetTokenInformation(token, TokenUser, user, size, &size) ||
        !ConvertStringSidToSidW(L"S-1-3-4", &owner_rights)) { result = GetLastError(); goto done; }
    ZeroMemory(entries, sizeof(entries));
    entries[0].grfAccessPermissions = host_rights;
    entries[0].grfAccessMode = SET_ACCESS;
    entries[0].grfInheritance = directory ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : NO_INHERITANCE;
    entries[0].Trustee.TrusteeForm = TRUSTEE_IS_SID;
    entries[0].Trustee.ptstrName = (LPWSTR)user->User.Sid;
    entries[1] = entries[0];
    entries[1].grfAccessPermissions = rights;
    entries[1].Trustee.ptstrName = (LPWSTR)package;
    entries[2] = entries[0];
    entries[2].grfAccessPermissions = READ_CONTROL;
    entries[2].Trustee.ptstrName = (LPWSTR)owner_rights;
    result = SetEntriesInAclW(3, entries, NULL, &acl);
 done:
    if (owner_rights) LocalFree(owner_rights);
    free(user); CloseHandle(token); SetLastError(result);
    if (result != ERROR_SUCCESS) { if (acl) LocalFree(acl); return NULL; }
    return acl;
}

static int ui_profile_valid(const wchar_t profile[VISJAIL_UI_PROFILE_CHARS]) {
    if (wmemcmp(profile, L"visjail.", 8) || profile[40]) return 0;
    for (size_t i = 8; i < 40; ++i)
        if (!((profile[i] >= L'0' && profile[i] <= L'9') || (profile[i] >= L'a' && profile[i] <= L'f'))) return 0;
    return 1;
}

static void ui_security_free(VisjailUiSecurity *security) {
    if (security->low) LocalFree(security->low);
    if (security->acl) LocalFree(security->acl);
    ZeroMemory(security, sizeof(*security));
}

static int ui_security_init(VisjailUiSecurity *security, PSID sid) {
    BOOL present = FALSE, defaulted = FALSE;
    ZeroMemory(security, sizeof(*security));
    security->acl = private_acl(sid, VISJAIL_UI_HOST_RIGHTS, VISJAIL_UI_DESKTOP_RIGHTS, 0);
    return security->acl && ConvertStringSecurityDescriptorToSecurityDescriptorW(
        L"S:(ML;;NW;;;LW)", SDDL_REVISION_1, &security->low, NULL) &&
        GetSecurityDescriptorSacl(security->low, &present, &security->label, &defaulted) && present && security->label &&
        InitializeSecurityDescriptor(&security->descriptor, SECURITY_DESCRIPTOR_REVISION) &&
        SetSecurityDescriptorDacl(&security->descriptor, TRUE, security->acl, FALSE) &&
        SetSecurityDescriptorSacl(&security->descriptor, TRUE, security->label, FALSE) &&
        SetSecurityDescriptorControl(&security->descriptor, SE_DACL_PROTECTED, SE_DACL_PROTECTED);
}

static int ui_object(HANDLE handle, const wchar_t *type, wchar_t *name, DWORD bytes) {
    wchar_t actual_type[32];
    DWORD needed = 0;
    if (!GetUserObjectInformationW(handle, UOI_TYPE, actual_type, sizeof(actual_type), &needed) ||
        needed < sizeof(wchar_t) || needed > sizeof(actual_type) || needed % sizeof(wchar_t) ||
        actual_type[needed / sizeof(wchar_t) - 1] || wcscmp(actual_type, type)) {
        SetLastError(ERROR_INVALID_HANDLE); return 0;
    }
    if (!GetUserObjectInformationW(handle, UOI_NAME, name, bytes, &needed)) return 0;
    if (needed < sizeof(wchar_t) || needed > bytes || needed % sizeof(wchar_t) || name[needed / sizeof(wchar_t) - 1]) {
        SetLastError(ERROR_INVALID_DATA); return 0;
    }
    return 1;
}

static int ui_desktop_valid(HDESK desktop, const wchar_t *profile, const VisjailUiSecurity *security) {
    wchar_t name[VISJAIL_UI_PROFILE_CHARS];
    PACL acl = NULL, label = NULL;
    PSECURITY_DESCRIPTOR actual = NULL;
    SECURITY_DESCRIPTOR_CONTROL control;
    DWORD revision, status;
    int ok = 0;
    if (!ui_object(desktop, L"Desktop", name, sizeof(name)) || wcscmp(name, profile)) {
        SetLastError(ERROR_INVALID_HANDLE); return 0;
    }
    status = GetSecurityInfo(desktop, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION | LABEL_SECURITY_INFORMATION,
        NULL, NULL, &acl, &label, &actual);
    if (status == ERROR_SUCCESS) {
        ok = GetSecurityDescriptorControl(actual, &control, &revision) && (control & SE_DACL_PROTECTED) &&
            acl && label && acl->AclSize == security->acl->AclSize && label->AclSize == security->label->AclSize &&
            !memcmp(acl, security->acl, acl->AclSize) && !memcmp(label, security->label, label->AclSize);
        if (!ok) status = ERROR_ACCESS_DENIED;
    }
    if (actual) LocalFree(actual);
    SetLastError(status); return ok;
}
#endif
