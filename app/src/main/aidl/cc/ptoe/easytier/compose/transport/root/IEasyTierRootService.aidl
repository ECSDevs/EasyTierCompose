package cc.ptoe.easytier.compose.transport.root;

import cc.ptoe.easytier.compose.transport.root.RootTunSpec;
import cc.ptoe.easytier.compose.transport.root.RootRuntimeStatus;
import cc.ptoe.easytier.compose.transport.root.IRootStatusCallback;

interface IEasyTierRootService {
    void start(String profileId, String toml, in RootTunSpec spec);
    void stop();
    RootRuntimeStatus getStatus();

    // Push-based status delivery. Once registered, the daemon calls back on every
    // status change (STARTING -> RUNNING, peer list updates, ERROR, STOPPED), so the
    // app never needs to poll getStatus() in a loop. The daemon immediately pushes
    // the current status on registration so the caller doesn't miss the latest state.
    void registerStatusCallback(IRootStatusCallback cb);
    void unregisterStatusCallback(IRootStatusCallback cb);
}
