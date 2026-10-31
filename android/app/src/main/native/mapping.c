#include "mapping.h"

#include <string.h>

// How far an analog source must travel before a button target counts as pressed, and how far it must
// fall back before it counts as released, as a fraction of the travel available in that direction.
// The gap between them is deliberate: a trigger resting exactly on a single threshold would chatter
// the button open and shut on sensor noise alone.
#define PRESS_ENTER 0.55f
#define PRESS_LEAVE 0.45f

void mapping_reset_state(struct mapping_table *table) {
    memset(table->drive, 0, sizeof(table->drive));
    memset(table->pressed, 0, sizeof(table->pressed));
    memset(table->chord_held, 0, sizeof(table->chord_held));
    memset(table->chord_fired, 0, sizeof(table->chord_fired));
    memset(table->chord_suppress, 0, sizeof(table->chord_suppress));
}

void mapping_load(struct mapping_table *table,
                  const struct binding *entries, int count,
                  const struct chord *chords, int chord_count) {
    if (count < 0) {
        count = 0;
    }
    if (count > MAX_BINDINGS) {
        count = MAX_BINDINGS;
    }
    if (chord_count < 0) {
        chord_count = 0;
    }
    if (chord_count > MAX_CHORDS) {
        chord_count = MAX_CHORDS;
    }
    memcpy(table->entries, entries, (size_t)count * sizeof(struct binding));
    table->count = count;
    memcpy(table->chords, chords, (size_t)chord_count * sizeof(struct chord));
    table->chord_count = chord_count;
    // Deliberately cleared rather than carried over: the new table's row 3 has nothing to do with the
    // old one's, so keeping the drives would leave a target held by a binding that no longer exists.
    mapping_reset_state(table);
}

static int binding_matches(const struct binding *b, const struct input_event *in) {
    if (b->source_kind == BIND_KEY) {
        return in->type == EV_KEY && b->source_code == in->code;
    }
    return in->type == EV_ABS && b->source_code == in->code;
}

/*
 * How hard this binding is being driven, 0..1.
 *
 * Measured from the axis's resting value toward the end the binding names, so one rule covers a stick
 * that rests centred and a trigger that rests at its minimum: both read 0 untouched and 1 at the
 * limit. Movement the other way is not this binding's business and reads 0.
 */
static float source_drive(const struct binding *b,
                          const struct axis_range *source,
                          const struct input_event *in) {
    if (b->source_kind == BIND_KEY) {
        return in->value != 0 ? 1.0f : 0.0f;
    }

    const struct axis_range *r = &source[b->source_code];
    if (!r->present) {
        return 0.0f;
    }
    int dir = b->source_dir < 0 ? -1 : 1;
    long available = dir > 0 ? (long)r->maximum - (long)r->rest : (long)r->rest - (long)r->minimum;
    if (available <= 0) {
        return 0.0f;
    }
    long travelled = ((long)in->value - (long)r->rest) * dir;
    if (travelled <= 0) {
        return 0.0f;
    }
    if (travelled >= available) {
        return 1.0f;
    }
    return (float)((double)travelled / (double)available);
}

/*
 * What an analog target should now read, given every binding pointing at it.
 *
 * Combining rather than overwriting is what makes opposite halves usable: bind L2 to "left stick
 * left" and R2 to "left stick right" and the two have to meet in the middle, which they cannot do if
 * whichever moved last simply wins. Whole-axis bindings are excluded because they carry a position
 * rather than a one-directional push, and are written straight through by the caller.
 */
static int combined_axis_value(const struct mapping_table *table,
                               const struct axis_range *target,
                               unsigned short code) {
    const struct axis_range *r = &target[code];
    float positive = 0.0f;
    float negative = 0.0f;

    for (int i = 0; i < table->count; i++) {
        const struct binding *b = &table->entries[i];
        if (b->target_kind == BIND_KEY || b->target_code != code) {
            continue;
        }
        if (b->source_kind == BIND_AXIS && b->target_kind == BIND_AXIS) {
            continue;
        }
        if (b->target_dir < 0) {
            if (table->drive[i] > negative) {
                negative = table->drive[i];
            }
        } else if (table->drive[i] > positive) {
            positive = table->drive[i];
        }
    }

    float net = positive - negative;
    if (net >= 0.0f) {
        return r->rest + (int)(net * (float)(r->maximum - r->rest) + 0.5f);
    }
    return r->rest - (int)(-net * (float)(r->rest - r->minimum) + 0.5f);
}

