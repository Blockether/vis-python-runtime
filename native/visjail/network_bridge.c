#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "network_bridge.h"

#if defined(__linux__)

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/capability.h>
#include <net/if.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern int vis_bwrap_main(int argc, char **argv);

enum bridge_kind
{
  BRIDGE_OUTBOUND = 1,
  BRIDGE_INBOUND = 2
};

struct direction
{
  int source;
  int destination;
  unsigned char buffer[16384];
  size_t offset;
  size_t length;
  int eof;
  int shutdown_sent;
};

static int
decode_status(int status)
{
  if (WIFEXITED(status))
    return WEXITSTATUS(status);
  if (WIFSIGNALED(status))
    return 128 + WTERMSIG(status);
  return 255;
}

static void
bridge_error(const char *operation)
{
  dprintf(STDERR_FILENO, "visjail: network bridge %s: %s\n", operation, strerror(errno));
}

static int
write_text(const char *path, const char *text)
{
  size_t length = strlen(text);
  size_t offset = 0;
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  if (fd < 0)
    return -1;
  while (offset < length)
    {
      ssize_t count = write(fd, text + offset, length - offset);
      if (count < 0 && errno == EINTR)
        continue;
      if (count <= 0)
        {
          int saved = errno == 0 ? EIO : errno;
          close(fd);
          errno = saved;
          return -1;
        }
      offset += (size_t) count;
    }
  return close(fd);
}

static int
drop_namespace_capabilities(void)
{
  struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
  struct __user_cap_data_struct data[2] = {{0}};
  return (int) syscall(SYS_capset, &header, &data);
}

static int
enter_network_namespace(void)
{
  char mapping[96];
  uid_t uid = geteuid();
  gid_t gid = getegid();

  if (unshare(CLONE_NEWUSER) != 0)
    return -1;
  if (write_text("/proc/self/setgroups", "deny") != 0 && errno != ENOENT)
    return -1;
  snprintf(mapping, sizeof(mapping), "%u %u 1\n", (unsigned int) uid, (unsigned int) uid);
  if (write_text("/proc/self/uid_map", mapping) != 0)
    return -1;
  snprintf(mapping, sizeof(mapping), "%u %u 1\n", (unsigned int) gid, (unsigned int) gid);
  if (write_text("/proc/self/gid_map", mapping) != 0)
    return -1;
  if (unshare(CLONE_NEWNET) != 0)
    return -1;

  {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct ifreq request;
    if (fd < 0)
      return -1;
    memset(&request, 0, sizeof(request));
    memcpy(request.ifr_name, "lo", 3);
    if (ioctl(fd, SIOCGIFFLAGS, &request) != 0)
      {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
      }
    request.ifr_flags = (short) (request.ifr_flags | IFF_UP | IFF_RUNNING);
    if (ioctl(fd, SIOCSIFFLAGS, &request) != 0)
      {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
      }
    close(fd);
  }
  return 0;
}

static int
loopback_listener(int port)
{
  struct sockaddr_in address;
  int fd;
  int one = 1;
  fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
  if (fd < 0)
    return -1;
  setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = htons((unsigned short) port);
  if (bind(fd, (struct sockaddr *) &address, sizeof(address)) != 0 || listen(fd, 64) != 0)
    {
      int saved = errno;
      close(fd);
      errno = saved;
      return -1;
    }
  return fd;
}

static int
loopback_connect(int port, int attempts)
{
  struct sockaddr_in address;
  struct timespec pause = {0, 20000000};
  int attempt;
  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = htons((unsigned short) port);
  for (attempt = 0; attempt < attempts; attempt++)
    {
      int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
      if (fd < 0)
        return -1;
      if (connect(fd, (struct sockaddr *) &address, sizeof(address)) == 0)
        return fd;
      {
        int saved = errno;
        close(fd);
        if (saved != ECONNREFUSED && saved != EINTR)
          {
            errno = saved;
            return -1;
          }
        errno = saved;
      }
      nanosleep(&pause, NULL);
    }
  return -1;
}

