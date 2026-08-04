#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <set>
#include <net/if.h>
#include <stdexcept>
#include <string>
#include <sched.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, kTag, __VA_ARGS__)

namespace {
constexpr char kTag[] = "EasyTierRootTun";
constexpr char kDefaultTunName[] = "easytier0";
int g_control_fd = -1;
int g_ifindex = 0;
std::string g_tun_name;
std::set<std::string> g_routes;

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/io/IOException");
    env->ThrowNew(exception, message.c_str());
}

std::string addrToString(in_addr address) {
    char buffer[INET_ADDRSTRLEN]{};
    inet_ntop(AF_INET, &address, buffer, sizeof(buffer));
    return buffer;
}

bool interfaceExists(const char* name) {
    int socketFd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (socketFd < 0) return false;
    ifreq request{};
    std::strncpy(request.ifr_name, name, IFNAMSIZ - 1);
    bool exists = ioctl(socketFd, SIOCGIFFLAGS, &request) == 0;
    close(socketFd);
    return exists;
}

void appendAttribute(nlmsghdr* header, size_t maximum, int type, const void* data, size_t length) {
    size_t offset = NLMSG_ALIGN(header->nlmsg_len);
    size_t attributeLength = RTA_LENGTH(length);
    if (offset + RTA_ALIGN(attributeLength) > maximum) throw std::runtime_error("netlink message too large");
    auto* attribute = reinterpret_cast<rtattr*>(reinterpret_cast<char*>(header) + offset);
    attribute->rta_type = type;
    attribute->rta_len = attributeLength;
    std::memcpy(RTA_DATA(attribute), data, length);
    header->nlmsg_len = offset + RTA_ALIGN(attributeLength);
}

// Returns 0 on success, or a positive errno when the kernel acknowledges with
// an error. Throws std::runtime_error only when the netlink transport itself
// fails (socket/send/recv), not on kernel-level route errors.
int netlinkAckErrno(nlmsghdr* message) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) {
        LOGE("netlink: open socket failed: %s", std::strerror(errno));
        throw std::runtime_error(std::string("open netlink: ") + std::strerror(errno));
    }
    sockaddr_nl address{};
    address.nl_family = AF_NETLINK;
    iovec iov{message, message->nlmsg_len};
    msghdr request{};
    request.msg_name = &address;
    request.msg_namelen = sizeof(address);
    request.msg_iov = &iov;
    request.msg_iovlen = 1;
    if (sendmsg(fd, &request, 0) < 0) {
        auto error = std::string("send netlink: ") + std::strerror(errno);
        LOGE("netlink: sendmsg failed: %s", std::strerror(errno));
        close(fd);
        throw std::runtime_error(error);
    }
    char response[4096]{};
    auto received = recv(fd, response, sizeof(response), 0);
    close(fd);
    if (received < static_cast<ssize_t>(sizeof(nlmsghdr))) throw std::runtime_error("short netlink acknowledgement");
    auto* header = reinterpret_cast<nlmsghdr*>(response);
    if (header->nlmsg_type == NLMSG_ERROR) {
        auto* error = reinterpret_cast<nlmsgerr*>(NLMSG_DATA(header));
        if (error->error != 0) {
            int err = -error->error;
            LOGE("netlink: ack error: %s (errno=%d)", std::strerror(err), err);
            return err;
        }
    }
    return 0;
}

void netlinkAck(nlmsghdr* message) {
    int err = netlinkAckErrno(message);
    if (err != 0) throw std::runtime_error(std::string("netlink: ") + std::strerror(err));
}

void configureLink(int ifindex, int mtu) {
    LOGI("configureLink: ifindex=%d mtu=%d", ifindex, mtu);
    struct {
        nlmsghdr header;
        ifinfomsg link;
        char buffer[256];
    } message{};
    message.header.nlmsg_len = NLMSG_LENGTH(sizeof(ifinfomsg));
    message.header.nlmsg_type = RTM_NEWLINK;
    message.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    message.link.ifi_family = AF_UNSPEC;
    message.link.ifi_index = ifindex;
    message.link.ifi_change = IFF_UP;
    message.link.ifi_flags = IFF_UP;
    appendAttribute(&message.header, sizeof(message), IFLA_MTU, &mtu, sizeof(mtu));
    netlinkAck(&message.header);
    LOGI("configureLink: success");
}

void configureAddress(int ifindex, const std::string& cidr) {
    auto slash = cidr.find('/');
    if (slash == std::string::npos) throw std::runtime_error("invalid IPv4 CIDR");
    int prefix = std::stoi(cidr.substr(slash + 1));
    if (prefix < 0 || prefix > 32) throw std::runtime_error("invalid IPv4 prefix");
    in_addr address{};
    if (inet_pton(AF_INET, cidr.substr(0, slash).c_str(), &address) != 1) throw std::runtime_error("invalid IPv4 address");
    LOGI("configureAddress: ifindex=%d addr=%s prefix=%d", ifindex, addrToString(address).c_str(), prefix);
    struct {
        nlmsghdr header;
        ifaddrmsg address;
        char buffer[128];
    } message{};
    message.header.nlmsg_len = NLMSG_LENGTH(sizeof(ifaddrmsg));
    message.header.nlmsg_type = RTM_NEWADDR;
    message.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL;
    message.address.ifa_family = AF_INET;
    message.address.ifa_prefixlen = static_cast<unsigned char>(prefix);
    message.address.ifa_scope = RT_SCOPE_UNIVERSE;
    message.address.ifa_index = ifindex;
    appendAttribute(&message.header, sizeof(message), IFA_LOCAL, &address, sizeof(address));
    appendAttribute(&message.header, sizeof(message), IFA_ADDRESS, &address, sizeof(address));
    netlinkAck(&message.header);
    LOGI("configureAddress: success");
}

