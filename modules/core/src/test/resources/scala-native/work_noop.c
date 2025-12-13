// Minimal libuv work callbacks to avoid executing Scala code on libuv worker threads.
// Scoped to tests only via src/test/resources/scala-native.

#define _GNU_SOURCE
#include <uv.h>
#ifndef _WIN32
#include <pthread.h>
#endif

void emile_uv_work_noop(uv_work_t* req) {
    (void)req;
}

void emile_uv_after_work_noop(uv_work_t* req, int status) {
    (void)req;
    (void)status;
}

// Helper that queues a work request fully in C and returns its observed type or
// a libuv error code (<0) if queueing fails.
int emile_uv_work_req_type() {
#ifdef _WIN32
    // TODO: Provide Windows-specific uv_queue_work path without pthreads and enable alignment test.
    return UV_ENOSYS;
#else
    uv_loop_t loop;
    int init_rc = uv_loop_init(&loop);
    if (init_rc != 0) return init_rc;

    uv_work_t req;
    int rc = uv_queue_work(&loop, &req, emile_uv_work_noop, emile_uv_after_work_noop);
    if (rc != 0) {
        uv_loop_close(&loop);
        return rc;
    }

    uv_run(&loop, UV_RUN_DEFAULT);
    int ty = uv_req_get_type((uv_req_t*)&req);
    uv_loop_close(&loop);
    return ty;
#endif
}
