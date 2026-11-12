#define _GNU_SOURCE
#include "visjail.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#if defined(__linux__)
#include "network_bridge.h"
#include <sys/prctl.h>
#include <sys/syscall.h>
#endif
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <arpa/inet.h>
#include <libproc.h>
#include <sandbox.h>
#include <util.h>
#else
#include <pty.h>
#endif

extern char **environ;

#if defined(__linux__)
/* Landlock ABI 6 (Linux 6.12) can keep a process from signalling outside its
   domain. Older kernel headers lack these names. */
#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif
#define VISJAIL_LANDLOCK_VERSION 1U
#define VISJAIL_LANDLOCK_SCOPE_SIGNAL (1ULL << 1)

struct visjail_landlock_ruleset
{
  uint64_t handled_access_fs;
  uint64_t handled_access_net;
  uint64_t scoped;
};

int
visjail_signal_scoped(void)
{
  return syscall(__NR_landlock_create_ruleset, NULL, 0, VISJAIL_LANDLOCK_VERSION) >= 6;
}

/* The calling process and its descendants signal only inside their own domain.
   Returns 0, also when the kernel has no signal scope, or -1 when it refuses. */
static int
scope_signals(void)
{
  struct visjail_landlock_ruleset ruleset = {0, 0, VISJAIL_LANDLOCK_SCOPE_SIGNAL};
  long fd;
  int result;

  if (!visjail_signal_scoped())
    return 0;
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0)
    return -1;
  fd = syscall(__NR_landlock_create_ruleset, &ruleset, sizeof ruleset, 0);
  if (fd < 0)
    return -1;
  result = syscall(__NR_landlock_restrict_self, (int) fd, 0) == 0 ? 0 : -1;
  close((int) fd);
  return result;
}
#endif

static int
answer_error(char *out, int cap, const char *format, ...)
{
  if (out != NULL && cap > 0)
    {
      va_list ap;
      va_start(ap, format);
      vsnprintf(out, (size_t) cap, format, ap);
      va_end(ap);
      out[cap - 1] = '\0';
    }
  return -1;
}

static char **
decode_blob(const char *blob, int length, int *count)
{
  int n = 0;
  int i;
  int at = 0;
  char **items;

  if (blob == NULL || length <= 0 || blob[length - 1] != '\0')
    return NULL;
  if (length == 1)
    {
      items = calloc(1, sizeof(char *));
      if (items != NULL)
        *count = 0;
      return items;
    }
  for (i = 0; i < length; i++)
    if (blob[i] == '\0')
      n++;
  items = calloc((size_t) n + 1, sizeof(char *));
  if (items == NULL)
    return NULL;
  for (i = 0; i < n; i++)
    {
      size_t size = strlen(blob + at) + 1;
      items[i] = malloc(size);
      if (items[i] == NULL)
        {
          while (i-- > 0)
            free(items[i]);
          free(items);
          return NULL;
        }
      memcpy(items[i], blob + at, size);
      at += (int) size;
    }
  items[n] = NULL;
  *count = n;
  return items;
}

static void
free_items(char **items)
{
  int i;
  if (items == NULL)
    return;
  for (i = 0; items[i] != NULL; i++)
    free(items[i]);
  free(items);
}

static int
make_pipe(int pair[2])
{
#if defined(__linux__)
  return pipe2(pair, O_CLOEXEC);
#else
  if (pipe(pair) != 0)
    return -1;
  if (fcntl(pair[0], F_SETFD, FD_CLOEXEC) != 0 ||
      fcntl(pair[1], F_SETFD, FD_CLOEXEC) != 0)
    {
      int saved = errno;
      close(pair[0]);
      close(pair[1]);
      errno = saved;
      return -1;
    }
  return 0;
#endif
}

static void
close_pair(int pair[2])
{
  if (pair[0] >= 0)
    close(pair[0]);
  if (pair[1] >= 0)
    close(pair[1]);
  pair[0] = pair[1] = -1;
}

