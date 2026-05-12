"use strict";
// Drop-in replacement for node-pty/lib/index.js on android-arm64.
//
// node-pty has no prebuilt binary for android-arm64 and node-gyp can't rebuild
// inside Termux (TLS abort fetching node headers). The dashboard's chat UI
// doesn't depend on PTY-backed shell sessions, but it imports node-pty at the
// top of its module graph — so a missing native binding crashes the server
// before any UI ever renders. This stub satisfies the import surface (spawn,
// fork, open, createTerminal, native) and only throws if anyone actually
// tries to open a terminal. The dashboard's "open shell" feature is degraded,
// nothing else.
Object.defineProperty(exports, "__esModule", { value: true });
function notSupported() {
  const err = new Error(
    "Terminal sessions are not available on Pocket Pi " +
    "(node-pty has no android-arm64 prebuild). The rest of the dashboard works."
  );
  err.code = "NODE_PTY_UNAVAILABLE";
  throw err;
}
exports.spawn = notSupported;
exports.fork = notSupported;
exports.createTerminal = notSupported;
exports.open = notSupported;
exports.native = null;
