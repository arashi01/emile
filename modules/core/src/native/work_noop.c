// Minimal libuv work callbacks that avoid touching the Scala Native runtime.
// They are used only for alignment tests where we must queue a work request
// without executing Scala code on libuv's worker threads.

typedef struct uv_work_s uv_work_t;

void emile_uv_work_noop(uv_work_t* req) {
    (void)req;
}

void emile_uv_after_work_noop(uv_work_t* req, int status) {
    (void)req;
    (void)status;
}
