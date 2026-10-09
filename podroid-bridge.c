/*
 * podroid-bridge — virtio-console socket ↔ PTY relay for Podroid
 *
 * Bidirectional relay between:
 *   stdin/stdout  — PTY slave (Termux TerminalSession)
 *   terminal.sock — QEMU virtio-console chardev (/dev/hvc0 in VM; primary terminal)
 *
 * Architecture:
 *   - PTY → VM: keystrokes pass through raw; the VM's getty/bash handles echo/editing.
 *   - VM  → PTY: output forwarded as-is. virtio-console reports real terminal
 *     size via TIOCGWINSZ inside the guest, so we don't need to intercept
 *     ESC[18t queries (the old PL011-serial hack).
 *   - SIGWINCH: TerminalSession calls ioctl(TIOCSWINSZ) on PTY master →
 *     we write "RESIZE rows cols\n" to ctrl.sock (/dev/hvc1 in VM) → init
 *     daemon stty's hvc0 → foreground TUI gets SIGWINCH.
 *
 * Signals:
 *   SIGPIPE  — ignored; EPIPE on write = EOF, handled in main loop
 *   SIGINT   — graceful shutdown
 *   SIGTERM  — graceful shutdown
 *   SIGWINCH — async flag, debounced in the select() loop. Each signal just
 *              refreshes a timestamp; send_resize() fires once after the
 *              burst has been quiet for RESIZE_DEBOUNCE_MS.
 *
 * Args: <terminal.sock> <ctrl.sock>
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

/* Coalesce per-frame SIGWINCH bursts (Android keyboard slide fires ~25 of
 * them in 200 ms) into a single RESIZE message. The shell only redraws once,
 * so the user sees one prompt redraw at the end of the animation instead of
 * 25 cursor flashes during it. 80 ms is enough to coalesce a slide burst — the
 * keyboard finishes at 200 ms, the LAST SIGWINCH is the only one that matters,
 * and shorter than ~80 ms risks firing mid-animation. */
#define RESIZE_DEBOUNCE_MS 80

/* Fallback select() timeout used ONLY when the self-pipe couldn't be created
 * (socketpair() failed). Without a wake fd the handlers can't nudge select(),
 * and Bionic installs handlers with SA_RESTART, so a blocked select() with a
 * NULL timeout silently auto-restarts on SIGTERM instead of returning — the
 * loop would never see g_shutdown and shutdown/resize would wedge. Re-checking
 * the signal flags every 50 ms (the bridge's pre-self-pipe poll interval)
 * keeps shutdown and resize responsive. The self-pipe fast path keeps a NULL
 * (block-forever) timeout, so this costs nothing on the normal path. */
#define WAKE_FALLBACK_POLL_MS 50

static volatile sig_atomic_t g_winch    = 0;
static volatile sig_atomic_t g_shutdown = 0;
static int                   g_ctrl_fd   = -1;
static int                   g_term_fd   = -1;
static int                   g_winch_pending  = 0;
static long                  g_winch_last_ms  = 0;

/* Self-pipe pair so signal handlers can wake select() instead of forcing the
 * main loop to poll on a short timeout. g_wake_fd[1] is written by the
 * handlers (write() is async-signal-safe); g_wake_fd[0] is added to select's
 * read set and drained whenever it fires. This replaces the previous 50 ms
 * polling timeout — keystroke round-trip drops by up to ~50 ms. */
static int                   g_wake_fd[2] = { -1, -1 };

/* Saved PTY-master termios captured before cfmakeraw(), restored in cleanup()
 * so the PTY isn't left in raw mode if it ever outlives this process. */
static struct termios        g_saved_termios;
static int                   g_termios_saved = 0;

static void on_winch(int sig) {
    (void)sig;
    g_winch = 1;
    /* Wake select() so the main loop notices the signal and (re)starts the
     * debounce timer. errno preservation via write()'s contract. */
    if (g_wake_fd[1] >= 0) {
        char b = 'w';
        ssize_t n = write(g_wake_fd[1], &b, 1);
        (void)n; /* errno (e.g. EAGAIN) is harmless — handler will retrigger */
    }
}
static void on_term(int sig)  {
    (void)sig;
    g_shutdown = 1;
    if (g_wake_fd[1] >= 0) {
        char b = 't';
        ssize_t n = write(g_wake_fd[1], &b, 1);
        (void)n;
    }
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int connect_unix(const char *path, int max_attempts, unsigned int delay_us) {
    for (int attempt = 0; attempt < max_attempts; attempt++) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
        if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) return fd;
        int saved = errno;
        close(fd);
        /* Retry on the "not ready yet" / interrupted errors: ECONNREFUSED and
         * ENOENT (QEMU still binding the socket), plus EINTR (a SIGWINCH/SIGTERM
         * mid-connect) and EAGAIN/EINPROGRESS. Any other errno is a hard fail. */
        if (saved != ECONNREFUSED && saved != ENOENT &&
            saved != EINTR && saved != EAGAIN && saved != EINPROGRESS) return -1;
        if (attempt < max_attempts - 1) usleep(delay_us);
    }
    return -1;
}

