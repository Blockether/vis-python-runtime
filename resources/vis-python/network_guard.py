def __vis_install_net_guard__():
    import builtins as _b
    import socket as _s

    def _norm(x):
        return str(x).strip().lower().rstrip(".").lstrip(".")

    _allowed = set(_norm(d) for d in __vis_allowed_domains__ if _norm(d))
    _denied = set(_norm(d) for d in __vis_denied_domains__ if _norm(d))

    # ONE interpreter can serve several sessions, so the policy is a HOLDER the
    # checks read at CALL time and an install REPLACES. Wrapping `socket` a
    # second time would leave the previous session's policy underneath this one,
    # blocking hosts the current session allows and never coming back off.
    _b.__vis_net_policy__ = {
        "allowed": _allowed,
        "denied": _denied,
        "allow_specific": set(d for d in _allowed if d != "*"),
        "deny_specific": set(d for d in _denied if d != "*"),
        "allow_star": ("*" in _allowed) or (len(_allowed) == 0),
        "deny_star": "*" in _denied,
        # Addresses an ALLOWED lookup answered. A connect() sees the address, not
        # the name, so without this the policy refuses the very domain it allows.
        "resolved": set(),
    }

    if getattr(_s, "__vis_net_guarded__", False):
        return

    def _match(h, pats):
        return any(h == d or h.endswith("." + d) for d in pats)

    def _host_ok(host, policy):
        h = _norm(host)
        if _match(h, policy["deny_specific"]):
            return False
        if _match(h, policy["allow_specific"]):
            return True
        if policy["deny_star"]:
            return False
        return policy["allow_star"]

    def _check(host):
        policy = getattr(_b, "__vis_net_policy__", None)
        if policy is None:
            return
        h = _norm(host)
        if _host_ok(h, policy):
            return
        # A denied NAME raises before _remember runs, so only addresses an allowed
        # resolution returned can pass here; a literal nobody resolved stays refused.
        if h in policy["resolved"] and not _match(h, policy["deny_specific"]):
            return
        raise PermissionError(
            "vis: network host '%s' is blocked (allowlist=%s, denylist=%s)"
            % (host, sorted(policy["allowed"]) or ["*"], sorted(policy["denied"]))
        )

    def _addresses(result):
        if isinstance(result, str):
            return [result]
        if (
            isinstance(result, tuple)
            and len(result) == 3
            and isinstance(result[2], list)
        ):
            # gethostbyname_ex / gethostbyaddr: (name, aliases, addresses)
            return [a for a in result[2] if isinstance(a, str)]
        out = []
        try:
            for entry in result:
                sockaddr = entry[4]
                if (
                    isinstance(sockaddr, (tuple, list))
                    and sockaddr
                    and isinstance(sockaddr[0], str)
                ):
                    out.append(sockaddr[0])
        except (TypeError, IndexError, KeyError):
            return out
        return out

    def _remember(result):
        policy = getattr(_b, "__vis_net_policy__", None)
        if policy is None:
            return
        for address in _addresses(result):
            policy["resolved"].add(_norm(address))

    def _addr_host(address):
        if (
            isinstance(address, (tuple, list))
            and address
            and isinstance(address[0], str)
        ):
            return address[0]
        return None

    def _wrap_dns(orig):
        def g(host, *a, **k):
            _check(host)
            result = orig(host, *a, **k)
            _remember(result)
            return result

        return g

    _s.getaddrinfo = _wrap_dns(_s.getaddrinfo)
    _s.gethostbyname = _wrap_dns(_s.gethostbyname)
    _s.gethostbyname_ex = _wrap_dns(_s.gethostbyname_ex)

    def _wrap_conn(orig):
        def g(self, address, *a, **k):
            h = _addr_host(address)
            if h is not None:
                _check(h)
            return orig(self, address, *a, **k)

        return g

    try:
        _s.socket.connect = _wrap_conn(_s.socket.connect)
        _s.socket.connect_ex = _wrap_conn(_s.socket.connect_ex)
    except Exception:
        pass

    def _wrap_create(orig):
        def g(address, *a, **k):
            h = _addr_host(address)
            if h is not None:
                _check(h)
            return orig(address, *a, **k)

        return g

    try:
        _s.create_connection = _wrap_create(_s.create_connection)
    except Exception:
        pass

    _s.__vis_net_guarded__ = True


__vis_install_net_guard__()
