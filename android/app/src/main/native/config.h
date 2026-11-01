/*
 * Live configuration.
 *
 * Every option here used to be fixed at launch, so changing one meant restarting the daemon — which
 * costs the exclusive grab, and with it about a second of the controller being visible to the whole
 * system. Instead the app writes this document and pokes the control fifo; the daemon re-reads it
 * between two events.
 *
 * `generation` is the contract that makes the change observable: the app picks the next number,
 * writes the file, asks for a reload, and waits to see that number come back in the heartbeat. A
 * config we fail to parse is never adopted, so the previous one keeps running and the app sees its
 * generation stall rather than a toggle that silently lied.
 */
#ifndef CONFIG_H
#define CONFIG_H

// Custom mapping capacity. A controller has far fewer controls than this; the limit exists so a
// malformed config can never walk us off the end of the table.
#define MAX_BINDINGS 64

/*
 * What one end of a binding refers to.
 *
 * This used to be two separate tables — buttons to buttons, axes to axes — with no way to cross
 * between them, so a controller reporting its triggers as axes could not fill a button slot and no
 * button could ever stand in for a stick direction. Naming the kind on each end instead makes the
 * target space wide enough that both fall out of the same table.
 */
enum bind_kind {
    BIND_KEY = 0,   // a button; `dir` unused, stored as 0
    BIND_AXIS = 1,  // a whole axis; `dir` is its orientation (+1 as-is, -1 flipped)
    BIND_HALF = 2,  // one half of an axis's travel; `dir` says which half, measured from rest
};

// One remapped control. A source absent from the table is forwarded untouched, which is what lets
// the wizard's "skip" mean "leave this one alone" rather than "break it".
struct binding {
    unsigned char source_kind;
    unsigned short source_code;
    signed char source_dir;
    unsigned char target_kind;
    unsigned short target_code;
    signed char target_dir;
};

// Chords: one target fired by 2..CHORD_MAX_MEMBERS controls held together.
#define MAX_CHORDS 16
#define CHORD_MAX_MEMBERS 4

// One control a chord listens to (a button, or one half of an axis), or the key it fires.
struct bind_ref {
    unsigned char kind;
    unsigned short code;
    signed char dir;
};

struct chord {
    int count;
    struct bind_ref members[CHORD_MAX_MEMBERS];
    struct bind_ref target;  // always a key
};

struct config {
    int home_mode;  // enum home_mode in input_mirror.c: 0 pass through, 1 Back, 2 Home
    int combo_hold_kill_app;
    int virtual_mouse;
    int rumble_strength;  // percent, 0 = off
    long long generation;

    struct binding bindings[MAX_BINDINGS];
    int binding_count;

    struct chord chords[MAX_CHORDS];
    int chord_count;
};

/**
 * Read and parse the config, leaving `out` untouched unless the whole document is good. Values the
 * document omits fall back to `current`, so a partial config is a change to what it names and
 * nothing else. `generation` is mandatory: it is what makes a half-written or foreign file fail
 * loudly here rather than be adopted as a set of defaults. Returns 0 on success.
 */
int parse_config(const char *path, const struct config *current, struct config *out);

/**
 * Open the control fifo. Opened O_RDWR so the daemon is its own phantom writer: as the only reader
 * it would otherwise see POLLHUP on every gap between the app's writes and spin the loop at full
 * speed. Returns the fd, or -1.
 */
int open_control_fifo(const char *path);

#endif  // CONFIG_H