static int write_all(int fd, const char *buf, int n) {
    int written = 0;
    while (written < n) {
        int w = write(fd, buf + written, n - written);
        if (w <= 0) { if (w < 0 && errno == EINTR) continue; return -1; }
        written += w;
    }
    return 0;
}

static const char *g_ctrl_path = NULL;

static void send_resize(void) {
    // Lazy reconnect: ctrl.sock may not have been ready at startup (QEMU still
    // binding it), or the chardev may have disconnected. Retry on every
    // SIGWINCH so TUI apps eventually get the correct size.
    if (g_ctrl_fd < 0 && g_ctrl_path) {
        g_ctrl_fd = connect_unix(g_ctrl_path, 1, 0);
    }
    if (g_ctrl_fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) < 0) return;
    if (ws.ws_row == 0 || ws.ws_col == 0) return;
    char msg[64];
    int len = snprintf(msg, sizeof(msg), "RESIZE %d %d\n", ws.ws_row, ws.ws_col);
    if (len > 0 && write_all(g_ctrl_fd, msg, len) < 0) {
        // Broken pipe — tear down and reconnect next time.
        close(g_ctrl_fd);
        g_ctrl_fd = -1;
    }
}

static void cleanup(void) {
    /* Restore the PTY master to its pre-raw line discipline. The bridge
     * normally owns the PTY for its whole lifetime, but if it ever outlives
     * the bridge this prevents a stuck raw terminal. */
    if (g_termios_saved) {
        tcsetattr(STDIN_FILENO, TCSANOW, &g_saved_termios);
        g_termios_saved = 0;
    }
    if (g_term_fd >= 0) { close(g_term_fd); g_term_fd = -1; }
    if (g_ctrl_fd >= 0) { close(g_ctrl_fd); g_ctrl_fd = -1; }
    if (g_wake_fd[0] >= 0) { close(g_wake_fd[0]); g_wake_fd[0] = -1; }
    if (g_wake_fd[1] >= 0) { close(g_wake_fd[1]); g_wake_fd[1] = -1; }
}