// Whole axis onto whole axis: rescale between two controllers that disagree about their ranges (a
// trigger reporting 0..255 into one expecting -32767..32767, say). Worked in "distance from the
// source's minimum" so the arithmetic holds for a trigger resting at its minimum as well as a stick
// resting in the middle; flipping is then simply measuring that distance from the other end.
static int rescale_axis(const struct binding *b,
                        const struct axis_range *source,
                        const struct axis_range *target,
                        int value) {
    const struct axis_range *from = &source[b->source_code];
    const struct axis_range *to = &target[b->target_code];

    // Without a usable range on both sides there is nothing to scale against. Forwarding the raw
    // value under the new code is the honest fallback: the control reaches the right place, just
    // without the correction we could not compute.
    if (!from->present || !to->present) {
        return value;
    }
    long source_span = (long)from->maximum - (long)from->minimum;
    long target_span = (long)to->maximum - (long)to->minimum;
    if (source_span <= 0 || target_span <= 0) {
        return value;
    }

    long travelled = (long)value - (long)from->minimum;
    if (travelled < 0) {
        travelled = 0;
    }
    if (travelled > source_span) {
        travelled = source_span;
    }
    if (b->source_dir * b->target_dir < 0) {
        travelled = source_span - travelled;
    }
    return (int)((long)to->minimum + (travelled * target_span) / source_span);
}

// Resolve one binding. Returns how many events it produced (0 or 1). `out` has room for at least one.
static int apply_binding(struct mapping_table *table,
                         int index,
                         const struct axis_range *source,
                         const struct axis_range *target,
                         const struct input_event *in,
                         struct input_event *out) {
    const struct binding *b = &table->entries[index];

    // Button straight onto button: the value passes through untouched, which matters because the
    // kernel's autorepeat (value 2) is neither a press nor a release and thresholding would lose it.
    if (b->source_kind == BIND_KEY && b->target_kind == BIND_KEY) {
        *out = *in;
        out->code = b->target_code;
        return 1;
    }

    if (b->source_kind == BIND_AXIS && b->target_kind == BIND_AXIS) {
        *out = *in;
        out->code = b->target_code;
        out->value = rescale_axis(b, source, target, in->value);
        return 1;
    }

    float drive = source_drive(b, source, in);

    if (b->target_kind == BIND_KEY) {
        int held = table->pressed[index]
                       ? (drive > PRESS_LEAVE)
                       : (drive >= PRESS_ENTER);
        if (held == table->pressed[index]) {
            return 0;
        }
        table->pressed[index] = (unsigned char)held;
        *out = *in;
        out->type = EV_KEY;
        out->code = b->target_code;
        out->value = held;
        return 1;
    }

    // Analog target driven by something that only pushes one way — a button, a trigger, or one half
    // of a stick. A digital source gives full deflection; an analog one stays proportional.
    if (!target[b->target_code].present) {
        return 0;
    }
    table->drive[index] = drive;
    int value = combined_axis_value(table, target, b->target_code);
    *out = *in;
    out->type = EV_ABS;
    out->code = b->target_code;
    out->value = value;
    return 1;
}

// One side of rest onto the same side of the target's rest, so rest lands on rest and each limit on
// the matching limit even when the two controllers centre their axes at different points of their
// ranges (a 0..255 stick resting at 127 into a -32768..32767 one resting at 0, say).
static int rescale_side(long value, long from_rest, long from_end, long to_rest, long to_end) {
    long span = from_end - from_rest;
    if (span == 0) {
        return (int)to_rest;
    }
    long travelled = value - from_rest;
    // Past the declared limit is a noisy sensor, not more travel than the target can show.
    if ((span > 0 && travelled > span) || (span < 0 && travelled < span)) {
        travelled = span;
    }
    return (int)(to_rest + travelled * (to_end - to_rest) / span);
}

