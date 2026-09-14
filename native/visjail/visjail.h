#ifndef VISJAIL_H
#define VISJAIL_H

#include <stddef.h>

#define VISJAIL_PTY 1
#define VISJAIL_MERGE_STDERR 2
#define VISJAIL_CONFINED 4
#define VISJAIL_RESULT_COUNT 4

#if defined(__linux__)
int vis_bwrap_main(int argc, char **argv);
#endif

/*
 * Spawn one detached child. VISJAIL_CONFINED enters bubblewrap on Linux or
 * the supplied Seatbelt profile on macOS; without it the child execs directly.
 * argv_blob and env_blob are NUL-separated UTF-8 strings. A confined Linux
 * argv is bubblewrap's complete argv (argv[0], policy flags, --, command).
 * proxy_port and inbound_port create a private Linux network namespace whose only
 * host crossings are those two loopback endpoints. The policy compiler omits its
 * own network-namespace flag whenever this bridge owns the namespace. Result
 * receives pid, stdin-write, stdout-read and stderr-read on Unix.
 * Windows requires CONFINED, profile "windows:<context-id>", no network ports,
 * and an absolute staged or OS System32 executable. Result[0] is opaque (use
 * visjail_windows_pid for the descriptive OS PID); descriptors are opaque IDs,
 * never HANDLE casts. A PTY shares one duplex descriptor in all stream slots.
 * Environment is complete. Windows main-process reaping terminates descendants.
 * No Windows request trusts VIS_SEATBELT_ACTIVE or accepts a Unix path policy.
 */
int visjail_spawn(const char *argv_blob, int argv_len,
                              const char *env_blob, int env_len,
                              const char *cwd, const char *profile,
                               int flags, int rows, int cols,
                               int proxy_port, int inbound_port,
                              int result[VISJAIL_RESULT_COUNT],
                              char *error, int error_cap);
int visjail_read(int fd, void *buffer, int length);
int visjail_write(int fd, const void *buffer, int length);
int visjail_close(int fd);
int visjail_poll(int fd, int timeout_ms);
int visjail_wait(int pid, int nohang, int *exit_code);
int visjail_kill(int pid, int signal_number);

#if defined(_WIN32)
/* Existing empty local directory, retained on close. No source object gets ACEs.
 * stage copies a regular file/tree under a new relative app path; all reparse
 * points and multilink files are rejected. Failure poisons partially staged apps.
 * seal pins and publishes app read/execute; work/tmp are writable, not replaceable.
 * destroy terminates and waits for every context descendant before releasing it.
 * create returns a positive context ID; other lifecycle calls return zero on
 * success, negative Windows error on failure. pid returns unsigned DWORD bits,
 * or zero for an unknown opaque ID. IDs are process-local and never recycled. */
int visjail_windows_create(const char *directory, char *error, int error_cap);
int visjail_windows_stage(int id, const char *source,
    const char *relative_destination, char *error, int error_cap);
int visjail_windows_seal(int id, char *error, int error_cap);
int visjail_windows_destroy(int id);
int visjail_windows_pid(int process_id);
#endif

#endif