static void
close_from_three(void)
{
#if defined(__linux__) && defined(__NR_close_range)
  if (syscall(__NR_close_range, 3U, ~0U, 0U) == 0)
    return;
#endif
  {
    long max_fd = sysconf(_SC_OPEN_MAX);
    int fd;
    if (max_fd < 0 || max_fd > 1048576)
      max_fd = 1048576;
    for (fd = 3; fd < max_fd; fd++)
      close(fd);
  }
}

/* Give each caught signal its default action, as exec would. Until exec the child
   still has the handlers of its host. A JVM handler takes a signal for a thread that
   the child does not have, so a TERM sent before exec was lost. Ignored signals stay
   ignored, as exec keeps them. */
static void
default_caught_signals(void)
{
  struct sigaction current;
  struct sigaction fallback;

  memset(&fallback, 0, sizeof fallback);
  fallback.sa_handler = SIG_DFL;
  sigemptyset(&fallback.sa_mask);
  for (int signal_number = 1; signal_number < NSIG; signal_number++)
    {
      if (signal_number == SIGKILL || signal_number == SIGSTOP)
        continue;
      if (sigaction(signal_number, NULL, &current) != 0)
        continue;
      if ((current.sa_flags & SA_SIGINFO) != 0 ||
          (current.sa_handler != SIG_DFL && current.sa_handler != SIG_IGN))
        sigaction(signal_number, &fallback, NULL);
    }
}

static void
child_fail(const char *message)
{
  int saved = errno;
  dprintf(STDERR_FILENO, "visjail: %s: %s\n", message, strerror(saved));
  _exit(125);
}

static void
install_environment(char **env)
{
  environ = env;
}

static int
decode_status(int status)
{
  if (WIFEXITED(status))
    return WEXITSTATUS(status);
  if (WIFSIGNALED(status))
    return 128 + WTERMSIG(status);
  return 255;
}