// Returns 0 on success, or a positive errno on kernel-level failure. Throws
// std::runtime_error only for programming errors (invalid CIDR / netlink
// transport failure). Used by syncRoutes so a single route's kernel error
// (e.g. EEXIST, ENOENT) does not abort the whole sync.
int changeRouteErrno(int ifindex, const std::string& cidr, bool add) {
    auto slash = cidr.find('/');
    if (slash == std::string::npos) throw std::runtime_error("invalid route CIDR");
    int prefix = std::stoi(cidr.substr(slash + 1));
    if (prefix < 0 || prefix > 32) throw std::runtime_error("invalid route prefix");
    in_addr destination{};
    if (inet_pton(AF_INET, cidr.substr(0, slash).c_str(), &destination) != 1) throw std::runtime_error("invalid route address");
    // Mask host bits — the kernel rejects routes with non-zero host bits in some configurations.
    uint32_t mask = prefix == 0 ? 0 : htonl(~((1U << (32 - prefix)) - 1));
    destination.s_addr &= mask;
    LOGI("changeRoute: %s cidr=%s (masked=%s/%d) ifindex=%d", add ? "ADD" : "DEL", cidr.c_str(), addrToString(destination).c_str(), prefix, ifindex);
    struct {
        nlmsghdr header;
        rtmsg route;
        char buffer[128];
    } message{};
    message.header.nlmsg_len = NLMSG_LENGTH(sizeof(rtmsg));
    message.header.nlmsg_type = add ? RTM_NEWROUTE : RTM_DELROUTE;
    message.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | (add ? NLM_F_CREATE | NLM_F_EXCL : 0);
    message.route.rtm_family = AF_INET;
    message.route.rtm_dst_len = static_cast<unsigned char>(prefix);
    message.route.rtm_table = RT_TABLE_MAIN;
    message.route.rtm_protocol = RTPROT_STATIC;
    // TUN routes reach remote networks through the userspace process, so they are
    // universe-scope, not link-scope. RT_SCOPE_LINK is only valid for the connected
    // network and causes EINVAL for arbitrary CIDRs.
    message.route.rtm_scope = RT_SCOPE_UNIVERSE;
    message.route.rtm_type = RTN_UNICAST;
    appendAttribute(&message.header, sizeof(message), RTA_DST, &destination, sizeof(destination));
    appendAttribute(&message.header, sizeof(message), RTA_OIF, &ifindex, sizeof(ifindex));
    int err = netlinkAckErrno(&message.header);
    if (err == 0) LOGI("changeRoute: success");
    return err;
}

void changeRoute(int ifindex, const std::string& cidr, bool add) {
    int err = changeRouteErrno(ifindex, cidr, add);
    if (err != 0) throw std::runtime_error(std::string("netlink: ") + std::strerror(err));
}

