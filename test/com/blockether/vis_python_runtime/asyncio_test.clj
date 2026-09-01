(ns com.blockether.vis-python-runtime.asyncio-test
  "`import asyncio` in a sandbox block (`resources/vis-python/async_runtime.py`).

   The model reaches for `asyncio.run(...)`, `asyncio.gather(...)`, a Queue, a
   Lock — so the import is AST-rewritten onto the runtime's OWN trampoline and
   its bounded pool instead of a real event loop. What the shim answers has to
   behave like the real thing: tasks and task groups, timeouts that bound the
   WAIT rather than notice the deadline afterwards, and synchronization
   primitives that actually rendezvous between children settling at the same
   time.

   Concurrency is the pool behind `__vis_par__`, which this library supplies
   (`vis_runtime.par`) unless a host binds its own. Ported from Vis'
   `env_python_form_eval_test`; the case asserting that `import socket` is a
   no-op stayed there, because that refusal was GraalPy's sandbox and CPython
   confines the network with `network_guard` instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; These cases weigh coroutine frames against the collector, so each
           ;; runs in a session of its own and none of them may outlive the test.
           (harness/close-sessions!)))))

(defn- tools
  "A session with the two tools these cases drive: `echo`, which answers a
   string, and `row`, whose MAP is the shape a caller subscripts and
   `json.dumps` — a slot that never settled shows up there as a refusal instead
   of a repr."
  []
  (let [session (harness/block-session)]
    (harness/tool! session "echo" "x" "    return '<' + str(x) + '>'")
    (harness/tool! session
                   "row"
                   "x"
                   "    return {'tool': 'row', 'arg': x, 'nested': {'deep': [1, 2, 3]}}")
    session))

(defn- out
  "What the block PRINTED, trimmed — its one success channel."
  [answer]
  (str/trim (str (:stdout answer))))

(defn- ran
  "Run `code` in a session with the tools, expecting it not to raise, and answer
   what it printed."
  [code]
  (let [answer (block (tools) code)]
    (is (nil? (:error answer)) code)
    (out answer)))