int
visjail_spawn(const char *argv_blob, int argv_len,
              const char *env_blob, int env_len,
              const char *cwd, const char *profile,
               int flags, int rows, int cols,
               int proxy_port, int inbound_port,
              int result[VISJAIL_RESULT_COUNT],
              char *error, int error_cap)
{
  char **argv = NULL;
  char **env = NULL;
  int argc = 0;
  int envc = 0;
  int input[2] = {-1, -1};
  int output[2] = {-1, -1};
  int errors[2] = {-1, -1};
  int ready[2] = {-1, -1};
  int master = -1;
  int slave = -1;
  pid_t pid;
  int use_pty = (flags & VISJAIL_PTY) != 0;
  int merge_error = (flags & VISJAIL_MERGE_STDERR) != 0;
  int confined = (flags & VISJAIL_CONFINED) != 0;

  if (result == NULL)
    return answer_error(error, error_cap, "result buffer is required");
  for (int i = 0; i < VISJAIL_RESULT_COUNT; i++)
    result[i] = -1;
  argv = decode_blob(argv_blob, argv_len, &argc);
  env = decode_blob(env_blob, env_len, &envc);
  if (argv == NULL || argc == 0)
    {
      free_items(argv);
      free_items(env);
      return answer_error(error, error_cap, "argv must contain at least one string");
    }
  if (env == NULL)
    {
      free_items(argv);
      return answer_error(error, error_cap, "environment blob is invalid");
    }
  if (proxy_port < 0 || proxy_port > 65535 || inbound_port < 0 || inbound_port > 65535)
    {
      free_items(argv);
      free_items(env);
      return answer_error(error, error_cap, "network bridge ports must be between 0 and 65535");
    }

  if (use_pty)
    {
      struct winsize size = {(unsigned short) rows, (unsigned short) cols, 0, 0};
      if (openpty(&master, &slave, NULL, NULL, &size) != 0)
        goto system_error;
      if (fcntl(master, F_SETFD, FD_CLOEXEC) != 0 ||
          fcntl(slave, F_SETFD, FD_CLOEXEC) != 0)
        goto system_error;
    }
  else if (make_pipe(input) != 0 || make_pipe(output) != 0 ||
           (!merge_error && make_pipe(errors) != 0))
    goto system_error;

  if (make_pipe(ready) != 0)
    goto system_error;
  pid = fork();
  if (pid < 0)
    goto system_error;
  if (pid == 0)
    {
      close(ready[0]);
      default_caught_signals();
      if (setsid() < 0)
        child_fail("setsid");
      /* The parent must not expose a pid before group-directed signals work. */
      char ready_byte = 1;
      ssize_t written;
      do written = write(ready[1], &ready_byte, 1); while (written < 0 && errno == EINTR);
      if (written != 1)
        child_fail("process group ready");
      close(ready[1]);
      if (use_pty)
        {
          if (ioctl(slave, TIOCSCTTY, 0) < 0)
            child_fail("TIOCSCTTY");
          if (dup2(slave, STDIN_FILENO) < 0 ||
              dup2(slave, STDOUT_FILENO) < 0 ||
              dup2(slave, STDERR_FILENO) < 0)
            child_fail("dup2 pty");
        }
      else
        {
          if (dup2(input[0], STDIN_FILENO) < 0 ||
              dup2(output[1], STDOUT_FILENO) < 0 ||
              dup2(merge_error ? output[1] : errors[1], STDERR_FILENO) < 0)
            child_fail("dup2 pipe");
        }
      install_environment(env);
      if (cwd != NULL && cwd[0] != '\0' && chdir(cwd) != 0)
        child_fail("chdir");
      if (!confined)
        {
          close_from_three();
          execvp(argv[0], argv);
          child_fail("execvp");
        }
#if defined(__APPLE__)
      close_from_three();
      {
        char *sandbox_error = NULL;
        if (profile == NULL || profile[0] == '\0')
          {
            errno = EINVAL;
            child_fail("Seatbelt profile is required");
          }
        if (sandbox_init(profile, 0, &sandbox_error) != 0)
          {
            if (sandbox_error != NULL)
              dprintf(STDERR_FILENO, "visjail: Seatbelt: %s\n", sandbox_error);
            sandbox_free_error(sandbox_error);
            _exit(125);
          }
        execvp(argv[0], argv);
        child_fail("execvp");
      }
#else
      (void) profile;
      if (scope_signals() != 0)
        child_fail("Landlock signal scope");
      _exit(visjail_linux_bridge_run(argv, argc, proxy_port, inbound_port));
#endif
    }

  close(ready[1]); ready[1] = -1;
  char ready_byte;
  ssize_t received;
  do received = read(ready[0], &ready_byte, 1); while (received < 0 && errno == EINTR);
  if (received < 0)
    {
      int saved = errno;
      kill(-pid, SIGKILL);
      kill(pid, SIGKILL);
      while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
      errno = saved;
      goto system_error;
    }
  close_pair(ready);
  if (use_pty)
    {
      close(slave); slave = -1;
      result[0] = (int) pid;
      result[1] = master;
      result[2] = master;
      result[3] = master;
      master = -1;
    }
  else
    {
      close(input[0]); input[0] = -1;
      close(output[1]); output[1] = -1;
      if (!merge_error)
        { close(errors[1]); errors[1] = -1; }
      result[0] = (int) pid;
      result[1] = input[1]; input[1] = -1;
      result[2] = output[0]; output[0] = -1;
      result[3] = merge_error ? -1 : errors[0]; errors[0] = -1;
    }
  free_items(argv);
  free_items(env);
  return 0;

system_error:
  {
    int saved = errno;
    close_pair(input);
    close_pair(output);
    close_pair(errors);
    close_pair(ready);
    if (master >= 0) close(master);
    if (slave >= 0) close(slave);
    free_items(argv);
    free_items(env);
    errno = saved;
    return answer_error(error, error_cap, "%s", strerror(saved));
  }
}

