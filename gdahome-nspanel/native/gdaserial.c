/*
 * gdaserial — ponte UART <-> TCP per il chip Zigbee del pannello (stile ser2net).
 *
 * Permette a Zigbee2MQTT / ZHA di usare lo Zigbee integrato del NSPanel Pro
 * come coordinatore di rete:  socket://<IP-pannello>:6638
 *
 * Uso: gdaserial -d /dev/ttyS1 -b 115200 -p 6638 [-f] [-v]
 *   -d  dispositivo seriale
 *   -b  baud rate (9600..921600)
 *   -p  porta TCP
 *   -f  controllo di flusso hardware RTS/CTS
 *   -v  log dettagliato su stderr
 *
 * Un solo client alla volta: una nuova connessione sostituisce la precedente.
 */
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <termios.h>
#include <unistd.h>

static int verbose = 0;
#define LOG(...) do { fprintf(stderr, "gdaserial: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define VLOG(...) do { if (verbose) LOG(__VA_ARGS__); } while (0)

static speed_t to_speed(long b) {
    switch (b) {
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 500000: return B500000;
        case 921600: return B921600;
        default: return 0;
    }
}

static int open_serial(const char *dev, speed_t speed, int rtscts) {
    int fd = open(dev, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) { LOG("open %s: %s", dev, strerror(errno)); return -1; }
    struct termios t;
    if (tcgetattr(fd, &t) < 0) { LOG("tcgetattr: %s", strerror(errno)); close(fd); return -1; }
    cfmakeraw(&t);
    cfsetispeed(&t, speed);
    cfsetospeed(&t, speed);
    t.c_cflag |= CLOCAL | CREAD;
    t.c_cflag &= ~CSTOPB;
    if (rtscts) t.c_cflag |= CRTSCTS; else t.c_cflag &= ~CRTSCTS;
    t.c_iflag &= ~(IXON | IXOFF | IXANY);
    t.c_cc[VMIN] = 0;
    t.c_cc[VTIME] = 0;
    if (tcsetattr(fd, TCSANOW, &t) < 0) { LOG("tcsetattr: %s", strerror(errno)); close(fd); return -1; }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

static int listen_tcp(int port) {
    int s = socket(AF_INET6, SOCK_STREAM, 0);
    int v6 = 1;
    if (s < 0) { s = socket(AF_INET, SOCK_STREAM, 0); v6 = 0; }
    if (s < 0) { LOG("socket: %s", strerror(errno)); return -1; }
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    int rc;
    if (v6) {
        int zero = 0;
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof zero);
        struct sockaddr_in6 a; memset(&a, 0, sizeof a);
        a.sin6_family = AF_INET6; a.sin6_addr = in6addr_any; a.sin6_port = htons(port);
        rc = bind(s, (struct sockaddr *)&a, sizeof a);
    } else {
        struct sockaddr_in a; memset(&a, 0, sizeof a);
        a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_ANY); a.sin_port = htons(port);
        rc = bind(s, (struct sockaddr *)&a, sizeof a);
    }
    if (rc < 0 || listen(s, 2) < 0) { LOG("bind/listen %d: %s", port, strerror(errno)); close(s); return -1; }
    return s;
}

/* Scrive tutto il buffer anche su fd non bloccanti. */
static int write_all(int fd, const char *buf, ssize_t n) {
    while (n > 0) {
        ssize_t w = write(fd, buf, n);
        if (w < 0) {
            if (errno == EAGAIN || errno == EINTR) { struct pollfd p = { fd, POLLOUT, 0 }; poll(&p, 1, 100); continue; }
            return -1;
        }
        buf += w; n -= w;
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *dev = NULL;
    long baud = 115200;
    int port = 6638, rtscts = 0, opt;
    while ((opt = getopt(argc, argv, "d:b:p:fv")) != -1) {
        switch (opt) {
            case 'd': dev = optarg; break;
            case 'b': baud = strtol(optarg, NULL, 10); break;
            case 'p': port = (int)strtol(optarg, NULL, 10); break;
            case 'f': rtscts = 1; break;
            case 'v': verbose = 1; break;
            default:
                fprintf(stderr, "uso: %s -d /dev/ttySx [-b 115200] [-p 6638] [-f] [-v]\n", argv[0]);
                return 2;
        }
    }
    if (!dev) { LOG("manca -d <dispositivo>"); return 2; }
    speed_t speed = to_speed(baud);
    if (!speed) { LOG("baud non supportato: %ld", baud); return 2; }
    signal(SIGPIPE, SIG_IGN);

    int ser = open_serial(dev, speed, rtscts);
    if (ser < 0) return 1;
    int lst = listen_tcp(port);
    if (lst < 0) return 1;
    LOG("pronto: %s @ %ld%s <-> tcp/%d", dev, baud, rtscts ? " rtscts" : "", port);

    int cli = -1;
    char buf[4096];
    for (;;) {
        struct pollfd p[3] = {
            { lst, POLLIN, 0 },
            { ser, POLLIN, 0 },
            { cli, POLLIN, 0 },
        };
        int n = poll(p, cli >= 0 ? 3 : 2, -1);
        if (n < 0) { if (errno == EINTR) continue; LOG("poll: %s", strerror(errno)); return 1; }

        if (p[0].revents & POLLIN) {
            int c = accept(lst, NULL, NULL);
            if (c >= 0) {
                if (cli >= 0) { VLOG("client sostituito"); close(cli); }
                int one = 1;
                setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                setsockopt(c, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
                cli = c;
                tcflush(ser, TCIOFLUSH);
                LOG("client connesso");
                continue; /* ricalcola i pollfd */
            }
        }
        if (p[1].revents & (POLLERR | POLLHUP | POLLNVAL)) { LOG("errore seriale"); return 1; }
        if (p[1].revents & POLLIN) {
            ssize_t r = read(ser, buf, sizeof buf);
            if (r > 0 && cli >= 0 && write_all(cli, buf, r) < 0) { close(cli); cli = -1; LOG("client perso"); }
            VLOG("uart->tcp %zd", r);
        }
        if (cli >= 0 && (p[2].revents & (POLLIN | POLLERR | POLLHUP))) {
            ssize_t r = read(cli, buf, sizeof buf);
            if (r <= 0) { close(cli); cli = -1; LOG("client disconnesso"); }
            else { if (write_all(ser, buf, r) < 0) { LOG("scrittura seriale: %s", strerror(errno)); return 1; } VLOG("tcp->uart %zd", r); }
        }
    }
}
