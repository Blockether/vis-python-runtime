#ifndef VISJAIL_NETWORK_BRIDGE_H
#define VISJAIL_NETWORK_BRIDGE_H

#if defined(__linux__)
int visjail_linux_bridge_run(char **argv, int argc, int proxy_port, int inbound_port);
#endif

#endif