int
visjail_read(int fd, void *buffer, int length)
{
  ssize_t n;
  do n = read(fd, buffer, (size_t) length); while (n < 0 && errno == EINTR);
  if (n < 0 && errno == EIO)
    return 0;
  return n < 0 ? -errno : (int) n;
}

int
visjail_write(int fd, const void *buffer, int length)
{
  ssize_t n;
  do n = write(fd, buffer, (size_t) length); while (n < 0 && errno == EINTR);
  return n < 0 ? -errno : (int) n;
}

int
visjail_close(int fd)
{
  return close(fd) == 0 ? 0 : -errno;
}

int
visjail_poll(int fd, int timeout_ms)
{
  struct pollfd item = {fd, POLLIN, 0};
  int answer;
  do answer = poll(&item, 1, timeout_ms); while (answer < 0 && errno == EINTR);
  if (answer < 0)
    return -errno;
  return answer == 0 ? 0 : 1;
}

int
visjail_wait(int pid, int nohang, int *exit_code)
{
  int status;
  pid_t answer;
  do answer = waitpid((pid_t) pid, &status, nohang ? WNOHANG : 0);
  while (answer < 0 && errno == EINTR);
  if (answer == 0)
    return 0;
  if (answer < 0)
    return -errno;
  if (exit_code != NULL)
    *exit_code = decode_status(status);
  return 1;
}

int
visjail_kill(int pid, int signal_number)
{
  return kill((pid_t) -pid, signal_number) == 0 ? 0 : -errno;
}

#if defined(__APPLE__)
/* Exported by libsystem_sandbox, but no public header declares it. */
extern int sandbox_check(pid_t pid, const char *operation, int type, ...);
#define VISJAIL_FILTER_GLOBAL_NAME 2
#define VISJAIL_CHECK_NO_REPORT 0x40000000
#define VISJAIL_SWEEP_PASSES 64

/* A process that a profile with this owner confined: it is sandboxed, its profile
   allows the owner's Mach name and denies a name that no profile allows. Another
   sandbox gives the same answer for both names. */
static int
confined_by(pid_t pid, const char *service, const char *control)
{
  int type = VISJAIL_FILTER_GLOBAL_NAME | VISJAIL_CHECK_NO_REPORT;
  return sandbox_check(pid, NULL, 0) == 1
         && sandbox_check(pid, "mach-lookup", type, service) == 0
         && sandbox_check(pid, "mach-lookup", type, control) == 1;
}

static int
contains_pid(const pid_t *pids, int count, pid_t pid)
{
  for (int i = 0; i < count; i++)
    if (pids[i] == pid)
      return 1;
  return 0;
}

int
visjail_end_confined(const char *service)
{
  char control[512];
  pid_t *stopped = NULL;
  int count = 0;
  int capacity = 0;
  int changed = 1;
  pid_t self = getpid();

  if (service == NULL || service[0] == '\0'
      || snprintf(control, sizeof control, "%s.control", service) >= (int) sizeof control)
    return -EINVAL;
  /* Stop first: a stopped process cannot fork, so a pass that finds no new
     process has them all. The kill comes after the last pass. */
  for (int pass = 0; changed && pass < VISJAIL_SWEEP_PASSES; pass++)
    {
      int room = proc_listallpids(NULL, 0);
      pid_t *pids;
      int n;
      changed = 0;
      if (room <= 0)
        break;
      room += 64;
      pids = calloc((size_t) room, sizeof(pid_t));
      if (pids == NULL)
        break;
      n = proc_listallpids(pids, room * (int) sizeof(pid_t));
      for (int i = 0; i < n; i++)
        {
          pid_t pid = pids[i];
          if (pid <= 1 || pid == self || contains_pid(stopped, count, pid))
            continue;
          if (!confined_by(pid, service, control) || kill(pid, SIGSTOP) != 0)
            continue;
          /* The pid can belong to another process after the check. */
          if (!confined_by(pid, service, control))
            {
              kill(pid, SIGCONT);
              continue;
            }
          if (count == capacity)
            {
              int grown = capacity == 0 ? 64 : capacity * 2;
              pid_t *larger = realloc(stopped, (size_t) grown * sizeof(pid_t));
              if (larger == NULL)
                {
                  kill(pid, SIGKILL);
                  continue;
                }
              stopped = larger;
              capacity = grown;
            }
          stopped[count++] = pid;
          changed = 1;
        }
      free(pids);
    }
  for (int i = 0; i < count; i++)
    kill(stopped[i], SIGKILL);
  free(stopped);
  return count;
}

