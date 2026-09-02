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
#include <sys/syscall.h>
#endif
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <sandbox.h>
#include <util.h>
#else
#include <pty.h>
#endif

extern char **environ;

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
#if defined(__linux__)
  if (confined && (proxy_port > 0 || inbound_port > 0))
    for (int i = 0; i < argc; i++)
      if (strcmp(argv[i], "--unshare-net") == 0)
        {
          free_items(argv);
          free_items(env);
          return answer_error(error, error_cap,
                              "network bridge owns the namespace; remove --unshare-net");
        }
#endif

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

  pid = fork();
  if (pid < 0)
    goto system_error;
  if (pid == 0)
    {
      if (setsid() < 0)
        child_fail("setsid");
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
      _exit(visjail_linux_bridge_run(argv, argc, proxy_port, inbound_port));
#endif
    }

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