static int
send_descriptor(int control, int kind, int descriptor)
{
  struct msghdr message;
  struct iovec vector;
  char ancillary[CMSG_SPACE(sizeof(int))];
  struct cmsghdr *header;
  memset(&message, 0, sizeof(message));
  memset(ancillary, 0, sizeof(ancillary));
  vector.iov_base = &kind;
  vector.iov_len = sizeof(kind);
  message.msg_iov = &vector;
  message.msg_iovlen = 1;
  message.msg_control = ancillary;
  message.msg_controllen = sizeof(ancillary);
  header = CMSG_FIRSTHDR(&message);
  header->cmsg_level = SOL_SOCKET;
  header->cmsg_type = SCM_RIGHTS;
  header->cmsg_len = CMSG_LEN(sizeof(int));
  memcpy(CMSG_DATA(header), &descriptor, sizeof(descriptor));
  return sendmsg(control, &message, MSG_NOSIGNAL) == (ssize_t) sizeof(kind) ? 0 : -1;
}

static int
receive_descriptor(int control, int *kind)
{
  struct msghdr message;
  struct iovec vector;
  char ancillary[CMSG_SPACE(sizeof(int))];
  struct cmsghdr *header;
  int descriptor = -1;
  memset(&message, 0, sizeof(message));
  memset(ancillary, 0, sizeof(ancillary));
  vector.iov_base = kind;
  vector.iov_len = sizeof(*kind);
  message.msg_iov = &vector;
  message.msg_iovlen = 1;
  message.msg_control = ancillary;
  message.msg_controllen = sizeof(ancillary);
  if (recvmsg(control, &message, MSG_CMSG_CLOEXEC) <= 0)
    return -1;
  for (header = CMSG_FIRSTHDR(&message); header != NULL; header = CMSG_NXTHDR(&message, header))
    if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS &&
        header->cmsg_len >= CMSG_LEN(sizeof(int)))
      {
        memcpy(&descriptor, CMSG_DATA(header), sizeof(descriptor));
        break;
      }
  if (descriptor < 0)
    errno = EBADMSG;
  return descriptor;
}

static int
close_range_part(unsigned int first, unsigned int last)
{
  if (first > last)
    return 0;
#if defined(__NR_close_range)
  return (int) syscall(__NR_close_range, first, last, 0U);
#else
  (void) first;
  (void) last;
  errno = ENOSYS;
  return -1;
#endif
}

static void
close_except(int first, int second)
{
  int keep[2];
  int count = 0;
  unsigned int next = 3;
  int ranges_ok = 1;
  long maximum;
  int fd;
  if (first >= 3)
    keep[count++] = first;
  if (second >= 3 && second != first)
    keep[count++] = second;
  if (count == 2 && keep[0] > keep[1])
    {
      int swap = keep[0];
      keep[0] = keep[1];
      keep[1] = swap;
    }
  for (int i = 0; i < count; i++)
    {
      if (close_range_part(next, (unsigned int) keep[i] - 1U) != 0)
        ranges_ok = 0;
      next = (unsigned int) keep[i] + 1U;
    }
  if (close_range_part(next, UINT_MAX) != 0)
    ranges_ok = 0;
  if (ranges_ok)
    return;
  maximum = sysconf(_SC_OPEN_MAX);
  if (maximum < 0 || maximum > 1048576)
    maximum = 1048576;
  for (fd = 3; fd < maximum; fd++)
    if (fd != first && fd != second)
      close(fd);
}

static int
nonblocking(int fd)
{
  int flags = fcntl(fd, F_GETFL, 0);
  return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int
move_direction(struct direction *direction, short source_events, short destination_events)
{
  if (!direction->eof && direction->length == 0 && (source_events & (POLLIN | POLLHUP)))
    {
      ssize_t count = recv(direction->source, direction->buffer, sizeof(direction->buffer), 0);
      if (count > 0)
        {
          direction->offset = 0;
          direction->length = (size_t) count;
        }
      else if (count == 0)
        direction->eof = 1;
      else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)
        return -1;
    }
  if (direction->length > 0 && (destination_events & POLLOUT))
    {
      ssize_t count = send(direction->destination, direction->buffer + direction->offset,
                           direction->length, MSG_NOSIGNAL);
      if (count > 0)
        {
          direction->offset += (size_t) count;
          direction->length -= (size_t) count;
        }
      else if (count < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)
        return -1;
    }
  if (direction->eof && direction->length == 0 && !direction->shutdown_sent)
    {
      shutdown(direction->destination, SHUT_WR);
      direction->shutdown_sent = 1;
    }
  return 0;
}