/*
 * An event no binding claims, on its way through untouched. An axis still has to be rescaled when the
 * two controllers disagree about its range, or a pad reporting 0..255 drives a target expecting
 * -32768..32767 between "barely left of centre" and "barely right". Left exactly as it came when
 * either side does not declare the axis, or both declare the same range.
 */
static void pass_through(const struct axis_range *source,
                         const struct axis_range *target,
                         const struct input_event *in,
                         struct input_event *out) {
    *out = *in;
    if (in->type != EV_ABS || in->code >= ABS_CNT) {
        return;
    }
    const struct axis_range *from = &source[in->code];
    const struct axis_range *to = &target[in->code];
    if (!from->present || !to->present ||
        (from->minimum == to->minimum && from->maximum == to->maximum)) {
        return;
    }
    if (in->value >= from->rest) {
        out->value = rescale_side(in->value, from->rest, from->maximum, to->rest, to->maximum);
    } else {
        out->value = rescale_side(in->value, from->rest, from->minimum, to->rest, to->minimum);
    }
}

// Every binding naming this event, or the event untouched when none does. `extra` is or-ed into
// each output's flags.
static int apply_bindings(struct mapping_table *table,
                          const struct axis_range *source,
                          const struct axis_range *target,
                          const struct input_event *in,
                          struct input_event *out,
                          unsigned char *flags,
                          int max_out,
                          unsigned char extra) {
    if (max_out < 1) {
        return 0;
    }
    int matched = 0;
    int written = 0;
    for (int i = 0; i < table->count && written < max_out; i++) {
        if (!binding_matches(&table->entries[i], in)) {
            continue;
        }
        matched = 1;
        if (apply_binding(table, i, source, target, in, &out[written])) {
            flags[written++] = MAPPED_BOUND | extra;
        }
    }

    // Untouched is the default on purpose: it is what makes a control the user skipped in the wizard
    // go on working exactly as it did, instead of going dead because nobody named it.
    if (!matched) {
        pass_through(source, target, in, &out[0]);
        flags[0] = extra;
        return 1;
    }
    return written;
}

static int ref_matches(const struct bind_ref *r, const struct input_event *in) {
    return in->code == r->code && in->type == (r->kind == BIND_KEY ? EV_KEY : EV_ABS);
}

static int same_ref(const struct bind_ref *a, const struct bind_ref *b) {
    return a->kind == b->kind && a->code == b->code && a->dir == b->dir;
}

// Whether a member is held after this event, with the same hysteresis as an axis bound to a button.
static int member_held(const struct bind_ref *r, int was, const struct axis_range *source,
                       const struct input_event *in) {
    if (r->kind == BIND_KEY) {
        return in->value != 0;
    }
    struct binding b = {.source_kind = r->kind, .source_code = r->code, .source_dir = r->dir};
    float drive = source_drive(&b, source, in);
    return was ? drive > PRESS_LEAVE : drive >= PRESS_ENTER;
}

// Some fired chord other than `except` already swallows this member, so its press never leaked.
static int suppressed_elsewhere(const struct mapping_table *table, int except, const struct bind_ref *r) {
    for (int c = 0; c < table->chord_count; c++) {
        if (c == except) {
            continue;
        }
        for (int m = 0; m < table->chords[c].count; m++) {
            if ((table->chord_suppress[c] & (1u << m)) && same_ref(&table->chords[c].members[m], r)) {
                return 1;
            }
        }
    }
    return 0;
}

int mapping_key_in_chord(const struct mapping_table *table, unsigned short code) {
    struct bind_ref key = {.kind = BIND_KEY, .code = code, .dir = 0};
    return suppressed_elsewhere(table, -1, &key);
}

