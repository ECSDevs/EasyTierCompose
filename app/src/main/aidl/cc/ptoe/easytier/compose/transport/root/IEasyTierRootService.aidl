package cc.ptoe.easytier.compose.transport.root;

import cc.ptoe.easytier.compose.transport.root.RootTunSpec;
import cc.ptoe.easytier.compose.transport.root.RootRuntimeStatus;

interface IEasyTierRootService {
    void start(String profileId, String toml, in RootTunSpec spec);
    void stop();
    RootRuntimeStatus getStatus();
}
