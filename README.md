# common-utils-collections

Part of the common-utils classes, designed to help when writing standalone Java applications.

This subpackage addresses the following needs:

## Priority-aware deques and thread-safe LRU cache.

The following classes are provided to address these needs:

### `PriorityDeque<T>` (interface)
Core contract for a deque with integer priorities.

- Higher integer means higher priority.
- Within the same priority, order is controlled by `Policy`:
  - `FIFO` (default)
  - `LIFO`
- Supports both global operations (across all priorities) and priority-specific operations (e.g. `poll(5)`, `clear(5)`).

### `GeneralPurposePriorityDeque<T>`
General implementation for arbitrary integer priorities (including sparse/unbounded ranges).

- Backed by a `TreeMap<Integer, ArrayDeque<T>>`.
- Good when priorities are dynamic or not bounded to a small range.
- Not thread-safe by itself.

### `BucketedPriorityDeque<T>`
Fast implementation for bounded priorities.

- Backed by an array of buckets + bitmask lookup.
- Priority range is fixed per instance:
  - global max bound: `0..31`
  - per-instance bound: `0..maxPriority`
- Best for high-throughput cases with known priority bounds.
- Not thread-safe by itself.

### `SynchronizedPriorityDequeWrapper<T>`
Thread-safe `PriorityDeque` wrapper using a read/write lock.

- Delegates all business logic to an underlying `PriorityDeque`.
- Read operations use read lock; mutating operations use write lock.
- `iterator()` and `iterator(priority)` return snapshot iterators (safe, not live views).

### `BlockingPriorityDequeWrapper<T>`
`BlockingQueue<T>` adapter over a `PriorityDeque`.

- Adds blocking semantics (`take`, timed `poll`) with `ReentrantLock` + `Condition`.
- Standard `BlockingQueue` insert methods use a `defaultPriority`.
- Also provides non-standard `add(T, int priority)` for explicit priority insertion.
- Unbounded queue semantics (`remainingCapacity() == Integer.MAX_VALUE`).

### `Cache<K, V>`
Thread-safe bounded LRU cache with hit/miss stats.

- `computeIfAbsent` is atomic and supports cached `null` values.
- Bounded by `maxCacheSize` with LRU eviction.
- Stats: `getHitCount()`, `getMissCount()`, `getCacheHitRate()`.

## Which one should I use?

- Use `GeneralPurposePriorityDeque` when priorities are open-ended or sparse.
- Use `BucketedPriorityDeque` when priorities are bounded and performance is critical.
- Wrap either deque with `SynchronizedPriorityDequeWrapper` for thread-safe access.
- Use `BlockingPriorityDequeWrapper` when you need a `BlockingQueue` API (e.g. worker loops, executors).
- Use `Cache` when you need a small in-memory LRU with compute-on-miss semantics.

## Primer

### 1) Basic priority deque usage

```java
import com.threeamigos.common.util.implementations.collections.GeneralPurposePriorityDeque;
import com.threeamigos.common.util.interfaces.collections.PriorityDeque;

PriorityDeque<String> deque = new GeneralPurposePriorityDeque<>();
deque.add("low", 1);
deque.add("high-oldest", 5);
deque.add("high-newest", 5);

String first = deque.pollFifo(); // "high-oldest"

deque.setPolicy(PriorityDeque.Policy.LIFO);
String second = deque.poll();    // "high-newest"
```

### 2) Bounded priorities with bucketed deque

```java
import com.threeamigos.common.util.implementations.collections.BucketedPriorityDeque;

BucketedPriorityDeque<Runnable> deque = new BucketedPriorityDeque<>(10); // valid priorities: 0..10
deque.add(taskA, 2);
deque.add(taskB, 9);

Runnable next = deque.poll(); // taskB (higher priority first)
```

### 3) Thread-safe wrapper

```java
import com.threeamigos.common.util.implementations.collections.GeneralPurposePriorityDeque;
import com.threeamigos.common.util.implementations.collections.SynchronizedPriorityDequeWrapper;
import com.threeamigos.common.util.interfaces.collections.PriorityDeque;

PriorityDeque<String> threadSafe =
        new SynchronizedPriorityDequeWrapper<>(new GeneralPurposePriorityDeque<>());

threadSafe.add("job", 3);
String job = threadSafe.poll();
```

### 4) Blocking queue adapter

```java
import com.threeamigos.common.util.implementations.collections.BlockingPriorityDequeWrapper;
import com.threeamigos.common.util.implementations.collections.BucketedPriorityDeque;

BlockingPriorityDequeWrapper<Runnable> queue =
        new BlockingPriorityDequeWrapper<>(new BucketedPriorityDeque<>(10), 0);

queue.put(defaultPriorityTask);  // uses default priority (0)
queue.add(highPriorityTask, 10); // explicit priority API

Runnable next = queue.take();    // blocks until available
```

### 5) LRU cache usage

```java
import com.threeamigos.common.util.implementations.collections.Cache;

Cache<String, String> cache = new Cache<>(1000, 16, 0.75f);

String value = cache.computeIfAbsent("k1", () -> loadValue("k1"));
double hitRate = cache.getCacheHitRate();

cache.invalidate("k1");
cache.invalidateAll(key -> key.startsWith("temp:"));
```

## Important notes

- `GeneralPurposePriorityDeque` and `BucketedPriorityDeque` are not thread-safe by themselves.
- Both deque implementations reject `null` elements.
- `BucketedPriorityDeque` enforces priority bounds and throws `IllegalArgumentException` for invalid priorities.
- Wrapper iterators are snapshots, not live iterators.