static int
loopback_address(const struct in_sockinfo *in, int local)
{
  if (in->insi_vflag & INI_IPV4)
    {
      struct in_addr address = local ? in->insi_laddr.ina_46.i46a_addr4
                                     : in->insi_faddr.ina_46.i46a_addr4;
      return (ntohl(address.s_addr) >> 24) == 127;
    }
  if (in->insi_vflag & INI_IPV6)
    {
      const struct in6_addr *address = local ? &in->insi_laddr.ina_6 : &in->insi_faddr.ina_6;
      return IN6_IS_ADDR_LOOPBACK(address)
             || (IN6_IS_ADDR_V4MAPPED(address) && address->s6_addr[12] == 127);
    }
  return 0;
}

/* Whether pid holds a TCP socket from loopback peer_port to loopback server_port. */
static int
holds_connection(pid_t pid, int server_port, int peer_port)
{
  int size = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, NULL, 0);
  struct proc_fdinfo *fds;
  int found = 0;
  int n;

  if (size <= 0)
    return 0;
  size += 16 * (int) sizeof *fds;
  fds = malloc((size_t) size);
  if (fds == NULL)
    return -ENOMEM;
  n = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, fds, size) / (int) sizeof *fds;
  for (int i = 0; i < n && !found; i++)
    {
      struct socket_fdinfo info;
      const struct in_sockinfo *in;
      if (fds[i].proc_fdtype != PROX_FDTYPE_SOCKET
          || proc_pidfdinfo(pid, fds[i].proc_fd, PROC_PIDFDSOCKETINFO, &info, sizeof info)
             != (int) sizeof info
          || info.psi.soi_kind != SOCKINFO_TCP)
        continue;
      in = &info.psi.soi_proto.pri_tcp.tcpsi_ini;
      found = ntohs((uint16_t) in->insi_lport) == peer_port
              && ntohs((uint16_t) in->insi_fport) == server_port
              && loopback_address(in, 1) && loopback_address(in, 0);
    }
  free(fds);
  return found;
}

int
visjail_loopback_peer_confined(int server_port, int peer_port, const char *mark,
                               const char *own_service)
{
  char control[512];
  int type = VISJAIL_FILTER_GLOBAL_NAME | VISJAIL_CHECK_NO_REPORT;
  int room;
  pid_t *pids;
  int n;
  int found = 0;

  if (server_port < 1 || server_port > 65535 || peer_port < 1 || peer_port > 65535
      || mark == NULL || mark[0] == '\0'
      || snprintf(control, sizeof control, "%s.control", mark) >= (int) sizeof control)
    return -EINVAL;
  room = proc_listpids(PROC_UID_ONLY, getuid(), NULL, 0);
  if (room <= 0)
    return -errno;
  room += 64 * (int) sizeof(pid_t);
  pids = malloc((size_t) room);
  if (pids == NULL)
    return -ENOMEM;
  n = proc_listpids(PROC_UID_ONLY, getuid(), pids, room) / (int) sizeof(pid_t);
  for (int i = 0; i < n && found == 0; i++)
    {
      pid_t pid = pids[i];
      /* The fd holder counts, not the process that opened the socket: a parent can
         pass the connection to a child and exit. */
      if (pid <= 1 || !confined_by(pid, mark, control))
        continue;
      if (own_service != NULL && own_service[0] != '\0'
          && sandbox_check(pid, "mach-lookup", type, own_service) == 0)
        continue;
      found = holds_connection(pid, server_port, peer_port);
    }
  free(pids);
  return found;
}
#endif