static int
relay(int first, int second)
{
  struct direction forward = {first, second, {0}, 0, 0, 0, 0};
  struct direction reverse = {second, first, {0}, 0, 0, 0, 0};
  if (nonblocking(first) != 0 || nonblocking(second) != 0)
    return 1;
  while (!(forward.eof && forward.length == 0 && reverse.eof && reverse.length == 0))
    {
      struct pollfd descriptors[2];
      descriptors[0].fd = first;
      descriptors[0].events = (short) ((!forward.eof && forward.length == 0 ? POLLIN : 0) |
                                       (reverse.length > 0 ? POLLOUT : 0));
      descriptors[0].revents = 0;
      descriptors[1].fd = second;
      descriptors[1].events = (short) ((!reverse.eof && reverse.length == 0 ? POLLIN : 0) |
                                       (forward.length > 0 ? POLLOUT : 0));
      descriptors[1].revents = 0;
      if (poll(descriptors, 2, -1) < 0)
        {
          if (errno == EINTR)
            continue;
          return 1;
        }
      if ((descriptors[0].revents | descriptors[1].revents) & (POLLERR | POLLNVAL))
        return 1;
      if (move_direction(&forward, descriptors[0].revents, descriptors[1].revents) != 0 ||
          move_direction(&reverse, descriptors[1].revents, descriptors[0].revents) != 0)
        return 1;
    }
  return 0;
}

static void
relay_child(int first, int second)
{
  pid_t parent = getppid();
  if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent)
    _exit(125);
  close_except(first, second);
  _exit(relay(first, second));
}

static int
spawn_relay(int first, int second)
{
  pid_t pid = fork();
  if (pid < 0)
    return -1;
  if (pid == 0)
    relay_child(first, second);
  close(first);
  close(second);
  return 0;
}

static void
reap_relays(pid_t principal, int *principal_status, int *principal_done)
{
  int status;
  pid_t pid;
  while ((pid = waitpid(-1, &status, WNOHANG)) > 0)
    if (pid == principal)
      {
        *principal_status = status;
        *principal_done = 1;
      }
}

static int
run_inside(char **argv, int argc, int control, int proxy_port, int inbound_port)
{
  int outbound = -1;
  pid_t payload;
  int payload_status = 0;
  int payload_done = 0;
  if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() == 1)
    return 125;
  if (enter_network_namespace() != 0)
    {
      bridge_error("namespace");
      return 125;
    }
  if (proxy_port > 0 && (outbound = loopback_listener(proxy_port)) < 0)
    {
      bridge_error("outbound listener");
      return 125;
    }
  payload = fork();
  if (payload < 0)
    {
      bridge_error("payload fork");
      return 125;
    }
  if (payload == 0)
    {
      close_except(-1, -1);
      /* Bubblewrap expects an unprivileged caller and creates its own nested
       * user namespace. The outer namespace grants capabilities only so this
       * supervisor can create and configure the private network namespace. */
      if (drop_namespace_capabilities() != 0)
        {
          bridge_error("drop capabilities");
          _exit(125);
        }
      _exit(vis_bwrap_main(argc, argv));
    }
  while (!payload_done)
    {
      struct pollfd descriptors[2];
      int count = 1;
      descriptors[0].fd = control;
      descriptors[0].events = POLLIN;
      descriptors[0].revents = 0;
      if (outbound >= 0)
        {
          descriptors[1].fd = outbound;
          descriptors[1].events = POLLIN;
          descriptors[1].revents = 0;
          count = 2;
        }
      if (poll(descriptors, (nfds_t) count, 100) < 0 && errno != EINTR)
        break;
      if (descriptors[0].revents & POLLIN)
        {
          int kind = 0;
          int host = receive_descriptor(control, &kind);
          if (host >= 0)
            {
              if (kind == BRIDGE_INBOUND && inbound_port > 0)
                {
                  int target = loopback_connect(inbound_port, 1500);
                  if (target >= 0)
                    {
                      if (spawn_relay(host, target) != 0)
                        { close(host); close(target); }
                    }
                  else
                    close(host);
                }
              else
                close(host);
            }
        }
      if (outbound >= 0 && descriptors[1].revents & POLLIN)
        {
          int accepted = accept4(outbound, NULL, NULL, SOCK_CLOEXEC);
          if (accepted >= 0)
            {
              if (send_descriptor(control, BRIDGE_OUTBOUND, accepted) != 0)
                bridge_error("outbound handoff");
              close(accepted);
            }
        }
      reap_relays(payload, &payload_status, &payload_done);
    }
  if (outbound >= 0)
    close(outbound);
  close(control);
  if (!payload_done)
    {
      kill(payload, SIGKILL);
      while (waitpid(payload, &payload_status, 0) < 0 && errno == EINTR) {}
    }
  return decode_status(payload_status);
}

