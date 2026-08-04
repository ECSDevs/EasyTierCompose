package cc.ptoe.easytier.compose.transport.root;

import cc.ptoe.easytier.compose.transport.root.RootRuntimeStatus;

// oneway so the daemon never blocks on the app processing the update — important
// since the daemon emits from its poll loop and a slow Binder round-trip would
// skew the polling cadence.
oneway interface IRootStatusCallback {
    void onStatusUpdated(in RootRuntimeStatus status);
}