int main(int argc, char *argv[]) {
    // Silence stderr: this process's stderr is the PTY, so any diagnostic
    // bytes would bleed into the user's terminal output. Redirect to
    // /dev/null before anything can write.
    {
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            if (devnull != STDERR_FILENO) close(devnull);
        }
    }
    if (argc < 3) return 1;

    g_term_fd = connect_unix(argv[1], 50, 200000);
    if (g_term_fd < 0) {
        return 1;
    }

    struct termios raw;
    if (tcgetattr(STDIN_FILENO, &raw) == 0) {
        g_saved_termios = raw;   /* keep the pre-raw settings for cleanup() */
        g_termios_saved = 1;
        cfmakeraw(&raw);
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }

    // Retry ctrl.sock aggressively — QEMU's virtio chardev can take a beat to
    // bind on slower devices. 50×100ms = 5s budget. If it still fails,
    // send_resize() will keep retrying lazily on every SIGWINCH.
    g_ctrl_path = argv[2];
    g_ctrl_fd = connect_unix(argv[2], 50, 100000);

    send_resize();

    /* Kick getty to (re)emit the login prompt. terminal.sock is a QEMU chardev
     * with server=on,wait=off, so whatever getty printed to hvc0 before this
     * bridge connected was discarded (no client attached). getty starts right
     * after `::wait:openrc default`, racing this connect, so the prompt is
     * usually gone — leaving a blank terminal until a keystroke retriggers it.
     * A lone CR is read by getty as an empty login name, so it re-displays
     * /etc/issue + the login prompt. Mirrors the manual Enter users hit today;
     * if the prompt did survive the race this only adds one harmless reprompt. */
    write_all(g_term_fd, "\r", 1);

    /* Self-pipe for signal-to-select wakeup. AF_UNIX socketpair gives bidirec-
     * tional fds; we only use one direction. Set O_NONBLOCK on the write end
     * so a flood of signals can never block the signal handler. */
    int have_wake_pipe = 0;
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, g_wake_fd) == 0) {
        int flags = fcntl(g_wake_fd[1], F_GETFL, 0);
        if (flags >= 0) fcntl(g_wake_fd[1], F_SETFL, flags | O_NONBLOCK);
        int rflags = fcntl(g_wake_fd[0], F_GETFL, 0);
        if (rflags >= 0) fcntl(g_wake_fd[0], F_SETFL, rflags | O_NONBLOCK);
        have_wake_pipe = 1;
    }

    signal(SIGWINCH, on_winch);
    signal(SIGINT,   on_term);
    signal(SIGTERM,  on_term);
    signal(SIGPIPE,  SIG_IGN);

    /* 64 KB matches the sixel4 fork's main-thread PTY drain buffer and the new
     * 64 KB reader on the TerminalSession side — fewer syscalls for chunked
     * VM output (large `cat`, btop redraws, sixel images). */
    char buf[65536];

    for (;;) {
        if (g_shutdown) break;
        // Coalesce SIGWINCH bursts: every signal just refreshes the timestamp.
        // The actual send_resize() is fired below once the burst has been
        // quiet for RESIZE_DEBOUNCE_MS.
        if (g_winch) {
            g_winch = 0;
            g_winch_pending = 1;
            g_winch_last_ms = now_ms();
        }
        if (g_winch_pending && now_ms() - g_winch_last_ms >= RESIZE_DEBOUNCE_MS) {
            g_winch_pending = 0;
            send_resize();
        }

        /* FD_SET past FD_SETSIZE scribbles past the bitmap. STDIN is fd 0 and
         * g_term_fd is among the first fds opened, so this is defense-in-depth;
         * a g_term_fd that high means the relay can't run, so bail cleanly. */
        if (g_term_fd >= FD_SETSIZE) break;
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(STDIN_FILENO, &rfds);
        FD_SET(g_term_fd,    &rfds);
        int nfds = (g_term_fd > STDIN_FILENO ? g_term_fd : STDIN_FILENO) + 1;
        if (g_wake_fd[0] >= 0 && g_wake_fd[0] < FD_SETSIZE) {
            FD_SET(g_wake_fd[0], &rfds);
            if (g_wake_fd[0] >= nfds) nfds = g_wake_fd[0] + 1;
        }

        /* Block indefinitely when no winch is in flight (input drives wakeups).
         * When a winch is pending, sleep only until the debounce window closes
         * so send_resize() fires promptly. With no self-pipe, fall back to a
         * finite poll so signal flags are re-checked even though the handlers
         * can't wake us (see WAKE_FALLBACK_POLL_MS). */
        struct timeval  tv;
        struct timeval *tvp = NULL;
        if (g_winch_pending) {
            long remain = RESIZE_DEBOUNCE_MS - (now_ms() - g_winch_last_ms);
            if (remain < 0) remain = 0;
            tv.tv_sec  = remain / 1000;
            tv.tv_usec = (remain % 1000) * 1000;
            tvp = &tv;
        } else if (!have_wake_pipe) {
            tv.tv_sec  = 0;
            tv.tv_usec = WAKE_FALLBACK_POLL_MS * 1000;
            tvp = &tv;
        }
        int ret = select(nfds, &rfds, NULL, NULL, tvp);
        if (ret < 0) { if (errno == EINTR) continue; break; }

        /* Drain wake fd — its only purpose is to break us out of select(). */
        if (g_wake_fd[0] >= 0 && FD_ISSET(g_wake_fd[0], &rfds)) {
            char drain[64];
            while (read(g_wake_fd[0], drain, sizeof(drain)) > 0) { /* spin */ }
        }

        /* KNOWN LIMITATION (single-threaded full-duplex relay): both directions
         * use blocking write_all(). If one peer's send buffer fills while the
         * other direction also has data ready, write_all() blocks the loop and
         * briefly starves the opposite direction until the buffer drains. A
         * fully robust fix needs select-on-write + non-blocking writes + per-fd
         * queues — a relay rewrite that risks the terminal path on BOTH
         * backends. For a PTY <-> virtio-console terminal (the consumer reads
         * continuously, so neither buffer stays full) this is theoretical and
         * matches the upstream Termux relay shape; left as-is deliberately. */
        if (FD_ISSET(STDIN_FILENO, &rfds)) {
            int n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_all(g_term_fd, buf, n) < 0) break;
        }
        if (FD_ISSET(g_term_fd, &rfds)) {
            int n = read(g_term_fd, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_all(STDOUT_FILENO, buf, n) < 0) break;
        }
    }

    cleanup();
    return 0;
}