static int
run_outside(int control, int inbound_listener, int proxy_port, pid_t inside)
{
  int inside_status = 0;
  int inside_done = 0;
  while (!inside_done)
    {
      struct pollfd descriptors[2];
      int count = 1;
      descriptors[0].fd = control;
      descriptors[0].events = POLLIN;
      descriptors[0].revents = 0;
      if (inbound_listener >= 0)
        {
          descriptors[1].fd = inbound_listener;
          descriptors[1].events = POLLIN;
          descriptors[1].revents = 0;
          count = 2;
        }
      if (poll(descriptors, (nfds_t) count, 100) < 0 && errno != EINTR)
        break;
      if (descriptors[0].revents & POLLIN)
        {
          int kind = 0;
          int accepted = receive_descriptor(control, &kind);
          if (accepted >= 0)
            {
              if (kind == BRIDGE_OUTBOUND && proxy_port > 0)
                {
                  int target = loopback_connect(proxy_port, 100);
                  if (target >= 0)
                    {
                      if (spawn_relay(accepted, target) != 0)
                        { close(accepted); close(target); }
                    }
                  else
                    close(accepted);
                }
              else
                close(accepted);
            }
        }
      if (inbound_listener >= 0 && descriptors[1].revents & POLLIN)
        {
          int accepted = accept4(inbound_listener, NULL, NULL, SOCK_CLOEXEC);
          if (accepted >= 0)
            {
              if (send_descriptor(control, BRIDGE_INBOUND, accepted) != 0)
                bridge_error("inbound handoff");
              close(accepted);
            }
        }
      reap_relays(inside, &inside_status, &inside_done);
    }
  if (inbound_listener >= 0)
    close(inbound_listener);
  close(control);
  if (!inside_done)
    {
      kill(inside, SIGKILL);
      while (waitpid(inside, &inside_status, 0) < 0 && errno == EINTR) {}
    }
  return decode_status(inside_status);
}

int
visjail_linux_bridge_run(char **argv, int argc, int proxy_port, int inbound_port)
{
  int control[2] = {-1, -1};
  int inbound_listener = -1;
  pid_t inside;
  if (proxy_port <= 0 && inbound_port <= 0)
    {
      close_except(-1, -1);
      return vis_bwrap_main(argc, argv);
    }
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, control) != 0)
    {
      bridge_error("control socket");
      return 125;
    }
  if (inbound_port > 0 && (inbound_listener = loopback_listener(inbound_port)) < 0)
    {
      bridge_error("inbound listener");
      close(control[0]);
      close(control[1]);
      return 125;
    }
  inside = fork();
  if (inside < 0)
    {
      bridge_error("namespace fork");
      if (inbound_listener >= 0) close(inbound_listener);
      close(control[0]);
      close(control[1]);
      return 125;
    }
  if (inside == 0)
    {
      close(control[0]);
      if (inbound_listener >= 0) close(inbound_listener);
      close_except(control[1], -1);
      _exit(run_inside(argv, argc, control[1], proxy_port, inbound_port));
    }
  close(control[1]);
  close_except(control[0], inbound_listener);
  return run_outside(control[0], inbound_listener, proxy_port, inside);
}

#endif
