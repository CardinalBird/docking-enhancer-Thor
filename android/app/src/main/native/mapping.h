// Applying a custom controller mapping to forwarded events.
//
// The table comes from the config; this module knows nothing about where it was stored or how it was
// captured, and nothing about the mirror's state beyond both controllers' axis travel. It turns one
// incoming event into the events the target should see.
#ifndef MAPPING_H
#define MAPPING_H

#include <linux/input.h>

#include "config.h"

// One absolute axis's travel, as the kernel reports it. Both devices are described this way so a
// remapped axis can be rescaled between them without the user ever being asked for a number.
struct axis_range {
    int present;
    int minimum;
    int maximum;
    int rest;  // the value the axis sits at untouched: mid-travel on a stick, minimum on a trigger
};

// Most bindings produce one event; the ceiling exists because several bindings may name the same
// source, and a source event must never be able to overrun the caller's buffer. A firing chord can
// add a release per member that leaked through before it completed, plus its own press.
#define MAX_MAPPED_EVENTS 16

// Per output event, from mapping_apply.
#define MAPPED_BOUND 1  // a binding or chord produced it: no layout guesswork on top
#define MAPPED_LEAK 2   // takes back a chord member's press the target saw before the chord completed

/*
 * The mapping in force, plus the little state that applying it needs.
 *
 * The state is what lets analog and digital cross. A stick sends a stream of values where a button
 * target wants two transitions, and two sources may drive opposite ends of the same axis — neither is
 * decidable from the event in hand alone, so each binding remembers how hard it is currently being
 * driven.
 */
struct mapping_table {
    struct binding entries[MAX_BINDINGS];
    int count;
    float drive[MAX_BINDINGS];           // 0..1, how hard each binding is currently driven
    unsigned char pressed[MAX_BINDINGS]; // for button targets: the edge detector's last answer

    struct chord chords[MAX_CHORDS];
    int chord_count;
    unsigned char chord_held[MAX_CHORDS];     // bit per member: held right now
    unsigned char chord_fired[MAX_CHORDS];    // the target has been pressed
    unsigned char chord_suppress[MAX_CHORDS]; // bit per member: swallowed until it is released
};

/** Adopt a binding and chord table, clearing all state so nothing carries over from the old mapping. */
void mapping_load(struct mapping_table *table,
                  const struct binding *entries, int count,
                  const struct chord *chords, int chord_count);

/** Forget every press in progress (after the target was told to release everything). */
void mapping_reset_state(struct mapping_table *table);

/**
 * Feed one event to the chords only, for a control something earlier in the pipeline swallows (Guide,
 * under the Home button setting). Returns the events written, all chord output.
 */
int mapping_chords_only(struct mapping_table *table,
                        const struct axis_range *source,
                        const struct axis_range *target,
                        const struct input_event *in,
                        struct input_event *out,
                        unsigned char *flags,
                        int max_out);

/** True while a fired chord owns this key: its events are swallowed until it is released. */
int mapping_key_in_chord(const struct mapping_table *table, unsigned short code);

/**
 * Resolve one event from the source controller into what the target should be told.
 *
 * Writes up to `max_out` events into `out` and returns how many. An event no binding names is copied
 * through unchanged (returns 1) — that is what makes an unmapped control keep working. A bound event
 * that changes nothing returns 0: an analog source sends a stream of values, and a button target only
 * wants the two transitions in it.
 *
 * `flags[i]` says how out[i] came about (MAPPED_*). Callers need MAPPED_BOUND because a binding is
 * the user saying exactly where a control goes: a layout correction applied afterwards would be
 * second-guessing them, and in the face-button case would invert precisely what they just set.
 *
 * Chords run first. When every member of one is held its target is pressed and the members are
 * swallowed until each is released; members pressed before the last one already reached the target,
 * so they are released there (MAPPED_LEAK). Releasing any member releases the target.
 */
int mapping_apply(struct mapping_table *table,
                  const struct axis_range *source,
                  const struct axis_range *target,
                  const struct input_event *in,
                  struct input_event *out,
                  unsigned char *flags,
                  int max_out);

#endif  // MAPPING_H