void destroyInterface() {
    LOGI("destroyInterface: g_ifindex=%d routes=%zu", g_ifindex, g_routes.size());
    for (const auto& route : g_routes) {
        try { changeRoute(g_ifindex, route, false); } catch (const std::exception& e) {
            LOGE("destroyInterface: delete route %s failed: %s", route.c_str(), e.what());
        }
    }
    g_routes.clear();
    if (g_control_fd >= 0) {
        close(g_control_fd);
        g_control_fd = -1;
    }
    if (g_ifindex > 0) {
        struct { nlmsghdr header; ifinfomsg link; } message{};
        message.header.nlmsg_len = NLMSG_LENGTH(sizeof(ifinfomsg));
        message.header.nlmsg_type = RTM_DELLINK;
        message.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
        message.link.ifi_family = AF_UNSPEC;
        message.link.ifi_index = g_ifindex;
        try { netlinkAck(&message.header); } catch (const std::exception& e) {
            LOGE("destroyInterface: DELLINK failed: %s", e.what());
        }
        g_ifindex = 0;
    }
    LOGI("destroyInterface: done");
}
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_ptoe_easytier_compose_transport_root_RootTunNative_create(JNIEnv* env, jobject, jstring cidr, jint mtu, jstring devName) {
    std::string cidrValue;
    if (cidr != nullptr) {
        const char* chars = env->GetStringUTFChars(cidr, nullptr);
        cidrValue = chars;
        env->ReleaseStringUTFChars(cidr, chars);
    }
    std::string devNameValue;
    if (devName != nullptr) {
        const char* chars = env->GetStringUTFChars(devName, nullptr);
        devNameValue = chars;
        env->ReleaseStringUTFChars(devName, chars);
    }
    if (devNameValue.empty()) devNameValue = kDefaultTunName;
    g_tun_name = devNameValue;
    LOGI("create: cidr=%s mtu=%d devName=%s", cidrValue.c_str(), mtu, g_tun_name.c_str());
    try {
        if (g_control_fd >= 0) throw std::runtime_error(g_tun_name + " is already owned by this helper");
        if (interfaceExists(g_tun_name.c_str())) throw std::runtime_error(g_tun_name + " already exists");
        if (mtu < 576 || mtu > 9000) throw std::runtime_error("invalid MTU");
        int fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            LOGE("create: open /dev/net/tun failed: %s", std::strerror(errno));
            throw std::runtime_error(std::string("open /dev/net/tun: ") + std::strerror(errno));
        }
        ifreq request{};
        request.ifr_flags = IFF_TUN | IFF_NO_PI;
        std::strncpy(request.ifr_name, g_tun_name.c_str(), IFNAMSIZ - 1);
        if (ioctl(fd, TUNSETIFF, &request) < 0) {
            auto error = std::string("TUNSETIFF: ") + std::strerror(errno);
            LOGE("create: TUNSETIFF failed: %s", std::strerror(errno));
            close(fd);
            throw std::runtime_error(error);
        }
        g_ifindex = if_nametoindex(g_tun_name.c_str());
        if (g_ifindex == 0) {
            close(fd);
            throw std::runtime_error("could not resolve " + g_tun_name + " index");
        }
        g_control_fd = dup(fd);
        if (g_control_fd < 0) {
            close(fd);
            throw std::runtime_error("could not retain root TUN descriptor");
        }
        LOGI("create: tun fd=%d ifindex=%d", fd, g_ifindex);
        configureLink(g_ifindex, mtu);
        if (!cidrValue.empty()) configureAddress(g_ifindex, cidrValue);
        LOGI("create: success, returning fd=%d", fd);
        return fd;
    } catch (const std::exception& error) {
        LOGE("create: failed: %s", error.what());
        destroyInterface();
        throwIOException(env, error.what());
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_ptoe_easytier_compose_transport_root_RootTunNative_syncRoutes(JNIEnv* env, jobject, jobjectArray routes) {
    try {
        if (g_ifindex == 0) throw std::runtime_error("root TUN is not active");
        std::set<std::string> wanted;
        jsize count = env->GetArrayLength(routes);
        LOGI("syncRoutes: %d routes requested, current %zu routes", count, g_routes.size());
        for (jsize index = 0; index < count; ++index) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(routes, index));
            const char* chars = env->GetStringUTFChars(value, nullptr);
            wanted.emplace(chars);
            LOGD("syncRoutes: wanted[%zu]=%s", (size_t)index, chars);
            env->ReleaseStringUTFChars(value, chars);
            env->DeleteLocalRef(value);
        }
        // Delete stale routes. ENOENT / ESRCH mean the route is already gone from
        // the kernel — treat as success and drop from local state. Other errors
        // are logged but the route is kept in g_routes so the next sync retries.
        std::set<std::string> nextRoutes;
        for (const auto& route : g_routes) {
            if (wanted.contains(route)) { nextRoutes.insert(route); continue; }
            LOGI("syncRoutes: removing stale route %s", route.c_str());
            try {
                int err = changeRouteErrno(g_ifindex, route, false);
                if (err == 0 || err == ENOENT || err == ESRCH) {
                    // removed or already absent
                } else {
                    LOGE("syncRoutes: delete route %s failed: %s (errno=%d)", route.c_str(), std::strerror(err), err);
                    nextRoutes.insert(route);
                }
            } catch (const std::exception& e) {
                LOGE("syncRoutes: delete route %s failed: %s", route.c_str(), e.what());
                nextRoutes.insert(route);
            }
        }
        // Add new routes. EEXIST means the route is already in the kernel (e.g.
        // from a previous run that did not clean up) — treat as success. Other
        // errors are logged and the route is skipped; the next sync retries.
        for (const auto& route : wanted) if (!nextRoutes.contains(route)) {
            LOGI("syncRoutes: adding new route %s", route.c_str());
            try {
                int err = changeRouteErrno(g_ifindex, route, true);
                if (err == 0 || err == EEXIST) {
                    nextRoutes.insert(route);
                } else {
                    LOGE("syncRoutes: add route %s failed: %s (errno=%d)", route.c_str(), std::strerror(err), err);
                }
            } catch (const std::exception& e) {
                LOGE("syncRoutes: add route %s failed: %s", route.c_str(), e.what());
            }
        }
        g_routes = std::move(nextRoutes);
        LOGI("syncRoutes: done, %zu routes active", g_routes.size());
    } catch (const std::exception& error) {
        LOGE("syncRoutes: failed: %s", error.what());
        throwIOException(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_ptoe_easytier_compose_transport_root_RootTunNative_destroy(JNIEnv*, jobject) {
    LOGI("destroy: called");
    destroyInterface();
}