(def ^:private run-src
  "import asyncio


async def main():
    a = await echo('x')
    b = await echo('y')
    return a + b


print(asyncio.run(main()))")

(def ^:private sleep-src
  "import asyncio, time

t0 = time.monotonic()
v = await asyncio.sleep(0.1, result='done')
print(v, time.monotonic() - t0)")

(def ^:private gather-src
  "import asyncio


async def main():
    return await asyncio.gather(echo('a'), echo('b'))


print(asyncio.run(main()))")

(def ^:private compose-src
  "import asyncio


async def boom():
    raise ValueError('bad')


async def main():
    t = asyncio.create_task(echo('task'), name='named')
    assert not t.done() and t.get_name() == 'named'
    assert await t == '<task>' and t.done() and t.result() == '<task>'
    assert t.get_coro() is None
    vals = await asyncio.gather(echo('ok'), boom(), return_exceptions=True)
    assert vals[0] == '<ok>' and isinstance(vals[1], ValueError)
    async with asyncio.TaskGroup() as tg:
        a = tg.create_task(echo('a'))
        b = tg.create_task(asyncio.to_thread(lambda x: x + 1, 6))
    assert a.result() == '<a>' and b.result() == 7
    done, pending = await asyncio.wait([asyncio.sleep(0, 1), asyncio.sleep(0, 2)])
    assert len(done) == 2 and not pending
    assert await asyncio.wait_for(asyncio.sleep(0, 'ready'), 1) == 'ready'
    return 'compat-ok'


print(asyncio.run(main()))")

(def ^:private release-src
  "import asyncio


async def boom():
    raise ValueError('bad')


async def check():
    global held
    held = asyncio.create_task(asyncio.sleep(10))
    try:
        await asyncio.gather(asyncio.create_task(boom()), held)
    except Exception:
        pass
    call = echo('payload')
    assert await call == '<payload>'
    failed = asyncio.to_thread(lambda x: 1 / 0, bytearray(1000000))
    try:
        await failed
    except ZeroDivisionError:
        pass
    pending = asyncio.to_thread(lambda x: x, bytearray(1000000))
    cancelled = asyncio.create_task(pending)
    cancelled.cancel()
    bad = asyncio.create_task(boom())
    try:
        await bad
    except ValueError:
        pass
    return (held.done(), held.cancelled(), held.get_coro() is None,
            call.fn is None, call.a == (), call.k == {},
            failed.ran, failed.failed, failed.fn is None, failed.a == (), failed.k == {},
            pending.ran, pending.failed, pending.fn is None, pending.a == (), pending.k == {},
            bad.get_coro() is None, bad.exception().__traceback__ is None)


print(asyncio.run(check()))")

(def ^:private churn-src
  "import asyncio, gc


async def churn():
    for _ in range(40):
        tasks = [asyncio.create_task(asyncio.sleep(0, i)) for i in range(25)]
        vals = await asyncio.gather(*tasks)
        assert vals == list(range(25))
        assert all(t.done() and t.get_coro() is None for t in tasks)
    doomed = asyncio.create_task(asyncio.sleep(10))
    assert doomed.cancel() and doomed.cancelled() and doomed.get_coro() is None
    return 'clean'


print(asyncio.run(churn()))
gc.collect()
print(len(asyncio.all_tasks()))")

(def ^:private from-import-src
  "from asyncio import run, gather


async def m():
    return await gather(echo('p'), echo('q'))


print(run(m()))")

(def ^:private primitives-src
  "import asyncio


async def main():
    q = asyncio.Queue(maxsize=1)
    q.put_nowait('a')
    try:
        q.put_nowait('b')
        return 'no QueueFull'
    except asyncio.QueueFull:
        pass
    assert q.get_nowait() == 'a'
    try:
        q.get_nowait()
        return 'no QueueEmpty'
    except asyncio.QueueEmpty:
        pass
    lifo = asyncio.LifoQueue()
    pq = asyncio.PriorityQueue()
    for i in (1, 2, 3):
        lifo.put_nowait(i)
    for i in (3, 1, 2):
        pq.put_nowait(i)
    assert [lifo.get_nowait() for _ in range(3)] == [3, 2, 1]
    assert [pq.get_nowait() for _ in range(3)] == [1, 2, 3]
    lock = asyncio.Lock()
    async with lock:
        assert lock.locked()
    assert not lock.locked()
    sem = asyncio.Semaphore(1)
    async with sem:
        assert sem.locked()
    assert not sem.locked()
    cond = asyncio.Condition()
    async with cond:
        assert cond.locked()
    assert not cond.locked()
    async with asyncio.timeout(5):
        await asyncio.sleep(0)
    loop = asyncio.get_event_loop()
    assert await loop.run_in_executor(None, lambda v: v * 2, 21) == 42
    fut = loop.create_future()
    fut.set_result('handed')
    assert await fut == 'handed' and fut.done()
    assert asyncio.run_coroutine_threadsafe(asyncio.sleep(0, 'safe')).result() == 'safe'
    with asyncio.Runner() as runner:
        assert runner.run(asyncio.sleep(0, 'runner')) == 'runner'
    jq = asyncio.Queue()
    await jq.put('last')
    assert await jq.get() == 'last'
    jq.task_done()
    await jq.join()
    return 'primitives-ok'


print(asyncio.run(main()))")

(def ^:private wait-for-src
  "import asyncio, time


async def main():
    started = time.monotonic()
    q = asyncio.Queue()
    try:
        await asyncio.wait_for(q.get(), 0.1)
        return 'queue never timed out'
    except TimeoutError:
        pass
    event = asyncio.Event()
    try:
        await asyncio.wait_for(event.wait(), 0.1)
        return 'event never timed out'
    except TimeoutError:
        pass
    try:
        await asyncio.wait_for(asyncio.sleep(30), 0.1)
        return 'sleep never timed out'
    except TimeoutError:
        pass
    return 'bounded', time.monotonic() - started < 5.0


print(asyncio.run(main()))")

(def ^:private rendezvous-src
  "import asyncio

lock = asyncio.Lock()
event = asyncio.Event()
queue = asyncio.Queue(maxsize=1)
barrier = asyncio.Barrier(2)
handoff = asyncio.Future()
order = []


async def critical(tag):
    async with lock:
        order.append('in' + tag)
        await asyncio.sleep(0.05)
        order.append('out' + tag)
    return tag


async def waiter():
    await event.wait()
    return 'released'


async def setter():
    await asyncio.sleep(0.05)
    event.set()
    return 'set'


async def producer():
    for i in range(3):
        await queue.put(i)
    handoff.set_result('handed over')
    return 'produced'


async def consumer():
    got = []
    for _ in range(3):
        got.append(await queue.get())
        queue.task_done()
    await queue.join()
    return got


async def rendezvous(tag):
    await barrier.wait()
    return 'past ' + tag


async def main():
    values = await asyncio.gather(critical('a'), critical('b'), waiter(), setter(),
                                  producer(), consumer(), rendezvous('x'), rendezvous('y'),
                                  asyncio.wait_for(handoff, 5))
    assert order in (['ina', 'outa', 'inb', 'outb'], ['inb', 'outb', 'ina', 'outa']), order
    ranked = []
    for slot in asyncio.as_completed([asyncio.sleep(0.2, 'slow'), asyncio.sleep(0.01, 'fast')]):
        ranked.append(await slot)
    return values[2], values[4], values[5], values[8], ranked


print(asyncio.run(main()))")

(def ^:private condition-src
  "import asyncio

cond = asyncio.Condition()
state = {'ready': False}
seen = []


async def holder():
    async with cond:
        await asyncio.sleep(0.2)
    return 'held'


async def stray():
    await asyncio.sleep(0.05)
    for name in ('notify_all', 'notify', 'release'):
        try:
            getattr(cond, name)()
            seen.append(name + ':allowed')
        except RuntimeError as exc:
            seen.append(name + ':' + str(exc).split(':')[0])
    try:
        await asyncio.wait_for(cond.wait(), 1)
        seen.append('wait:allowed')
    except RuntimeError as exc:
        seen.append('wait:' + str(exc).split(':')[0])
    return 'stray'


async def waiter():
    async with cond:
        await asyncio.wait_for(cond.wait_for(lambda: state['ready']), 5)
    return 'woke'


async def signaller():
    await asyncio.sleep(0.3)
    async with cond:
        state['ready'] = True
        cond.notify_all()
    return 'signalled'


async def main():
    values = await asyncio.gather(holder(), stray(), waiter(), signaller())
    return values, seen


print(asyncio.run(main()))")

(def ^:private refusal-src
  "import asyncio

try:
    asyncio.open_connection
    print('not refused')
except AttributeError as exc:
    print('requests' in str(exc), hasattr(asyncio, 'start_server'),
          hasattr(asyncio, 'Queue'), hasattr(asyncio, 'to_thread'))")

(def ^:private to-thread-src
  "import asyncio, json

loop = asyncio.get_event_loop()
res = await gather(asyncio.to_thread(row, 'a'),
                   loop.run_in_executor(None, row, 'b'),
                   asyncio.to_thread(echo, 'c'),
                   asyncio.to_thread(lambda v: v * 2, 21),
                   asyncio.to_thread(lambda: gather(echo('x'), echo('y'))))
print([type(v).__name__ for v in res])
print(json.dumps(res, sort_keys=True))")

(harness/defbuilt-test asyncio-shim-test
  (testing "asyncio.run(main()) drives a coroutine that awaits tools"
    (is (= "<x><y>" (ran run-src))))
  (testing "asyncio.gather runs awaitables through our gather"
    (let [printed (ran gather-src)]
      (is (str/includes? printed "<a>"))
      (is (str/includes? printed "<b>"))))
  (testing "Task, TaskGroup, wait_for, wait, to_thread and return_exceptions compose"
    (is (= "compat-ok" (ran compose-src))))
  (testing "queues, locks, semaphores, conditions, futures and Runner compose"
    (is (= "primitives-ok" (ran primitives-src))))
  (testing "from asyncio import run rebinds to the shim; gather stays the builtin"
    (is (str/includes? (ran from-import-src) "<p>")))
  (testing "an event-loop-only name refuses BY NAME and leaves hasattr answering"
    (is (= "True False True True" (ran refusal-src)))))

(harness/defbuilt-test asyncio-sleep-test
  (testing "asyncio.sleep really sleeps and returns its result"
    (let [started  (System/nanoTime)
          printed  (ran sleep-src)
          elapsed  (/ (- (System/nanoTime) started) 1000000.0)
          measured (second (str/split printed #"\s+"))]
      (is (str/starts-with? printed "done "))
      (is (<= 80.0 elapsed))
      (is (<= 0.08 (Double/parseDouble measured))))))

(harness/defbuilt-test asyncio-wait-for-bounds-the-wait-test
  ;; The deadline goes INTO the wait: before it did, the last case sat on
  ;; `sleep(30)` for half a minute and only THEN reported it had taken too long.
  (testing "wait_for bounds the wait ITSELF instead of noticing the deadline afterwards"
    (let [started (System/nanoTime)
          printed (ran wait-for-src)
          elapsed (/ (- (System/nanoTime) started) 1000000.0)]
      (is (= "('bounded', True)" printed))
      (is (< elapsed 15000.0)))))

(harness/defbuilt-test asyncio-frame-release-test
  (testing "failed and cancelled work releases siblings, call payloads and exception frames"
    (is (= (str "(True, True, True, True, True, True, True, True, True, "
                "True, True, True, True, True, True, True, True, True)")
           (ran release-src))))
  (testing "completed and cancelled tasks release coroutine frames without a global registry"
    (is (= "clean\n0" (ran churn-src)))))

(harness/defbuilt-test asyncio-rendezvous-test
  ;; A rendezvous only means anything when two gather children settle AT THE SAME
  ;; TIME, which is what the pool behind `__vis_par__` is for. `handoff` is bound
  ;; by a TOP-LEVEL statement on purpose: an awaitable placeholder used to be
  ;; DRIVEN by the statement auto-settle right there, so the block hung forever
  ;; on a value only a sibling thread could ever set.
  (testing "synchronization primitives rendezvous across concurrently settled gather children"
    (is (= "('released', 'produced', [0, 1, 2], 'handed over', ['fast', 'slow'])"
           (ran rendezvous-src))))
  ;; Regression, issue #155: ownership was threading's acquire-PROBE, which only
  ;; answers whether the lock is held by ANYONE. Across gather children that let a
  ;; non-holder's `notify_all()` through while a sibling held the lock, and let a
  ;; non-holder's `wait()` / `release()` drop the lock that sibling owned — after
  ;; which the true owner's own notify died with threading's internal message,
  ;; the child waiting for that notify never woke, and the turn stayed running
  ;; with nothing to attribute the failure to.
  (testing "a condition refuses notify / wait / release from a child that does not hold it"
    (is (= (str "(['held', 'stray', 'woke', 'signalled'], "
                "['notify_all:cannot notify on un-acquired lock', "
                "'notify:cannot notify on un-acquired lock', "
                "'release:cannot release un-acquired lock', "
                "'wait:cannot wait on un-acquired lock'])")
           (ran condition-src)))))

;; Regression: handing a TOOL ITSELF to the pool — `asyncio.to_thread(row, 'a')`,
;; `loop.run_in_executor(None, row, 'b')` — ran the tool's own wrapper in the
;; worker, which only BUILT that tool's thunk, so the `gather` slot came back
;; holding an unrun call. Binding the slot hid it (statements auto-settle);
;; `json.dumps(res)` did not, and refused an object the caller never created.
(harness/defbuilt-test asyncio-to-thread-settles-a-tool-test
  (testing "a tool handed to to_thread / run_in_executor settles inside its gather slot"
    ;; A tool's dict arrives as `__VisDict__`: every map rebuilt at the host
    ;; boundary is one, so a missing key raises a KeyError that names the keys
    ;; the tool DID answer. It is a real dict - the JSON below is the proof.
    (is (= (str "['__VisDict__', '__VisDict__', '__VisResultStr__', 'int', '__VisResultList__']\n"
                "[{\"arg\": \"a\", \"nested\": {\"deep\": [1, 2, 3]}, \"tool\": \"row\"}, "
                "{\"arg\": \"b\", \"nested\": {\"deep\": [1, 2, 3]}, \"tool\": \"row\"}, "
                "\"<c>\", 42, [\"<x>\", \"<y>\"]]")
           (ran to-thread-src)))))