// Send a member's release down the path its press took, so whatever the target saw goes back up.
static int release_member(struct mapping_table *table,
                          const struct axis_range *source,
                          const struct axis_range *target,
                          const struct input_event *like,
                          const struct bind_ref *r,
                          struct input_event *out,
                          unsigned char *flags,
                          int max_out) {
    struct input_event release = *like;
    release.type = r->kind == BIND_KEY ? EV_KEY : EV_ABS;
    release.code = r->code;
    release.value = r->kind == BIND_KEY ? 0 : source[r->code].rest;
    return apply_bindings(table, source, target, &release, out, flags, max_out, MAPPED_LEAK);
}

static int emit_chord_key(const struct chord *ch, const struct input_event *like, int value,
                          struct input_event *out, unsigned char *flags, int max_out) {
    if (max_out < 1) {
        return 0;
    }
    out[0] = *like;
    out[0].type = EV_KEY;
    out[0].code = ch->target.code;
    out[0].value = value;
    flags[0] = MAPPED_BOUND;
    return 1;
}

// Advance every chord this event belongs to. Sets *consumed when a chord owns the event.
static int run_chords(struct mapping_table *table,
                      const struct axis_range *source,
                      const struct axis_range *target,
                      const struct input_event *in,
                      struct input_event *out,
                      unsigned char *flags,
                      int max_out,
                      int *consumed) {
    *consumed = 0;
    int written = 0;
    if (in->type != EV_KEY && in->type != EV_ABS) {
        return 0;
    }
    for (int c = 0; c < table->chord_count; c++) {
        const struct chord *ch = &table->chords[c];
        unsigned char before = table->chord_held[c];
        int touched = 0;
        for (int m = 0; m < ch->count; m++) {
            if (!ref_matches(&ch->members[m], in)) {
                continue;
            }
            touched = 1;
            unsigned char bit = (unsigned char)(1u << m);
            int held = member_held(&ch->members[m], (before & bit) != 0, source, in);
            table->chord_held[c] = held ? (table->chord_held[c] | bit) : (table->chord_held[c] & ~bit);
            if (table->chord_suppress[c] & bit) {
                *consumed = 1;
                if (!held) {
                    table->chord_suppress[c] &= (unsigned char)~bit;
                }
            }
        }
        if (!touched) {
            continue;
        }

        unsigned char all = (unsigned char)((1u << ch->count) - 1);
        int complete = (table->chord_held[c] & all) == all;
        if (!table->chord_fired[c] && complete && table->chord_held[c] != before) {
            // The members held before this one reached the target as themselves: take them back.
            for (int m = 0; m < ch->count; m++) {
                unsigned char bit = (unsigned char)(1u << m);
                if ((before & bit) && !suppressed_elsewhere(table, c, &ch->members[m])) {
                    written += release_member(table, source, target, in, &ch->members[m],
                                              &out[written], &flags[written], max_out - written);
                }
            }
            table->chord_fired[c] = 1;
            table->chord_suppress[c] = all;
            written += emit_chord_key(ch, in, 1, &out[written], &flags[written], max_out - written);
            *consumed = 1;
        } else if (table->chord_fired[c] && !complete) {
            table->chord_fired[c] = 0;
            written += emit_chord_key(ch, in, 0, &out[written], &flags[written], max_out - written);
            *consumed = 1;
        }
    }
    return written;
}

int mapping_chords_only(struct mapping_table *table,
                        const struct axis_range *source,
                        const struct axis_range *target,
                        const struct input_event *in,
                        struct input_event *out,
                        unsigned char *flags,
                        int max_out) {
    int consumed = 0;
    return run_chords(table, source, target, in, out, flags, max_out, &consumed);
}

int mapping_apply(struct mapping_table *table,
                  const struct axis_range *source,
                  const struct axis_range *target,
                  const struct input_event *in,
                  struct input_event *out,
                  unsigned char *flags,
                  int max_out) {
    if (max_out < 1) {
        return 0;
    }
    if (in->type != EV_KEY && in->type != EV_ABS) {
        pass_through(source, target, in, &out[0]);
        flags[0] = 0;
        return 1;
    }
    int consumed = 0;
    int written = run_chords(table, source, target, in, out, flags, max_out, &consumed);
    if (consumed) {
        return written;
    }
    return written + apply_bindings(table, source, target, in, &out[written], &flags[written],
                                    max_out - written, 0);
}
