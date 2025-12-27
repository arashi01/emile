/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 *
 * Signal-to-libuv bridge for Émile.
 *
 * This provides a clean integration between POSIX signals and libuv's event loop
 * using uv_async, which is explicitly documented as async-signal-safe.
 *
 * Pattern:
 * 1. Register a signal with emile_signal_watch()
 * 2. This creates a uv_async handle and installs a POSIX signal handler
 * 3. When signal arrives, handler calls uv_async_send() (async-signal-safe)
 * 4. libuv invokes the async callback during normal loop iteration
 * 5. The Scala callback can safely run in this context
 *
 * This avoids the paradigm mismatch between:
 * - POSIX signals (interrupt any code, async-signal-safety constraints)
 * - libuv event loop (cooperative, single-threaded callbacks)
 * - Scala/cats-effect (GC-managed memory, fiber scheduling)
 *
 * Thread Safety:
 * - POSIX signals are process-global; signal_slots is intentionally static.
 * - Each signal can only have one handler per process (POSIX constraint).
 * - The slot array is accessed from signal context (via emile_signal_handler)
 *   which only reads and calls uv_async_send (async-signal-safe).
 * - Modification of slots happens only in emile_signal_watch/unwatch which
 *   must be called from the main thread (libuv constraint).
 * - For Scala Native multithreading: each thread should have its own loop,
 *   but signals remain process-global. Coordinate signal ownership at app level.
 */

#define _POSIX_C_SOURCE 200809L

#include <uv.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

/* Maximum signal number we support */
#define EMILE_MAX_SIGNALS 32

/* Per-signal state */
typedef struct {
    uv_async_t* async_handle;    /* libuv async handle for this signal */
    int signum;                   /* Signal number */
    int active;                   /* Whether this slot is active */
    struct sigaction prev_action; /* Previous signal handler for restoration */
} emile_signal_slot_t;

/*
 * Static storage for signal slots - accessed from signal handler.
 * This is intentionally process-global because POSIX signals are process-global.
 * Thread safety note: signal handlers can only safely call async-signal-safe
 * functions; we only call uv_async_send which is documented as safe.
 */
static emile_signal_slot_t signal_slots[EMILE_MAX_SIGNALS];

/* Generic signal handler - calls uv_async_send which is async-signal-safe */
static void emile_signal_handler(int signum) {
    if (signum >= 0 && signum < EMILE_MAX_SIGNALS) {
        emile_signal_slot_t* slot = &signal_slots[signum];
        if (slot->active && slot->async_handle) {
            /* uv_async_send is async-signal-safe per libuv documentation */
            uv_async_send(slot->async_handle);
        }
    }
}

/* Close callback - frees the async handle memory after libuv is done with it */
static void emile_close_callback(uv_handle_t* handle) {
    free(handle);
}

/**
 * Register to watch a signal.
 *
 * This creates a uv_async handle and installs a signal handler.
 * When the signal arrives, the async handle is woken up.
 *
 * @param loop The libuv event loop
 * @param signum The signal number to watch
 * @param async_cb The callback to invoke (typically a trampoline to Scala)
 * @param out_async Output: the created async handle (caller can use to set data/callback)
 * @return 0 on success, negative error code on failure
 */
int emile_signal_watch(uv_loop_t* loop, int signum, uv_async_cb async_cb, uv_async_t** out_async) {
    if (signum < 0 || signum >= EMILE_MAX_SIGNALS) {
        return -EINVAL;
    }
    
    emile_signal_slot_t* slot = &signal_slots[signum];
    
    /* Check if already watching this signal */
    if (slot->active) {
        return -EBUSY;
    }
    
    /* Allocate async handle */
    uv_async_t* async = (uv_async_t*)malloc(sizeof(uv_async_t));
    if (!async) {
        return -ENOMEM;
    }
    
    /* Initialize async handle - caller must provide callback */
    if (!async_cb) {
        free(async);
        return -EINVAL;
    }
    int err = uv_async_init(loop, async, async_cb);
    if (err < 0) {
        free(async);
        return err;
    }
    
    /* Store signal number in handle data for callback to retrieve */
    async->data = (void*)(intptr_t)signum;
    
    /* Install signal handler */
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = emile_signal_handler;
    action.sa_flags = SA_RESTART;
    sigfillset(&action.sa_mask); /* Block all signals during handler */
    
    if (sigaction(signum, &action, &slot->prev_action) != 0) {
        int saved_errno = errno;
        uv_close((uv_handle_t*)async, NULL);
        free(async);
        return -saved_errno;
    }
    
    /* Mark slot as active */
    slot->async_handle = async;
    slot->signum = signum;
    slot->active = 1;
    
    if (out_async) {
        *out_async = async;
    }
    
    return 0;
}

/**
 * Stop watching a signal and clean up.
 *
 * @param signum The signal number to stop watching
 * @return 0 on success, negative error code on failure
 */
int emile_signal_unwatch(int signum) {
    if (signum < 0 || signum >= EMILE_MAX_SIGNALS) {
        return -EINVAL;
    }
    
    emile_signal_slot_t* slot = &signal_slots[signum];
    
    if (!slot->active) {
        return -ENOENT;
    }
    
    /* Restore previous signal handler */
    sigaction(signum, &slot->prev_action, NULL);
    
    /* Clear slot state first - handle may still be in use by libuv */
    slot->active = 0;
    
    /* Close and free async handle via callback */
    if (slot->async_handle) {
        uv_async_t* handle = slot->async_handle;
        slot->async_handle = NULL;
        /* uv_close schedules the close; emile_close_callback frees memory */
        uv_close((uv_handle_t*)handle, emile_close_callback);
    }
    
    return 0;
}

/**
 * Get the async handle for a watched signal.
 *
 * @param signum The signal number
 * @return The async handle, or NULL if not watching
 */
uv_async_t* emile_signal_get_async(int signum) {
    if (signum < 0 || signum >= EMILE_MAX_SIGNALS) {
        return NULL;
    }
    
    emile_signal_slot_t* slot = &signal_slots[signum];
    return slot->active ? slot->async_handle : NULL;
}

/**
 * Check if a signal is being watched.
 *
 * @param signum The signal number
 * @return 1 if watching, 0 if not
 */
int emile_signal_is_watched(int signum) {
    if (signum < 0 || signum >= EMILE_MAX_SIGNALS) {
        return 0;
    }
    return signal_slots[signum].active;
}
